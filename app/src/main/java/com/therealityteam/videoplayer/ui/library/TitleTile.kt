package com.therealityteam.videoplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.vibrant.Vibrant
import com.pico.spatial.ui.foundation.vibrant.withVibrant
import kotlin.math.abs

/**
 * The card for a title that has no artwork.
 *
 * It used to be one big letter on a flat tinted square, on the reasoning that the name was already
 * written on the line underneath and a tile repeating it would read as a mistake. On a wall with a
 * lot of unmatched files that came out as rows of anonymous `S`s and `0`s — you could see that
 * something was there, but not what. So the tile says the name, and is made to look like a cover
 * rather than a gap where one should be.
 *
 * The colours come from the name itself, so a title keeps the same cover every time it is drawn and
 * two titles side by side rarely look alike. Three stops drifting round the wheel and downward in
 * brightness give it depth without any of them being a colour anybody chose: the point is that the
 * wall looks composed, not that any one tile is a particular shade.
 */
@Composable
internal fun TitleTile(title: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val stops = remember(title) { titleGradient(title) }
    Box(
        modifier = modifier
            .background(Brush.linearGradient(stops), shape)
            // A light off the top-left corner, the way a physical cover catches one. Very low and
            // very wide: the moment it is a visible shape it stops reading as light and starts
            // reading as a smudge.
            .background(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset.Zero,
                    radius = 640f,
                ),
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = titleStyle(titleTier(title)),
            // The tile paints its own ground rather than sitting on the theme's, the same as a
            // poster does, so the text is set against THAT rather than through a theme role — and,
            // like any text over a picture, held out of the glass's colour adaptation.
            color = Color.White.copy(alpha = 0.94f).withVibrant(Vibrant.None),
            textAlign = TextAlign.Center,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/**
 * Three colours for [title], stable for as long as the title is.
 *
 * Kept in the same band of saturation and brightness whatever the hue, so that a wall of these is a
 * set rather than a paint chart — and dark enough, throughout, for white text to sit on.
 */
internal fun titleGradient(title: String): List<Color> {
    val h = abs(title.hashCode())
    val hue = (h % 360).toFloat()
    // How far round the wheel the three stops travel. Small enough to stay one colour family;
    // varied per title so they are not all the same diagonal sweep.
    val drift = 18f + (h / 360 % 22)
    val sat = (0.50f + (h / 97 % 20) / 100f).coerceIn(0.45f, 0.72f)
    return listOf(
        Color.hsv(hue, sat * 0.88f, 0.62f),
        Color.hsv((hue + drift) % 360f, sat, 0.45f),
        Color.hsv((hue + drift * 2f) % 360f, (sat * 1.06f).coerceAtMost(0.85f), 0.30f),
    )
}

/**
 * How big the name is set, from how much of it there is: a step of PICO's type scale, 0 (largest)
 * to 4.
 *
 * Counted by the room the characters take rather than by how many there are: a CJK character is
 * about twice the width of a Latin one, so `攻壳机动队` and `Ghost in the Shell` need the same
 * space and would otherwise be set two sizes apart.
 */
internal fun titleTier(title: String): Int {
    val room = title.sumOf { if (it.code >= 0x2E80) 2 else 1 }
    return when {
        room <= 10 -> 0
        room <= 20 -> 1
        room <= 36 -> 2
        room <= 64 -> 3
        else -> 4
    }
}

/** The type role for each [titleTier]: Display S, Headline L / M, Title M / S. */
@Composable
private fun titleStyle(tier: Int): TextStyle = PicoTheme.typography.let {
    when (tier) {
        0 -> it.displaySmall
        1 -> it.headlineLarge
        2 -> it.headlineMedium
        3 -> it.titleMedium
        else -> it.titleSmall
    }
}

/** The nominal size of [titleTier]'s role — 28 / 24 / 20 / 16 / 14 sp — for code that only measures. */
internal fun titleSize(title: String): TextUnit = TIER_SP[titleTier(title)].sp

private val TIER_SP = intArrayOf(28, 24, 20, 16, 14)
