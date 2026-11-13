package com.pupus.phone.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeController : DeviceController {
    val calls = mutableListOf<Any>()
    var tapOutcome: Outcome = Outcome.ok(true)

    private fun record(value: Any, outcome: Outcome = Outcome.ok(true)): Outcome {
        calls.add(value)
        return outcome
    }

    override fun health() = record("health", Outcome.Ok(JSONObject().put("ok", true)))
    override fun tree(visibleOnly: Boolean) = record("tree:$visibleOnly", Outcome.Ok(JSONObject().put("nodes", 0)))
    override fun screenshot(params: ShotParams) = record(params)
    override fun tap(target: TapTarget) = record(target, tapOutcome)
    override fun longPress(request: LongPressRequest) = record(request)
    override fun swipe(request: SwipeRequest) = record(request)
    override fun scroll(request: ScrollRequest) = record(request, Outcome.ok(false))
    override fun text(request: TextRequest) = record(request)
    override fun global(action: GlobalActionName) = record(action)
    override fun apps() = record("apps")
    override fun launch(pkg: String) = record("launch:$pkg")
    override fun intent(request: IntentRequest) = record(request)
    override fun media(action: MediaAction) = record(action)
    override fun mediaState() = record("mediaState")
    override fun playSearch(request: PlaySearchRequest) = record(request)
    override fun notifications(pkg: String?) = record("notifications:$pkg")
    override fun contacts(query: String) = record("contacts:$query")
    override fun call(request: CallRequest) = record(request)
}

class RouteHandlerTest {
    private val token = "0123456789abcdef".repeat(4)
    private val controller = FakeController()
    private val handler = RouteHandler({ token }, controller)

    private fun call(method: String, path: String, body: String? = null, query: Map<String, String> = emptyMap()) =
        handler.handle(ApiRequest(method, path, "Bearer $token", query, body))

    @Test
    fun unauthorizedWithoutOrWithWrongToken() {
        val none = handler.handle(ApiRequest("GET", "/health", null))
        assertEquals(401, none.status)
        assertEquals("unauthorized", none.body.getString("error"))
        assertEquals(401, handler.handle(ApiRequest("GET", "/health", "Bearer nope")).status)
        assertTrue(controller.calls.isEmpty())
    }

    @Test
    fun preflightAuthorizationMatchesHandle() {
        assertTrue(handler.isAuthorized("Bearer $token"))
        assertEquals(false, handler.isAuthorized(null))
        assertEquals(false, handler.isAuthorized("Bearer nope"))
    }

    @Test
    fun shortcutRoutesParseTheirInput() {
        assertEquals(200, call("GET", "/media/state").status)
        call("POST", "/media/play_search", """{"query":" Queen ","package":"com.spotify.music"}""")
        call("POST", "/media/play_search", """{"query":"Queen"}""")
        call("GET", "/notifications", query = mapOf("package" to "com.whatsapp"))
        call("GET", "/notifications")
        call("GET", "/contacts", query = mapOf("q" to " Papá "))
        call("POST", "/call", """{"number":"+34 612 345 678"}""")
        assertEquals(
            listOf(
                "mediaState",
                PlaySearchRequest("Queen", "com.spotify.music"),
                PlaySearchRequest("Queen", null),
                "notifications:com.whatsapp",
                "notifications:null",
                "contacts:Papá",
                CallRequest("+34 612 345 678"),
            ),
            controller.calls,
        )
    }

    @Test
    fun shortcutRoutesRejectBadInput() {
        assertEquals(400, call("POST", "/media/play_search", """{"query":""}""").status)
        assertEquals(400, call("POST", "/media/play_search", """{"query":"x","package":"spotify; rm"}""").status)
        assertEquals(400, call("POST", "/media/play_search", """{"query":"${"x".repeat(201)}"}""").status)
        assertEquals(400, call("GET", "/notifications", query = mapOf("package" to "../etc")).status)
        assertEquals(400, call("GET", "/contacts").status)
        assertEquals(400, call("GET", "/contacts", query = mapOf("q" to "x".repeat(65))).status)
        assertEquals(400, call("POST", "/call", """{"number":"*#06#"}""").status)
        assertEquals(400, call("POST", "/call", """{"number":"12"}""").status)
        assertTrue(controller.calls.isEmpty())
    }

    @Test
    fun staleNodeMapsTo409() {
        controller.tapOutcome = Outcome.STALE_NODE
        val response = call("POST", "/input/tap", """{"nodeId":3}""")
        assertEquals(409, response.status)
        assertEquals("stale_node", response.body.getString("error"))
    }

    @Test
    fun unknownRouteAndWrongMethod() {
        assertEquals(404, call("GET", "/nope").status)
        assertEquals("not_found", call("GET", "/nope").body.getString("error"))
        assertEquals(405, call("GET", "/input/tap").status)
        assertEquals(200, call("GET", "/health/").status)
    }

    @Test
    fun tapByPointOrNode() {
        assertEquals(200, call("POST", "/input/tap", """{"x":10,"y":20}""").status)
        call("POST", "/input/tap", """{"nodeId":7}""")
        assertEquals(listOf(TapTarget.Point(10, 20), TapTarget.Node(7)), controller.calls)
    }

