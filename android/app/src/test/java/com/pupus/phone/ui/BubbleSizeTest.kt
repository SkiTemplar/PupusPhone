package com.pupus.phone.ui

import com.pupus.phone.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleSizeTest {
    private val nav = BubbleSizePolicy.DEFAULT_NAVIGATION_PACKAGES
    private val spotify = "com.spotify.music"
    private val gallery = "com.google.android.apps.photos"
    private val home = "com.miui.home"

    @Test
    fun largeInCarModeOverAnOrdinaryApp() {
        assertEquals(BubbleSize.LARGE, BubbleSizePolicy.of(true, spotify, isShowing = false, nav))
        assertEquals(BubbleSize.LARGE, BubbleSizePolicy.of(true, null, isShowing = false, nav))
    }

    @Test
    fun smallWithoutCarMode() {
        assertEquals(BubbleSize.SMALL, BubbleSizePolicy.of(false, spotify, isShowing = false, nav))
    }

    @Test
    fun smallOverNavigationApps() {
        assertEquals(BubbleSize.SMALL, BubbleSizePolicy.of(true, "com.google.android.apps.maps", false, nav))
        assertEquals(BubbleSize.SMALL, BubbleSizePolicy.of(true, "com.waze", false, nav))
        assertEquals(BubbleSize.LARGE, BubbleSizePolicy.of(true, "com.waze", false, setOf("com.sygic")))
    }

    @Test
    fun smallWhileShowing() {
        assertEquals(BubbleSize.SMALL, BubbleSizePolicy.of(true, gallery, isShowing = true, nav))
    }

    @Test
    fun parsesTheNavigationList() {
        assertEquals(
            setOf("com.waze", "com.sygic.aura", "net.osmand"),
            BubbleSizePolicy.parsePackages(" com.waze, com.sygic.aura;\nnet.osmand  not a package ,, .bad"),
        )
        assertTrue(BubbleSizePolicy.parsePackages("").isEmpty())
    }

    @Test
    fun ignoresOwnSystemAndKeyboardWindows() {
        val ignored = setOf("com.pupus.phone", "com.android.systemui", "com.google.android.inputmethod.latin")
        assertTrue(BubbleSizePolicy.isForegroundApp(spotify, ignored))
        ignored.forEach { assertFalse(it, BubbleSizePolicy.isForegroundApp(it, ignored)) }
        assertFalse(BubbleSizePolicy.isForegroundApp(null, ignored))
        assertFalse(BubbleSizePolicy.isForegroundApp(" ", ignored))
    }

    private fun turnThatOpens(pkg: String, spoke: Boolean, endAt: Long = 1_000L): ShowingTracker {
        var t = ShowingTracker().onVoiceState(VoiceState.LISTENING, 0).onVoiceState(VoiceState.THINKING, 10)
        t = t.onForeground(pkg, isLauncher = false)
        if (spoke) t = t.onVoiceState(VoiceState.SPEAKING, 500)
        return t.onVoiceState(VoiceState.IDLE, endAt)
    }

    @Test
    fun aSpokenTurnThatOpenedAnAppShowsIt() {
        val t = turnThatOpens(gallery, spoke = true)
        assertTrue(t.isShowing(1_000L))
        assertEquals(gallery, t.showingPackage)
        assertEquals(1_000L + ShowingTracker.HOLD_MS, t.expiresAtMs())
    }

    @Test
    fun aSilentTurnDoesNotShow() {
        val t = turnThatOpens(spotify, spoke = false)
        assertFalse(t.isShowing(1_000L))
        assertNull(t.expiresAtMs())
    }

    @Test
    fun aSpokenTurnWithoutOpeningAnythingDoesNotShow() {
        val t = ShowingTracker().onVoiceState(VoiceState.THINKING, 0)
            .onVoiceState(VoiceState.SPEAKING, 10).onVoiceState(VoiceState.IDLE, 20)
        assertFalse(t.isShowing(20))
    }

    @Test
    fun openingTheHomeScreenDuringTheTurnDoesNotShow() {
        val t = ShowingTracker().onVoiceState(VoiceState.THINKING, 0)
            .onForeground(gallery, isLauncher = false).onForeground(home, isLauncher = true)
            .onVoiceState(VoiceState.SPEAKING, 10).onVoiceState(VoiceState.IDLE, 20)
        assertFalse(t.isShowing(20))
    }

    @Test
    fun showingEndsAtHomeOrAfterTheHoldTime() {
        val t = turnThatOpens(gallery, spoke = true)
        assertFalse(t.onForeground(home, isLauncher = true).isShowing(1_001L))
        assertTrue(t.isShowing(1_000L + ShowingTracker.HOLD_MS - 1))
        assertFalse(t.isShowing(1_000L + ShowingTracker.HOLD_MS))
    }

    @Test
    fun switchingAppsOutsideATurnKeepsShowing() {
        val t = turnThatOpens(gallery, spoke = true).onForeground(spotify, isLauncher = false)
        assertTrue(t.isShowing(2_000L))
    }

    @Test
    fun carModeOffClearsShowing() {
        val t = turnThatOpens(gallery, spoke = true).onVoiceState(VoiceState.STOPPED, 2_000L)
        assertFalse(t.isShowing(2_000L))
    }

    @Test
    fun appsOpenedBeforeTheTurnAreNotShown() {
        val t = ShowingTracker().onForeground(gallery, isLauncher = false)
            .onVoiceState(VoiceState.THINKING, 0).onVoiceState(VoiceState.SPEAKING, 10).onVoiceState(VoiceState.IDLE, 20)
        assertFalse(t.isShowing(20))
    }

    @Test
    fun resizeKeepsTheRightEdgeAndTheCentre() {
        // Screen 1080x2400, small 96 px at the right edge (margin 4), growing to 200 px.
        val pos = BubbleResize.place(980, 800, 96, 200, 1080, 2400, 4)
        assertEquals(1080 - 4 - 200, pos.x)
        assertEquals(800 + 48 - 100, pos.y)
        val back = BubbleResize.place(pos.x, pos.y, 200, 96, 1080, 2400, 4)
        assertEquals(980, back.x)
        assertEquals(800, back.y)
    }

    @Test
    fun resizeKeepsTheLeftEdge() {
        assertEquals(4, BubbleResize.place(4, 800, 96, 200, 1080, 2400, 4).x)
    }

    @Test
    fun resizeStaysOnScreen() {
        val top = BubbleResize.place(980, 4, 96, 200, 1080, 2400, 4)
        assertEquals(4, top.y)
        val bottom = BubbleResize.place(980, 2400 - 100, 96, 200, 1080, 2400, 4)
        assertEquals(2400 - 200 - 4, bottom.y)
        val tiny = BubbleResize.place(4, 4, 96, 200, 150, 150, 4)
        assertEquals(4, tiny.x)
        assertEquals(4, tiny.y)
    }
}
