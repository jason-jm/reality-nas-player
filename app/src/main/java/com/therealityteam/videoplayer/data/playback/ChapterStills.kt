package com.therealityteam.videoplayer.data.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaDataSource
import android.media.MediaFormat
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * 章节 pictures: a small frame from each chapter of the file playing, for the chapter list in 更多 —
 * "Chapter 07" says little, the picture says which scene. Each is the keyframe nearest a few seconds
 * into its chapter ([ChapterStillPlan]), decoded in software so the film's hardware decoder is left
 * alone — the platform's ([FrameGrabber]), or for what it cannot read (10-bit HEVC and H.264, the
 * formats only mpv plays) the app's own FFmpeg ([FfmpegGrabber]) — and kept in [dir], the app's
 * cache, so the list opens with them the next time.
 *
 * A file whose picture neither reads is noted as such, so it is not tried again and the list keeps
 * its plain numbers.
 */
class ChapterStills(private val dir: File) {

    /**
     * The pictures already kept for [chapters] of [itemId], handed to [onStill] (on this IO context),
     * [from] outwards. Returns the chapters still to picture, in that order — or null when the file
     * is known to give none.
     */
    suspend fun kept(itemId: String, chapters: List<Chapter>, from: Int, onStill: (Long, Bitmap) -> Unit): List<Int>? =
        withContext(Dispatchers.IO) {
            val folder = folderOf(itemId)
            if (File(folder, NONE).exists()) return@withContext null
            val missing = ArrayList<Int>()
            var kept = 0
            for (i in ChapterStillPlan.order(chapters.size, from)) {
                val bmp = File(folder, "${chapters[i].startMs}.jpg").takeIf { it.isFile }?.let { read(it) }
                when {
                    bmp != null -> { onStill(chapters[i].startMs, bmp); kept++ }
                    // Tried before and gave nothing (a last chapter with no keyframe to find): its number stays.
                    File(folder, "${chapters[i].startMs}.$NONE").exists() -> kept++
                    else -> missing += i
                }
            }
            // Seen again: the folder counts as fresh when the oldest are trimmed.
            if (kept > 0) folder.setLastModified(System.currentTimeMillis())
            missing
        }

