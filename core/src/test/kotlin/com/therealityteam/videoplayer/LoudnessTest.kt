package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.Loudness
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

/** 音量增强 + 夜间模式: quiet lines come up, loud scenes come down, and nothing is clipped flat. */
class LoudnessTest {

    private val rate = 48_000

    private fun sine(amplitude: Float, seconds: Float = 1f, channels: Int = 2): ShortArray {
        val frames = (rate * seconds).toInt()
        return ShortArray(frames * channels) { i ->
            val f = i / channels
            (amplitude * 32767f * sin(2 * PI * 440 * f / rate)).roundToInt().toShort()
        }
    }

    /** The peak of the second half, once the compressor has settled, in dBFS. */
    private fun settledPeakDb(samples: ShortArray): Float {
        val peak = samples.copyOfRange(samples.size / 2, samples.size).maxOf { abs(it.toInt()) }
        return 20f * log10(peak / 32767f)
    }

    private fun run(input: ShortArray, gain: Float = 1f, night: Boolean = false, channels: Int = 2): ShortArray {
        val out = input.copyOf()
        Loudness(rate, channels).apply { this.gain = gain; this.night = night }.process(out, out.size / channels)
        return out
    }

    @Test fun `off leaves every sample as it was`() {
        val input = sine(0.8f)
        assertArrayEquals(input, run(input))
    }

    @Test fun `at night a quiet line comes up by the make-up gain`() {
        val quiet = run(sine(0.01f), night = true) // −40 dBFS: below the knee, so only lifted
        assertEquals(-40f + Loudness.MAKEUP_DB, settledPeakDb(quiet), 0.5f)
    }

    @Test fun `at night a loud scene comes down, and the gap between loud and quiet narrows`() {
        val loudIn = -6f
        val loud = settledPeakDb(run(sine(0.5f), night = true))
        val quiet = settledPeakDb(run(sine(0.01f), night = true))
        assertTrue("loud came down: $loud", loud < loudIn - 3f)
        // 34 dB apart going in; well under 20 coming out.
        assertTrue("range ${loud - quiet}", loud - quiet < 20f)
    }

    @Test fun `a doubled loud scene is rounded off below full scale, never clipped flat`() {
        val out = run(sine(1f), gain = 2f)
        assertTrue(out.none { it == Short.MAX_VALUE || it == Short.MIN_VALUE })
        // Quiet material under the limiter's knee is simply doubled.
        val soft = run(sine(0.2f), gain = 2f)
        assertEquals(20f * log10(0.4f), settledPeakDb(soft), 0.2f)
    }

    @Test fun `on a 5_1 track the night mode lifts the centre over the rest`() {
        // The same quiet tone on every channel: the centre comes out 3 dB above the fronts.
        val input = sine(0.01f, channels = 6)
        val out = run(input, night = true, channels = 6)
        val front = out.filterIndexed { i, _ -> i % 6 == 0 }.drop(rate / 2).maxOf { abs(it.toInt()) }
        val centre = out.filterIndexed { i, _ -> i % 6 == 2 }.drop(rate / 2).maxOf { abs(it.toInt()) }
        assertEquals(3f, 20f * log10(centre.toFloat() / front), 0.5f)
    }
}
