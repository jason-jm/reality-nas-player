package com.therealityteam.videoplayer.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.vibrant.Vibrant
import com.pico.spatial.ui.foundation.vibrant.withVibrant

/**
 * OS-level frosted-glass surface built from the PICO design system. With Vibrant enabled
 * (manifest `com.pico.spatial.ui.isVibrant=true`), the design [PicoTheme.colorScheme] fill
 * tokens are vibrant-encoded, so painting them renders true PICO glass that blends with the
 * room — the same material the system windows/menus use.
 *
 * Pass [tint] (e.g. a poster's dominant color) to tint the glass at a vibrant level, so the
 * surface "breathes" with the content per the Aura thesis.
 */
@Composable
fun Modifier.glassSurface(
    corner: Dp = 24.dp,
    tint: Color? = null,
    level: Vibrant = Vibrant.Semidark,
): Modifier {
    val bg = tint?.withVibrant(level) ?: PicoTheme.colorScheme.fillSecondary
    return this.clip(RoundedCornerShape(corner)).background(bg)
}
