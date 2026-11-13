package com.pupus.phone.device

import android.accessibilityservice.AccessibilityService
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.pupus.phone.BuildConfig
import com.pupus.phone.api.DeviceController
import com.pupus.phone.api.GlobalActionName
import com.pupus.phone.api.CallRequest
import com.pupus.phone.api.IntentRequest
import com.pupus.phone.api.LongPressRequest
import com.pupus.phone.api.MediaAction
import com.pupus.phone.api.PlaySearchRequest
import com.pupus.phone.api.Outcome
import com.pupus.phone.api.ScrollRequest
import com.pupus.phone.api.ShotParams
import com.pupus.phone.api.SwipeRequest
import com.pupus.phone.api.TapTarget
import com.pupus.phone.api.TextRequest
import com.pupus.phone.tree.A11yNode
import com.pupus.phone.tree.AccessibilityNodeAdapter
import com.pupus.phone.tree.NodeFreshness
import com.pupus.phone.tree.NodeLookup
import com.pupus.phone.tree.NodeSearch
import com.pupus.phone.tree.TreeSerializer
import com.pupus.phone.tree.TreeSnapshot
import org.json.JSONObject

/** [DeviceController] over a connected AccessibilityService. */
class AndroidDeviceController(private val service: AccessibilityService) : DeviceController {
    private val gestures = Gestures(service)
    private val capture = ScreenCapture(service)
    private val system = SystemActions(service)
    private val mediaSessions = MediaSessions(service)
    private val notificationReader = NotificationReader(service)
    private val phone = PhoneActions(service)

    @Volatile
    private var snapshot: TreeSnapshot = TreeSnapshot.EMPTY

    override fun health(): Outcome {
        val (width, height) = screenSize()
        return Outcome.Ok(
            JSONObject()
                .put("ok", true)
                .put("version", BuildConfig.VERSION_NAME)
                .put("accessibility", true)
                .put("screen", JSONObject().put("width", width).put("height", height))
                .put("foregroundPackage", foregroundPackage() ?: JSONObject.NULL),
        )
    }

    override fun tree(visibleOnly: Boolean): Outcome {
        val taken = TreeSerializer.serialize(roots(), visibleOnly)
        snapshot = taken
        val (width, height) = screenSize()
        return Outcome.Ok(TreeSerializer.toJson(taken, foregroundPackage(), width, height))
    }

    override fun screenshot(params: ShotParams): Outcome = capture.take(params)

    override fun tap(target: TapTarget): Outcome = when (target) {
        is TapTarget.Point -> gestures.tap(target.x, target.y)
        is TapTarget.Node -> tapNode(target.nodeId)
    }

    /** Runs [block] only on a node that still matches the last snapshot (404 / 409 otherwise). */
    private inline fun withFreshNode(nodeId: Int, block: (A11yNode) -> Outcome): Outcome =
        when (val lookup = NodeFreshness.resolve(snapshot, nodeId)) {
            is NodeLookup.Fresh -> block(lookup.node)
            NodeLookup.NotFound -> Outcome.NODE_NOT_FOUND
            NodeLookup.Stale -> Outcome.STALE_NODE
        }

    private fun tapNode(nodeId: Int): Outcome = withFreshNode(nodeId) { node ->
        val clickable = NodeSearch.clickableSelfOrAncestor(node) as? AccessibilityNodeAdapter
        if (clickable != null && clickable.info.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return@withFreshNode Outcome.ok(true)
        }
        Log.i(TAG, "Node $nodeId not clickable via action; tapping its center")
        val (x, y) = NodeSearch.center(node.bounds)
        gestures.tap(x, y)
    }

    override fun longPress(request: LongPressRequest): Outcome =
        gestures.press(request.x, request.y, request.durationMs)

    override fun swipe(request: SwipeRequest): Outcome =
        gestures.swipe(request.x1, request.y1, request.x2, request.y2, request.durationMs)

    override fun scroll(request: ScrollRequest): Outcome {
        val action = if (request.direction.forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val nodeId = request.nodeId
        if (nodeId != null) return withFreshNode(nodeId) { Outcome.ok(infoOf(it)?.performAction(action) == true) }
        val node = NodeSearch.firstScrollable(roots()) ?: return Outcome.ok(false)
        return Outcome.ok(infoOf(node)?.performAction(action) == true)
    }

    override fun text(request: TextRequest): Outcome {
        val nodeId = request.nodeId ?: return setText(inputFocus(), request)
        return withFreshNode(nodeId) { setText(infoOf(it), request) }
    }

    private fun setText(info: AccessibilityNodeInfo?, request: TextRequest): Outcome {
        if (info == null) return Outcome.ok(false)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, request.text)
        }
        val isSet = info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!isSet || !request.submit) return Outcome.ok(isSet)
        val enter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        return Outcome.ok(info.performAction(enter))
    }

    override fun global(action: GlobalActionName): Outcome {
        val code = when (action) {
            GlobalActionName.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            GlobalActionName.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            GlobalActionName.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            GlobalActionName.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            GlobalActionName.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            GlobalActionName.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        }
        return Outcome.ok(service.performGlobalAction(code))
    }

    override fun apps(): Outcome = system.apps()
    override fun launch(pkg: String): Outcome = system.launch(pkg)
    override fun intent(request: IntentRequest): Outcome = system.intent(request)
    override fun media(action: MediaAction): Outcome = system.media(action)
    override fun mediaState(): Outcome = mediaSessions.state()
    override fun playSearch(request: PlaySearchRequest): Outcome = mediaSessions.playSearch(request)
    override fun notifications(pkg: String?): Outcome = notificationReader.read(pkg)
    override fun contacts(query: String): Outcome = phone.contacts(query)
    override fun call(request: CallRequest): Outcome = phone.call(request)

    /** Roots of all interactive windows except our own overlay; falls back to the active window. */
    private fun roots(): List<A11yNode> {
        val fromWindows = try {
            service.windows
                .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                .mapNotNull { it.root }
        } catch (e: RuntimeException) {
            Log.w(TAG, "getWindows failed; using rootInActiveWindow", e)
            emptyList()
        }
        val infos = fromWindows.ifEmpty { listOfNotNull(service.rootInActiveWindow) }
        return infos.map { AccessibilityNodeAdapter(it) }
    }

    private fun inputFocus(): AccessibilityNodeInfo? =
        service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun infoOf(node: A11yNode): AccessibilityNodeInfo? = (node as? AccessibilityNodeAdapter)?.info

    private fun foregroundPackage(): String? = service.rootInActiveWindow?.packageName?.toString()

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return Pair(0, 0)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    private companion object {
        const val TAG = "PupusDevice"
    }
}
