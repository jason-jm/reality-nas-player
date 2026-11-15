package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.TrackTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A stream's title shown only when it names something. */
class TrackTitleTest {

    @Test fun `a muxer's default is no name`() {
        listOf("Main Subtitle", "Subtitle", "Subtitles", "SUB", "Audio", "Track 2", "Track #3", "Stereo", "5.1",
            "Surround Audio", "und", "Default", "Main Audio", "Sound", "  ").forEach { assertNull(it, TrackTitle.named(it)) }
        assertNull(TrackTitle.named(null))
    }

    @Test fun `a title that tells tracks apart stays`() {
        listOf("Commentary", "Director's Commentary", "Forced", "Signs & Songs", "Full", "简体中文", "国语", "CHS",
            "English SDH", "Japanese (Dolby Atmos)").forEach { assertEquals(it, it, TrackTitle.named(it)) }
        assertEquals("简日双语", TrackTitle.named(" 简日双语 "))
    }

    @Test fun `a codec and its channels is no name - the stream line says it, or says it right`() {
        listOf("FLAC 2.0", "AAC 5.1", "DTS-HD MA 7.1", "AC3 2.0", "E-AC-3 5.1", "FLAC", "LPCM 2.0", "Opus 2ch", "5.1 HE-AAC", "2.0 AAC").forEach {
            org.junit.Assert.assertNull(it, com.therealityteam.videoplayer.data.playback.TrackTitle.named(it))
        }
        listOf("TrueHD Atmos 7.1", "DTS:X 7.1", "Commentary", "国语 AC3", "FLAC 2.0 Commentary").forEach {
            org.junit.Assert.assertEquals(it, it, com.therealityteam.videoplayer.data.playback.TrackTitle.named(it))
        }
    }
}