    /**
     * Pictures [chapters] [missing] of [itemId], in that order, handing each to [onStill] (on this IO
     * context) as it comes. [where] is what is read: a path, or an http URL. Returns false when the
     * file gives no picture at all (it is then noted, and [kept] says so the next time).
     */
    suspend fun make(
        itemId: String,
        where: String,
        chapters: List<Chapter>,
        missing: List<Int>,
        durationMs: Long,
        onStill: (Long, Bitmap) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val folder = folderOf(itemId)
        val kept = chapters.size - missing.size
        val began = SystemClock.elapsedRealtime()
        var made = 0
        var failed = 0
        var unreadable = false
        var by = "?"
        try {
            openGrabber(where).use { grabber ->
                by = grabber.name
                coroutineScope {
                    // Cancelled (the film changed, the player closed): a read under way gives up now.
                    val watcher = launch { try { awaitCancellation() } finally { grabber.abort() } }
                    try {
                        for (i in missing) {
                            ensureActive()
                            val frame = frameFor(grabber, chapters, i, durationMs)
                            if (frame == null) {
                                failed++
                                // Nothing from the first two: no decoder here reads this picture.
                                if (made == 0 && kept == 0 && failed >= 2) { unreadable = true; break }
                                // The file gives pictures, this chapter none: not looked for again
                                // (each try can read on to the end of the file). A cut-off read is not this.
                                if ((made > 0 || kept > 0) && isActive) {
                                    runCatching { folder.mkdirs(); File(folder, "${chapters[i].startMs}.$NONE").createNewFile() }
                                }
                                continue
                            }
                            if (made == 0) { folder.mkdirs(); trim(folder) }
                            runCatching { File(folder, "${chapters[i].startMs}.jpg").outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                            onStill(chapters[i].startMs, frame)
                            made++
                        }
                    } finally {
                        watcher.cancel()
                    }
                }
            }
        } catch (e: FrameGrabber.NoDecoder) {
            android.util.Log.i("AuraChapters", "stills: ${e.message}")
            unreadable = true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("AuraChapters", "stills stopped after $made: ${e.message}")
        }
        android.util.Log.i(
            "AuraPerf",
            "chapter stills ($by): $made made, $kept kept, $failed failed of ${chapters.size} in ${SystemClock.elapsedRealtime() - began}ms",
        )
        if (made == 0 && kept == 0) {
            // Noted only when the file was read and its picture was not — never when the share was away.
            if (unreadable) runCatching { folder.mkdirs(); File(folder, NONE).createNewFile() }
            return@withContext false
        }
        true
    }

    private fun folderOf(itemId: String) = File(dir, Integer.toHexString(itemId.hashCode()))

    /**
     * The platform's decoders for what they read; the app's FFmpeg for the rest — and for a file the
     * platform's extractor cannot open at all (RealMedia, a Windows Media file).
     */
    private fun openGrabber(where: String): Grabber =
        try {
            FrameGrabber(where)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.i("AuraChapters", "stills: the platform cannot (${e.message}); FFmpeg")
            when (val handle = FrameNative.openOrZero(where)) {
                0L -> throw e
                -1L -> throw FrameGrabber.NoDecoder("FFmpeg either")
                else -> FfmpegGrabber(where, handle)
            }
        }

    /** Chapter [i]'s picture: the keyframe nearest a few seconds in, or further in when that one is dark. */
    private fun frameFor(g: Grabber, chapters: List<Chapter>, i: Int, durationMs: Long): Bitmap? {
        val start = chapters[i].startMs
        val first = g.frameAt(ChapterStillPlan.firstTryMs(chapters, i, durationMs), start) ?: return null
        if (!isDark(first)) return first
        val again = g.frameAt(ChapterStillPlan.secondTryMs(chapters, i, durationMs), start) ?: return first
        return if (isDark(again)) { again.recycle(); first } else { first.recycle(); again }
    }

    private fun read(file: File): Bitmap? =
        runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) }.getOrNull()

    /** A frame with next to no light in it: a fade, a night scene's first seconds. */
    private fun isDark(frame: Bitmap): Boolean {
        var sum = 0L
        val stepX = (frame.width / 8).coerceAtLeast(1)
        val stepY = (frame.height / 8).coerceAtLeast(1)
        var n = 0
        for (y in stepY / 2 until frame.height step stepY) for (x in stepX / 2 until frame.width step stepX) {
            val c = frame.getPixel(x, y)
            sum += (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3
            n++
        }
        return n == 0 || sum / n < DARK
    }

    /** Keeps the pictures of the last [KEEP] files; a new file's folder pushes the oldest out. */
    private fun trim(fresh: File) {
        val all = dir.listFiles { f -> f.isDirectory && f != fresh } ?: return
        if (all.size < KEEP) return
        all.sortedBy { it.lastModified() }.take(all.size - KEEP + 1).forEach { it.deleteRecursively() }
    }

    private companion object {
        const val DARK = 14
        const val KEEP = 120
        const val NONE = "none"
    }
}

/** Where a still is taken from: the keyframe nearest [ms] not before [notBeforeMs] (its chapter's start). */
private interface Grabber : Closeable {
    val name: String
    fun frameAt(ms: Long, notBeforeMs: Long): Bitmap?
    /** Makes a read under way give up — from any thread. */
    fun abort() {}
}

/**
 * The app's FFmpeg ([FrameNative]): every format mpv plays, 10-bit pictures included. A picture that
 * ran out of time (a seek reading on past the last indexed keyframe) leaves the file reopened for
 * the next, its reader having been cut off mid-read.
 */
private class FfmpegGrabber(private val where: String, private var handle: Long) : Grabber {
    override val name = "ffmpeg"
    private val lock = Any()
    @Volatile private var aborted = false

