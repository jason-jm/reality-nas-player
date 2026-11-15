package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.MpvStartTracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpvStartTracksTest {

    @Test
    fun aShareFileIsKnownWhateverPortThePlayerUsed() {
        val a = MpvStartTracks.key("http://127.0.0.1:36387/9d489426/[raws] Film.mkv")
        val b = MpvStartTracks.key("http://127.0.0.1:45277/9d489426/[raws] Film.mkv")
        assertEquals(a, b)
        assertEquals("loopback/9d489426/[raws] Film.mkv", a)
        // A local file, a server's own URL: as they are.
        assertEquals("/sdcard/Movies/a.mkv", MpvStartTracks.key("/sdcard/Movies/a.mkv"))
        assertEquals("http://192.168.1.5:8200/MediaItems/1.mkv", MpvStartTracks.key("http://192.168.1.5:8200/MediaItems/1.mkv"))
    }

    @Test
    fun optionsCarryWhatWasPickedSubtitlesOffIncluded() {
        assertEquals(listOf("aid=2", "sid=3"), MpvStartTracks.options("2", "3"))
        assertEquals(listOf("sid=no"), MpvStartTracks.options(null, "no"))
        assertEquals(emptyList<String>(), MpvStartTracks.options(null, null))
    }

    @Test
    fun readsBackWhatItWrote() {
        assertEquals(MpvStartTracks.Picked("1", "no", 42L), MpvStartTracks.decode(MpvStartTracks.encode("1", "no", 42L)))
        assertEquals(MpvStartTracks.Picked(null, "3", 7L), MpvStartTracks.decode(MpvStartTracks.encode(null, "3", 7L)))
        assertNull(MpvStartTracks.decode("garbage"))
        assertNull(MpvStartTracks.decode(null))
    }
}
