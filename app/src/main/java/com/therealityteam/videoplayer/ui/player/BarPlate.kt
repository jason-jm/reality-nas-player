package com.therealityteam.videoplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.hover.SpatialHoverEffectContext
import com.pico.spatial.ui.foundation.hover.SpatialHoverEffectGroup
import com.pico.spatial.ui.foundation.hover.SpatialHoverEffectRootScope
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.hover.spatialHoverEffectGroup
import com.pico.spatial.ui.foundation.hover.tween

/**
 * The bar's plate, and how the bar rests.
 *
 * The user (2026-10-23): while the eyes are on the bar it must not go — only a few seconds after they leave it. Where the
 * eyes are is the system's alone: hover is drawn outside the app, which is never told of it (SpatialHoverEffect, for the
 * gaze's privacy), so the bar cannot hold itself up while it is looked at. The system can. After its quiet spell the bar
 * RESTS instead of going ([awake] false): still there and still taking clicks, but drawn only while a gaze or a ray is on
 * it, and fading [REST_FADE_DELAY_MS] after it leaves. A click needs the eyes on what it clicks, so a resting bar is never
 * clicked unseen.
 *
 * One hover group spans the bar, as the system's TabBar does it: anything on the bar looked at holds the whole bar up. Its
 * buttons only trigger the group — answering it too, they would all light at once. The fade wraps the plate as well as the
 * controls (the modifier order matters: a fade after the background left the plate standing). The plate is a colour, not
 * the system glass: no public hover effect fades a material (measured on the B3110 — alpha and clip both left the glass
 * up, an empty pane under nothing), so the bar is the glass's colour as the headset shows it, in the Toolbar's corners and
 * inset.
 */
@Composable
fun BarPlate(awake: Boolean, content: @Composable () -> Unit) {
    val group = remember { SpatialHoverEffectGroup.obtain() }
    val fade: SpatialHoverEffectRootScope.(SpatialHoverEffectContext) -> Unit = { hover ->
        val shown = awake || hover.isActive
        animation(tween(durationMillis = REST_FADE_MS, delayMillis = if (shown) 0 else REST_FADE_DELAY_MS)) {
            alpha(if (shown) 1f else 0f)
        }
    }
    Box(
        modifier = Modifier
            .spatialHoverEffectGroup(group)
            .spatialHoverEffect(fade)
            .clip(RoundedCornerShape(BAR_CORNER))
            .background(BAR_PLATE)
            .padding(BAR_INSET),
    ) {
        Box(modifier = Modifier.spatialHoverEffectGroup(group.behavior(SpatialHoverEffectGroup.Behavior.Trigger))) {
            content()
        }
    }
}

/** How long a resting bar takes to fade in or out, and how long it stays after the gaze has left it. */
internal const val REST_FADE_MS = 350
internal const val REST_FADE_DELAY_MS = 2_000

/** The Toolbar's own segment, as SpatialUI 6.1.9 draws it (ToolbarDefaults: CornerRadius, SegmentContentPadding). */
private val BAR_CORNER = 32.dp

/** The plate the bar's controls sit on, in place of the system glass (see [BarPlate]): the glass as the B3110 shows it. */
private val BAR_PLATE = Color(0xF0_8F_93_8C) // design-style: fixed-figma-color — the Toolbar glass measured off the headset
private val BAR_INSET = 8.dp
