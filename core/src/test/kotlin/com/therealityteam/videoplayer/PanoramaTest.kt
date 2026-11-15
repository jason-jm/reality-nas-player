package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.playback.PanoLayout
import com.therealityteam.videoplayer.data.playback.PanoMesh
import com.therealityteam.videoplayer.data.playback.Panorama
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The projections against FFmpeg's own. The pictures in test/resources/pano were made by its v360
 * filter from `eqcode.png` — an equirectangular frame whose red counts the yaw (left edge 0, right
 * 255) and green the pitch (top 0, bottom 255) — so a pixel says which direction it shows. Each mesh
 * vertex is looked up in the picture at the texture coordinates the mesh gives it, and must show the
 * vertex's own direction.
 */
class PanoramaTest {

    @Test
    fun drawnWithoutAMesh() {
        // One eye's pixels: half a side-by-side frame's width, half an over-under one's height.
        val sbs = PanoLayout.of(ProjectionMode.CUBEMAP, com.therealityteam.videoplayer.data.model.StereoMode.SBS).meshless(12288, 1024)
        assertEquals(6144, sbs.frameWidth)
        assertEquals(1024, sbs.frameHeight)
        assertEquals(6 to 1, Panorama.cubeGrid(sbs))
        val ou = PanoLayout.of(ProjectionMode.EAC, com.therealityteam.videoplayer.data.model.StereoMode.OU).meshless(3072, 4096)
        assertEquals(2048, ou.frameHeight)
        // A file's own mesh is drawn as YouTube's EAC, its eyes kept.
        val own = PanoLayout(ProjectionMode.EQ360, com.therealityteam.videoplayer.data.model.StereoMode.OU, ownMesh = PanoMesh(FloatArray(0), FloatArray(0), IntArray(0)), eyesSwapped = true)
            .meshless(3072, 4096)
        assertEquals(ProjectionMode.EAC, own.projection)
        assertNull(own.ownMesh)
        assertTrue(own.eyesSwapped)
    }

    @Test
    fun equirectangularPointsAreWhereTheyLook() {
        val l = PanoLayout.of(ProjectionMode.EQ360)
        assertUv(0.5, 0.5, Panorama.uv(l, 0.0, 0.0, -1.0)) // ahead: the middle
        assertUv(0.75, 0.5, Panorama.uv(l, 1.0, 0.0, 0.0)) // right: three quarters across
        assertUv(0.25, 0.5, Panorama.uv(l, -1.0, 0.0, 0.0))
        assertEquals(1.0, Panorama.uv(l, 0.0, 1.0, 0.0)!![1], 1e-9) // up: the top row
        val half = PanoLayout.of(ProjectionMode.EQ180)
        assertUv(1.0, 0.5, Panorama.uv(half, 1.0, 0.0, 0.0)) // a VR180's right edge
        assertNull(Panorama.uv(half, 0.0, 0.0, 1.0)) // nothing behind
    }

    @Test
    fun fisheyeCentreIsAheadAndItsRimIsHalfTheLens() {
        val l = PanoLayout.of(ProjectionMode.FISHEYE, lensFov = 180f)
        assertUv(0.5, 0.5, Panorama.uv(l, 0.0, 0.0, -1.0))
        assertUv(1.0, 0.5, Panorama.uv(l, 1.0, 0.0, 0.0))
        assertUv(0.5, 1.0, Panorama.uv(l, 0.0, 1.0, 0.0))
        assertNull(Panorama.uv(l, 0.0, 0.0, 1.0))
    }

    @Test
    fun equirectMeshMatchesItsPicture() = check("eqcode.png", PanoLayout.of(ProjectionMode.EQ360))

    @Test
    fun halfEquirectMeshMatchesFfmpeg() = check("hequirect.png", PanoLayout.of(ProjectionMode.EQ180))

