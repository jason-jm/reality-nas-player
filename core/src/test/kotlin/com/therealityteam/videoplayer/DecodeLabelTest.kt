package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.DecodeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeLabelTest {
    @Test
    fun vendorCodecsAreHardwareAndroidAndFfmpegAreSoftware() {
        assertFalse(DecodeLabel.isSoftware("c2.qti.hevc.decoder"))
        assertFalse(DecodeLabel.isSoftware("OMX.qcom.video.decoder.avc"))
        assertTrue(DecodeLabel.isSoftware("c2.android.aac.decoder"))
        assertTrue(DecodeLabel.isSoftware("OMX.google.h264.decoder"))
        assertTrue(DecodeLabel.isSoftware("ffmpeg"))
    }

    @Test
    fun decoderLineNamesBothSidesOrNothing() {
        assertEquals("硬解 HEVC · 音频 AC-3 (FFmpeg)", DecodeLabel.decoders("c2.qti.hevc.decoder", "ffmpegLavc60.3.100-ac3"))
        assertEquals("软解 HEVC", DecodeLabel.decoders("c2.android.hevc.decoder", null))
        assertEquals("音频 AAC", DecodeLabel.decoders(null, "c2.android.aac.decoder"))
        assertNull(DecodeLabel.decoders(null, null))
    }

    @Test
    fun decodersAreNamedByWhatTheyDecode() {
        // What the emulator showed for 1917: the component names, one of them wrapped over two lines.
        assertEquals("硬解 H.264 · 音频 TrueHD (FFmpeg)", DecodeLabel.decoders("c2.goldfish.h264.decoder", "ffmpegLavc60.3.100-truehd"))
        assertEquals("硬解 H.264 · 音频 FFmpeg", DecodeLabel.decoders("OMX.qcom.video.decoder.avc", "ffmpeg"))
        assertEquals("Dolby Vision", DecodeLabel.codecOf("c2.qti.dolby-vision.decoder"))
        assertEquals("DTS", DecodeLabel.codecOf("ffmpegLavc60.3.100-dca"))
        assertEquals("PCM", DecodeLabel.codecOf("ffmpegLavc60.3.100-pcm_s24le"))
        // A name that says no codec is shown as it is.
        assertEquals("硬解 c2.vendor.mystery.decoder", DecodeLabel.decoders("c2.vendor.mystery.decoder", null))
    }

    @Test
    fun frameTallyWaitsForTheFirstFrame() {
        assertNull(DecodeLabel.frames(0, 0))
        assertEquals("丢帧 3 / 已渲染 1420", DecodeLabel.frames(1420, 3))
        assertEquals("丢帧 0 / 已渲染 1", DecodeLabel.frames(1, 0))
    }
}