    @Test
    fun tapValidation() {
        assertEquals(400, call("POST", "/input/tap", "{}").status)
        assertEquals(400, call("POST", "/input/tap", """{"x":1.5,"y":2}""").status)
        assertEquals(400, call("POST", "/input/tap", """{"x":"1","y":2}""").status)
        assertEquals(400, call("POST", "/input/tap", """{"x":-1,"y":2}""").status)
        val broken = call("POST", "/input/tap", "{not json")
        assertEquals(400, broken.status)
        assertTrue(broken.body.getString("error").startsWith("bad_request"))
        assertTrue(controller.calls.isEmpty())
    }

    @Test
    fun nodeNotFoundIsPropagatedAs404() {
        controller.tapOutcome = Outcome.NODE_NOT_FOUND
        val response = call("POST", "/input/tap", """{"nodeId":99}""")
        assertEquals(404, response.status)
        assertEquals("node_not_found", response.body.getString("error"))
    }

    @Test
    fun gestureDefaults() {
        call("POST", "/input/long_press", """{"x":1,"y":2}""")
        call("POST", "/input/swipe", """{"x1":1,"y1":2,"x2":3,"y2":4}""")
        call("POST", "/input/swipe", """{"x1":1,"y1":2,"x2":3,"y2":4,"durationMs":900}""")
        assertEquals(
            listOf(LongPressRequest(1, 2, 600), SwipeRequest(1, 2, 3, 4, 300), SwipeRequest(1, 2, 3, 4, 900)),
            controller.calls,
        )
        assertEquals(400, call("POST", "/input/swipe", """{"x1":1,"y1":2,"x2":3}""").status)
        assertEquals(400, call("POST", "/input/long_press", """{"x":1,"y":2,"durationMs":0}""").status)
    }

    @Test
    fun scrollTextGlobalMedia() {
        val scroll = call("POST", "/input/scroll", """{"direction":"down"}""")
        assertEquals(false, scroll.body.getBoolean("ok"))
        call("POST", "/input/text", """{"text":"hola","submit":true}""")
        call("POST", "/input/global", """{"action":"quick_settings"}""")
        call("POST", "/media", """{"action":"volume_up"}""")
        assertEquals(
            listOf(
                ScrollRequest(ScrollDirection.DOWN, null), TextRequest("hola", null, true),
                GlobalActionName.QUICK_SETTINGS, MediaAction.VOLUME_UP,
            ),
            controller.calls,
        )
        assertTrue(ScrollDirection.DOWN.forward && ScrollDirection.RIGHT.forward)
        assertTrue(!ScrollDirection.UP.forward && !ScrollDirection.LEFT.forward)
        assertEquals(400, call("POST", "/input/scroll", """{"direction":"sideways"}""").status)
        assertEquals(400, call("POST", "/input/text", """{"text":5}""").status)
        assertEquals(400, call("POST", "/input/global", """{"action":"reboot"}""").status)
        assertEquals(400, call("POST", "/media", "{}").status)
    }

    @Test
    fun appsAndIntents() {
        call("GET", "/apps")
        call("POST", "/apps/launch", """{"package":"com.spotify.music"}""")
        call("POST", "/intent", """{"data":"spotify:search:queen","extras":{"k":"v"}}""")
        assertEquals(
            listOf(
                "apps", "launch:com.spotify.music",
                IntentRequest("android.intent.action.VIEW", "spotify:search:queen", null, mapOf("k" to "v")),
            ),
            controller.calls,
        )
        assertEquals(400, call("POST", "/apps/launch", "{}").status)
        assertEquals(400, call("POST", "/intent", "{}").status)
        assertEquals(400, call("POST", "/intent", """{"data":"x","extras":{"n":1}}""").status)
    }

    @Test
    fun screenQueries() {
        call("GET", "/screen/tree")
        call("GET", "/screen/tree", query = mapOf("visibleOnly" to "false"))
        call("GET", "/screen/shot")
        call("GET", "/screen/shot", query = mapOf("scale" to "1", "quality" to "100"))
        assertEquals(
            listOf("tree:true", "tree:false", ShotParams(0.5f, 60), ShotParams(1f, 100)),
            controller.calls,
        )
        assertEquals(400, call("GET", "/screen/shot", query = mapOf("scale" to "0.05")).status)
        assertEquals(400, call("GET", "/screen/shot", query = mapOf("quality" to "101")).status)
        assertEquals(400, call("GET", "/screen/tree", query = mapOf("visibleOnly" to "maybe")).status)
    }

    @Test
    fun controllerExceptionBecomes500() {
        val failing = RouteHandler({ token }, object : DeviceController by controller {
            override fun apps(): Outcome = throw IllegalStateException("boom")
        })
        val response = failing.handle(ApiRequest("GET", "/apps", "Bearer $token"))
        assertEquals(500, response.status)
        assertTrue(response.body.getString("error").contains("boom"))
    }
}