    @Test
    fun cubemapMeshMatchesFfmpeg() = check("c3x2.png", PanoLayout.of(ProjectionMode.CUBEMAP), edgeGuard = 3.0)

    @Test
    fun paddedCubemapMatchesFfmpeg() =
        // FFmpeg's fout_pad=16: a face's picture 8 pixels in from each of its edges — Spherical V2's cbmp padding 8.
        check("c3x2_pad8.png", PanoLayout(ProjectionMode.CUBEMAP, frameWidth = 768, frameHeight = 512, cubePadding = 8), edgeGuard = 3.0)

    @Test
    fun aCubemapsPaddingFollowsItsSize() {
        // 32 of a 5760-wide frame's pixels is 11 of the same frame copied 2048 wide.
        val l = PanoLayout(ProjectionMode.CUBEMAP, cubePadding = 32).sized(5760, 3840)
        assertEquals(11, l.sized(2048, 1365).cubePadding)
        // Chosen by the viewer, the file's padding stays.
        val chosen = PanoLayout.forFilm(
            com.therealityteam.videoplayer.data.model.VideoFlags(projection = ProjectionMode.CUBEMAP, userOverridden = true), "x.mp4",
            com.therealityteam.videoplayer.data.playback.SphericalInfo(projection = ProjectionMode.CUBEMAP, cubePadding = 8, frameWidth = 768, frameHeight = 512),
        )!!
        assertEquals(8, chosen.cubePadding)
        assertEquals(768, chosen.frameWidth)
    }

    @Test
    fun cubeStripsMatchFfmpeg() {
        // FFmpeg's c6x1 and c1x6: the same faces in one strip, told apart by the frame's shape.
        check("c6x1.png", PanoLayout(ProjectionMode.CUBEMAP, frameWidth = 1536, frameHeight = 256), edgeGuard = 3.0)
        check("c1x6.png", PanoLayout(ProjectionMode.CUBEMAP, frameAspect = 256f / 1536f), edgeGuard = 3.0)
    }

    @Test
    fun eacMeshMatchesFfmpeg() =
        check("eac.png", PanoLayout(ProjectionMode.EAC, frameWidth = 768, frameHeight = 512), edgeGuard = 3.0)

    @Test
    fun fisheyeMeshMatchesFfmpeg() = check("fisheye.png", PanoLayout.of(ProjectionMode.FISHEYE, lensFov = 180f))

    @Test
    fun wideFisheyeMeshMatchesFfmpeg() = check("fisheye_200.png", PanoLayout.of(ProjectionMode.FISHEYE, lensFov = 200f))

    @Test
    fun dualFisheyeMeshMatchesFfmpeg() = check("dfisheye.png", PanoLayout.of(ProjectionMode.DUAL_FISHEYE, lensFov = 190f))

    @Test
    fun meshesSurroundTheViewerAtTheirRadius() {
        for (p in PanoLayout.SPHERICAL) {
            val m = Panorama.mesh(PanoLayout.of(p), 50f)
            assertTrue("$p has triangles", m.indices.size >= 3 && m.indices.size % 3 == 0)
            assertTrue("$p indices in range", m.indices.all { it in 0 until m.vertexCount })
            for (k in 0 until m.vertexCount) {
                val r = sqrt(m.positions[3 * k].let { it * it } + m.positions[3 * k + 1].let { it * it } + m.positions[3 * k + 2].let { it * it })
                assertEquals("$p vertex $k on the sphere", 50.0, r.toDouble(), 1e-3)
            }
            assertTrue("$p uv in the frame", m.uvs.all { it in -1e-6f..1.000001f })
        }
    }

    private fun assertUv(u: Double, v: Double, got: DoubleArray?) {
        assertTrue("a point there", got != null)
        assertEquals(u, got!![0], 1e-9)
        assertEquals(v, got[1], 1e-9)
    }

