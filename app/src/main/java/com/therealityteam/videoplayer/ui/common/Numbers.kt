package com.therealityteam.videoplayer.ui.common

import java.text.NumberFormat
import java.util.Locale

/*
 * Numbers as the reader writes them. Built by hand they were English: "50%" where German and French write "50 %"
 * and Turkish "%50", "1.25×" and "44.1 kHz" where German, Russian or Polish write a comma. Locale.getDefault()
 * follows the app's own language (the per-app locale) as well as the headset's.
 */

/** [percent] as a percentage: 50 → "50%", "50 %" (de, fr, sv…), "%50" (tr). */
fun percentText(percent: Int, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getPercentInstance(locale).format(percent / 100.0)

/** [value] with at most [maxFraction] decimals and no trailing zeros: 1.25 → "1.25" / "1,25"; 2.0 → "2". */
fun decimalText(value: Double, maxFraction: Int = 2, minFraction: Int = 0, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = minFraction
        maximumFractionDigits = maxFraction
        isGroupingUsed = false
    }.format(value)

/** A playback speed: "1.25×", "1,25×", "2×". */
fun speedText(speed: Float, locale: Locale = Locale.getDefault()): String = decimalText(speed.toDouble(), 2, 0, locale) + "×"

/** A sample rate in kHz, as players name it: 48000 → "48 kHz", 44100 → "44.1 kHz" / "44,1 kHz". */
fun kiloHertzText(hz: Int, locale: Locale = Locale.getDefault()): String = decimalText(hz / 1000.0, 1, 0, locale) + " kHz"
