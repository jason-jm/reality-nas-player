package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.ControllerModel
import com.therealityteam.videoplayer.data.playback.ControllerModel.Axis
import com.therealityteam.videoplayer.data.playback.ControllerModel.Intent
import com.therealityteam.videoplayer.data.playback.ControllerModel.State
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerModelTest {

    @Test
    fun `drift inside the dead zone is rest, the larger axis wins outside it`() {
        assertEquals(Axis.NONE, ControllerModel.axisOf(0.3f, -0.4f))
        assertEquals(Axis.RIGHT, ControllerModel.axisOf(0.9f, 0.2f))
        assertEquals(Axis.LEFT, ControllerModel.axisOf(-0.7f, 0.6f))
        assertEquals(Axis.UP, ControllerModel.axisOf(0.1f, 0.8f))
        assertEquals(Axis.DOWN, ControllerModel.axisOf(0.0f, -1.0f))
    }

    @Test
    fun `a push steps once, then repeats while held, and stops at rest`() {
        var (s, i) = ControllerModel.step(State(), 1f, 0f, false, nowMs = 1000)
        assertEquals(listOf(Intent.SEEK_FORWARD), i)
        val r = ControllerModel.step(s, 1f, 0f, false, nowMs = 1200); s = r.first
        assertEquals(emptyList<Intent>(), r.second)                        // before the first repeat
        val r2 = ControllerModel.step(s, 1f, 0f, false, nowMs = 1460); s = r2.first
        assertEquals(listOf(Intent.SEEK_FORWARD), r2.second)                // 450ms later
        val r3 = ControllerModel.step(s, 1f, 0f, false, nowMs = 1720); s = r3.first
        assertEquals(listOf(Intent.SEEK_FORWARD), r3.second)                // then every 250ms
        val r4 = ControllerModel.step(s, 0f, 0f, false, nowMs = 1800); s = r4.first
        assertEquals(emptyList<Intent>(), r4.second)
        assertEquals(Axis.NONE, s.axis)
        val r5 = ControllerModel.step(s, 0f, 0.9f, false, nowMs = 1900)
        assertEquals(listOf(Intent.VOLUME_UP), r5.second)                   // a fresh push steps at once
    }

    @Test
    fun `a click is not a hold, a hold boosts until release`() {
        var (s, i) = ControllerModel.step(State(), 0f, 0f, true, nowMs = 0)
        assertEquals(emptyList<Intent>(), i)
        val click = ControllerModel.step(s, 0f, 0f, false, nowMs = 150)
        assertEquals(emptyList<Intent>(), click.second)                      // released early: a click
        s = click.first
        s = ControllerModel.step(s, 0f, 0f, true, nowMs = 1000).first
        val held = ControllerModel.step(s, 0f, 0f, true, nowMs = 1450); s = held.first
        assertEquals(listOf(Intent.BOOST_ON), held.second)
        assertEquals(emptyList<Intent>(), ControllerModel.step(s, 0f, 0f, true, nowMs = 2000).second) // once
        val up = ControllerModel.step(s, 0f, 0f, false, nowMs = 2500)
        assertEquals(listOf(Intent.BOOST_OFF), up.second)
        assertEquals(State(), up.first)
    }
}
