package com.therealityteam.videoplayer.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Draws Aura's app icon — the theme's deep violet, a ring of light (the aura) and a play mark
 * inside it — at every size a desktop asks for. The icon's source, as much as a test: rerun it and
 * the packaging icons are rebuilt.
 *
 *   ./gradlew -p desktop test --tests '*IconRender*' -Daura.icon=packaging
 */
class IconRender {

    @Test
    fun render() {
        val dir = System.getProperty("aura.icon")?.let(::File)
        assumeTrue("set -Daura.icon", dir != null)
        val out = if (dir!!.isAbsolute) dir else File(System.getProperty("user.dir"), dir.path).canonicalFile
        val iconset = File(out, "Aura.iconset").apply { mkdirs() }
        File(out, "png").mkdirs()
        for (size in listOf(16, 24, 32, 48, 64, 128, 256, 512, 1024)) {
            val png = draw(size)
            File(out, "png/aura-$size.png").writeBytes(png)
            when (size) {
                16 -> File(iconset, "icon_16x16.png").writeBytes(png)
                32 -> { File(iconset, "icon_16x16@2x.png").writeBytes(png); File(iconset, "icon_32x32.png").writeBytes(png) }
                64 -> File(iconset, "icon_32x32@2x.png").writeBytes(png)
                128 -> File(iconset, "icon_128x128.png").writeBytes(png)
                256 -> { File(iconset, "icon_128x128@2x.png").writeBytes(png); File(iconset, "icon_256x256.png").writeBytes(png) }
                512 -> { File(iconset, "icon_256x256@2x.png").writeBytes(png); File(iconset, "icon_512x512.png").writeBytes(png) }
                1024 -> { File(iconset, "icon_512x512@2x.png").writeBytes(png); File(out, "Aura.png").writeBytes(png) }
            }
        }
        println("ICON written to $out")
    }

    private fun draw(size: Int): ByteArray {
        val scene = ImageComposeScene(size, size, Density(1f)) {
            Canvas(Modifier.fillMaxSize()) { scale(size / 1024f, pivot = Offset.Zero) { icon() } }
        }
        try {
            return scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
        } finally {
            scene.close()
        }
    }

