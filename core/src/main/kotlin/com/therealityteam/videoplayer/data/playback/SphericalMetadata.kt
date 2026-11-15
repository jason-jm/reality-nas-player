package com.therealityteam.videoplayer.data.playback

import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.model.StereoMode
import com.therealityteam.videoplayer.data.model.VideoFlags

/**
 * What a video file says of itself as a 360°/180° picture — read from its header, never guessed:
 * - MP4/MOV: Google's Spherical Video V2 (`st3d`, `sv3d` › `proj` › `prhd` + `equi`/`cbmp`/`mshp`) in
 *   the video sample entry, the older V1 (an XML `uuid` box in the track), and Apple's `vexu`
 *   (visionOS: `proj` › `prji` equi/hequ/fish, `pack` › `pkin` side/over, `eyes` › `stri`);
 * - Matroska/WebM: the video track's StereoMode and Projection (type, private data, pose).
 * And whether its sound is a sound field ([AmbisonicAudio]): an audio track's `SA3D` or Opus `dOps`
 * (MP4), or its Opus header (Matroska) — channel mapping family 2.
 */
data class SphericalInfo(
    val projection: ProjectionMode? = null,
    val stereo: StereoMode? = null,
    /** The right eye's picture comes first (Matroska StereoMode 2 and 11, Apple's reversed views). */
    val eyesSwapped: Boolean = false,
    /** Equirectangular coverage, degrees, when the file says (equi bounds, V1 cropped area). */
    val yawFrom: Float = -180f,
    val yawTo: Float = 180f,
    val pitchFrom: Float = -90f,
    val pitchTo: Float = 90f,
    /** The picture's own orientation, degrees (prhd / ProjectionPose). */
    val poseYaw: Float = 0f,
    val posePitch: Float = 0f,
    val poseRoll: Float = 0f,
    /** Cubemap face padding, pixels (cbmp). */
    val cubePadding: Int = 0,
    /** A mesh projection (`mshp`): the picture's own mesh, not one of the shapes made here. */
    val mesh: Boolean = false,
    /** The mesh projection's box body, as written ([MeshProjection.parse] reads it). */
    val meshBody: ByteArray? = null,
    /**
     * Two views coded apart (MV-HEVC: Apple's and PICO 4 Ultra's spatial video, Apple's projected media), not
     * packed in one frame: a frame is one eye's whole picture — the base layer's, the left eye's.
     */
    val multiview: Boolean = false,
    /** A spatial video's camera, as it says (`hfov`, `vexu › eyes › cams › blin`): its view across, degrees, and its lenses' distance apart, millimetres; 0 unsaid. */
    val horizontalFov: Float = 0f,
    val baselineMm: Float = 0f,
    /** The video track's frame, pixels, as its header says (0 unsaid): how a cubemap's faces lie, EAC's border. */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** Which of the markings said so: "sv3d", "st3d", "uuid", "vexu", "matroska". */
    val from: String = "",
    /** An audio track that is a sound field round the camera (SA3D, Opus family 2). */
    val ambisonic: AmbisonicAudio? = null,
) {
    val isSpherical: Boolean get() = projection != null && projection != ProjectionMode.FLAT

    /**
     * The library's flags for a file that says this of itself — its projection, and its eyes where it
     * marks them; for a flat film its stereo packing — as [VideoFlags.fromFile], which a rescan of its
     * name keeps. Its flags now ([current])
     * stand for what it does not say: the eyes and which comes first, a fisheye lens's angle. Null when
     * it says nothing of a sphere, or the viewer chose its format (their word stands).
     */
    fun learnedFlags(current: VideoFlags): VideoFlags? {
        if (current.userOverridden) return null
        if (!isSpherical) {
            // Stereo by the file's own marking (st3d, Matroska StereoMode, vexu's packing, the stream's
            // frame packing SEI), and no sphere: a 3D film for the window — or, where the name made it a
            // panorama, that panorama's eyes. Not a frame whose right eye comes first (a window cannot
            // hand the eyes their halves the other way round), nor two coded views (one picture a frame).
            val packed = stereo?.takeIf { (it == StereoMode.SBS || it == StereoMode.OU) && !multiview && !eyesSwapped } ?: return null
            return when {
                current.projection in PanoLayout.SPHERICAL -> current.copy(stereo = packed).takeIf { it != current }
                else -> VideoFlags(packed, ProjectionMode.FLAT, fromFile = true)
            }
        }
        val p = if (mesh) ProjectionMode.EQ360 else projection!!
        val eyes = when {
            // Its views coded apart: each frame one eye's picture, not two packed.
            multiview -> StereoMode.MONO
            else -> stereo ?: current.stereo.takeIf { it == StereoMode.SBS || it == StereoMode.OU } ?: StereoMode.MONO
        }
        return VideoFlags(
            stereo = eyes,
            projection = p,
            lensFov = if (p == ProjectionMode.FISHEYE || p == ProjectionMode.DUAL_FISHEYE) current.lensFov else 0,
            rightFirst = eyes != StereoMode.MONO && if (stereo != null) eyesSwapped else current.rightFirst,
            fromFile = true,
        )
    }

    /**
     * The layout these say, for a frame of [width] × [height] (0 when unknown). A file's own mesh is
     * read here; one that cannot be read is taken for YouTube's EAC, the mesh projections met.
     */
    fun layout(fallbackStereo: StereoMode = StereoMode.MONO, width: Int = 0, height: Int = 0): PanoLayout? {
        val p = projection ?: return null
        // Two coded views: the frame is one eye's picture, whatever a name said of its packing.
        val eyes = if (multiview) StereoMode.MONO else stereo ?: fallbackStereo
        // The file's own word on which eye comes first holds only for its own stereo marking.
        val swapped = eyesSwapped && stereo != null && eyes != StereoMode.MONO
        val w = if (width > 0) width else frameWidth
        val h = if (height > 0) height else frameHeight
        if (mesh) {
            val own = meshBody?.let { MeshProjection.parse(it) }?.firstOrNull()
            return if (own != null) PanoLayout(ProjectionMode.EQ360, eyes, ownMesh = own, eyesSwapped = swapped)
            else PanoLayout.of(ProjectionMode.EAC, eyes).copy(eyesSwapped = swapped).sized(w, h)
        }
        val base = PanoLayout.of(p, eyes).copy(eyesSwapped = swapped)
        return when (p) {
            ProjectionMode.EQ360, ProjectionMode.EQ180 ->
                base.copy(yawFrom = yawFrom, yawTo = yawTo, pitchFrom = pitchFrom, pitchTo = pitchTo)
            ProjectionMode.CUBEMAP -> base.copy(cubePadding = cubePadding).sized(w, h)
            else -> base.sized(w, h)
        }
    }

}

