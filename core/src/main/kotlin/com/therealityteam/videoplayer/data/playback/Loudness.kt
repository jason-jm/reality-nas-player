package com.therealityteam.videoplayer.data.playback

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * 音量增强 and 夜间模式 on interleaved 16-bit PCM, in one pass:
 *  - the boost ([gain]) scales every sample;
 *  - 夜间模式 ([night]) evens the loudness out — a compressor that brings explosions and music down
 *    and, with its make-up gain, quiet dialogue up — and on a 5.1 track lifts the centre channel,
 *    where the dialogue is, and lowers the LFE;
 *  - whenever either is on, peaks are rounded off just below full scale (a soft limiter) instead of
 *    clipped flat, which is what the bare boost used to do to every loud scene.
 *
 * Off — unity gain, no night mode — leaves the samples untouched. Stateful (the compressor's
 * envelope): one instance per stream, [reset] after a seek.
 */
class Loudness(sampleRate: Int, private val channels: Int) {

    @Volatile var gain: Float = 1f
    @Volatile var night: Boolean = false

    private var envelope = 0f
    private val attack = coefficient(ATTACK_S, sampleRate)
    private val release = coefficient(RELEASE_S, sampleRate)
    private val frame = FloatArray(channels.coerceAtLeast(1))

    fun reset() { envelope = 0f }

    /** Processes the first [frames] interleaved frames of [samples] in place. */
    fun process(samples: ShortArray, frames: Int) {
        val g = gain
        val n = night
        if (g == 1f && !n) return
        val ch = channels.coerceAtLeast(1)
        val surround = n && ch >= 6
        for (f in 0 until frames) {
            val base = f * ch
            var peak = 0f
            for (c in 0 until ch) {
                var x = samples[base + c] / 32768f * g
                if (surround) x *= when (c) { CENTRE -> CENTRE_LIFT; LFE -> LFE_CUT; else -> 1f }
                frame[c] = x
                val a = abs(x)
                if (a > peak) peak = a
            }
            var level = 1f
            if (n) {
                envelope += (peak - envelope) * (if (peak > envelope) attack else release)
                level = gainFor(envelope)
            }
            for (c in 0 until ch) samples[base + c] = toShort(softLimit(frame[c] * level))
        }
    }

    companion object {
        /** Where the compressor starts to act, dBFS. */
        const val THRESHOLD_DB = -30f
        /** Above it, 3 dB in become 1 dB out. */
        const val RATIO = 3f
        /** The bend into compression is spread over this many dB around the threshold. */
        const val KNEE_DB = 8f
        /** Everything is lifted back by this much, so what was quiet comes up. */
        const val MAKEUP_DB = 10f
        const val ATTACK_S = 0.008f
        const val RELEASE_S = 0.25f
        /** About −3 dBFS: peaks above it are eased toward full scale, never past it. */
        const val LIMIT_KNEE = 0.7f

        /** 5.1 order: FL FR FC LFE BL BR. */
        private const val CENTRE = 2
        private const val LFE = 3
        private const val CENTRE_LIFT = 1.41f // +3 dB
        private const val LFE_CUT = 0.5f      // −6 dB

        private fun coefficient(seconds: Float, sampleRate: Int): Float =
            (1.0 - exp(-1.0 / (seconds * sampleRate.coerceAtLeast(1)))).toFloat()

        /** The compressor's gain (linear, make-up included) for a detected level (linear peak). */
        fun gainFor(envelope: Float): Float {
            val levelDb = 20f * log10(envelope.coerceAtLeast(1e-6f))
            val over = levelDb - THRESHOLD_DB
            val slope = 1f - 1f / RATIO
            val reductionDb = when {
                over <= -KNEE_DB / 2 -> 0f
                over >= KNEE_DB / 2 -> over * slope
                else -> slope * (over + KNEE_DB / 2).pow(2) / (2 * KNEE_DB)
            }
            return 10f.pow((MAKEUP_DB - reductionDb) / 20f)
        }

        /** Untouched up to [LIMIT_KNEE]; above it, eased toward full scale and never past it. */
        fun softLimit(x: Float): Float {
            val a = abs(x)
            if (a <= LIMIT_KNEE) return x
            val room = 1f - LIMIT_KNEE
            val y = LIMIT_KNEE + room * tanh((a - LIMIT_KNEE) / room)
            return if (x < 0f) -y else y
        }

        private fun toShort(x: Float): Short = (x * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
    }
}
