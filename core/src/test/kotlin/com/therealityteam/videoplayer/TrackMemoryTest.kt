package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.MediaTrack
import com.therealityteam.videoplayer.data.playback.TrackMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMemoryTest {
    private val tracks = listOf(MediaTrack("0:0", "日语 · TrueHD 7.1"), MediaTrack("0:1", "英语 · AAC 2.0"))

    @Test
    fun audioReSelectsTheSavedTrackWhenItStillExists() {
        assertEquals("0:1", TrackMemory.audioToApply("0:1", tracks))
        assertNull("nothing saved", TrackMemory.audioToApply(null, tracks))
        assertNull("saved track gone", TrackMemory.audioToApply("2:0", tracks))
        assertNull("tracks not known yet", TrackMemory.audioToApply("0:1", emptyList()))
    }

    @Test
    fun subtitleReSelectsOrTurnsOff() {
        assertEquals(TrackMemory.Subtitle.Select("0:1"), TrackMemory.subtitleToApply("0:1", tracks))
        assertEquals(TrackMemory.Subtitle.Off, TrackMemory.subtitleToApply(TrackMemory.SUBTITLES_OFF, tracks))
        assertNull(TrackMemory.subtitleToApply(null, tracks))
        assertNull("saved track gone → leave default", TrackMemory.subtitleToApply("5:0", tracks))
    }

    @Test
    fun offWaitsForTheTracksToArrive() {
        // An early empty list must not consume the "off" memory before the file's tracks show up.
        assertNull(TrackMemory.subtitleToApply(TrackMemory.SUBTITLES_OFF, emptyList()))
    }
}
