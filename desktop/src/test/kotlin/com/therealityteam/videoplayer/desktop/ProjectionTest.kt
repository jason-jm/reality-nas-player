package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.model.StereoMode
import com.therealityteam.videoplayer.data.model.VideoFlags
import com.therealityteam.videoplayer.data.playback.PanoLayout
import com.therealityteam.videoplayer.desktop.player.PanoChoice
import com.therealityteam.videoplayer.desktop.player.PictureFormat
import com.therealityteam.videoplayer.desktop.player.Projection
import com.therealityteam.videoplayer.desktop.player.SphereView
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The layout rules for 3D and spherical files; the pictures themselves are checked by the probe. */
class ProjectionTest {

    @Test
    fun `one eye of each packing, at its true shape`() {
        // Half side-by-side: two squeezed pictures, so the frame is really 32:9 and the left eye 16:9.
        assertEquals(Projection.Eye("960x1080+0+0", 1920.0 / 1080 * 2), Projection.eye(StereoMode.SBS, 1920, 1080))
        // Full side-by-side: square pixels already.
        assertEquals(Projection.Eye("1920x1080+0+0", null), Projection.eye(StereoMode.SBS, 3840, 1080))
        // Half over-under: the frame is really 8:9, the top eye 16:9.
        assertEquals(Projection.Eye("1920x540+0+0", 1920.0 / 1080 / 2), Projection.eye(StereoMode.OU, 1920, 1080))
        assertEquals(Projection.Eye("1920x1080+0+0", null), Projection.eye(StereoMode.OU, 1920, 2160))
        assertNull(Projection.eye(StereoMode.MONO, 1920, 1080))
        // The right eye first: the left eye's picture is the second half.
        assertEquals(Projection.Eye("1920x1080+1920+0", null), Projection.eye(StereoMode.SBS, 3840, 1080, rightFirst = true))
        assertEquals(Projection.Eye("1920x1080+0+1080", null), Projection.eye(StereoMode.OU, 1920, 2160, rightFirst = true))
        assertNull(Projection.eye(StereoMode.SBS, 0, 0))
    }

    @Test
    fun `formats move as the headset's menu moves them`() {
        val vr180 = VideoFlags(StereoMode.SBS, ProjectionMode.EQ180)
        assertEquals(PictureFormat.EQ180, PictureFormat.of(vr180))
        assertEquals(VideoFlags(StereoMode.SBS, ProjectionMode.EQ180, userOverridden = true), PictureFormat.EQ180.flags(vr180))
        assertEquals(VideoFlags(StereoMode.MONO, ProjectionMode.EQ360, userOverridden = true), PictureFormat.EQ360.flags(vr180))
        assertEquals(PictureFormat.SBS, PictureFormat.of(VideoFlags(StereoMode.SBS, ProjectionMode.FLAT)))
        assertEquals(PictureFormat.FLAT_2D, PictureFormat.of(VideoFlags()))
        // A fisheye is a 180°, a cubemap or a 360 camera's pair a 360°, as the headset's 投影 tiles have them.
        assertEquals(PictureFormat.EQ180, PictureFormat.of(VideoFlags(StereoMode.SBS, ProjectionMode.FISHEYE)))
        assertEquals(PictureFormat.EQ360, PictureFormat.of(VideoFlags(StereoMode.MONO, ProjectionMode.EAC)))
        assertEquals(PictureFormat.EQ360, PictureFormat.of(VideoFlags(StereoMode.MONO, ProjectionMode.DUAL_FISHEYE)))
    }

    @Test
    fun `panorama formats are the headset's`() {
        assertEquals(
            listOf(ProjectionMode.EQ360, ProjectionMode.EAC, ProjectionMode.CUBEMAP, ProjectionMode.DUAL_FISHEYE),
            PanoChoice.of(ProjectionMode.CUBEMAP).map { it.projection },
        )
        val lenses = PanoChoice.of(ProjectionMode.FISHEYE)
        assertEquals(listOf(0, 180, 190, 200, 220), lenses.map { it.lens })
        // A choice keeps the eyes and is the viewer's word.
        val chosen = PanoChoice(ProjectionMode.FISHEYE, 200).flags(VideoFlags(StereoMode.SBS, ProjectionMode.EQ180))
        assertEquals(VideoFlags(StereoMode.SBS, ProjectionMode.FISHEYE, userOverridden = true, lensFov = 200), chosen)
        val l = PanoLayout.of(ProjectionMode.FISHEYE, StereoMode.SBS, 200f)
        assertTrue(PanoChoice(ProjectionMode.FISHEYE, 200).matches(l))
        assertTrue(!PanoChoice(ProjectionMode.FISHEYE, 190).matches(l))
    }

