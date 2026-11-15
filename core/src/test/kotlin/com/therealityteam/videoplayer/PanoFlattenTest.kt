package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.model.StereoMode
import com.therealityteam.videoplayer.data.playback.PanoFlatten
import com.therealityteam.videoplayer.data.playback.PanoLayout
import com.therealityteam.videoplayer.data.playback.PanoViewport.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoFlattenTest {

    /** A frame whose red is its column (0–255 across), green its row counted up from the bottom, blue [blue]. */
    private fun coded(w: Int, h: Int, blue: (Int, Int) -> Int = { _, _ -> 0 }) = IntArray(w * h) {
        val x = it % w; val y = it / w
        val r = x * 255 / (w - 1); val g = (h - 1 - y) * 255 / (h - 1)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or blue(x, y)
    }

    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test
    fun theMiddleIsStraightAhead() {
        val out = PanoFlatten.view(coded(512, 256), 512, 256, PanoLayout.of(ProjectionMode.EQ360), 64, 48, Look(fov = 100f))
        val centre = out[24 * 64 + 32]
        assertEquals(128.0, red(centre).toDouble(), 4.0)
        assertEquals(128.0, green(centre).toDouble(), 4.0)
        // The right edge looks 50° to the right: 50/360 of the way further across.
        val right = out[24 * 64 + 63]
        assertEquals(128.0 + 255.0 * 50 / 360, red(right).toDouble(), 5.0)
    }

    @Test
    fun aStereoFrameGivesItsLeftEye() {
        // Side by side: blue marks the right half (the right eye's).
        val sbs = coded(1024, 256) { x, _ -> if (x >= 512) 255 else 0 }
        val left = PanoFlatten.view(sbs, 1024, 256, PanoLayout.of(ProjectionMode.EQ360, StereoMode.SBS), 32, 24)
        assertTrue(left.all { blue(it) == 0 })
        // Right eye first: the left eye's picture is the right half.
        val swapped = PanoFlatten.view(sbs, 1024, 256, PanoLayout.of(ProjectionMode.EQ360, StereoMode.SBS).copy(eyesSwapped = true), 32, 24)
        assertTrue(swapped.all { blue(it) == 255 })
        // Over-under: the top half is the left eye's.
        val ou = coded(512, 512) { _, y -> if (y < 256) 0 else 255 }
        val top = PanoFlatten.view(ou, 512, 512, PanoLayout.of(ProjectionMode.EQ360, StereoMode.OU), 32, 24)
        assertTrue(top.all { blue(it) == 0 })
    }

    @Test
    fun behindAHalfSphereIsBlack() {
        val out = PanoFlatten.view(coded(256, 256), 256, 256, PanoLayout.of(ProjectionMode.EQ180), 16, 12, Look(yaw = 180f, fov = 60f))
        assertTrue(out.all { it == 0xFF000000.toInt() })
    }
}
