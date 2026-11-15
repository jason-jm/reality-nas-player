package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.ExoPlaybackManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The names the track lists and the media info panel give codecs, from their mime types. */
class CodecNameTest {

    @Test fun `vendor and x- prefixes are not part of the name`() {
        assertEquals("DTS", ExoPlaybackManager.codecName("audio/vnd.dts"))
        assertEquals("DTS-HD", ExoPlaybackManager.codecName("audio/vnd.dts.hd"))
        assertEquals("DTS-HD", ExoPlaybackManager.codecName("audio/vnd.dts.hd;profile=lbr"))
        assertEquals("DTS:X", ExoPlaybackManager.codecName("audio/vnd.dts.uhd;profile=p2"))
        assertEquals("Dolby TrueHD", ExoPlaybackManager.codecName("audio/true-hd"))
        assertEquals("SRT", ExoPlaybackManager.codecName("application/x-subrip"))
    }

    @Test fun `known and unknown codecs`() {
        assertEquals("AAC", ExoPlaybackManager.codecName("audio/mp4a-latm"))
        assertEquals("Dolby E-AC-3", ExoPlaybackManager.codecName("audio/eac3"))
        assertEquals("ALAC", ExoPlaybackManager.codecName("audio/alac"))
        assertNull(ExoPlaybackManager.codecName(null))
    }

    @Test fun `pictures are named as the 解码 line and mpv name them`() {
        assertEquals("H.264", ExoPlaybackManager.codecName("video/avc"))
        assertEquals("HEVC", ExoPlaybackManager.codecName("video/hevc"))
        assertEquals("VP9", ExoPlaybackManager.codecName("video/x-vnd.on2.vp9"))
        assertEquals("AV1", ExoPlaybackManager.codecName("video/av01"))
        assertEquals("Dolby Vision", ExoPlaybackManager.codecName("video/dolby-vision"))
    }
}
