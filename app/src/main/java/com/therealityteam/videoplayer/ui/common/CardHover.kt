package com.therealityteam.videoplayer.ui.common

import androidx.compose.foundation.Indication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.hover.SpatialHoverEffectGroup
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.hover.spatialHoverEffectGroup
import com.therealityteam.videoplayer.platform.DeepLink

/**
 * The hover of a card that is a picture with words under it — a poster, an episode's still, a face.
 *
 * The system's highlight fills exactly the bounds of whatever carries it. Carried by the whole card,
 * it drew one box round the picture AND the words: the name and the year flush against its edge, its
 * foot close on whatever came next, so a lit card looked crammed against its neighbours. So a card is
 * a hover GROUP: all of it answers the gaze — look at the name and the card lights — but only the
 * picture shows it, in the picture's own shape, and the press shows there too. The words stay put.
 */
@Stable
class CardHover internal constructor() {
    internal val group: SpatialHoverEffectGroup = SpatialHoverEffectGroup.obtain()

    /** The card's one interaction source: its click, its press on the picture, its haptics. */
    val interaction: MutableInteractionSource = MutableInteractionSource()
}

@Composable
fun rememberCardHover(): CardHover = remember { CardHover() }

/**
 * On the whole card, ahead of its click: it answers the gaze for its picture, with no look of its
 * own (the one effect it declares changes nothing), and hands the group down to the picture inside.
 */
fun Modifier.cardHoverArea(hover: CardHover): Modifier =
    spatialHoverEffectGroup(hover.group).spatialHoverEffect { alpha(1f) }

/**
 * On the picture: the system highlight, in [shape], whenever any part of the card is looked at —
 * and the card's press, drawn by [indication] (the theme's `LocalIndication`) on the picture alone.
 */
fun Modifier.cardHoverPicture(hover: CardHover, shape: Shape, indication: Indication): Modifier =
    clip(shape).spatialHoverEffect().hoverDebug().indication(hover.interaction, indication)

/**
 * Automation ([DeepLink.hoverDebug]): this hover region drawn lit — inside whatever clip precedes it,
 * as the system's highlight is — so a screenshot shows where every highlight would fall at once.
 */
fun Modifier.hoverDebug(): Modifier =
    if (!DeepLink.hoverDebug) this else drawWithContent { drawContent(); drawRect(HOVER_DEBUG_LIGHT) }

private val HOVER_DEBUG_LIGHT = Color(0x8CFFFFFF) // design-style: fixed-figma-color automation-only overlay

/**
 * Between two rows of a side navigation (the home screen's shelves, a settings page's sections):
 * the chosen row's pill and the highlight of the row being looked at stay two shapes. Stacked edge to
 * edge, a lit row met the chosen one's pill and the two read as one block.
 */
val SideRowGap = 6.dp