    override fun frameAt(ms: Long, notBeforeMs: Long): Bitmap? {
        val h = synchronized(lock) { handle }
        if (h == 0L) return null
        val began = SystemClock.elapsedRealtime()
        val out = FrameNative.frameAt(h, ms, notBeforeMs, WIDTH)
        if (out == null) {
            if (SystemClock.elapsedRealtime() - began >= GAVE_UP_MS) synchronized(lock) {
                FrameNative.close(handle)
                handle = if (aborted) 0L else FrameNative.openOrZero(where).takeIf { it != -1L } ?: 0L
            }
            return null
        }
        val w = out[0]
        val h2 = out[1]
        if (w <= 0 || h2 <= 0 || out.size < 3 + w * h2) return null
        // HDR10: the values are the light still coded (PQ) — brought down to a plain picture's.
        if (out[2] == 1) {
            val t = PqTone.table
            for (k in 3 until out.size) {
                val c = out[k]
                val r = t[((c shr 16) and 0xFF) * (PqTone.STEPS - 1) / 255]
                val g = t[((c shr 8) and 0xFF) * (PqTone.STEPS - 1) / 255]
                val b = t[(c and 0xFF) * (PqTone.STEPS - 1) / 255]
                out[k] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(out, 3, w, w, h2, Bitmap.Config.ARGB_8888)
    }

    override fun abort() = synchronized(lock) {
        aborted = true
        if (handle != 0L) FrameNative.abort(handle)
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) FrameNative.close(handle)
        handle = 0L
    }

    private companion object {
        /** Twice the 64 dp the list shows a picture at. */
        const val WIDTH = 256
        /** A picture that took this long was cut off by the native side's own limit (10 s). */
        const val GAVE_UP_MS = 9_500L
    }
}

/** libauraframes.so (app/natives/build-frames.sh) over the FFmpeg libmpv.so plays with. */
internal object FrameNative {
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("auraframes") }
            .onFailure { android.util.Log.w("AuraChapters", "libauraframes not loaded: no FFmpeg stills", it) }.isSuccess
    }

    /**
     * A handle; 0 when the file could not be read, -1 when it has no picture FFmpeg decodes. A handle
     * is a native pointer, its top byte tagged on arm64 — negative as a Long, so never compared by sign.
     */
    fun openOrZero(where: String): Long = if (available) open(where) else 0L

    @JvmStatic external fun open(url: String): Long
    @JvmStatic external fun frameAt(handle: Long, ms: Long, notBeforeMs: Long, maxWidth: Int): IntArray?
    @JvmStatic external fun abort(handle: Long)
    @JvmStatic external fun close(handle: Long)
}

/**
 * Keyframes out of a video file as small bitmaps: the platform's extractor, and a software decoder
 * reading into memory.
 *
 * An http file is read through [RangeSource], not the extractor's own http reader: that one learns a
 * file's size from an int (HttpURLConnection.getContentLength), so a film over 2 GB has no size, its
 * Matroska reader takes it for a live stream and ignores every seek — each chapter got the film's
 * first frames. (The platform's retriever reads the same way; it is why a frame poster made over a
 * share can be black.)
 */
private class FrameGrabber(where: String) : Grabber {
    override val name = "platform"

    /** No decoder here takes this video's format. */
    class NoDecoder(mime: String) : Exception("no decoder for $mime")

    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var source: RangeSource? = null
    /** An HDR10 picture (PQ): its light is brought down to a plain picture's, or it reads grey and flat. */
    private var pq = false

