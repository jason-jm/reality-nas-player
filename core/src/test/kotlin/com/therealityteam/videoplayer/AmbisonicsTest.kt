package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.AmbisonicAudio
import com.therealityteam.videoplayer.data.playback.AmbisonicDecoder
import com.therealityteam.videoplayer.data.playback.SoundField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class AmbisonicsTest {

    private val rate = 48_000
    private val foa = AmbisonicAudio(1, 4)

    /**
     * A tone (or [noise]) from azimuth [az] (degrees counter-clockwise from ahead, as ambisonics counts)
     * and elevation [el], first-order ACN/SN3D: W, Y, Z, X.
     */
    private fun source(az: Double, el: Double = 0.0, frames: Int = rate / 2, hz: Double = 500.0, noise: Boolean = false): ShortArray {
        val a = Math.toRadians(az)
        val e = Math.toRadians(el)
        val random = java.util.Random(7)
        val out = ShortArray(frames * 4)
        for (f in 0 until frames) {
            val s = if (noise) (random.nextDouble() * 2 - 1) * 0.3 else 0.3 * sin(2 * PI * hz * f / rate)
            val gains = doubleArrayOf(1.0, sin(a) * cos(e), sin(e), cos(a) * cos(e))
            for (c in 0..3) out[4 * f + c] = (s * gains[c] * 32767).toInt().toShort()
        }
        return out
    }

    /** The two ears' loudness (RMS) after decoding [input] turned by [rotation], the first tenth skipped. */
    private fun ears(input: ShortArray, rotation: FloatArray = SoundField.IDENTITY, audio: AmbisonicAudio = foa): Pair<Double, Double> {
        val frames = input.size / audio.channels
        val out = ShortArray(frames * 2)
        val d = AmbisonicDecoder(rate)
        d.snapTo(rotation)
        d.decode(input, frames, audio, rotation, out)
        var l = 0.0
        var r = 0.0
        for (f in frames / 10 until frames) { l += out[2 * f] * out[2 * f].toDouble(); r += out[2 * f + 1] * out[2 * f + 1].toDouble() }
        return sqrt(l) to sqrt(r)
    }

    private fun db(a: Double, b: Double) = 20 * log10(a / b)

    @Test
    fun aSoundToTheLeftIsLouderInTheLeftEar() {
        val (l, r) = ears(source(90.0, noise = true))
        assertTrue("left ${db(l, r)} dB", db(l, r) > 6)
        val (l2, r2) = ears(source(-90.0, noise = true))
        assertTrue("right ${db(r2, l2)} dB", db(r2, l2) > 6)
    }

    @Test
    fun aSoundAheadOrBehindOrAboveIsInTheMiddle() {
        for (az in listOf(0.0, 180.0)) {
            val (l, r) = ears(source(az, noise = true))
            assertEquals("az $az", 0.0, db(l, r), 0.5)
        }
        val (l, r) = ears(source(0.0, el = 90.0, noise = true))
        assertEquals(0.0, db(l, r), 0.5)
    }

    @Test
    fun aSoundAheadKeepsItsLevel() {
        // Low frequencies pass the head unchanged: a tone ahead comes out at about its own level.
        val (l, _) = ears(source(0.0, hz = 200.0))
        val inRms = 0.3 / sqrt(2.0) * 32767 * sqrt(rate / 2 * 0.9)
        assertEquals(0.0, db(l, inRms), 3.0)
    }

    @Test
    fun theNearEarHearsItFirst() {
        // A click from the left: the left ear's peak comes first. (A first-order field is broad: the far ear's
        // loudest path is round from ahead and behind, a quarter of the head later, not the half round from the side.)
        val frames = 2_000
        val input = ShortArray(frames * 4)
        input[4 * 100] = 20_000; input[4 * 100 + 1] = 20_000 // W and Y: hard left
        val out = ShortArray(frames * 2)
        AmbisonicDecoder(rate).decode(input, frames, foa, SoundField.IDENTITY, out)
        val peak = { ear: Int -> (0 until frames).maxBy { kotlin.math.abs(out[2 * it + ear].toInt()) } }
        val lag = (peak(1) - peak(0)) / rate.toDouble() * 1000
        assertTrue("lag $lag ms", lag in 0.2..0.8)
    }

    @Test
    fun turningTheHeadTurnsTheField() {
        // Looking left a quarter turn (yaw −90°), the sound on the left is ahead: the ears agree.
        val ahead = ears(source(90.0, noise = true), SoundField.look(-90f, 0f))
        assertEquals(0.0, db(ahead.first, ahead.second), 0.5)
        // Turned all the way round, the left sound is on the right.
        val behind = ears(source(90.0, noise = true), SoundField.look(180f, 0f))
        assertTrue(db(behind.second, behind.first) > 6)
        // Looking right, the sound ahead is on the left.
        val right = ears(source(0.0, noise = true), SoundField.look(90f, 0f))
        assertTrue(db(right.first, right.second) > 6)
    }

    @Test
    fun theWindowsLookMapsTheFilmToTheListener() {
        // Looking up 90°: what is above is ahead (−z), and what was ahead is below (−y).
        val m = SoundField.look(0f, 90f)
        fun apply(x: Float, y: Float, z: Float) = floatArrayOf(m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z)
        val up = apply(0f, 1f, 0f)
        assertEquals(-1f, up[2], 1e-5f)
        val front = apply(0f, 0f, -1f)
        assertEquals(-1f, front[1], 1e-5f)
    }

    @Test
    fun headLockedStereoStaysPut() {
        val audio = AmbisonicAudio(1, 6, headLocked = true)
        val frames = rate / 4
        val input = ShortArray(frames * 6)
        for (f in 0 until frames) input[6 * f + 4] = (10_000 * sin(2 * PI * 300 * f / rate)).toInt().toShort() // left only
        val (l, r) = ears(input, SoundField.look(180f, 0f), audio)
        assertTrue(l > 0)
        assertEquals(0.0, r, 0.0)
    }

    @Test
    fun aQuadTrackIsMixedDown() {
        val frames = 4
        val input = ShortArray(frames * 4) { if (it % 4 == 0) 10_000 else 0 } // front left only
        val out = ShortArray(frames * 2)
        AmbisonicDecoder.downmixQuad(input, frames, out)
        assertTrue(out[0] > 5_000)
        assertEquals(0, out[1].toInt())
    }

    @Test
    fun channelCountsThatAreFields() {
        assertEquals(1, AmbisonicAudio.ofChannels(4, "opus")!!.order)
        assertTrue(AmbisonicAudio.ofChannels(6, "opus")!!.headLocked)
        assertEquals(2, AmbisonicAudio.ofChannels(9, "opus")!!.order)
        assertEquals(3, AmbisonicAudio.ofChannels(18, "opus")!!.order)
        assertNull(AmbisonicAudio.ofChannels(5, "opus"))
        assertNull(AmbisonicAudio.ofChannels(2, "opus"))
    }
}
