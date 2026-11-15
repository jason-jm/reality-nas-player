package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.CaptionFollow
import com.therealityteam.videoplayer.data.playback.CaptionFollow.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CaptionFollowTest {

    private fun run(start: State?, head: Float, seconds: Float): State {
        var s = start
        repeat((seconds * 72).toInt()) { s = CaptionFollow.step(s, head, 1f / 72) }
        return s!!
    }

    @Test
    fun theyStartWhereTheHeadPoints() {
        assertEquals(30f, CaptionFollow.step(null, 30f, 0.016f).yaw, 0f)
    }

    @Test
    fun aGlanceAsideLeavesThemBe() {
        val s = run(State(0f), head = 20f, seconds = 2f)
        assertEquals(0f, s.yaw, 0f)
    }

    @Test
    fun aTurnTakesThemAlongAndTheyStop() {
        val halfway = run(State(0f), head = 90f, seconds = 0.15f)
        assertTrue("on the way: ${halfway.yaw}", halfway.yaw in 20f..80f)
        val there = run(halfway, head = 90f, seconds = 2f)
        assertTrue("arrived: ${there.yaw}", abs(there.yaw - 90f) <= CaptionFollow.STOP_DEGREES)
        assertEquals(false, there.following)
        // Arrived, a small look away does not start them again.
        assertEquals(there.yaw, run(there, head = 100f, seconds = 1f).yaw, 0f)
    }

    @Test
    fun theyGoTheShortWayRoundBehind() {
        // From 170° to −170° is 20° through 180°, not 340° back through the front.
        val s = run(State(170f, following = true), head = -170f, seconds = 0.05f)
        assertTrue("went through 180: ${s.yaw}", s.yaw > 170f || s.yaw < -170f)
    }

    @Test
    fun wrapKeepsAnglesInOneTurn() {
        assertEquals(-170f, CaptionFollow.wrap(190f), 1e-4f)
        assertEquals(180f, CaptionFollow.wrap(-180f), 1e-4f)
        assertEquals(10f, CaptionFollow.wrap(730f), 1e-4f)
    }
}
