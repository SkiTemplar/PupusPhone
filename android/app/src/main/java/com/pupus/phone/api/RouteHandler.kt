package com.pupus.phone.api

import android.util.Log
import org.json.JSONObject

data class ApiRequest(
    val method: String,
    val path: String,
    val authorization: String?,
    val query: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class ApiResponse(val status: Int, val body: JSONObject)

/** Result of a controller operation. */
sealed class Outcome {
    data class Ok(val body: JSONObject) : Outcome()
    data class Failure(val status: Int, val error: String) : Outcome()

    companion object {
        fun ok(value: Boolean = true): Outcome = Ok(JSONObject().put("ok", value))
        val NODE_NOT_FOUND: Outcome = Failure(404, "node_not_found")
        val STALE_NODE: Outcome = Failure(409, "stale_node")
    }
}

/** Device operations; implemented over the AccessibilityService, faked in tests. */
interface DeviceController {
    fun health(): Outcome
    fun tree(visibleOnly: Boolean): Outcome
    fun screenshot(params: ShotParams): Outcome
    fun tap(target: TapTarget): Outcome
    fun longPress(request: LongPressRequest): Outcome
    fun swipe(request: SwipeRequest): Outcome
    fun scroll(request: ScrollRequest): Outcome
    fun text(request: TextRequest): Outcome
    fun global(action: GlobalActionName): Outcome
    fun apps(): Outcome
    fun launch(pkg: String): Outcome
    fun intent(request: IntentRequest): Outcome
    fun media(action: MediaAction): Outcome

    // Personal-data and shortcut endpoints. Defaults keep test fakes short; the real controller overrides them.
    fun mediaState(): Outcome = NOT_IMPLEMENTED
    fun playSearch(request: PlaySearchRequest): Outcome = NOT_IMPLEMENTED
    fun notifications(pkg: String?): Outcome = NOT_IMPLEMENTED
    fun contacts(query: String): Outcome = NOT_IMPLEMENTED
    fun call(request: CallRequest): Outcome = NOT_IMPLEMENTED
}

private val NOT_IMPLEMENTED: Outcome = Outcome.Failure(501, "not_implemented")

/** HTTP-agnostic routing of the device API (docs/protocol.md section 1). */
class RouteHandler(
    private val tokenProvider: () -> String,
    private val controller: DeviceController,
) {
    private val routes: Map<Pair<String, String>, (ApiRequest) -> Outcome> = mapOf(
        ("GET" to "/health") to { _ -> controller.health() },
        ("GET" to "/screen/tree") to { r -> controller.tree(RequestParser.visibleOnly(r.query)) },
        ("GET" to "/screen/shot") to { r -> controller.screenshot(RequestParser.shot(r.query)) },
        ("POST" to "/input/tap") to { r -> controller.tap(RequestParser.tap(json(r))) },
        ("POST" to "/input/long_press") to { r -> controller.longPress(RequestParser.longPress(json(r))) },
        ("POST" to "/input/swipe") to { r -> controller.swipe(RequestParser.swipe(json(r))) },
        ("POST" to "/input/scroll") to { r -> controller.scroll(RequestParser.scroll(json(r))) },
        ("POST" to "/input/text") to { r -> controller.text(RequestParser.text(json(r))) },
        ("POST" to "/input/global") to { r -> controller.global(RequestParser.global(json(r))) },
        ("GET" to "/apps") to { _ -> controller.apps() },
        ("POST" to "/apps/launch") to { r -> controller.launch(RequestParser.launch(json(r))) },
        ("POST" to "/intent") to { r -> controller.intent(RequestParser.intent(json(r))) },
        ("POST" to "/media") to { r -> controller.media(RequestParser.media(json(r))) },
        ("GET" to "/media/state") to { _ -> controller.mediaState() },
        ("POST" to "/media/play_search") to { r -> controller.playSearch(RequestParser.playSearch(json(r))) },
        ("GET" to "/notifications") to { r -> controller.notifications(RequestParser.notificationsPackage(r.query)) },
        ("GET" to "/contacts") to { r -> controller.contacts(RequestParser.contactsQuery(r.query)) },
        ("POST" to "/call") to { r -> controller.call(RequestParser.call(json(r))) },
    )
    private val knownPaths = routes.keys.map { it.second }.toSet()

    /** Token check alone, so the HTTP layer can reject before reading the body. */
    fun isAuthorized(authorization: String?): Boolean = DeviceToken.isAuthorized(authorization, tokenProvider())

    fun handle(request: ApiRequest): ApiResponse {
        if (!isAuthorized(request.authorization)) return error(401, "unauthorized")
        val path = normalize(request.path)
        val route = routes[request.method.uppercase() to path]
            ?: return if (path in knownPaths) error(405, "method_not_allowed") else error(404, "not_found")
        return try {
            when (val outcome = route(request)) {
                is Outcome.Ok -> ApiResponse(200, outcome.body)
                is Outcome.Failure -> error(outcome.status, outcome.error)
            }
        } catch (e: BadRequest) {
            error(400, "bad_request: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Route $path failed", e)
            error(500, "internal_error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun json(request: ApiRequest): JSONObject = RequestParser.body(request.body)

    private fun normalize(path: String): String {
        val clean = path.substringBefore('?')
        return if (clean.length > 1) clean.trimEnd('/') else clean
    }

    companion object {
        private const val TAG = "PupusRoutes"

        fun error(status: Int, message: String) = ApiResponse(status, JSONObject().put("error", message))
    }
}