    /** The icon on a 1024 grid: the macOS tile (824, radius 186) with room for its shadow. */
    private fun DrawScope.icon() {
        val tileTop = Offset(100f, 100f)
        val tile = Size(824f, 824f)
        val radius = CornerRadius(186f, 186f)
        // Shadow: a few soft offsets.
        for (i in 1..6) {
            drawRoundRect(
                Color.Black.copy(alpha = 0.06f), topLeft = tileTop + Offset(0f, 6f + i * 3f), size = tile,
                cornerRadius = CornerRadius(186f + i * 2f, 186f + i * 2f),
            )
        }
        // Ground: deep violet into night.
        drawRoundRect(
            Brush.linearGradient(
                0f to Color(0xFF2B1F63), 0.55f to Color(0xFF13112C), 1f to Color(0xFF08080F),
                start = tileTop, end = tileTop + Offset(tile.width, tile.height),
            ),
            topLeft = tileTop, size = tile, cornerRadius = radius,
        )
        val clip = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(tileTop.x, tileTop.y, tileTop.x + tile.width, tileTop.y + tile.height, radius))
        }
        clipPath(clip) {
            auraMark(Offset(512f, 500f))
            // A sheen across the top of the tile.
            drawRect(
                Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f), 1f to Color.Transparent, startY = 100f, endY = 420f),
                topLeft = tileTop, size = Size(tile.width, 320f),
            )
        }
        // A hairline edge so the tile holds its shape on a dark dock.
        drawRoundRect(Color.White.copy(alpha = 0.16f), topLeft = tileTop, size = tile, cornerRadius = radius, style = Stroke(width = 3f))
    }

    /** The light, the ring of light (the aura) and the play mark, about [center] on the 1024 grid. */
    private fun DrawScope.auraMark(center: Offset) {
        // Light behind the ring.
        drawCircle(
            Brush.radialGradient(listOf(Color(0x998F7DFF), Color(0x008F7DFF)), center = center, radius = 360f),
            radius = 360f, center = center,
        )
        // The aura: a halo (wide faint strokes) under a crisp ring, brighter at the top.
        val ring = Brush.sweepGradient(
            listOf(Color(0xFF6C8CFF), Color(0xFFB9A9FF), Color(0xFF7B63FF), Color(0xFF4FA3FF), Color(0xFF6C8CFF)),
            center = center,
        )
        for (i in 18 downTo 1) {
            drawCircle(ring, radius = 250f, center = center, alpha = 0.028f, style = Stroke(width = 30f + i * 6f))
        }
        drawCircle(ring, radius = 250f, center = center, style = Stroke(width = 30f))
        // The play mark, rounded, a touch right of centre as the eye expects.
        val tri = Path().apply {
            moveTo(center.x - 52f, center.y - 112f)
            lineTo(center.x - 52f, center.y + 112f)
            lineTo(center.x + 126f, center.y)
            close()
        }
        drawPath(tri, Color.White, style = Fill)
        drawPath(tri, Color.White, style = Stroke(width = 34f, join = StrokeJoin.Round, cap = StrokeCap.Round))
    }

    /**
     * The PICO app's launcher icon: the same violet, ring and play mark, but square and edge to edge —
     * the launcher cuts its own shape, as it did the scaffold's placeholder this replaces — with the
     * mark drawn smaller, inside the circle every mask keeps.
     *
     *   ./gradlew -p desktop test --tests '*IconRender.renderPico' -Daura.icon.pico=<…>/app/src/main/res/drawable/ic_launcher_foreground.png
     */
    @Test
    fun renderPico() {
        val target = System.getProperty("aura.icon.pico")
        assumeTrue("set -Daura.icon.pico", target != null)
        val out = File(target!!).let { if (it.isAbsolute) it else File(System.getProperty("user.dir"), it.path).canonicalFile }
        val size = 1424
        val scene = ImageComposeScene(size, size, Density(1f)) {
            Canvas(Modifier.fillMaxSize()) { scale(size / 1024f, pivot = Offset.Zero) { picoIcon() } }
        }
        try {
            out.writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
        println("PICO ICON written to $out")
    }

    /**
     * The PICO Store's layered icon (spatial-design › app icon › layered design): 1024 px PNGs, each kept
     * inside the canvas's inscribed circle — icon_bg (opaque there, not near-black), icon_fg2 (the ring) and
     * icon_fg3 (the play mark), the foreground layers solid and sharp-edged with no glow, shadow or sheen,
     * since the system lifts each layer on hover and lights it itself. Also icon_preview.png, the three
     * stacked, for a look.
     *
     *   ./gradlew -p desktop test --tests '*IconRender.renderPicoLayers' -Daura.icon.layers=<…>/docs/store/icon
     */
    @Test
    fun renderPicoLayers() {
        val target = System.getProperty("aura.icon.layers")
        assumeTrue("set -Daura.icon.layers", target != null)
        val out = File(target!!).let { if (it.isAbsolute) it else File(System.getProperty("user.dir"), it.path).canonicalFile }
        out.mkdirs()
        val center = Offset(512f, 512f)
        val layers: List<Pair<String, DrawScope.() -> Unit>> = listOf(
            "icon_bg.png" to { layerGround(center) },
            "icon_fg2.png" to { scale(MARK_SCALE, pivot = center) { drawCircle(ringBrush(center), radius = 250f, center = center, style = Stroke(width = 34f)) } },
            "icon_fg3.png" to { scale(MARK_SCALE, pivot = center) { playMark(center) } },
        )
        for ((name, draw) in layers) File(out, name).writeBytes(render(1024) { draw() })
        File(out, "icon_preview.png").writeBytes(render(1024) { layers.forEach { it.second(this) } })
        println("PICO LAYERS written to $out")
    }

    private fun render(size: Int, draw: DrawScope.() -> Unit): ByteArray {
        val scene = ImageComposeScene(size, size, Density(1f)) { Canvas(Modifier.fillMaxSize()) { draw() } }
        try {
            return scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
        } finally {
            scene.close()
        }
    }

    /** The background layer: the violet, opaque to every edge, lifted from the tile's night so the system's light shows. */
    private fun DrawScope.layerGround(center: Offset) {
        drawRect(
            Brush.linearGradient(
                0f to Color(0xFF3A2B86), 0.6f to Color(0xFF231C55), 1f to Color(0xFF17143A),
                start = Offset.Zero, end = Offset(1024f, 1024f),
            ),
        )
        // The light the ring sits in — part of the ground here, so the layer stays opaque.
        drawCircle(
            Brush.radialGradient(listOf(Color(0x668F7DFF), Color(0x008F7DFF)), center = center, radius = 420f),
            radius = 420f, center = center,
        )
    }

    private fun ringBrush(center: Offset) = Brush.sweepGradient(
        listOf(Color(0xFF6C8CFF), Color(0xFFB9A9FF), Color(0xFF7B63FF), Color(0xFF4FA3FF), Color(0xFF6C8CFF)),
        center = center,
    )

    /** The play mark, rounded, a touch right of centre as the eye expects. */
    private fun DrawScope.playMark(center: Offset) {
        val tri = Path().apply {
            moveTo(center.x - 52f, center.y - 112f)
            lineTo(center.x - 52f, center.y + 112f)
            lineTo(center.x + 126f, center.y)
            close()
        }
        drawPath(tri, Color.White, style = Fill)
        drawPath(tri, Color.White, style = Stroke(width = 34f, join = StrokeJoin.Round, cap = StrokeCap.Round))
    }

    private fun DrawScope.picoIcon() {
        // Ground: deep violet into night, to every edge.
        drawRect(
            Brush.linearGradient(
                0f to Color(0xFF2B1F63), 0.55f to Color(0xFF13112C), 1f to Color(0xFF08080F),
                start = Offset.Zero, end = Offset(1024f, 1024f),
            ),
        )
        // The mark at 0.8 about the middle: its halo stays inside the circle a round mask keeps.
        scale(0.8f, pivot = Offset(512f, 512f)) { auraMark(Offset(512f, 512f)) }
        // The sheen across the top.
        drawRect(
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 1f to Color.Transparent, startY = 0f, endY = 400f),
            size = Size(1024f, 400f),
        )
    }
}

/** The mark's size in the layered icon: the ring's outer edge at 0.95 × 267 ≈ 254 px from the middle, well inside the 512 circle. */
private const val MARK_SCALE = 0.95f