    /** The constants a sphere shader was written with, by name — a vector's or a matrix's as its list. */
    private fun constants(text: String): Map<String, String> =
        Regex("""const (?:float|vec2|vec4|mat3) (aura_\w+) = (?:\w+\()?([^);\n]*)\)?;""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `each projection writes its own sphere shader`() {
        val modes = mapOf(
            ProjectionMode.EQ360 to "0.0", ProjectionMode.EQ180 to "0.0", ProjectionMode.FISHEYE to "1.0",
            ProjectionMode.DUAL_FISHEYE to "2.0", ProjectionMode.CUBEMAP to "3.0", ProjectionMode.EAC to "4.0",
        )
        for ((p, mode) in modes) {
            val text = Projection.sphereShader(PanoLayout.of(p), 3072, 2048)
            assertTrue(!text.contains("{{"), "$p: every value written in")
            assertEquals(mode, constants(text)["aura_mode"], "$p")
        }
        // A VR180's coverage, in radians.
        val vr180 = constants(Projection.sphereShader(PanoLayout.of(ProjectionMode.EQ180, StereoMode.SBS)))
        val bounds = vr180.getValue("aura_bounds").split(',').map { it.trim().toDouble() }
        listOf(-PI / 2, PI / 2, -PI / 2, PI / 2).zip(bounds).forEach { (want, got) -> assertEquals(want, got, 1e-5) }
        assertEquals("1.0", vr180["aura_stereo"])
        // A lens of 200°: its half-angle.
        val lens = constants(Projection.sphereShader(PanoLayout.of(ProjectionMode.FISHEYE, lensFov = 200f)))
        assertEquals(Math.toRadians(100.0), lens.getValue("aura_lens").toDouble(), 1e-5)
        // Cubemap strips by the eye's shape: a 6×1 strip side by side is still a strip per eye.
        assertEquals("6.0, 1.0", constants(Projection.sphereShader(PanoLayout.of(ProjectionMode.CUBEMAP), 6144, 1024))["aura_grid"])
        assertEquals("1.0, 6.0", constants(Projection.sphereShader(PanoLayout.of(ProjectionMode.CUBEMAP), 1024, 6144))["aura_grid"])
        assertEquals("6.0, 1.0", constants(Projection.sphereShader(PanoLayout.of(ProjectionMode.CUBEMAP, StereoMode.SBS), 12288, 1024))["aura_grid"])
        assertEquals("3.0, 2.0", constants(Projection.sphereShader(PanoLayout.of(ProjectionMode.CUBEMAP)))["aura_grid"])
        // EAC's 2-pixel border, a part of one eye's picture (over-under: half the frame's height).
        val eac = constants(Projection.sphereShader(PanoLayout.of(ProjectionMode.EAC, StereoMode.OU), 3072, 4096))
        val (pu, pv) = eac.getValue("aura_pad").split(',').map { it.trim().toDouble() }
        assertEquals(2.0 / 3072, pu, 1e-6)
        assertEquals(2.0 / 2048, pv, 1e-6)
        // A cubemap's padding: 8 of a 256-pixel face.
        val padded = constants(Projection.sphereShader(PanoLayout.of(ProjectionMode.CUBEMAP).copy(cubePadding = 8), 768, 512))
        val (cu, cv) = padded.getValue("aura_pad").split(',').map { it.trim().toDouble() }
        assertEquals(8.0 / 256, cu, 1e-6)
        assertEquals(8.0 / 256, cv, 1e-6)
        assertEquals("0.0, 0.0", constants(Projection.sphereShader(PanoLayout.of(ProjectionMode.EQ360), 2048, 1024))["aura_pad"])
        // The right eye first: its picture is the second half.
        assertEquals("1.0", constants(Projection.sphereShader(PanoLayout.of(ProjectionMode.EQ360, StereoMode.SBS).copy(eyesSwapped = true)))["aura_swap"])
        assertEquals("0.0", constants(Projection.sphereShader(PanoLayout.of(ProjectionMode.EQ360).copy(eyesSwapped = true)))["aura_swap"])
        // Two layouts, two texts: mpv reads a path once, so each is named by its text.
        assertTrue(Projection.sphereShader(PanoLayout.of(ProjectionMode.EQ360)) != Projection.sphereShader(PanoLayout.of(ProjectionMode.EAC)))
    }

    @Test
    fun `a pose turns the sphere as the headset turns it`() {
        val id = Projection.poseMatrix(Projection.NO_POSE)
        assertEquals(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), id.map { Math.round(it * 1e9) / 1e9 + 0.0 })
        // Yaw 90° (counter-clockwise from above): the picture's front, (0, 0, −1), is turned to the left.
        val m = Projection.poseMatrix(floatArrayOf(90f, 0f, 0f))
        fun turn(v: DoubleArray) = DoubleArray(3) { i -> (0 until 3).sumOf { k -> m[i * 3 + k] * v[k] } }
        val front = turn(doubleArrayOf(0.0, 0.0, -1.0))
        assertEquals(-1.0, front[0], 1e-9)
        assertEquals(0.0, front[2], 1e-9)
    }

    @Test
    fun `a view is named by where it looks, a quarter degree at a time`() {
        val (a, textA) = Projection.viewShader(SphereView(0.1, 0.2), 16.0 / 9)
        val (b, _) = Projection.viewShader(SphereView(0.1 + 1e-5, 0.2), 16.0 / 9)
        val (c, _) = Projection.viewShader(SphereView(0.1 + Math.toRadians(0.5), 0.2), 16.0 / 9)
        assertEquals(a, b, "the same quarter degree is the same file")
        assertTrue(a != c)
        assertTrue(textA.contains("//!SAVE AURAVIEW") && textA.length < 400)
        // Every value the texture carries is between 0 and 1, whatever the view.
        val (_, t) = Projection.viewShader(SphereView(-3.0, -1.4, Projection.MAX_FOV), 3.5)
        Regex("""vec4\(([^)]*)\)""").find(t)!!.groupValues[1].split(',').map { it.trim().toDouble() }
            .forEach { assertTrue(it in 0.0..1.0, "$it out of range") }
        assertEquals(PI - 0.2, Projection.wrap(-PI - 0.2), 1e-9)
    }

    @Test
    fun `a sound field's graph takes its components where its file put them`() {
        val foa = com.therealityteam.videoplayer.data.playback.AmbisonicAudio(order = 1, channels = 4)
        val g = com.therealityteam.videoplayer.data.playback.SoundFieldFilter.graph(foa)!!
        // W, Y and X (ACN 0, 1, 3); Z (2) is left out.
        assertTrue(g.contains("pan=mono|c0=c0,") && g.contains("pan=mono|c0=c1,") && g.contains("pan=mono|c0=c3,"))
        assertTrue(!g.contains("c0=c2"))
        assertTrue(g.endsWith("join=inputs=2:channel_layout=stereo"))
        // SA3D's channel map moves them; a head-locked pair is mixed in as it is.
        val mapped = com.therealityteam.videoplayer.data.playback.SoundFieldFilter.graph(foa.copy(channelMap = listOf(3, 2, 1, 0)))!!
        assertTrue(mapped.contains("[fw]pan=mono|c0=c3,") && mapped.contains("[fx]pan=mono|c0=c0,"))
        val locked = com.therealityteam.videoplayer.data.playback.SoundFieldFilter.graph(foa.copy(channels = 6, headLocked = true))!!
        assertTrue(locked.contains("pan=stereo|c0=c4|c1=c5"))
        // Too few channels for its order: no graph.
        kotlin.test.assertNull(com.therealityteam.videoplayer.data.playback.SoundFieldFilter.graph(foa.copy(order = 2)))
        // Looking right a quarter turn: the field's front (X) is the left-right axis.
        val (onY, onX) = com.therealityteam.videoplayer.data.playback.SoundFieldFilter.turn(PI / 2)
        assertEquals(0.0, onY, 1e-9)
        assertEquals(1.0, onX, 1e-9)
    }
}
