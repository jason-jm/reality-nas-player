package com.therealityteam.videoplayer.ui.theme

import androidx.compose.ui.graphics.Color
import com.therealityteam.videoplayer.data.model.MediaItem
import kotlin.math.abs

/**
 * Bridges the scraped dominant color into the Vibrant-styled UI: poster cards, selection
 * glow, and (in Full Space) Stage ambient light all derive from the artwork's color so the
 * interface "breathes" with the content. Falls back to a stable hue from the title when a
 * poster hasn't been scraped yet.
 */
object ThemeBridge {

    /** Ambient color for an item — extracted dominant color, or a stable title-derived hue. */
    fun ambient(item: MediaItem): Color = ambient(item.dominantColor, item.title)

    /** Ambient color from a dominant color (if scraped) or a stable title-derived hue. */
    fun ambient(dominantColor: Int?, title: String): Color =
        dominantColor?.let { Color(it) } ?: hueFromString(title)

    /** Deterministic pleasant color from any string (used for placeholder poster cards). */
    fun hueFromString(s: String): Color {
        val h = abs(s.hashCode())
        val hue = (h % 360).toFloat()
        val sat = 0.45f + (h / 360 % 30) / 100f // 0.45..0.75
        return Color.hsv(hue, sat.coerceIn(0.4f, 0.75f), 0.62f)
    }
}
