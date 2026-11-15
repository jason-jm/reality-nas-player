package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.ui.player.PlayerAdjust
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerAdjustTest {

    private fun scaleStep(from: Float, steps: Int) = PlayerAdjust.stepFraction(
        from, steps, PlayerAdjust.SCALE_STEP_PCT, PlayerAdjust.SCALE_MIN_PCT, PlayerAdjust.SCALE_MAX_PCT,
    )

    private fun raiseStep(from: Float, steps: Int) = PlayerAdjust.stepFraction(
        from, steps, PlayerAdjust.RAISE_STEP_PCT, PlayerAdjust.RAISE_MIN_PCT, PlayerAdjust.RAISE_MAX_PCT,
    )

    @Test
    fun `stepping the size down from 100 percent reads every step exactly`() {
        var v = 1.0f
        val shown = (1..5).map { v = scaleStep(v, -1); PlayerAdjust.percent(v) }
        // The old float sums showed 90, 79, 69, 60, 60.
        assertEquals(listOf(90, 80, 70, 60, 60), shown)
    }

    @Test
    fun `stepping the size up stops at 200 percent`() {
        var v = 1.0f
        repeat(15) { v = scaleStep(v, +1) }
        assertEquals(200, PlayerAdjust.percent(v))
        assertEquals(2.0f, v)
    }

    @Test
    fun `stepping the position down from the top reads every step exactly`() {
        var v = 0.35f
        val shown = (1..8).map { v = raiseStep(v, -1); PlayerAdjust.percent(v) }
        // The old float sums showed 29, 24, 19, 14, 9, 4, 0.
        assertEquals(listOf(30, 25, 20, 15, 10, 5, 0, 0), shown)
    }

    @Test
    fun `a drifted stored value lands back on the grid`() {
        assertEquals(0.8f, PlayerAdjust.snapFraction(0.79999995f, 10, 60, 200))
        assertEquals(0.3f, PlayerAdjust.snapFraction(0.29999998f, 5, 0, 35))
        assertEquals(0.6f, PlayerAdjust.snapFraction(0.1f, 10, 60, 200))
    }

    @Test
    fun `offsets step a tenth of a second within their limit`() {
        assertEquals(100L, PlayerAdjust.stepOffset(0L, +1, 2_000L))
        assertEquals(-300L, PlayerAdjust.stepOffset(-200L, -1, 2_000L))
        assertEquals(2_000L, PlayerAdjust.stepOffset(1_950L, +1, 2_000L))
        assertEquals(-30_000L, PlayerAdjust.stepOffset(-30_000L, -1, 30_000L))
        // A value from the old half-second grid stays where it was.
        assertEquals(1_600L, PlayerAdjust.stepOffset(1_500L, +1, 30_000L))
    }

    @Test
    fun `offsets read with a sign and one decimal`() {
        val us = java.util.Locale.US
        assertEquals("0.0s", PlayerAdjust.signedSeconds(0L, us))
        assertEquals("+0.4s", PlayerAdjust.signedSeconds(400L, us))
        assertEquals("−1.2s", PlayerAdjust.signedSeconds(-1_200L, us))
        // Past ten seconds the tenth still shows: 10.0 and 10.5 used to read the same.
        assertEquals("+10.0s", PlayerAdjust.signedSeconds(10_000L, us))
        assertEquals("+10.5s", PlayerAdjust.signedSeconds(10_500L, us))
        // The reader's decimal comma.
        assertEquals("+0,4s", PlayerAdjust.signedSeconds(400L, java.util.Locale.GERMANY))
    }
}
