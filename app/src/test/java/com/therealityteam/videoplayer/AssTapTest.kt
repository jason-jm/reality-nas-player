package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.AssStreams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** An MKV's ASS sample, as Media3 hands it over, read back into the event libass takes. */
class AssTapTest {

    @Test fun `a sample is its block, at its time, for its length`() {
        val sample = "Dialogue: 0:00:00:00,0:00:03:45,12,0,Default,,0,0,0,,{\\fad(200,200)}一切都被埋葬了".toByteArray()
        val e = AssStreams.event(sample, timeUs = 61_230_000)!!
        assertEquals(61_230L, e.startMs)
        assertEquals(3_450L, e.durationMs)
        assertEquals("12,0,Default,,0,0,0,,{\\fad(200,200)}一切都被埋葬了", String(e.chunk))
    }

    @Test fun `what is not a dialogue sample is left`() {
        assertNull(AssStreams.event("Comment: 0:00:00:00,0:00:01:00,1,0,x".toByteArray(), 0))
        assertNull(AssStreams.event("Dialogue: 0:00:00:00".toByteArray(), 0))
        assertNull(AssStreams.event("Dialogue: 0:00:00:00,1:2,3,0,x".toByteArray(), 0))
    }
}