    /**
     * Every vertex of [layout]'s mesh, looked up in [picture], shows its own direction — within two
     * levels of the code (~1.5°), for 99% of them (FFmpeg blends across a face's edge; [edgeGuard]
     * pixels of it are left out). Directions where the code itself jumps (behind, where yaw wraps; the
     * poles, where it has none) are left out too.
     */
    private fun check(picture: String, layout: PanoLayout, edgeGuard: Double = 1.5) {
        val img = ImageIO.read(javaClass.getResourceAsStream("/pano/$picture"))
        val mesh: PanoMesh = Panorama.mesh(layout, 1f)
        var tried = 0
        var good = 0
        val bad = ArrayList<String>()
        for (k in 0 until mesh.vertexCount) {
            val x = mesh.positions[3 * k].toDouble()
            val y = mesh.positions[3 * k + 1].toDouble()
            val z = mesh.positions[3 * k + 2].toDouble()
            val yaw = Math.toDegrees(atan2(x, -z))
            val pitch = Math.toDegrees(atan2(y, hypot(x, z)))
            if (abs(yaw) > 170 || abs(pitch) > 80) continue
            val u = mesh.uvs[2 * k].toDouble()
            val v = mesh.uvs[2 * k + 1].toDouble()
            if (nearFaceEdge(layout, img, u, v, edgeGuard)) continue
            val (r, g) = sample(img, u, v)
            val wantR = (yaw + 180) / 360 * 255
            val wantG = (90 - pitch) / 180 * 255
            tried++
            if (abs(r - wantR) <= 2.5 && abs(g - wantG) <= 2.5) good++
            else if (bad.size < 5) bad += "yaw %.1f pitch %.1f: uv (%.3f, %.3f) shows r %.1f g %.1f, wanted %.1f %.1f".format(yaw, pitch, u, v, r, g, wantR, wantG)
        }
        assertTrue("$picture: enough vertices looked at ($tried)", tried > 200)
        assertTrue("$picture: $good of $tried right; e.g. $bad", good >= tried * 0.99)
    }

    /** Within [guard] pixels of a cube face's edge (cubemaps only), where FFmpeg's picture blends faces. */
    private fun nearFaceEdge(layout: PanoLayout, img: BufferedImage, u: Double, v: Double, guard: Double): Boolean {
        if (layout.projection != ProjectionMode.CUBEMAP && layout.projection != ProjectionMode.EAC) return false
        val px = u * img.width
        val py = (1 - v) * img.height
        val aspect = img.width.toDouble() / img.height
        val fw = img.width / (if (aspect > 4) 6.0 else if (aspect < 0.3) 1.0 else 3.0)
        val fh = img.height / (if (aspect > 4) 1.0 else if (aspect < 0.3) 6.0 else 2.0)
        val dx = minOf(px % fw, fw - px % fw)
        val dy = minOf(py % fh, fh - py % fh)
        return dx < guard + 2 || dy < guard + 2
    }

    /** The picture's red and green at (u, v), v up, read between pixels as FFmpeg's linear interpolation would. */
    private fun sample(img: BufferedImage, u: Double, v: Double): Pair<Double, Double> {
        val fx = (u * img.width - 0.5).coerceIn(0.0, img.width - 1.0)
        val fy = ((1 - v) * img.height - 0.5).coerceIn(0.0, img.height - 1.0)
        val x0 = fx.toInt().coerceAtMost(img.width - 2)
        val y0 = fy.toInt().coerceAtMost(img.height - 2)
        val ax = fx - x0
        val ay = fy - y0
        fun ch(x: Int, y: Int, shift: Int) = ((img.getRGB(x, y) shr shift) and 0xFF).toDouble()
        fun mix(shift: Int) =
            (ch(x0, y0, shift) * (1 - ax) + ch(x0 + 1, y0, shift) * ax) * (1 - ay) +
                (ch(x0, y0 + 1, shift) * (1 - ax) + ch(x0 + 1, y0 + 1, shift) * ax) * ay
        return mix(16) to mix(8)
    }
}
