package com.therealityteam.videoplayer.desktop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import com.therealityteam.videoplayer.desktop.ui.theme.AuraShapes

/** Text in the theme's colours; one line and an ellipsis unless asked otherwise. */
@Composable
fun Txt(
    text: String,
    style: TextStyle = Aura.type.body,
    color: Color = Aura.colors.text,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign? = null,
) {
    BasicText(
        text = text,
        style = if (align != null) style.copy(color = color, textAlign = align) else style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A line icon (Lucide), tinted. */
@Composable
fun Icon(icon: ImageVector, tint: Color = Aura.colors.text, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

/** Where the pointer is over something that acts: the hand. */
fun Modifier.clickCursor(): Modifier = pointerHoverIcon(PointerIcon.Hand)

/** A hover / press wash for anything clickable, in the theme's colours. */
@Composable
fun interactionWash(source: MutableInteractionSource, selected: Boolean = false, rest: Color = Color.Transparent): Color {
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val target = when {
        pressed -> Aura.colors.pressed
        selected -> Aura.colors.accentSoft
        hovered -> Aura.colors.hover
        else -> rest
    }
    val c by animateColorAsState(target, tween(120))
    return c
}

@Composable
fun Divider(modifier: Modifier = Modifier, color: Color = Aura.colors.stroke) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** An indeterminate spinner: an arc chasing itself. */
@Composable
fun Spinner(size: Dp = 18.dp, color: Color = Aura.colors.accent, stroke: Dp = 2.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition()
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart))
    Canvas(modifier.size(size)) {
        val w = stroke.toPx()
        drawArc(
            color = color.copy(alpha = 0.18f), startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w), style = Stroke(w),
        )
        drawArc(
            color = color, startAngle = angle, sweepAngle = 100f, useCenter = false,
            topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w),
            style = Stroke(w, cap = StrokeCap.Round),
        )
    }
}

/** A thin progress bar; [fraction] null is indeterminate. */
@Composable
fun ProgressLine(fraction: Float?, modifier: Modifier = Modifier, color: Color = Aura.colors.accent, height: Dp = 3.dp) {
    val track = Aura.colors.stroke
    if (fraction != null) {
        Canvas(modifier.fillMaxWidth().height(height).clip(AuraShapes.pill)) {
            drawRect(track)
            drawRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
        }
    } else {
        val t = rememberInfiniteTransition()
        val x by t.animateFloat(-0.35f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)))
        Canvas(modifier.fillMaxWidth().height(height).clip(AuraShapes.pill)) {
            drawRect(track)
            drawRect(color, topLeft = Offset(size.width * x, 0f), size = Size(size.width * 0.35f, size.height))
        }
    }
}

/** A small label chip: "4K", "HDR", "3 eps". */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, color: Color = Aura.colors.textSecondary, fill: Color = Aura.colors.hover) {
    Box(
        modifier
            .clip(AuraShapes.small)
            .background(fill)
            .border(1.dp, Aura.colors.stroke, AuraShapes.small),
    ) {
        Txt(text, Aura.type.micro, color, Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** Hover as a plain boolean, for things that change more than their colour. */
@Composable
fun rememberHover(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return source to hovered
}

fun Modifier.hover(source: MutableInteractionSource): Modifier = hoverable(source)
