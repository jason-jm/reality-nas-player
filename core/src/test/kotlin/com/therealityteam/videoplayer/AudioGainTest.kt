package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.AudioGain
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioGainTest {

    @Test
    fun unityGainIsBitExact() {
        for (s in listOf(0, 1, -1, 12345, -12345, 32767, -32768)) {
            assertEquals(s.toShort(), AudioGain.apply(s.toShort(), 1.0f))
        }
    }

    @Test
    fun twoXDoublesQuietSamples() {
        assertEquals(2000.toShort(), AudioGain.apply(1000, 2.0f))
        assertEquals((-2000).toShort(), AudioGain.apply(-1000, 2.0f))
        assertEquals(1500.toShort(), AudioGain.apply(1000, 1.5f))
    }

    @Test
    fun loudSamplesHardClipInsteadOfWrapping() {
        // 30000 × 2 = 60000 would wrap to a negative value without clipping.
        assertEquals(32767.toShort(), AudioGain.apply(30000, 2.0f))
        assertEquals((-32768).toShort(), AudioGain.apply(-30000, 2.0f))
        assertEquals(32767.toShort(), AudioGain.apply(32767, 1.25f))
    }

    @Test
    fun theLadderStartsAtOffAndOnlyGoesUp() {
        assertEquals(1.0f, AudioGain.LEVELS.first(), 0f)
        assertEquals(AudioGain.LEVELS, AudioGain.LEVELS.sorted())
    }
}
