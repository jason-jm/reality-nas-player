package com.therealityteam.videoplayer.desktop.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.therealityteam.videoplayer.desktop.ui.components.Txt
import com.therealityteam.videoplayer.desktop.ui.components.clickCursor
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import com.therealityteam.videoplayer.desktop.ui.theme.AuraShapes
import kotlinx.coroutines.delay

/**
 * The seek bar: thin at rest, thicker under the pointer, with the chapters marked, what is
 * buffered shaded, and the time under the pointer shown above it. Dragging scrubs; the seek is
 * made on release (and live while dragging, throttled, so the picture follows the hand).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    chapters: List<Chapter>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    chapterTitle: (Long) -> String? = { null },
    /** A picture of the film at a time, sized in pixels; null when there is none. */
    preview: (suspend (ms: Long, widthPx: Int, heightPx: Int) -> ImageBitmap?)? = null,
    previewAspect: Float = 16f / 9f,
    /** What a screen reader calls it. */
    positionLabel: String = "",
) {
    val c = Aura.colors
    var width by remember { mutableFloatStateOf(1f) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var dragX by remember { mutableStateOf<Float?>(null) }
    var lastLive by remember { mutableStateOf(0L) }
    fun msAt(x: Float): Long = if (durationMs <= 0) 0 else ((x / width).coerceIn(0f, 1f) * durationMs).toLong()
    val shownPos = dragX?.let { msAt(it) } ?: positionMs
    val fraction = if (durationMs > 0) (shownPos.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferFraction = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val active = hoverX != null || dragX != null
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(22.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .onPointerEvent(PointerEventType.Move) { hoverX = it.changes.first().position.x }
            .onPointerEvent(PointerEventType.Enter) { hoverX = it.changes.first().position.x }
            .onPointerEvent(PointerEventType.Exit) { hoverX = null }
            // For screen readers: where the film is, of how long, and a way to move it.
            .semantics {
                contentDescription = positionLabel
                stateDescription = "${formatTime(positionMs)} / ${formatTime(durationMs)}"
                if (durationMs > 0) {
                    progressBarRangeInfo = ProgressBarRangeInfo(positionMs.toFloat().coerceIn(0f, durationMs.toFloat()), 0f..durationMs.toFloat())
                    setProgress { target -> onSeek(target.toLong().coerceIn(0, durationMs)); true }
                }
            }
            .pointerInput(durationMs) { detectTapGestures { onSeek(msAt(it.x)) } }
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { dragX = it.x },
                    onDragEnd = { dragX?.let { onSeek(msAt(it)) }; dragX = null },
                    onDragCancel = { dragX = null },
                ) { change, _ ->
                    dragX = change.position.x.coerceIn(0f, width)
                    val now = System.currentTimeMillis()
                    if (now - lastLive > 120) {
                        lastLive = now
                        onSeek(msAt(dragX!!))
                    }
                }
            }
            .clickCursor(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val barH = with(LocalDensity.current) { (if (active) 6.dp else 4.dp).toPx() }
        val accent = c.accent
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val y = (size.height - barH) / 2
            val r = CornerRadius(barH / 2, barH / 2)
            drawRoundRect(Color(0x40FFFFFF), Offset(0f, y), Size(size.width, barH), r)
            drawRoundRect(Color(0x55FFFFFF), Offset(0f, y), Size(size.width * bufferFraction, barH), r)
            drawRoundRect(accent, Offset(0f, y), Size(size.width * fraction, barH), r)
            // Chapter marks: a gap in the bar at each chapter's start.
            if (durationMs > 0 && chapters.size > 1) {
                chapters.drop(1).forEach { ch ->
                    val x = size.width * (ch.startMs.toFloat() / durationMs)
                    drawRect(Color(0xCC000000), Offset(x - 1f, y), Size(2f, barH))
                }
            }
            if (active) {
                drawCircle(Color.White, radius = barH * 1.25f, center = Offset(size.width * fraction, size.height / 2))
            }
        }
        val tipX = dragX ?: hoverX
        if (tipX != null && durationMs > 0) {
            val ms = msAt(tipX)
            val label = listOfNotNull(chapterTitle(ms), formatTime(ms)).joinToString("  ·  ")
            val density = LocalDensity.current
            val xDp = with(density) { tipX.toDp() }
            // The picture under the pointer, when the player can draw one: the last one stays up
            // until the next arrives, so sweeping along the bar never blinks to empty.
            val frameW = 212.dp
            val frameH = (frameW / previewAspect).coerceIn(88.dp, 160.dp)
            var frame by remember { mutableStateOf<ImageBitmap?>(null) }
            var frameFailed by remember { mutableStateOf(false) }
            if (preview != null) {
                LaunchedEffect(ms / maxOf(2_000L, durationMs / 400)) {
                    delay(40)
                    val img = with(density) { preview(ms, frameW.roundToPx(), frameH.roundToPx()) }
                    if (img != null) frame = img else if (frame == null) frameFailed = true
                }
            }
            val showFrame = preview != null && !frameFailed
            val tipWidth = if (showFrame) frameW else 180.dp
            val tipHeight = if (showFrame) frameH + 30.dp else 26.dp
            Box(
                Modifier
                    .offset(x = (xDp - tipWidth / 2).coerceIn(0.dp, maxWidth - tipWidth), y = -(tipHeight / 2 + 17.dp))
                    // Taller than the bar it floats over: its own height, centred on the bar, then lifted.
                    .requiredHeight(tipHeight)
                    .width(tipWidth),
                contentAlignment = Alignment.Center,
            ) {
                if (showFrame) {
                    Column(
                        Modifier.shadow(16.dp, AuraShapes.card).clip(AuraShapes.card).background(Color(0xF0101114))
                            .border(1.dp, Color(0x33FFFFFF), AuraShapes.card),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(frameW, frameH).background(Color.Black)) {
                            frame?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                        Box(Modifier.height(30.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                            Txt(label, Aura.type.caption, Color.White, maxLines = 1)
                        }
                    }
                } else {
                    Box(Modifier.clip(AuraShapes.small).background(Color(0xE6101114)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Txt(label, Aura.type.caption, Color.White)
                    }
                }
            }
        }
    }
}

/** 1:02:03 or 12:34. */
fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
