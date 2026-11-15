package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.FramePoster
import org.junit.Assert.assertEquals
import org.junit.Test

class FramePosterTest {
    @Test
    fun `the frame comes a tenth of the way in, within five seconds and two minutes`() {
        assertEquals(30_000_000L, FramePoster.frameTimeUs(0L))              // unknown length: thirty seconds in
        assertEquals(5_000_000L, FramePoster.frameTimeUs(20_000L))          // a short clip: not before five seconds
        assertEquals(9_000_000L, FramePoster.frameTimeUs(90_000L))          // ninety seconds → nine
        assertEquals(120_000_000L, FramePoster.frameTimeUs(3 * 3_600_000L)) // a three-hour film: capped at two minutes
    }
}
