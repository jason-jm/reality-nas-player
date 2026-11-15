package com.therealityteam.videoplayer.data.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A film's sound recorded as a sphere round the camera (ambisonics, ACN order, SN3D), as 360° cameras
 * and YouTube's spatial audio carry it: a file marks it with Google's `SA3D` box (MP4), or as Opus
 * channel mapping family 2 (WebM, MP4's `dOps`). Played as it is, its four channels would go to four
 * corner speakers — W, Y, Z, X — and sound nothing like the scene; [AmbisonicDecoder] turns it into
 * the two ears' sound for where the listener faces.
 */
data class AmbisonicAudio(
    /** 1 for first order (4 components: W, Y, Z, X); higher orders are heard through their first. */
    val order: Int,
    /** The track's channels: (order + 1)² components, and 2 more when [headLocked]. */
    val channels: Int,
    /** The last two channels are a stereo pair that stays put as the head turns (narration, music). */
    val headLocked: Boolean = false,
    /** Component k is in channel channelMap[k] (SA3D); null for the components in order. */
    val channelMap: List<Int>? = null,
    /** Which marking said so: "SA3D", "opus". */
    val from: String = "",
) {
    val components: Int get() = (order + 1) * (order + 1)

    companion object {
        /**
         * The ambisonics [channels] channels can carry (Opus family 2: (n + 1)² or (n + 1)² + 2),
         * or null when the count is not one of those.
         */
        fun ofChannels(channels: Int, from: String): AmbisonicAudio? {
            for (n in 1..14) {
                val c = (n + 1) * (n + 1)
                if (c == channels) return AmbisonicAudio(n, channels, false, null, from)
                if (c + 2 == channels) return AmbisonicAudio(n, channels, true, null, from)
                if (c > channels) break
            }
            return null
        }
    }
}

/**
 * Which way the film's sound field lies from the listener: a 3×3 rotation, row-major, taking a
 * direction in the film's frame to the listener's (both x right, y up, z back — the viewer's axes).
 * Set from the head and the sphere every frame in 沉浸空间, from the window's view when a panorama is
 * looked round in the window; read by the audio thread once per buffer.
 */
object SoundField {
    @Volatile var rotation: FloatArray = IDENTITY

    val IDENTITY: FloatArray get() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /**
     * The field heard through a window looking [yaw] degrees to the right and [pitch] up into the film
     * ([PanoViewport.Look]): what the window looks at is ahead.
     */
    fun look(yaw: Float, pitch: Float): FloatArray {
        val y = Math.toRadians(yaw.toDouble())
        val p = Math.toRadians(pitch.toDouble())
        val fx = sin(y) * cos(p); val fy = sin(p); val fz = -cos(y) * cos(p)
        val rx = cos(y); val rz = sin(y)
        val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy
        // Rows: the listener's right, up and back, in the film's frame.
        return floatArrayOf(
            rx.toFloat(), 0f, rz.toFloat(),
            ux.toFloat(), uy.toFloat(), uz.toFloat(),
            -fx.toFloat(), -fy.toFloat(), -fz.toFloat(),
        )
    }
}

/**
 * Ambisonics to the two ears, for where the listener faces. The field is turned ([SoundField]), decoded
 * to a ring of eight virtual speakers round the head (in two bands, after Gerzon: the basic decode low,
 * max-rE high — each source mostly in the speakers nearest it), and each speaker reaches each ear as it would round a head — later and duller
 * at the far ear (a spherical head: Woodworth's delay, Brown and Duda's one-pole shadow). Turning only
 * changes the decode; the ears' filters never change, so nothing clicks as the head moves. Two
 * head-locked channels, when the track has them, go straight to the ears.
 *
 * 16-bit interleaved in, 16-bit stereo out; one instance per stream (its filters keep their state).
 */
class AmbisonicDecoder(private val sampleRate: Int) {

    private val speakers = SPEAKERS
    /** Per speaker, per ear (left then right): delay in samples and the shadow filter's coefficients. */
    private val delay = FloatArray(2 * speakers)
    private val b0 = FloatArray(2 * speakers)
    private val b1 = FloatArray(2 * speakers)
    private val a1 = FloatArray(2 * speakers)
    private val x1 = FloatArray(2 * speakers)
    private val y1 = FloatArray(2 * speakers)
    /** Each speaker's recent samples, for the delays (a ring of [RING]). */
    private val ring = Array(speakers) { FloatArray(RING) }
    private var at = 0
    private val cosAz = FloatArray(speakers)
    private val sinAz = FloatArray(speakers)
    private var turn = SoundField.IDENTITY
    private val r = FloatArray(9)
    private val feed = FloatArray(speakers)
    /** The field's horizontal components below the crossover (one pole). */
    private var xLow = 0f
    private var yLow = 0f
    private val lowpass = (1 - kotlin.math.exp(-2 * PI * CROSSOVER_HZ / sampleRate)).toFloat()

    init {
        val k = 2.0 * sampleRate
        val beta = 2.0 * SPEED_OF_SOUND / HEAD_RADIUS
        for (s in 0 until speakers) {
            val az = 2 * PI * s / speakers // counter-clockwise from ahead: the second speaker is ahead-left
            cosAz[s] = cos(az).toFloat()
            sinAz[s] = sin(az).toFloat()
            for (ear in 0..1) {
                val earAz = if (ear == 0) PI / 2 else -PI / 2
                var theta = abs(az - earAz) % (2 * PI)
                if (theta > PI) theta = 2 * PI - theta
                val i = 2 * s + ear
                // Woodworth: the near side's path shortened by the cosine, the far side's lengthened round the head.
                val seconds = if (theta < PI / 2) HEAD_RADIUS / SPEED_OF_SOUND * (1 - cos(theta))
                else HEAD_RADIUS / SPEED_OF_SOUND * (1 + theta - PI / 2)
                delay[i] = (seconds * sampleRate).toFloat()
                // Brown–Duda head shadow: (α s + β) / (s + β), by the bilinear transform.
                val alpha = 1 + ALPHA_MIN / 2 + (1 - ALPHA_MIN / 2) * cos(theta / THETA_MIN * PI)
                val norm = k + beta
                b0[i] = ((alpha * k + beta) / norm).toFloat()
                b1[i] = ((beta - alpha * k) / norm).toFloat()
                a1[i] = ((beta - k) / norm).toFloat()
            }
        }
    }

    /** The field turned to [rotation] at once (a new stream, a seek), not eased there from the last. */
    fun snapTo(rotation: FloatArray) {
        turn = rotation.copyOf()
    }

    /** A seek: no sound from before it rings on. */
    fun reset() {
        x1.fill(0f); y1.fill(0f)
        xLow = 0f; yLow = 0f
        ring.forEach { it.fill(0f) }
    }

    /**
     * [frames] frames of [audio]'s [input] (interleaved, [AmbisonicAudio.channels] each) decoded into
     * [output] (interleaved stereo), the field turned to [rotation] — eased from the last buffer's
     * across this one.
     */
    fun decode(input: ShortArray, frames: Int, audio: AmbisonicAudio, rotation: FloatArray, output: ShortArray) {
        val ch = audio.channels
        val map = audio.channelMap
        val cw = map?.getOrNull(0) ?: 0
        val cy = map?.getOrNull(1) ?: 1
        val cz = map?.getOrNull(2) ?: 2
        val cx = map?.getOrNull(3) ?: 3
        val hl = if (audio.headLocked && ch >= audio.components + 2) audio.components else -1
        val from = turn
        val to = rotation
        for (f in 0 until frames) {
            val base = f * ch
            val w = input[base + cw] / 32768f
            val y = input[base + cy] / 32768f
            val z = input[base + cz] / 32768f
            val x = input[base + cx] / 32768f
            // The rotation, eased across the buffer.
            val t = if (frames > 1) f.toFloat() / (frames - 1) else 1f
            for (i in 0..8) r[i] = from[i] + (to[i] - from[i]) * t
            // The first-order components turn as a direction does: (X ahead, Y left, Z up) is (−Y, Z, −X) in the viewer's axes.
            val vx = -y; val vy = z; val vz = -x
            val px = r[0] * vx + r[1] * vy + r[2] * vz
            val pz = r[6] * vx + r[7] * vy + r[8] * vz
            val xr = -pz
            val yr = -px
            // Two bands (Gerzon): below the crossover the basic decode, whose sum points where the sound is
            // (the ears time it there); above, max-rE, whose energy does. The low band is the difference.
            xLow += lowpass * (xr - xLow)
            yLow += lowpass * (yr - yLow)
            val xd = RE_GAIN * xr + (BASIC_GAIN - RE_GAIN) * xLow
            val yd = RE_GAIN * yr + (BASIC_GAIN - RE_GAIN) * yLow
            // To the ring (the horizontal part; height reaches both ears alike, through W).
            for (s in 0 until speakers) {
                feed[s] = (w + xd * cosAz[s] + yd * sinAz[s]) / speakers
            }
            var left = 0f
            var right = 0f
            for (s in 0 until speakers) {
                val buf = ring[s]
                buf[at] = feed[s]
                for (ear in 0..1) {
                    val i = 2 * s + ear
                    // A fractional delay, linearly between the two samples either side.
                    val d = delay[i]
                    val whole = d.toInt()
                    val frac = d - whole
                    val a = buf[(at - whole + RING) % RING]
                    val b = buf[(at - whole - 1 + RING) % RING]
                    val delayed = a + (b - a) * frac
                    val out = b0[i] * delayed + b1[i] * x1[i] - a1[i] * y1[i]
                    x1[i] = delayed
                    y1[i] = out
                    if (ear == 0) left += out else right += out
                }
            }
            at = (at + 1) % RING
            left *= OUTPUT_GAIN
            right *= OUTPUT_GAIN
            if (hl >= 0) {
                left += input[base + hl] / 32768f
                right += input[base + hl + 1] / 32768f
            }
            output[2 * f] = pcm(left)
            output[2 * f + 1] = pcm(right)
        }
        turn = to.copyOf()
    }

    companion object {
        const val SPEAKERS = 8
        /** The basic decode's first-order weight on a 2-D ring. */
        private const val BASIC_GAIN = 2f
        /** max-rE's: cos 45° of the basic's. */
        private val RE_GAIN = (2 * cos(PI / 4)).toFloat()
        /** Where the ears stop timing a sound and start weighing it. */
        private const val CROSSOVER_HZ = 700.0
        private const val HEAD_RADIUS = 0.0875
        private const val SPEED_OF_SOUND = 343.0
        private const val ALPHA_MIN = 0.1
        /** Where the far side is darkest: 150° from the ear. */
        private val THETA_MIN = Math.toRadians(150.0)
        /** The near ear's treble lift for a sound at its side reaches +6 dB: room left so it does not clip. */
        private const val OUTPUT_GAIN = 0.8f
        /** Longer than the longest delay (0.66 ms; 64 samples is 0.67 ms at 96 kHz). */
        private const val RING = 128

        private fun pcm(v: Float): Short = (v * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()

        /** Stereo from four channels that are not a sound field (quad): the backs a little quieter. */
        fun downmixQuad(input: ShortArray, frames: Int, output: ShortArray) {
            val back = (1 / sqrt(2.0)).toFloat()
            val g = 1f / (1f + back)
            for (f in 0 until frames) {
                val b = 4 * f
                output[2 * f] = pcm((input[b] + back * input[b + 2]) * g / 32768f)
                output[2 * f + 1] = pcm((input[b + 1] + back * input[b + 3]) * g / 32768f)
            }
        }
    }
}
