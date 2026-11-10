package com.pupus.phone.api

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * NanoHTTPD glue for one listen address: rejects unauthenticated requests before
 * reading the body, converts sessions to [ApiRequest] and delegates to [RouteHandler].
 */
class DeviceApiServer(
    private val host: String,
    port: Int,
    private val handler: RouteHandler,
) : NanoHTTPD(host, port) {

    init {
        setAsyncRunner(BoundedAsyncRunner(MAX_WORKERS, MAX_QUEUED))
    }

    override fun serve(session: IHTTPSession): Response {
        val authorization = session.headers["authorization"]
        if (!handler.isAuthorized(authorization)) return closing(RouteHandler.error(401, "unauthorized"))
        val body = try {
            readBody(session)
        } catch (e: BodyTooLarge) {
            return closing(RouteHandler.error(413, "body_too_large"))
        } catch (e: IOException) {
            Log.e(TAG, "Cannot read request body", e)
            return closing(RouteHandler.error(400, "bad_request: unreadable body"))
        }
        val request = ApiRequest(
            method = session.method?.name ?: "GET",
            path = session.uri ?: "/",
            authorization = authorization,
            query = session.parameters.mapValues { it.value.firstOrNull().orEmpty() },
            body = body,
        )
        return json(handler.handle(request))
    }

    private fun readBody(session: IHTTPSession): String? {
        val length = session.headers["content-length"]?.toLongOrNull() ?: return null
        if (length <= 0) return null
        if (length > MAX_BODY_BYTES) throw BodyTooLarge()
        val buffer = ByteArray(length.toInt())
        var read = 0
        val stream = session.inputStream
        while (read < buffer.size) {
            val n = stream.read(buffer, read, buffer.size - read)
            if (n < 0) throw IOException("body truncated at $read of ${buffer.size} bytes")
            read += n
        }
        return String(buffer, Charsets.UTF_8)
    }

    private fun json(response: ApiResponse): Response =
        newFixedLengthResponse(JsonStatus(response.status), MIME_JSON, response.body.toString())

    /** The unread body would corrupt a keep-alive stream, so these responses close it. */
    private fun closing(response: ApiResponse): Response = json(response).apply { closeConnection(true) }

    private class BodyTooLarge : Exception()

    private class JsonStatus(private val code: Int) : Response.IStatus {
        override fun getDescription(): String = "$code ${Response.Status.lookup(code)?.description ?: "Status"}"
        override fun getRequestStatus(): Int = code
    }

    fun startSafely(): Boolean = try {
        start(SOCKET_READ_TIMEOUT, true)
        Log.i(TAG, "Device API listening on $host:$listeningPort")
        true
    } catch (e: IOException) {
        Log.e(TAG, "Cannot start device API server on $host", e)
        false
    }

    companion object {
        const val PORT = 8765
        private const val TAG = "PupusApi"
        private const val MIME_JSON = "application/json; charset=utf-8"
        private const val MAX_BODY_BYTES = 1L shl 20
        private const val MAX_WORKERS = 4
        private const val MAX_QUEUED = 16
    }
}
