package com.therealityteam.videoplayer.ui.player

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The player's continuous adjustments — subtitle size, position, outline and delay, and the A/V
 * offset: their ranges, their steps, and how a value reads on its card. Pure, so the snapping and
 * the labels are unit-tested without a player.
 *
 * Fractions step in whole percent and offsets in whole tenths of a second, so a value lands exactly
 * on its grid: ten 0.1f subtractions from 1.0f leave 0.79999995, which a card showed as "79%".
 */
internal object PlayerAdjust {
    /** Subtitle size, in percent of the 24sp base: 60–200%, 10% a step. */
    const val SCALE_MIN_PCT = 60
    const val SCALE_MAX_PCT = 200
    const val SCALE_STEP_PCT = 10
    const val SCALE_DEFAULT_PCT = 100

    /** Subtitle position, in percent of the panel's height raised off the bottom: 0–35%, 5% a step. */
    const val RAISE_MIN_PCT = 0
    const val RAISE_MAX_PCT = 35
    const val RAISE_STEP_PCT = 5

    /** Outline thickness, the stroke the 描边 style draws: 1–4, 2 by default. */
    const val OUTLINE_MIN = 1
    const val OUTLINE_MAX = 4
    const val OUTLINE_DEFAULT = 2

    /** Both offsets move a tenth of a second a step. */
    const val OFFSET_STEP_MS = 100L
    const val SUBTITLE_DELAY_LIMIT_MS = 30_000L
    const val AUDIO_DELAY_LIMIT_MS = 2_000L

    /** [fraction] as the whole percent a card shows. */
    fun percent(fraction: Float): Int = (fraction * 100).roundToInt()

    /**
     * [fraction] moved [steps] steps of [stepPct] percent within [minPct]..[maxPct], snapped to the
     * step grid first so a value carried over from an older build lands back on it.
     */
    fun stepFraction(fraction: Float, steps: Int, stepPct: Int, minPct: Int, maxPct: Int): Float {
        val onGrid = (percent(fraction).toDouble() / stepPct).roundToInt() * stepPct
        return (onGrid + steps * stepPct).coerceIn(minPct, maxPct) / 100f
    }

    /** A stored size or position put back on its grid, without moving it. */
    fun snapFraction(fraction: Float, stepPct: Int, minPct: Int, maxPct: Int): Float =
        stepFraction(fraction, 0, stepPct, minPct, maxPct)

    /** [ms] moved [steps] tenths of a second within ±[limitMs]. */
    fun stepOffset(ms: Long, steps: Int, limitMs: Long): Long {
        val onGrid = (ms.toDouble() / OFFSET_STEP_MS).roundToLong() * OFFSET_STEP_MS
        return (onGrid + steps * OFFSET_STEP_MS).coerceIn(-limitMs, limitMs)
    }

    /** An offset as its card shows it: "0.0s", "+0.4s", "−12.5s" — always a tenth, so 10.0 and 10.5 differ. */
    fun signedSeconds(ms: Long, locale: Locale = Locale.getDefault()): String =
        (if (ms > 0) "+" else if (ms < 0) "−" else "") +
            com.therealityteam.videoplayer.ui.common.decimalText(abs(ms) / 1000.0, 1, 1, locale) + "s"
}
