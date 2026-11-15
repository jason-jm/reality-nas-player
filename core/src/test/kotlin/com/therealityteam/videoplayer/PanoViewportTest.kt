package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.playback.PanoLayout
import com.therealityteam.videoplayer.data.playback.PanoMesh
import com.therealityteam.videoplayer.data.playback.PanoViewport
import com.therealityteam.videoplayer.data.playback.PanoViewport.Look
import com.therealityteam.videoplayer.data.playback.Panorama
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PanoViewportTest {

    private val sphere = Panorama.mesh(PanoLayout.of(ProjectionMode.EQ360), 50f)

    /** The texture coordinates the view shows at window point ([x], [y]): the triangle there, interpolated. */
    private fun uvAt(view: PanoMesh, x: Float, y: Float): Pair<Float, Float>? {
        for (t in 0 until view.indices.size / 3) {
            val i = IntArray(3) { view.indices[3 * t + it] }
            val (ax, ay) = view.positions[3 * i[0]] to view.positions[3 * i[0] + 1]
            val (bx, by) = view.positions[3 * i[1]] to view.positions[3 * i[1] + 1]
            val (cx, cy) = view.positions[3 * i[2]] to view.positions[3 * i[2] + 1]
            val det = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (abs(det) < 1e-9f) continue
            val l1 = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / det
            val l2 = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / det
            val l3 = 1 - l1 - l2
            if (l1 >= -1e-4f && l2 >= -1e-4f && l3 >= -1e-4f) {
                val u = l1 * view.uvs[2 * i[0]] + l2 * view.uvs[2 * i[1]] + l3 * view.uvs[2 * i[2]]
                val v = l1 * view.uvs[2 * i[0] + 1] + l2 * view.uvs[2 * i[1] + 1] + l3 * view.uvs[2 * i[2] + 1]
                return u to v
            }
        }
        return null
    }

    @Test
    fun theCentreShowsWhereItLooks() {
        val ahead = PanoViewport.view(sphere, Look(), 1.6f, 0.9f)
        val (u, v) = uvAt(ahead, 0f, 0f)!!
        assertEquals(0.5f, u, 0.003f)
        assertEquals(0.5f, v, 0.003f)
        // Looking right a quarter turn: three quarters across the picture.
        val right = PanoViewport.view(sphere, Look(yaw = 90f), 1.6f, 0.9f)
        assertEquals(0.75f, uvAt(right, 0f, 0f)!!.first, 0.003f)
        // Looking up 30°: the picture's row 30° above the horizon.
        val up = PanoViewport.view(sphere, Look(pitch = 30f), 1.6f, 0.9f)
        assertEquals(0.5f + 30f / 180f, uvAt(up, 0f, 0f)!!.second, 0.003f)
    }

    @Test
    fun theWindowsEdgeIsHalfTheViewAcross() {
        // A 90° view: its right edge looks 45° to the right.
        val view = PanoViewport.view(sphere, Look(fov = 90f), 1.6f, 0.9f)
        assertEquals(0.5f + 45f / 360f, uvAt(view, 0.7999f, 0f)!!.first, 0.004f)
    }

    @Test
    fun lookingBehindCrossesTheSeamCleanly() {
        val back = PanoViewport.view(sphere, Look(yaw = 180f), 1.6f, 0.9f)
        // Either side of the middle: the picture's two edges, never something from its middle.
        val left = uvAt(back, -0.2f, 0f)!!.first
        val right = uvAt(back, 0.2f, 0f)!!.first
        assertTrue("left of behind is the picture's right edge: $left", left > 0.9f)
        assertTrue("right of behind is its left edge: $right", right < 0.1f)
    }

    @Test
    fun everythingLiesInsideTheWindow() {
        for (look in listOf(Look(), Look(yaw = 137f, pitch = -40f), Look(pitch = 85f, fov = 110f))) {
            val view = PanoViewport.view(sphere, look, 1.6f, 0.9f)
            assertTrue(view.indices.isNotEmpty())
            for (k in 0 until view.vertexCount) {
                assertTrue(abs(view.positions[3 * k]) <= 0.8001f)
                assertTrue(abs(view.positions[3 * k + 1]) <= 0.4501f)
                assertEquals(0f, view.positions[3 * k + 2], 0f)
            }
            // The whole window is covered (a whole sphere has something everywhere).
            for (x in listOf(-0.79f, 0f, 0.79f)) for (y in listOf(-0.44f, 0f, 0.44f)) {
                assertTrue("$look covers ($x, $y)", uvAt(view, x, y) != null)
            }
        }
    }

    @Test
    fun aHalfSphereLeavesTheBackEmpty() {
        val vr180 = Panorama.mesh(PanoLayout.of(ProjectionMode.EQ180), 50f)
        val behind = PanoViewport.view(vr180, Look(yaw = 180f), 1.6f, 0.9f)
        assertTrue(uvAt(behind, 0f, 0f) == null)
    }

    @Test
    fun theWindowsCornersAreRound() {
        val view = PanoViewport.view(sphere, Look(), 1.6f, 0.9f, cornerRadius = 0.1f)
        // The corner itself is cut away; a point inside the curve is still shown.
        assertTrue(uvAt(view, 0.795f, 0.445f) == null)
        assertTrue(uvAt(view, 0.75f, 0.40f) != null)
        assertTrue(uvAt(view, 0f, 0.449f) != null)
        for (k in 0 until view.vertexCount) {
            val x = abs(view.positions[3 * k]) - (0.8f - 0.1f)
            val y = abs(view.positions[3 * k + 1]) - (0.45f - 0.1f)
            if (x > 0 && y > 0) assertTrue("inside the round", x * x + y * y <= 0.1f * 0.1f + 1e-4f)
        }
    }

    @Test
    fun aHalfSpheresViewStopsAtItsEdge() {
        val vr180 = PanoLayout.of(ProjectionMode.EQ180)
        assertTrue(PanoViewport.fits(vr180, Look(), 16f / 9f))
        assertTrue(PanoViewport.fits(vr180, Look(yaw = 40f), 16f / 9f))
        // A 90° view turned 50° right reaches 95°: past the picture.
        assertTrue(!PanoViewport.fits(vr180, Look(yaw = 50f), 16f / 9f))
        assertTrue(!PanoViewport.fits(vr180, Look(yaw = 180f), 16f / 9f))
        val fisheye = PanoLayout.of(ProjectionMode.FISHEYE, lensFov = 190f)
        assertTrue(PanoViewport.fits(fisheye, Look(), 16f / 9f))
        assertTrue(!PanoViewport.fits(fisheye, Look(pitch = 80f), 16f / 9f))
        // A whole sphere: everywhere.
        val whole = PanoLayout.of(ProjectionMode.EQ360)
        for (y in -180..180 step 45) for (p in -80..80 step 40) assertTrue(PanoViewport.fits(whole, Look(y.toFloat(), p.toFloat()), 16f / 9f))
    }
}
