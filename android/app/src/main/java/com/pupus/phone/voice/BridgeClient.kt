package com.pupus.phone.voice

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * WebSocket client to the bridge with automatic reconnection. All callbacks are
 * delivered on the main thread.
 */
class BridgeClient(private val listener: Listener) {
    interface Listener {
        fun onConnected()
        fun onMessage(message: BridgeMessage)
        fun onDisconnected(retryInSeconds: Int, reason: String)

        /** The bridge refused our token (close 1008 or HTTP 401/403); no more retries. */
        fun onRejected()
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val policy = ReconnectPolicy()
    private var socket: WebSocket? = null
    private var url: String? = null
    private var isOpen = false
    private var isStopped = true
    private var generation = 0

    fun connect(targetUrl: String) {
        close()
        url = targetUrl
        isStopped = false
        policy.onUserConnect()
        open()
    }

    fun isConnected(): Boolean = isOpen

    /**
     * The bridge was just asked to start: drops the pending retry (up to 30 s away) and
     * starts the backoff again from 1 s, so the socket opens soon after the bridge listens.
     */
    fun retryNow() {
        if (isStopped || isOpen) return
        generation++
        main.removeCallbacksAndMessages(null)
        socket?.cancel()
        socket = null
        policy.onUserConnect()
        open()
    }

    fun send(frame: String): Boolean {
        val ws = socket
        if (ws == null || !isOpen) {
            Log.w(TAG, "send while disconnected")
            return false
        }
        return ws.send(frame)
    }

    fun close() {
        isStopped = true
        generation++
        main.removeCallbacksAndMessages(null)
        socket?.close(NORMAL_CLOSE, "client closing")
        socket = null
        isOpen = false
    }

    fun shutdown() {
        close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun open() {
        val target = url ?: return
        val myGeneration = generation
        val request = try {
            Request.Builder().url(target).build()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid bridge URL", e)
            listener.onDisconnected(-1, "invalid_url")
            return
        }
        socket = http.newWebSocket(request, SocketListener(myGeneration))
    }

    private fun onSocketGone(decision: ReconnectDecision, reason: String) {
        isOpen = false
        socket = null
        if (isStopped) return
        if (decision is ReconnectDecision.Rejected) {
            Log.w(TAG, "Bridge rejected the token: $reason")
            isStopped = true
            listener.onRejected()
            return
        }
        val delay = (decision as ReconnectDecision.Retry).delaySeconds
        listener.onDisconnected(delay, reason)
        main.postDelayed({ if (!isStopped) open() }, delay * 1000L)
    }

    private inner class SocketListener(private val owner: Int) : WebSocketListener() {
        private fun post(block: () -> Unit) {
            main.post { if (owner == generation) block() }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) = post {
            isOpen = true
            listener.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) = post {
            val message = VoiceMessages.decode(text)
            // Only a `ready` frame proves the bridge accepted us; a bare open does not.
            if (message is BridgeMessage.Ready) policy.onReady()
            listener.onMessage(message)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post {
            onSocketGone(policy.onClosed(code), "closed $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket failure: ${t.message}")
            val status = response?.code
            post { onSocketGone(policy.onFailure(status), t.message ?: t.javaClass.simpleName) }
        }
    }

    private companion object {
        const val TAG = "PupusBridge"
        const val PING_SECONDS = 20L
        const val NORMAL_CLOSE = 1000
    }
}
