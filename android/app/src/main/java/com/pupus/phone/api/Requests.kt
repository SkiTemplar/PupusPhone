package com.pupus.phone.api

import org.json.JSONException
import org.json.JSONObject

/** Validation failure of a request; mapped to `400 {"error":"bad_request: <detail>"}`. */
class BadRequest(detail: String) : Exception(detail)

sealed class TapTarget {
    data class Point(val x: Int, val y: Int) : TapTarget()
    data class Node(val nodeId: Int) : TapTarget()
}

data class LongPressRequest(val x: Int, val y: Int, val durationMs: Int)
data class SwipeRequest(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Int)
data class ScrollRequest(val direction: ScrollDirection, val nodeId: Int?)
data class TextRequest(val text: String, val nodeId: Int?, val submit: Boolean)
data class IntentRequest(val action: String, val data: String?, val pkg: String?, val extras: Map<String, String>)
data class ShotParams(val scale: Float, val quality: Int)

enum class ScrollDirection(val wire: String, val forward: Boolean) {
    UP("up", false), DOWN("down", true), LEFT("left", false), RIGHT("right", true)
}

enum class GlobalActionName(val wire: String) {
    BACK("back"), HOME("home"), RECENTS("recents"), NOTIFICATIONS("notifications"),
    QUICK_SETTINGS("quick_settings"), LOCK_SCREEN("lock_screen")
}

enum class MediaAction(val wire: String) {
    PLAY_PAUSE("play_pause"), PLAY("play"), PAUSE("pause"), NEXT("next"),
    PREVIOUS("previous"), VOLUME_UP("volume_up"), VOLUME_DOWN("volume_down")
}

/** Parses and validates request bodies and query strings of the device API. */
object RequestParser {
    const val DEFAULT_LONG_PRESS_MS = 600
    const val DEFAULT_SWIPE_MS = 300
    const val DEFAULT_SCALE = 0.5f
    const val DEFAULT_QUALITY = 60
    const val DEFAULT_INTENT_ACTION = "android.intent.action.VIEW"
    private const val MAX_DURATION_MS = 60_000

    fun body(raw: String?): JSONObject {
        if (raw.isNullOrBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw BadRequest("invalid_json")
        }
    }

    fun tap(o: JSONObject): TapTarget {
        val nodeId = optInt(o, "nodeId")
        if (nodeId != null) return TapTarget.Node(nodeId)
        if (!o.has("x") && !o.has("y")) throw BadRequest("x,y or nodeId required")
        return TapTarget.Point(coord(o, "x"), coord(o, "y"))
    }

    fun longPress(o: JSONObject) =
        LongPressRequest(coord(o, "x"), coord(o, "y"), duration(o, DEFAULT_LONG_PRESS_MS))

    fun swipe(o: JSONObject) = SwipeRequest(
        coord(o, "x1"), coord(o, "y1"), coord(o, "x2"), coord(o, "y2"), duration(o, DEFAULT_SWIPE_MS),
    )

    fun scroll(o: JSONObject): ScrollRequest {
        val wire = reqString(o, "direction")
        val direction = ScrollDirection.entries.firstOrNull { it.wire == wire }
            ?: throw BadRequest("direction must be up|down|left|right")
        return ScrollRequest(direction, optInt(o, "nodeId"))
    }

    fun text(o: JSONObject): TextRequest {
        if (!o.has("text") || o.get("text") !is String) throw BadRequest("text must be a string")
        return TextRequest(o.getString("text"), optInt(o, "nodeId"), optBool(o, "submit") ?: false)
    }

    fun global(o: JSONObject): GlobalActionName {
        val wire = reqString(o, "action")
        return GlobalActionName.entries.firstOrNull { it.wire == wire }
            ?: throw BadRequest("unknown global action: $wire")
    }

    fun media(o: JSONObject): MediaAction {
        val wire = reqString(o, "action")
        return MediaAction.entries.firstOrNull { it.wire == wire }
            ?: throw BadRequest("unknown media action: $wire")
    }

    fun launch(o: JSONObject): String = reqString(o, "package")

    fun intent(o: JSONObject): IntentRequest {
        val action = optString(o, "action") ?: DEFAULT_INTENT_ACTION
        val data = optString(o, "data")
        val pkg = optString(o, "package")
        if (data == null && pkg == null && !o.has("action")) throw BadRequest("action, data or package required")
        return IntentRequest(action, data, pkg, extras(o))
    }

    fun shot(query: Map<String, String>): ShotParams {
        val scale = query["scale"]?.let { it.toFloatOrNull() ?: throw BadRequest("scale must be a number") }
            ?: DEFAULT_SCALE
        if (scale !in 0.1f..1f) throw BadRequest("scale must be in 0.1-1")
        val quality = query["quality"]?.let { it.toIntOrNull() ?: throw BadRequest("quality must be an integer") }
            ?: DEFAULT_QUALITY
        if (quality !in 10..100) throw BadRequest("quality must be in 10-100")
        return ShotParams(scale, quality)
    }

    fun visibleOnly(query: Map<String, String>): Boolean = when (query["visibleOnly"]?.lowercase()) {
        null, "true", "1" -> true
        "false", "0" -> false
        else -> throw BadRequest("visibleOnly must be true|false")
    }

    private fun extras(o: JSONObject): Map<String, String> {
        if (!o.has("extras") || o.isNull("extras")) return emptyMap()
        val raw = o.get("extras") as? JSONObject ?: throw BadRequest("extras must be an object")
        return raw.keys().asSequence().associateWith { key ->
            raw.get(key) as? String ?: throw BadRequest("extras.$key must be a string")
        }
    }

    private fun coord(o: JSONObject, key: String): Int {
        val value = optInt(o, key) ?: throw BadRequest("$key required")
        if (value < 0) throw BadRequest("$key must be >= 0")
        return value
    }

    private fun duration(o: JSONObject, default: Int): Int {
        val value = optInt(o, "durationMs") ?: return default
        if (value !in 1..MAX_DURATION_MS) throw BadRequest("durationMs must be in 1-$MAX_DURATION_MS")
        return value
    }

    fun optInt(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        val number = o.get(key) as? Number ?: throw BadRequest("$key must be an integer")
        val asDouble = number.toDouble()
        if (asDouble % 1.0 != 0.0 || asDouble !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
            throw BadRequest("$key must be an integer")
        }
        return asDouble.toInt()
    }

    private fun optBool(o: JSONObject, key: String): Boolean? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.get(key) as? Boolean ?: throw BadRequest("$key must be a boolean")
    }

    private fun optString(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        val value = o.get(key) as? String ?: throw BadRequest("$key must be a string")
        return value.ifBlank { throw BadRequest("$key must not be empty") }
    }

    private fun reqString(o: JSONObject, key: String): String = optString(o, key) ?: throw BadRequest("$key required")
}