    init {
        try {
            if (where.startsWith("http://") || where.startsWith("https://")) {
                extractor.setDataSource(RangeSource(where).also { source = it })
            } else {
                extractor.setDataSource(where)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw NoDecoder("no video track")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val profile = if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else null
            val hdr = profile?.let { hdrBase(mime, it) }
            pq = hdr != null ||
                (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) && format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) == MediaFormat.COLOR_TRANSFER_ST2084)
            // An HDR10 stream is its 10-bit profile's, with light metadata the decoder need not know.
            val name = softwareDecoder(mime, hdr ?: profile) ?: throw NoDecoder("$mime profile $profile")
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createByCodecName(name).apply {
                try {
                    configure(format, null, null, 0)
                    start()
                } catch (e: Exception) {
                    release()
                    throw NoDecoder("$mime (${e.message})")
                }
            }
        } catch (e: Exception) {
            extractor.release()
            source?.close()
            throw e
        }
    }

    /** The keyframe nearest [ms], [WIDTH] pixels wide; null when the decoder gave no picture. */
    override fun frameAt(ms: Long, notBeforeMs: Long): Bitmap? {
        val c = codec ?: return null
        c.flush()
        extractor.seekTo(ms * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var fed = 0
        val deadline = SystemClock.elapsedRealtime() + FRAME_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!inputDone) {
                val i = c.dequeueInputBuffer(WAIT_US)
                if (i >= 0) {
                    val n = extractor.readSampleData(c.getInputBuffer(i)!!, 0)
                    // The picture comes within a few samples of its keyframe; past that the stream is not decoding.
                    if (n < 0 || fed >= MAX_SAMPLES) {
                        c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        c.queueInputBuffer(i, 0, n, extractor.sampleTime.coerceAtLeast(0), 0)
                        extractor.advance()
                        fed++
                    }
                }
            }
            val o = c.dequeueOutputBuffer(info, WAIT_US)
            if (o >= 0) {
                val bmp = if (info.size > 0) c.getOutputImage(o)?.use { toBitmap(it) } else null
                c.releaseOutputBuffer(o, false)
                if (bmp != null) return bmp
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
            }
        }
        return null
    }

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
        source?.close()
    }

    /** The decoded frame, scaled down by sampling (8-bit or 10-bit YUV, BT.709 for HD, BT.601 below). */
    private fun toBitmap(image: Image): Bitmap? {
        val crop = image.cropRect
        val w = crop.width()
        val h = crop.height()
        if (w <= 0 || h <= 0) return null
        val outW = minOf(WIDTH, w)
        val outH = (h.toLong() * outW / w).toInt().coerceAtLeast(1)
        val planes = image.planes
        if (planes.size < 3) return null
        // 10-bit frames hold each sample in 16 bits, little-endian, the value in the high bits.
        val wide = image.format == ImageFormat.YCBCR_P010
        val hi = if (wide) 1 else 0
        val (yP, uP, vP) = Triple(planes[0], planes[1], planes[2])
        val yb = yP.buffer
        val ub = uP.buffer
        val vb = vP.buffer
        val hd = h >= 720
        val px = IntArray(outW * outH)
        for (oy in 0 until outH) {
            val sy = crop.top + (oy * h / outH)
            for (ox in 0 until outW) {
                val sx = crop.left + (ox * w / outW)
                val y = (yb.get(sy * yP.rowStride + sx * yP.pixelStride + hi).toInt() and 0xFF) - 16
                val u = (ub.get((sy / 2) * uP.rowStride + (sx / 2) * uP.pixelStride + hi).toInt() and 0xFF) - 128
                val v = (vb.get((sy / 2) * vP.rowStride + (sx / 2) * vP.pixelStride + hi).toInt() and 0xFF) - 128
                px[oy * outW + ox] = when {
                    pq -> {
                        // BT.2020 to R'G'B' (PQ-coded, 0–1), then each through the light curve.
                        val yn = y / 219f
                        val cb = u / 224f
                        val cr = v / 224f
                        rgb(
                            fromPq(yn + 1.4746f * cr),
                            fromPq(yn - 0.16455f * cb - 0.57135f * cr),
                            fromPq(yn + 1.8814f * cb),
                        )
                    }
                    hd -> {
                        val yy = 1.164f * y
                        rgb(clamp(yy + 1.793f * v), clamp(yy - 0.213f * u - 0.533f * v), clamp(yy + 2.112f * u))
                    }
                    else -> {
                        val yy = 1.164f * y
                        rgb(clamp(yy + 1.596f * v), clamp(yy - 0.392f * u - 0.813f * v), clamp(yy + 2.017f * u))
                    }
                }
            }
        }
        return Bitmap.createBitmap(px, outW, outH, Bitmap.Config.ARGB_8888)
    }

    private fun clamp(f: Float): Int = f.toInt().coerceIn(0, 255)

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun fromPq(e: Float): Int = PqTone.table[(e.coerceIn(0f, 1f) * (PqTone.STEPS - 1) + 0.5f).toInt()]

    private companion object {
        /** Twice the 64 dp the list shows a picture at. */
        const val WIDTH = 256
        const val WAIT_US = 10_000L
        const val FRAME_TIMEOUT_MS = 3_000L
        const val MAX_SAMPLES = 40

        /** The 10-bit profile an HDR10 / HDR10+ [profile] of [mime] is decoded as; null for any other. */
        fun hdrBase(mime: String, profile: Int): Int? = when (mime.lowercase()) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> when (profile) {
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                else -> null
            }
            MediaFormat.MIMETYPE_VIDEO_AV1 -> when (profile) {
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus -> MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                else -> null
            }
            MediaFormat.MIMETYPE_VIDEO_VP9 -> when (profile) {
                MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR,
                MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus -> MediaCodecInfo.CodecProfileLevel.VP9Profile2
                MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR,
                MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus -> MediaCodecInfo.CodecProfileLevel.VP9Profile3
                else -> null
            }
            else -> null
        }

        /**
         * A software decoder for [mime] — the film's hardware decoder stays the film's — that knows
         * [profile] when the file names one: 10-bit H.264 is "High 10", which none here decodes, and
         * would otherwise be fed and waited on for nothing.
         */
        fun softwareDecoder(mime: String, profile: Int?): String? =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                if (info.isEncoder || !info.isSoftwareOnly) return@firstOrNull false
                val type = info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) } ?: return@firstOrNull false
                profile == null || info.getCapabilitiesForType(type).profileLevels.any { it.profile == profile }
            }?.name
    }
}

