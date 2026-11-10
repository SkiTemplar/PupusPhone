package com.pupus.phone.voice

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder

/** Bridge -> phone frames (docs/protocol.md section 2). */
sealed class BridgeMessage {
    data class Ready(val model: String) : BridgeMessage()
    data class Say(val id: String, val text: String) : BridgeMessage()
    data class Status(val id: String, val text: String) : BridgeMessage()
    data class Done(val id: String, val durationMs: Long) : BridgeMessage()
    data class Error(val id: String?, val message: String) : BridgeMessage()

    /** Frame that is not valid JSON, lacks required fields or has an unknown type. */
    data class Invalid(val reason: String) : BridgeMessage()
}

object VoiceMessages {
    const val CLIENT = "android"

    fun hello(version: String): String =
        JSONObject().put("type", "hello").put("client", CLIENT).put("version", version).toString()

    fun utterance(id: String, text: String): String =
        JSONObject().put("type", "utterance").put("id", id).put("text", text).toString()

    fun cancel(id: String): String = JSONObject().put("type", "cancel").put("id", id).toString()

    fun reset(): String = JSONObject().put("type", "reset").toString()

    fun decode(frame: String): BridgeMessage {
        val o = try {
            JSONObject(frame)
        } catch (e: JSONException) {
            return BridgeMessage.Invalid("invalid_json")
        }
        return when (val type = o.optString("type", "")) {
            "ready" -> BridgeMessage.Ready(o.optString("model", ""))
            "say" -> withIdAndText(o) { id, text -> BridgeMessage.Say(id, text) }
            "status" -> withIdAndText(o) { id, text -> BridgeMessage.Status(id, text) }
            "done" -> decodeDone(o)
            "error" -> decodeError(o)
            else -> BridgeMessage.Invalid("unknown_type: $type")
        }
    }

    private fun withIdAndText(o: JSONObject, build: (String, String) -> BridgeMessage): BridgeMessage {
        val id = stringOrNull(o, "id") ?: return BridgeMessage.Invalid("missing id")
        val text = stringOrNull(o, "text") ?: return BridgeMessage.Invalid("missing text")
        return build(id, text)
    }

    private fun decodeDone(o: JSONObject): BridgeMessage {
        val id = stringOrNull(o, "id") ?: return BridgeMessage.Invalid("missing id")
        val duration = (o.opt("durationMs") as? Number)?.toLong() ?: 0L
        return BridgeMessage.Done(id, duration)
    }

    private fun decodeError(o: JSONObject): BridgeMessage {
        val message = stringOrNull(o, "message") ?: return BridgeMessage.Invalid("missing message")
        return BridgeMessage.Error(stringOrNull(o, "id"), message)
    }

    private fun stringOrNull(o: JSONObject, key: String): String? = o.opt(key) as? String
}

/** Builds `ws://host:8766/voice?token=<token>` from the configured base URL. */
object VoiceUrl {
    private const val WS = "ws://"
    private const val WSS = "wss://"

    /**
     * ws:// or wss:// with a host and, if present, a numeric port in 1..65535.
     * Parsed by OkHttp (after mapping to http/https) so it accepts exactly what
     * [BridgeClient] can open.
     */
    fun isValidBase(base: String): Boolean {
        val httpUrl = toHttp(base.trim()) ?: return false
        return httpUrl.toHttpUrlOrNull()?.host?.isNotEmpty() == true
    }

    private fun toHttp(url: String): String? = when {
        url.startsWith(WS, ignoreCase = true) -> "http://" + url.substring(WS.length)
        url.startsWith(WSS, ignoreCase = true) -> "https://" + url.substring(WSS.length)
        else -> null
    }

    fun build(base: String, token: String): String? {
        val trimmed = base.trim()
        if (!isValidBase(trimmed) || token.isBlank()) return null
        val separator = if (trimmed.contains('?')) "&" else "?"
        return trimmed + separator + "token=" + URLEncoder.encode(token.trim(), "UTF-8")
    }
}

/** Exponential reconnect backoff: 1, 2, 4 ... capped at [maxSeconds]. */
class Backoff(private val initialSeconds: Int = 1, private val maxSeconds: Int = 30) {
    private var attempt = 0

    fun nextDelaySeconds(): Int {
        val shift = attempt.coerceAtMost(MAX_SHIFT)
        attempt++
        return (initialSeconds shl shift).coerceAtMost(maxSeconds)
    }

    fun reset() {
        attempt = 0
    }

    private companion object {
        const val MAX_SHIFT = 16
    }
}
