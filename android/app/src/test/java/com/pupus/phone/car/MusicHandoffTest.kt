package com.pupus.phone.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicHandoffTest {
    private val own = "com.pupus.phone"

    @Test
    fun picksTheMostRecentSessionThatIsNotOurs() {
        assertEquals(1, MusicHandoff.pickTarget(listOf(own, "com.spotify.music", "com.google.android.youtube"), own))
    }

    @Test
    fun keepsThePriorityOrderOfTheSystem() {
        assertEquals(0, MusicHandoff.pickTarget(listOf("com.google.android.apps.youtube.music", "com.spotify.music"), own))
    }

    @Test
    fun noOtherSessionMeansNoTarget() {
        assertNull(MusicHandoff.pickTarget(listOf(own), own))
        assertNull(MusicHandoff.pickTarget(emptyList(), own))
    }
}