/**
 * An http file for the extractor, by Range requests of [BLOCK] bytes, the last few kept: a keyframe
 * and the index around it come in a handful of requests. Its size is read as a long, from the first
 * answer's Content-Range.
 */
private class RangeSource(private val url: String) : MediaDataSource() {

    private var total = -1L
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > KEPT
    }

    @Synchronized
    override fun getSize(): Long {
        if (total < 0) block(0)
        return total
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        val length = getSize()
        if (position >= length) return -1
        var done = 0
        while (done < size && position + done < length) {
            val at = position + done
            val index = at / BLOCK
            val b = block(index)
            val inBlock = (at - index * BLOCK).toInt()
            if (inBlock >= b.size) break
            val n = minOf(b.size - inBlock, size - done)
            System.arraycopy(b, inBlock, buffer, offset + done, n)
            done += n
        }
        return if (done == 0) -1 else done
    }

    private fun block(index: Long): ByteArray {
        blocks[index]?.let { return it }
        val start = index * BLOCK
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Range", "bytes=$start-${start + BLOCK - 1}")
            if (conn.responseCode != java.net.HttpURLConnection.HTTP_PARTIAL) throw java.io.IOException("HTTP ${conn.responseCode}: no ranges")
            if (total < 0) {
                total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                    ?: throw java.io.IOException("no size")
            }
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(BLOCK)
                val buf = ByteArray(64 * 1024)
                while (out.size() < BLOCK) {
                    val n = input.read(buf, 0, minOf(buf.size, BLOCK - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            blocks[index] = bytes
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    @Synchronized
    override fun close() {
        blocks.clear()
    }

    private companion object {
        const val BLOCK = 256 * 1024
        const val KEPT = 16
        const val TIMEOUT_MS = 6_000
    }
}
