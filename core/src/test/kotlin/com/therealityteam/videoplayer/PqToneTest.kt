package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.PqTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PqToneTest {

    @Test
    fun readsTheCurveAtItsKnownPoints() {
        assertEquals(0.0, PqTone.nits(0.0), 1e-9)
        assertEquals(10_000.0, PqTone.nits(1.0), 1e-6)
        // BT.2408: 203 nits is coded 0.58, 1000 nits 0.75.
        assertEquals(203.0, PqTone.nits(0.5806), 1.0)
        assertEquals(1000.0, PqTone.nits(0.7518), 5.0)
    }

    @Test
    fun paperWhiteSitsBelowTheTopAndAThousandNitsReachesIt() {
        assertEquals(0, PqTone.toSdr(0.0))
        // White paper is a plain picture's bright grey, leaving room for highlights.
        assertEquals(189.0, PqTone.toSdr(0.5806).toDouble(), 2.0)
        assertEquals(255, PqTone.toSdr(0.7518))
        assertEquals(255, PqTone.toSdr(1.0))
    }

    @Test
    fun neverDarkensAsTheLightRises() {
        val t = PqTone.table
        assertEquals(PqTone.STEPS, t.size)
        for (i in 1 until t.size) assertTrue("step $i", t[i] >= t[i - 1])
    }
}