object SphericalMetadata {

    /** What the file at [source] says, or an empty [SphericalInfo]. Reads its header only. */
    fun read(source: ByteSource): SphericalInfo = runCatching {
        val head = source.read(0, 12)
        when {
            head.size >= 4 && (head[0].toInt() and 0xFF) == 0x1A && (head[1].toInt() and 0xFF) == 0x45 &&
                (head[2].toInt() and 0xFF) == 0xDF && (head[3].toInt() and 0xFF) == 0xA3 -> readMatroska(source)
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in MP4_FIRST -> readMp4(source)
            else -> SphericalInfo()
        }
    }.getOrDefault(SphericalInfo())

    // ---- MP4 ------------------------------------------------------------------------------------

    private val MP4_FIRST = setOf("ftyp", "moov", "mdat", "free", "wide", "skip", "pnot")

    private class Box(val type: String, val start: Long, val payload: Long, val end: Long)

    /** The boxes between [from] and [to], their headers only. */
    private fun boxes(source: ByteSource, from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var offset = from
        while (offset + 8 <= to && out.size < 4096) {
            val h = source.read(offset, 16)
            if (h.size < 8) break
            var size = u32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) {
                if (h.size < 16) break
                size = u64(h, 8)
                header = 16L
            } else if (size == 0L) {
                size = to - offset
            }
            if (size < header) break
            out += Box(type, offset, offset + header, minOf(offset + size, to))
            offset += size
        }
        return out
    }

    private fun child(source: ByteSource, parent: Box, type: String): Box? =
        boxes(source, parent.payload, parent.end).firstOrNull { it.type == type }

    private fun readMp4(source: ByteSource): SphericalInfo {
        val moov = boxes(source, 0, source.length).firstOrNull { it.type == "moov" } ?: return SphericalInfo()
        var picture: SphericalInfo? = null
        var sound: AmbisonicAudio? = null
        for (trak in boxes(source, moov.payload, moov.end).filter { it.type == "trak" }) {
            val trakChildren = boxes(source, trak.payload, trak.end)
            val mdia = trakChildren.firstOrNull { it.type == "mdia" } ?: continue
            val hdlr = child(source, mdia, "hdlr") ?: continue
            val handler = String(source.read(hdlr.payload + 8, 4), Charsets.ISO_8859_1)
            val stbl = child(source, mdia, "minf")?.let { child(source, it, "stbl") }
            val stsd = stbl?.let { child(source, it, "stsd") }
            val entry = stsd?.let { boxes(source, it.payload + 8, it.end).firstOrNull() }
            if (handler == "soun") {
                if (sound == null && entry != null) sound = audioEntry(source, entry)
                continue
            }
            if (handler != "vide" || picture != null) continue
            var info = SphericalInfo()
            // V2 and Apple's, in the sample entry — and the frame's size, a visual sample entry's fields.
            if (entry != null) {
                info = sampleEntry(source, entry)
                val size = source.read(entry.payload + 24, 4)
                if (size.size == 4) {
                    info = info.copy(
                        frameWidth = ((size[0].toInt() and 0xFF) shl 8) or (size[1].toInt() and 0xFF),
                        frameHeight = ((size[2].toInt() and 0xFF) shl 8) or (size[3].toInt() and 0xFF),
                    )
                }
            }
            // No stereo marking in the container: the stream's own frame packing SEI, in its first access unit.
            if (info.stereo == null && entry != null && stbl != null) {
                framePacking(source, stbl, entry)?.let { fp ->
                    info = info.copy(stereo = fp.stereo, eyesSwapped = fp.rightFirst, from = info.from.ifEmpty { "sei" })
                }
            }
            // V1: an RDF/XML description in a uuid box of the track.
            if (!info.isSpherical) {
                trakChildren.filter { it.type == "uuid" }.forEach { u ->
                    val body = source.read(u.payload, (u.end - u.payload).toInt().coerceAtMost(64 * 1024))
                    if (body.size > 16 && body.copyOfRange(0, 16).contentEquals(V1_UUID)) {
                        v1(String(body, 16, body.size - 16, Charsets.UTF_8))?.let { v1 -> info = merge(info, v1) }
                    }
                }
            }
            picture = info
        }
        return (picture ?: SphericalInfo()).copy(ambisonic = sound)
    }

    /**
     * An audio sample entry's sound field: Google's `SA3D` (periphonic, ACN, SN3D only — the others
     * would be decoded wrong), or Opus's `dOps` with channel mapping family 2. Its own boxes start
     * after 28 bytes of fields, 16 or 36 more for QuickTime's sound description versions 1 and 2.
     */
    private fun audioEntry(source: ByteSource, entry: Box): AmbisonicAudio? {
        val fields = source.read(entry.payload, 10)
        if (fields.size < 10) return null
        val version = ((fields[8].toInt() and 0xFF) shl 8) or (fields[9].toInt() and 0xFF)
        val skip = when (version) { 1 -> 44L; 2 -> 64L; else -> 28L }
        for (b in boxes(source, entry.payload + skip, entry.end)) when (b.type) {
            "SA3D" -> {
                val body = source.read(b.payload, (b.end - b.payload).toInt().coerceAtMost(12 + 4 * 64))
                if (body.size < 12) continue
                val headLocked = body[1].toInt() and 0x80 != 0
                val type = body[1].toInt() and 0x7F
                val order = u32(body, 2).toInt()
                val ordering = body[6].toInt() and 0xFF
                val normalization = body[7].toInt() and 0xFF
                val channels = u32(body, 8).toInt()
                if (type != 0 || ordering != 0 || normalization != 0 || order !in 1..14) continue
                if (channels != (order + 1) * (order + 1) + if (headLocked) 2 else 0) continue
                val map = if (body.size >= 12 + 4 * channels) List(channels) { u32(body, 12 + 4 * it).toInt() } else null
                return AmbisonicAudio(order, channels, headLocked, map?.takeIf { m -> m.indices.any { m[it] != it } }, "SA3D")
            }
            "dOps" -> {
                val body = source.read(b.payload, 11)
                if (body.size >= 11 && body[10].toInt() and 0xFF == 2) {
                    return AmbisonicAudio.ofChannels(body[1].toInt() and 0xFF, "opus")
                }
            }
        }
        return null
    }

    /** A visual sample entry's own boxes start after its 78 bytes of fields. */
    private fun sampleEntry(source: ByteSource, entry: Box): SphericalInfo {
        var info = SphericalInfo()
        val children = boxes(source, entry.payload + 78, entry.end)
        children.firstOrNull { it.type == "st3d" }?.let { st3d ->
            val b = source.read(st3d.payload, 5)
            if (b.size >= 5) {
                val stereo = when (b[4].toInt() and 0xFF) {
                    1 -> StereoMode.OU
                    2 -> StereoMode.SBS
                    else -> StereoMode.MONO
                }
                info = info.copy(stereo = stereo, from = "st3d")
            }
        }
        children.firstOrNull { it.type == "sv3d" }?.let { sv3d ->
            val proj = child(source, sv3d, "proj") ?: return@let
            for (b in boxes(source, proj.payload, proj.end)) {
                val body = source.read(b.payload, (b.end - b.payload).toInt().coerceAtMost(64))
                when (b.type) {
                    "prhd" -> if (body.size >= 16) info = info.copy(
                        poseYaw = fixed16(body, 4), posePitch = fixed16(body, 8), poseRoll = fixed16(body, 12),
                    )
                    "equi" -> info = withEqui(info.copy(from = "sv3d"), body)
                    "cbmp" -> if (body.size >= 12) {
                        val layout = u32(body, 4)
                        info = info.copy(
                            projection = if (layout == 0L) ProjectionMode.CUBEMAP else null,
                            cubePadding = u32(body, 8).toInt(), from = "sv3d",
                        )
                    }
                    "mshp" -> info = info.copy(
                        projection = ProjectionMode.EQ360, mesh = true, from = "sv3d",
                        meshBody = source.read(b.payload, (b.end - b.payload).toInt().coerceAtMost(MAX_MESH_BOX)),
                    )
                }
            }
        }
        children.firstOrNull { it.type == "vexu" }?.let { vexu -> info = merge(info, vexu(source, vexu)) }
        // Beside it, Apple's spatial video says its view across, thousandths of a degree.
        children.firstOrNull { it.type == "hfov" }?.let { hfov ->
            val v = source.read(hfov.payload, 4)
            if (v.size >= 4) info = info.copy(horizontalFov = u32(v, 0) / 1000f)
        }
        return info
    }

    /**
     * The frame packing an H.264 / H.265 track's first access unit announces (FramePacking): the first
     * sample, from the sample sizes (stsz) and the first chunk's offset (stco / co64), its NAL units'
     * length size from the decoder configuration (avcC / hvcC).
     */
    private fun framePacking(source: ByteSource, stbl: Box, entry: Box): FramePacking.Packing? {
        val hevc = entry.type == "hvc1" || entry.type == "hev1"
        if (!hevc && entry.type != "avc1" && entry.type != "avc3") return null
        val config = boxes(source, entry.payload + 78, entry.end).firstOrNull { it.type == (if (hevc) "hvcC" else "avcC") } ?: return null
        val lengthByte = source.read(config.payload + (if (hevc) 21 else 4), 1).firstOrNull() ?: return null
        val lengthSize = (lengthByte.toInt() and 0x03) + 1
        val stsz = child(source, stbl, "stsz") ?: return null
        val sz = source.read(stsz.payload, 16)
        // A fragmented file's sample table is empty: its samples are in the fragments, not looked for here.
        if (sz.size < 12 || u32(sz, 8) == 0L) return null
        val size = u32(sz, 4).takeIf { it > 0 } ?: if (sz.size >= 16) u32(sz, 12) else return null
        fun firstOffset(box: Box, wide: Boolean): Long? {
            val b = source.read(box.payload + 4, if (wide) 12 else 8)
            if (b.size < (if (wide) 12 else 8) || u32(b, 0) == 0L) return null
            return if (wide) u64(b, 4) else u32(b, 4)
        }
        val offset = child(source, stbl, "stco")?.let { firstOffset(it, false) }
            ?: child(source, stbl, "co64")?.let { firstOffset(it, true) }
            ?: return null
        if (size <= 0) return null
        val sample = source.read(offset, size.coerceAtMost(MAX_FIRST_SAMPLE.toLong()).toInt())
        return FramePacking.inSample(sample, lengthSize, hevc)
    }

    /** Apple's Video Extended Usage box. */
    private fun vexu(source: ByteSource, vexu: Box): SphericalInfo {
        var info = SphericalInfo(from = "vexu")
        for (b in boxes(source, vexu.payload, vexu.end)) when (b.type) {
            "proj" -> child(source, b, "prji")?.let { prji ->
                val kind = String(source.read(prji.payload + 4, 4), Charsets.ISO_8859_1)
                val p = when (kind) {
                    "equi" -> ProjectionMode.EQ360
                    "hequ" -> ProjectionMode.EQ180
                    "fish" -> ProjectionMode.FISHEYE
                    "rect" -> ProjectionMode.FLAT
                    else -> null
                }
                info = if (p == ProjectionMode.EQ180) info.copy(projection = p, yawFrom = -90f, yawTo = 90f) else info.copy(projection = p)
            }
            "pack" -> child(source, b, "pkin")?.let { pkin ->
                val kind = String(source.read(pkin.payload + 4, 4), Charsets.ISO_8859_1)
                info = info.copy(stereo = if (kind == "over") StereoMode.OU else StereoMode.SBS)
            }
            "eyes" -> {
                child(source, b, "stri")?.let { stri ->
                    val flags = source.read(stri.payload + 4, 1).firstOrNull()?.toInt()?.and(0xFF) ?: 0
                    val both = flags and 0x03 == 0x03
                    info = info.copy(eyesSwapped = flags and 0x08 != 0, multiview = both)
                }
                // The lenses' distance apart, micrometres (cams › blin, a full box's u32).
                child(source, b, "cams")?.let { child(source, it, "blin") }?.let { blin ->
                    val v = source.read(blin.payload, 8)
                    if (v.size >= 8) info = info.copy(baselineMm = u32(v, 4) / 1000f)
                }
            }
        }
        // Packed views are one frame, not two.
        return if (info.stereo != null) info.copy(multiview = false) else info
    }

    /** The equi box (version, flags, then bounds top, bottom, left, right as 0.32 fractions). */
    private fun withEqui(info: SphericalInfo, body: ByteArray): SphericalInfo {
        if (body.size < 20) return info.copy(projection = ProjectionMode.EQ360)
        val top = frac32(body, 4)
        val bottom = frac32(body, 8)
        val left = frac32(body, 12)
        val right = frac32(body, 16)
        val yawFrom = -180f + left * 360f
        val yawTo = 180f - right * 360f
        val pitchTo = 90f - top * 180f
        val pitchFrom = -90f + bottom * 180f
        if (yawTo - yawFrom < 1f || pitchTo - pitchFrom < 1f) return info.copy(projection = ProjectionMode.EQ360)
        return info.copy(
            projection = if (yawTo - yawFrom >= 359f) ProjectionMode.EQ360 else ProjectionMode.EQ180,
            yawFrom = yawFrom, yawTo = yawTo, pitchFrom = pitchFrom, pitchTo = pitchTo,
        )
    }

    /** Spherical Video V1: GSpherical's XML. Its cropped area says how much of the sphere the picture is. */
    internal fun v1(xml: String): SphericalInfo? {
        fun tag(name: String) = Regex("<GSpherical:$name>\\s*([^<]*?)\\s*</GSpherical:$name>").find(xml)?.groupValues?.get(1)
        if (tag("Spherical")?.equals("true", ignoreCase = true) != true) return null
        if (tag("ProjectionType")?.lowercase()?.let { it == "equirectangular" } == false) return null
        val stereo = when (tag("StereoMode")?.lowercase()) {
            "top-bottom" -> StereoMode.OU
            "left-right" -> StereoMode.SBS
            "mono" -> StereoMode.MONO
            else -> null
        }
        val fullW = tag("FullPanoWidthPixels")?.toFloatOrNull()
        val fullH = tag("FullPanoHeightPixels")?.toFloatOrNull()
        val w = tag("CroppedAreaImageWidthPixels")?.toFloatOrNull()
        val h = tag("CroppedAreaImageHeightPixels")?.toFloatOrNull()
        val left = tag("CroppedAreaLeftPixels")?.toFloatOrNull() ?: 0f
        val top = tag("CroppedAreaTopPixels")?.toFloatOrNull() ?: 0f
        var info = SphericalInfo(projection = ProjectionMode.EQ360, stereo = stereo, from = "uuid")
        if (fullW != null && fullH != null && w != null && h != null && fullW > 0 && fullH > 0) {
            val yawFrom = -180f + left / fullW * 360f
            val yawTo = yawFrom + w / fullW * 360f
            val pitchTo = 90f - top / fullH * 180f
            val pitchFrom = pitchTo - h / fullH * 180f
            info = info.copy(
                projection = if (yawTo - yawFrom >= 359f) ProjectionMode.EQ360 else ProjectionMode.EQ180,
                yawFrom = yawFrom, yawTo = yawTo, pitchFrom = pitchFrom, pitchTo = pitchTo,
            )
        }
        tag("InitialViewHeadingDegrees")?.toFloatOrNull()?.let { info = info.copy(poseYaw = it) }
        return info
    }

    /** [b]'s findings where [a] has none. */
    private fun merge(a: SphericalInfo, b: SphericalInfo): SphericalInfo {
        val m = if (a.projection != null) a.copy(stereo = a.stereo ?: b.stereo) else b.copy(stereo = b.stereo ?: a.stereo)
        return m.copy(
            eyesSwapped = a.eyesSwapped || b.eyesSwapped, multiview = a.multiview || b.multiview,
            horizontalFov = maxOf(a.horizontalFov, b.horizontalFov), baselineMm = maxOf(a.baselineMm, b.baselineMm),
            frameWidth = maxOf(a.frameWidth, b.frameWidth), frameHeight = maxOf(a.frameHeight, b.frameHeight),
        )
    }

    // ---- Matroska -------------------------------------------------------------------------------

    private fun readMatroska(source: ByteSource): SphericalInfo {
        val tracks = ChapterReader.topLevel(source, ID_TRACKS) ?: return SphericalInfo()
        var picture: SphericalInfo? = null
        var sound: AmbisonicAudio? = null
        var offset = tracks.dataOffset
        while (offset < tracks.end) {
            val entry = ChapterReader.element(source, offset) ?: break
            if (entry.id == ID_TRACK_ENTRY) {
                var type = 0L
                var video: ChapterReader.Element? = null
                var codec = ""
                var private: ChapterReader.Element? = null
                var inner = entry.dataOffset
                while (inner < entry.end) {
                    val f = ChapterReader.element(source, inner) ?: break
                    when (f.id) {
                        ID_TRACK_TYPE -> type = ChapterReader.readUInt(source, f)
                        ID_VIDEO -> video = f
                        ID_CODEC_ID -> codec = String(source.read(f.dataOffset, f.dataSize.toInt().coerceIn(0, 32)), Charsets.ISO_8859_1).trimEnd('\u0000')
                        ID_CODEC_PRIVATE -> private = f
                    }
                    if (f.unknownSize) break
                    inner = f.end
                }
                if (type == 1L && video != null && picture == null) picture = matroskaVideo(source, video)
                if (type == 2L && codec == "A_OPUS" && sound == null && private != null) {
                    // OpusHead: magic, version, channels (9), pre-skip, rate, gain, mapping family (18).
                    val head = source.read(private.dataOffset, 19)
                    if (head.size >= 19 && String(head, 0, 8, Charsets.ISO_8859_1) == "OpusHead" && head[18].toInt() and 0xFF == 2) {
                        sound = AmbisonicAudio.ofChannels(head[9].toInt() and 0xFF, "opus")
                    }
                }
            }
            if (entry.unknownSize) break
            offset = entry.end
        }
        return (picture ?: SphericalInfo()).copy(ambisonic = sound)
    }

    private fun matroskaVideo(source: ByteSource, video: ChapterReader.Element): SphericalInfo {
        var info = SphericalInfo(from = "matroska")
        var offset = video.dataOffset
        while (offset < video.end) {
            val f = ChapterReader.element(source, offset) ?: break
            when (f.id) {
                ID_STEREO_MODE -> when (ChapterReader.readUInt(source, f)) {
                    1L -> info = info.copy(stereo = StereoMode.SBS)
                    2L -> info = info.copy(stereo = StereoMode.OU, eyesSwapped = true)
                    3L -> info = info.copy(stereo = StereoMode.OU)
                    11L -> info = info.copy(stereo = StereoMode.SBS, eyesSwapped = true)
                    0L -> info = info.copy(stereo = StereoMode.MONO)
                }
                ID_PROJECTION -> info = matroskaProjection(source, f, info)
                ID_PIXEL_WIDTH -> info = info.copy(frameWidth = ChapterReader.readUInt(source, f).toInt())
                ID_PIXEL_HEIGHT -> info = info.copy(frameHeight = ChapterReader.readUInt(source, f).toInt())
            }
            if (f.unknownSize) break
            offset = f.end
        }
        return info
    }

    private fun matroskaProjection(source: ByteSource, proj: ChapterReader.Element, start: SphericalInfo): SphericalInfo {
        var info = start
        var type = 0L
        var private: ByteArray? = null
        var offset = proj.dataOffset
        while (offset < proj.end) {
            val f = ChapterReader.element(source, offset) ?: break
            when (f.id) {
                ID_PROJECTION_TYPE -> type = ChapterReader.readUInt(source, f)
                ID_PROJECTION_PRIVATE -> private = source.read(f.dataOffset, f.dataSize.toInt().coerceIn(0, MAX_MESH_BOX))
                ID_POSE_YAW -> info = info.copy(poseYaw = float(source, f))
                ID_POSE_PITCH -> info = info.copy(posePitch = float(source, f))
                ID_POSE_ROLL -> info = info.copy(poseRoll = float(source, f))
            }
            offset = f.end
        }
        return when (type) {
            1L -> private?.let { withEqui(info, it) } ?: info.copy(projection = ProjectionMode.EQ360)
            2L -> {
                val layout = private?.takeIf { it.size >= 12 }?.let { u32(it, 4) } ?: 0L
                info.copy(
                    projection = if (layout == 0L) ProjectionMode.CUBEMAP else null,
                    cubePadding = private?.takeIf { it.size >= 12 }?.let { u32(it, 8).toInt() } ?: 0,
                )
            }
            3L -> info.copy(projection = ProjectionMode.EQ360, mesh = true, meshBody = private)
            else -> info
        }
    }

    private fun float(source: ByteSource, f: ChapterReader.Element): Float {
        val b = source.read(f.dataOffset, f.dataSize.toInt().coerceIn(0, 8))
        return when (b.size) {
            4 -> java.lang.Float.intBitsToFloat(u32(b, 0).toInt())
            8 -> java.lang.Double.longBitsToDouble(u64(b, 0)).toFloat()
            else -> 0f
        }
    }

    // ---- numbers --------------------------------------------------------------------------------

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    /** A signed 16.16 fixed-point number. */
    private fun fixed16(b: ByteArray, at: Int): Float = u32(b, at).toInt() / 65536f

    /** An unsigned 0.32 fixed-point fraction. */
    private fun frac32(b: ByteArray, at: Int): Float = (u32(b, at) / 4294967296.0).toFloat()

    private val V1_UUID = byteArrayOf(
        0xff.toByte(), 0xcc.toByte(), 0x82.toByte(), 0x63, 0xf8.toByte(), 0x55, 0x4a, 0x93.toByte(),
        0x88.toByte(), 0x14, 0x58, 0x7a, 0x02, 0x52, 0x1f, 0xdd.toByte(),
    )

    /** The most of a mesh projection read: YouTube's run to tens of kilobytes. */
    private const val MAX_MESH_BOX = 4 * 1024 * 1024

    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_TYPE = 0x83L
    /** The most of a first sample read for its frame packing SEI, which leads the access unit. */
    private const val MAX_FIRST_SAMPLE = 256 * 1024

    private const val ID_VIDEO = 0xE0L
    private const val ID_PIXEL_WIDTH = 0xB0L
    private const val ID_PIXEL_HEIGHT = 0xBAL
    private const val ID_CODEC_ID = 0x86L
    private const val ID_CODEC_PRIVATE = 0x63A2L
    private const val ID_STEREO_MODE = 0x53B8L
    private const val ID_PROJECTION = 0x7670L
    private const val ID_PROJECTION_TYPE = 0x7671L
    private const val ID_PROJECTION_PRIVATE = 0x7672L
    private const val ID_POSE_YAW = 0x7673L
    private const val ID_POSE_PITCH = 0x7674L
    private const val ID_POSE_ROLL = 0x7675L
}
