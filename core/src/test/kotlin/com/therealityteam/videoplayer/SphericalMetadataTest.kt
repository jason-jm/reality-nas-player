package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.model.StereoMode
import com.therealityteam.videoplayer.data.playback.ByteSource
import com.therealityteam.videoplayer.data.playback.SphericalInfo
import com.therealityteam.videoplayer.data.playback.SphericalMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SphericalMetadataTest {

    private class Bytes(private val b: ByteArray) : ByteSource {
        override val length: Long get() = b.size.toLong()
        override fun read(offset: Long, size: Int): ByteArray =
            if (offset >= b.size) ByteArray(0) else b.copyOfRange(offset.toInt(), minOf(b.size, offset.toInt() + size))
    }

    // ---- MP4 ----

    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val content = body.fold(ByteArray(0)) { a, x -> a + x }
        return be32(8 + content.size) + type.toByteArray(Charsets.ISO_8859_1) + content
    }

    private fun full(type: String, vararg body: ByteArray) = box(type, ByteArray(4), *body)
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be32(v: Long) = be32(v.toInt())
    private fun fourcc(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun frac(f: Double) = be32((f * 4294967296.0).toLong())

    /** A file with one video track whose sample entry holds [entryBoxes], and [trakBoxes] in its track. */
    private fun mp4(entryBoxes: List<ByteArray>, trakBoxes: List<ByteArray> = emptyList(), handler: String = "vide"): ByteArray {
        val avc1 = box("avc1", ByteArray(78), *entryBoxes.toTypedArray())
        val stsd = full("stsd", be32(1), avc1)
        val mdia = box("mdia", full("hdlr", ByteArray(4), fourcc(handler), ByteArray(12)), box("minf", box("stbl", stsd)))
        val trak = box("trak", *trakBoxes.toTypedArray(), mdia)
        return box("ftyp", fourcc("isom"), ByteArray(4)) + box("moov", trak) + box("mdat", ByteArray(32))
    }

    @Test
    fun v2EquirectangularStereoWithPose() {
        val sv3d = box(
            "sv3d",
            full("svhd", "Aura".toByteArray() + byteArrayOf(0)),
            box("proj", full("prhd", be32(90 * 65536), be32(-10 * 65536), be32(0)), full("equi", frac(0.0), frac(0.0), frac(0.0), frac(0.0))),
        )
        val info = SphericalMetadata.read(Bytes(mp4(listOf(full("st3d", byteArrayOf(1)), sv3d))))
        assertEquals(ProjectionMode.EQ360, info.projection)
        assertEquals(StereoMode.OU, info.stereo)
        assertEquals(90f, info.poseYaw, 1e-4f)
        assertEquals(-10f, info.posePitch, 1e-4f)
    }

    @Test
    fun v2BoundsMakeAVr180() {
        val sv3d = box("sv3d", box("proj", full("prhd", be32(0), be32(0), be32(0)), full("equi", frac(0.0), frac(0.0), frac(0.25), frac(0.25))))
        val info = SphericalMetadata.read(Bytes(mp4(listOf(full("st3d", byteArrayOf(2)), sv3d))))
        assertEquals(ProjectionMode.EQ180, info.projection)
        assertEquals(StereoMode.SBS, info.stereo)
        assertEquals(-90f, info.yawFrom, 1e-3f)
        assertEquals(90f, info.yawTo, 1e-3f)
        val layout = info.layout()!!
        assertEquals(-90f, layout.yawFrom, 1e-3f)
        assertEquals(StereoMode.SBS, layout.stereo)
    }

    @Test
    fun v2CubemapAndMesh() {
        val cube = box("sv3d", box("proj", full("prhd", be32(0), be32(0), be32(0)), full("cbmp", be32(0), be32(2))))
        val c = SphericalMetadata.read(Bytes(mp4(listOf(cube))))
        assertEquals(ProjectionMode.CUBEMAP, c.projection)
        assertEquals(2, c.cubePadding)
        val mesh = box("sv3d", box("proj", full("prhd", be32(0), be32(0), be32(0)), full("mshp", be32(0), ByteArray(8))))
        assertTrue(SphericalMetadata.read(Bytes(mp4(listOf(mesh)))).mesh)
    }

    @Test
    fun v1XmlInTheTrack() {
        val xml = """<?xml version="1.0"?><rdf:SphericalVideo xmlns:GSpherical="http://ns.google.com/videos/1.0/spherical/">
            <GSpherical:Spherical>true</GSpherical:Spherical><GSpherical:Stitched>true</GSpherical:Stitched>
            <GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType>
            <GSpherical:StereoMode>left-right</GSpherical:StereoMode>
            <GSpherical:CroppedAreaImageWidthPixels>2048</GSpherical:CroppedAreaImageWidthPixels>
            <GSpherical:CroppedAreaImageHeightPixels>2048</GSpherical:CroppedAreaImageHeightPixels>
            <GSpherical:FullPanoWidthPixels>4096</GSpherical:FullPanoWidthPixels>
            <GSpherical:FullPanoHeightPixels>2048</GSpherical:FullPanoHeightPixels>
            <GSpherical:CroppedAreaLeftPixels>1024</GSpherical:CroppedAreaLeftPixels>
            <GSpherical:CroppedAreaTopPixels>0</GSpherical:CroppedAreaTopPixels></rdf:SphericalVideo>"""
        val uuid = box("uuid", byteArrayOf(
            0xff.toByte(), 0xcc.toByte(), 0x82.toByte(), 0x63, 0xf8.toByte(), 0x55, 0x4a, 0x93.toByte(),
            0x88.toByte(), 0x14, 0x58, 0x7a, 0x02, 0x52, 0x1f, 0xdd.toByte(),
        ), xml.toByteArray())
        val info = SphericalMetadata.read(Bytes(mp4(emptyList(), trakBoxes = listOf(uuid))))
        assertEquals(ProjectionMode.EQ180, info.projection)
        assertEquals(StereoMode.SBS, info.stereo)
        assertEquals(-90f, info.yawFrom, 1e-3f)
        assertEquals(90f, info.yawTo, 1e-3f)
        assertEquals("uuid", info.from)
    }

    @Test
    fun appleHalfEquirectSideBySide() {
        val vexu = box(
            "vexu",
            box("eyes", full("stri", byteArrayOf(0x03))),
            box("proj", full("prji", fourcc("hequ"))),
            box("pack", full("pkin", fourcc("side"))),
        )
        val info = SphericalMetadata.read(Bytes(mp4(listOf(vexu))))
        assertEquals(ProjectionMode.EQ180, info.projection)
        assertEquals(StereoMode.SBS, info.stereo)
        assertFalse(info.multiview)
    }

    @Test
    fun appleSpatialVideoIsTwoViewsOfAFlatPicture() {
        val vexu = box("vexu", box("eyes", full("stri", byteArrayOf(0x03))))
        val info = SphericalMetadata.read(Bytes(mp4(listOf(vexu))))
        assertTrue(info.multiview)
        assertFalse(info.isSpherical)
    }

    @Test
    fun aSoundTrackIsNotLookedAt() {
        val sv3d = box("sv3d", box("proj", full("prhd", be32(0), be32(0), be32(0)), full("equi", frac(0.0), frac(0.0), frac(0.0), frac(0.0))))
        assertNull(SphericalMetadata.read(Bytes(mp4(listOf(sv3d), handler = "soun"))).projection)
    }

    /** A file with one sound track whose mp4a entry (QuickTime sound description [version]) holds [entryBoxes]. */
    private fun soundMp4(entryBoxes: List<ByteArray>, version: Int = 0): ByteArray {
        val fields = ByteArray(28 + when (version) { 1 -> 16; 2 -> 36; else -> 0 }).also { it[9] = version.toByte() }
        val mp4a = box("mp4a", fields, *entryBoxes.toTypedArray())
        val mdia = box("mdia", full("hdlr", ByteArray(4), fourcc("soun"), ByteArray(12)), box("minf", box("stbl", full("stsd", be32(1), mp4a))))
        return box("ftyp", fourcc("isom"), be32(0)) + box("moov", box("trak", mdia))
    }

    private fun sa3d(order: Int, channels: Int, headLocked: Boolean = false, normalization: Int = 0, map: List<Int> = List(channels) { it }) =
        box("SA3D", byteArrayOf(0, if (headLocked) 0x80.toByte() else 0), be32(order), byteArrayOf(0, normalization.toByte()), be32(channels), *map.map { be32(it) }.toTypedArray())

    @Test
    fun aSoundFieldIsMarkedInItsSampleEntry() {
        val foa = SphericalMetadata.read(Bytes(soundMp4(listOf(sa3d(1, 4))))).ambisonic!!
        assertEquals(1, foa.order)
        assertEquals(4, foa.channels)
        assertFalse(foa.headLocked)
        assertNull(foa.channelMap)
        assertEquals("SA3D", foa.from)
        // Head-locked stereo after the field; a QuickTime version 1 description; a channel map kept.
        val hl = SphericalMetadata.read(Bytes(soundMp4(listOf(sa3d(1, 6, headLocked = true, map = listOf(0, 1, 2, 3, 5, 4))), version = 1))).ambisonic!!
        assertTrue(hl.headLocked)
        assertEquals(listOf(0, 1, 2, 3, 5, 4), hl.channelMap)
        // FuMa normalisation, or channels that do not add up: not a field this reads right.
        assertNull(SphericalMetadata.read(Bytes(soundMp4(listOf(sa3d(1, 4, normalization = 1))))).ambisonic)
        assertNull(SphericalMetadata.read(Bytes(soundMp4(listOf(sa3d(1, 5))))).ambisonic)
        // Opus in MP4: dOps with channel mapping family 2.
        val dops = box("dOps", byteArrayOf(0, 9, 0, 0, 0, 0, 0xBB.toByte(), 0x80.toByte(), 0, 0, 2))
        assertEquals(2, SphericalMetadata.read(Bytes(soundMp4(listOf(dops)))).ambisonic!!.order)
    }

    @Test
    fun aPlainFileSaysNothing() {
        assertNull(SphericalMetadata.read(Bytes(mp4(emptyList()))).projection)
        assertNull(SphericalMetadata.read(Bytes(ByteArray(64))).projection)
    }

    // ---- Matroska ----

    private fun ebml(id: Long, vararg body: ByteArray): ByteArray {
        val content = body.fold(ByteArray(0)) { a, x -> a + x }
        val idBytes = ByteArrayOutputStream().apply {
            val n = when { id > 0xFFFFFF -> 4; id > 0xFFFF -> 3; id > 0xFF -> 2; else -> 1 }
            for (i in n - 1 downTo 0) write(((id ushr (8 * i)) and 0xFF).toInt())
        }.toByteArray()
        // An 8-byte size: 0x01 then seven bytes.
        val size = ByteArray(8).also { it[0] = 0x01; var v = content.size.toLong(); for (i in 7 downTo 1) { it[i] = (v and 0xFF).toByte(); v = v ushr 8 } }
        return idBytes + size + content
    }

    private fun uint(id: Long, v: Long) = ebml(id, byteArrayOf(v.toByte()))
    private fun float(id: Long, v: Float) = ebml(id, be32(java.lang.Float.floatToIntBits(v)))

    private fun mkv(video: ByteArray): ByteArray {
        val header = ebml(0x1A45DFA3L, uint(0x4286L, 1))
        val track = ebml(0xAEL, uint(0xD7L, 1), uint(0x83L, 1), video)
        val segment = ebml(0x18538067L, ebml(0x1654AE6BL, track))
        return header + segment
    }

    @Test
    fun matroskaStereoModeAndEquirectangularProjection() {
        val equi = ByteArray(4) + frac(0.0) + frac(0.0) + frac(0.25) + frac(0.25)
        val video = ebml(0xE0L, uint(0x53B8L, 1), ebml(0x7670L, uint(0x7671L, 1), ebml(0x7672L, equi), float(0x7673L, 30f)))
        val info = SphericalMetadata.read(Bytes(mkv(video)))
        assertEquals(ProjectionMode.EQ180, info.projection)
        assertEquals(StereoMode.SBS, info.stereo)
        assertEquals(30f, info.poseYaw, 1e-4f)
        assertFalse(info.eyesSwapped)
    }

    @Test
    fun matroskaRightEyeFirst() {
        val video = ebml(0xE0L, uint(0x53B8L, 11), ebml(0x7670L, uint(0x7671L, 1)))
        val info = SphericalMetadata.read(Bytes(mkv(video)))
        assertEquals(ProjectionMode.EQ360, info.projection)
        assertEquals(StereoMode.SBS, info.stereo)
        assertTrue(info.eyesSwapped)
        // Shown flat: both eyes the left eye's picture, from the frame's right half.
        val layout = info.layout()!!
        assertTrue(layout.eyesSwapped)
        assertEquals(StereoMode.MONO, layout.shownStereo)
        val us = com.therealityteam.videoplayer.data.playback.Panorama.mesh(layout, 1f).uvs.filterIndexed { i, _ -> i % 2 == 0 }
        assertTrue(us.all { it in 0.5f..1f })
        // The name's stereo word over a file that says nothing of its own: no swap read into it.
        assertFalse(SphericalInfo(projection = ProjectionMode.EQ360, eyesSwapped = true).layout(StereoMode.SBS)!!.eyesSwapped)
    }

    @Test
    fun overUnderRightOnTopShowsTheBottom() {
        val layout = SphericalInfo(projection = ProjectionMode.EQ360, stereo = StereoMode.OU, eyesSwapped = true).layout()!!
        val vs = com.therealityteam.videoplayer.data.playback.Panorama.mesh(layout, 1f).uvs.filterIndexed { i, _ -> i % 2 == 1 }
        assertTrue(vs.all { it in 0f..0.5f })
        assertEquals(StereoMode.MONO, layout.shownStereo)
        // Left first, as most are: left untouched.
        val plain = SphericalInfo(projection = ProjectionMode.EQ360, stereo = StereoMode.OU).layout()!!
        assertEquals(StereoMode.OU, plain.shownStereo)
        assertTrue(com.therealityteam.videoplayer.data.playback.Panorama.mesh(plain, 1f).uvs.filterIndexed { i, _ -> i % 2 == 1 }.any { it > 0.9f })
    }

    @Test
    fun matroskaCubemap() {
        val video = ebml(0xE0L, ebml(0x7670L, uint(0x7671L, 2), ebml(0x7672L, ByteArray(4) + be32(0) + be32(0))))
        assertEquals(ProjectionMode.CUBEMAP, SphericalMetadata.read(Bytes(mkv(video))).projection)
    }

    @Test
    fun matroskaFlatSaysNothing() {
        val video = ebml(0xE0L, uint(0xB0L, 1))
        val info = SphericalMetadata.read(Bytes(mkv(video)))
        assertNull(info.projection)
        assertNull(info.stereo)
    }

    // ---- real files: FFmpeg's (tools/dev/pano/inject-spherical.py, then a remux to Matroska) ----

    private fun file(name: String) = com.therealityteam.videoplayer.data.playback.FileByteSource(
        java.io.File(javaClass.getResource("/pano/$name")!!.toURI()),
    )

    @Test
    fun filesFfmpegWroteAreRead() {
        for (ext in listOf("mp4", "mkv")) {
            val tb = SphericalMetadata.read(file("tiny_eq_tb.$ext"))
            assertEquals("$ext tb", ProjectionMode.EQ360, tb.projection)
            assertEquals("$ext tb", StereoMode.OU, tb.stereo)
            assertEquals("$ext yaw", 90f, tb.poseYaw, 1e-3f)
            val vr180 = SphericalMetadata.read(file("tiny_vr180_sbs.$ext"))
            assertEquals("$ext vr180", ProjectionMode.EQ180, vr180.projection)
            assertEquals("$ext vr180", StereoMode.SBS, vr180.stereo)
            assertEquals("$ext vr180", -90f, vr180.yawFrom, 0.01f)
            assertEquals("$ext cube", ProjectionMode.CUBEMAP, SphericalMetadata.read(file("tiny_cube.$ext")).projection)
            // The moov before the media data (faststart), its chunk offsets moved by the injector.
            assertEquals("$ext faststart", ProjectionMode.EQ360, SphericalMetadata.read(file("tiny_fs_eq.$ext")).projection)
        }
        assertNull(SphericalMetadata.read(file("tiny_plain.mp4")).projection)
        assertNull(SphericalMetadata.read(file("tiny_plain.mp4")).ambisonic)
    }

    @Test
    fun soundFieldsFfmpegWroteAreRead() {
        // 4-channel AAC with Google's SA3D (inject-spherical.py --projection none --ambisonic 1).
        val mp4 = SphericalMetadata.read(file("tiny_ambi.mp4"))
        assertEquals(1, mp4.ambisonic?.order)
        assertEquals("SA3D", mp4.ambisonic?.from)
        // WebM: Opus, channel mapping family 2 (libopus -mapping_family 2).
        val webm = SphericalMetadata.read(file("tiny_ambi.webm"))
        assertEquals(4, webm.ambisonic?.channels)
        assertEquals("opus", webm.ambisonic?.from)
    }

    @Test
    fun whatTheLibraryLearns() {
        val vr180 = SphericalInfo(projection = ProjectionMode.EQ180, stereo = StereoMode.SBS)
        // A name that said nothing: the file's projection and eyes.
        val learned = vr180.learnedFlags(com.therealityteam.videoplayer.data.model.VideoFlags())!!
        assertEquals(ProjectionMode.EQ180, learned.projection)
        assertEquals(StereoMode.SBS, learned.stereo)
        assertTrue(learned.fromFile)
        // The viewer's choice stands; a flat file teaches nothing.
        assertNull(vr180.learnedFlags(com.therealityteam.videoplayer.data.model.VideoFlags(userOverridden = true)))
        assertNull(SphericalInfo().learnedFlags(com.therealityteam.videoplayer.data.model.VideoFlags()))
        // A sphere with no word on its eyes: the name's eyes, its right-eye-first and its lens stand.
        val named = com.therealityteam.videoplayer.data.model.VideoFlags(StereoMode.SBS, ProjectionMode.FISHEYE, lensFov = 200, rightFirst = true)
        val lens = SphericalInfo(projection = ProjectionMode.FISHEYE).learnedFlags(named)!!
        assertEquals(StereoMode.SBS, lens.stereo)
        assertTrue(lens.rightFirst)
        assertEquals(200, lens.lensFov)
        // The file's own eyes, and its word on which comes first, over the name's.
        val marked = SphericalInfo(projection = ProjectionMode.EQ360, stereo = StereoMode.OU, eyesSwapped = false).learnedFlags(named)!!
        assertEquals(StereoMode.OU, marked.stereo)
        assertFalse(marked.rightFirst)
        assertEquals(0, marked.lensFov)
        // A mesh of its own is a whole sphere in the library.
        assertEquals(ProjectionMode.EQ360, SphericalInfo(projection = ProjectionMode.EAC, mesh = true).learnedFlags(named)!!.projection)
    }

    @Test
    fun appleSpatialVideosAreRead() {
        // As Apple's own encoder writes MV-HEVC (tools/dev/pano/make-spatial.swift: VideoToolbox, the way an
        // iPhone's spatial video and Apple's projected media are made): vexu › eyes › stri, cams › blin,
        // proj › prji, and hfov beside it.
        val rect = SphericalMetadata.read(file("apple_mv_rect.mov"))
        assertTrue(rect.multiview)
        assertEquals(ProjectionMode.FLAT, rect.projection)
        assertFalse(rect.isSpherical)
        assertEquals(64f, rect.horizontalFov, 1e-3f)
        assertEquals(64f, rect.baselineMm, 1e-3f)
        val vr180 = SphericalMetadata.read(file("apple_mv_hequ.mov"))
        assertTrue(vr180.multiview)
        assertEquals(ProjectionMode.EQ180, vr180.projection)
        assertEquals(-90f, vr180.yawFrom, 1e-3f)
        assertEquals(90f, vr180.yawTo, 1e-3f)
        assertEquals(ProjectionMode.EQ360, SphericalMetadata.read(file("apple_mv_equi.mov")).projection)
        // The frame's size, from the sample entry.
        assertEquals(192, rect.frameWidth)
        assertEquals(108, rect.frameHeight)
        // Its frames are one eye's picture each: no packing, whatever a name said.
        assertEquals(StereoMode.MONO, vr180.layout(fallbackStereo = StereoMode.SBS)!!.stereo)
        val learned = vr180.learnedFlags(com.therealityteam.videoplayer.data.model.VideoFlags(StereoMode.SBS, ProjectionMode.EQ180, rightFirst = true))!!
        assertEquals(StereoMode.MONO, learned.stereo)
        assertFalse(learned.rightFirst)
    }

    @Test
    fun theFramesSizeSizesACubesFaces() {
        // Matroska's PixelWidth/PixelHeight and MP4's sample entry alike.
        for (ext in listOf("mp4", "mkv")) {
            val tb = SphericalMetadata.read(file("tiny_eq_tb.$ext"))
            assertTrue("$ext ${tb.frameWidth}x${tb.frameHeight}", tb.frameWidth > 0 && tb.frameHeight > 0)
        }
        // YouTube's 3D EAC, over-under: each eye's picture is half the frame's height.
        val eac = SphericalInfo(projection = ProjectionMode.EAC, stereo = StereoMode.OU, frameWidth = 3072, frameHeight = 4096).layout()!!
        assertEquals(3072, eac.frameWidth)
        assertEquals(2048, eac.frameHeight)
        // Named a cubemap, its header silent on the sphere but not on its size: the size still lays the faces out.
        val strip = com.therealityteam.videoplayer.data.playback.PanoLayout.forFilm(
            com.therealityteam.videoplayer.data.model.VideoFlags(), "hall_c6x1.mp4", SphericalInfo(frameWidth = 6144, frameHeight = 1024),
        )!!
        assertEquals(6144, strip.frameWidth)
        assertEquals(6 to 1, com.therealityteam.videoplayer.data.playback.Panorama.cubeGrid(strip))
    }

    @Test
    fun theStreamsOwnFramePacking() {
        // x264 --frame-packing 3 / 4: no box says so, the first access unit's SEI does.
        val sbs = SphericalMetadata.read(file("fpa_sbs.mp4"))
        assertEquals(StereoMode.SBS, sbs.stereo)
        assertEquals("sei", sbs.from)
        assertFalse(sbs.isSpherical)
        assertEquals(StereoMode.OU, SphericalMetadata.read(file("fpa_tb.mp4")).stereo)
        // A flat film the file marks as 3D is learned as one…
        val flat = sbs.learnedFlags(com.therealityteam.videoplayer.data.model.VideoFlags())!!
        assertEquals(StereoMode.SBS, flat.stereo)
        assertEquals(ProjectionMode.FLAT, flat.projection)
        assertTrue(flat.fromFile)
        // …a panorama by its name keeps its sphere and takes the eyes…
        val named = com.therealityteam.videoplayer.data.model.VideoFlags(StereoMode.MONO, ProjectionMode.EQ360)
        val eyes = SphericalInfo(stereo = StereoMode.OU).learnedFlags(named)!!
        assertEquals(ProjectionMode.EQ360, eyes.projection)
        assertEquals(StereoMode.OU, eyes.stereo)
        // …and a frame whose right eye comes first is left as it was (a window cannot swap the halves).
        assertNull(SphericalInfo(stereo = StereoMode.SBS, eyesSwapped = true).learnedFlags(com.therealityteam.videoplayer.data.model.VideoFlags()))
    }

    @Test
    fun aNamedPanoramaTakesTheFilesOwnEyes() {
        // Named a 360, its header saying side by side, right eye first (Matroska StereoMode 11): both heard.
        val l = com.therealityteam.videoplayer.data.playback.PanoLayout.forFilm(
            com.therealityteam.videoplayer.data.model.VideoFlags(), "harbour_vr360.mkv",
            SphericalInfo(stereo = StereoMode.SBS, eyesSwapped = true),
        )!!
        assertEquals(ProjectionMode.EQ360, l.projection)
        assertEquals(StereoMode.SBS, l.stereo)
        assertTrue(l.eyesSwapped)
        // A size not known keeps the header's.
        val sized = com.therealityteam.videoplayer.data.playback.PanoLayout.of(ProjectionMode.EAC).sized(3072, 2048)
        assertEquals(3072, sized.sized(0, 0).frameWidth)
    }
}
