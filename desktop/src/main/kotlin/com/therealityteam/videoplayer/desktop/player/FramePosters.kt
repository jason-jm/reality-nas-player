package com.therealityteam.videoplayer.desktop.player

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.playback.PanoFlatten
import com.therealityteam.videoplayer.data.playback.PanoLayout
import com.therealityteam.videoplayer.desktop.AppGraph
import com.therealityteam.videoplayer.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.features.Screenshots
import org.openani.mediamp.mpv.MpvMediampPlayer
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Posters made from the videos themselves, for the cards nothing identifies — home recordings, a
 * documentary no database lists, a stray clip — which otherwise wear a generated cover. A frame a
 * tenth of the way in (past the logos, before the story gives anything away; 5 s to 2 min) sits on
 * a blurred, darkened copy of itself: the headset's FramePoster, without the title the desktop's
 * card already carries beneath it. A real match later replaces it.
 *
 * Frames come from an mpv of their own — muted, without a window — so a film playing meanwhile is
 * not touched; one card's first file each, local, SMB and WebDAV (media servers bring their own).
 */
class FramePosters(private val graph: AppGraph) {
    private val mutex = Mutex()

    /** Make posters for every card still without one, when the setting allows. The number made. */
    suspend fun fill(limit: Int = 300): Int = mutex.withLock {
        if (!graph.settings.value.framePosters) return 0
        val groups = graph.library.groups.first { it.isNotEmpty() }
        val todo = groups.asSequence()
            .filter { it.posterPath == null && !it.key.startsWith("jellyfin") && !it.key.startsWith("plex") && !it.key.startsWith("emby") }
            .map { it.representative }
            .filter { eligible(it) }
            .take(limit)
            .toList()
        if (todo.isEmpty()) return 0
        val grabber = FrameGrabber(graph)
        var made = 0
        try {
            for (item in todo) {
                val t0 = System.currentTimeMillis()
                val path = runCatching { make(grabber, item) }.onFailure { Log.w(TAG, "no frame poster for ${item.fileName}", it) }.getOrNull()
                if (path != null) {
                    val fresh = graph.media.get(item.id) ?: continue
                    // A match that arrived meanwhile wins.
                    if (fresh.posterPath != null && !fresh.posterGenerated) continue
                    graph.media.upsert(fresh.copy(posterPath = path, posterGenerated = true, dominantColor = graph.artwork.dominantColor(path)))
                    made++
                    Log.i(TAG, "frame poster for ${item.fileName} in ${System.currentTimeMillis() - t0} ms")
                }
            }
        } finally {
            withContext(Dispatchers.Main) { grabber.close() }
        }
        made
    }

    private fun eligible(item: MediaItem): Boolean = FrameGrabber.reachable(item) && !(item.scrapeStatus == ScrapeStatus.MATCHED && item.tmdbId != null) && item.posterPath == null

    private suspend fun make(grabber: FrameGrabber, item: MediaItem): String? {
        graph.artwork.existing(item.id, TAG_ART)?.let { return it }
        val frame = grabber.frame(item, fraction = 0.1, minMs = 5_000, maxMs = 120_000) ?: return null
        try {
            val jpeg = compose(frame)
            return graph.artwork.store(item.id, TAG_ART) { out -> out.write(jpeg); true }
        } finally {
            frame.close()
        }
    }

    companion object {
        internal const val TAG = "AuraFrame"
        private const val TAG_ART = "frame"
        const val WIDTH = 600
        const val HEIGHT = 900

        /** A frame with next to no light in it — a fade, a title card gone dark. */
        fun isBlack(image: Image): Boolean {
            val surface = Surface.makeRasterN32Premul(8, 8)
            try {
                surface.canvas.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(8f, 8f))
                val bitmap = org.jetbrains.skia.Bitmap()
                bitmap.allocN32Pixels(8, 8)
                surface.readPixels(bitmap, 0, 0)
                var sum = 0
                for (y in 0 until 8) for (x in 0 until 8) {
                    val c = bitmap.getColor(x, y)
                    sum += (Color.getR(c) + Color.getG(c) + Color.getB(c)) / 3
                }
                return sum / 64 < 12
            } finally {
                surface.close()
            }
        }

        /**
         * A panorama's [frame] as a look straight into it, 100° across, 4:3 — core PanoFlatten, the headset's
         * poster's mapping (a stereo frame's left eye; a file's own mesh as EAC). The frame is brought to at
         * most 2048 across first: the view reads it bilinearly, and a 6K frame's pixels are not wanted.
         */
        fun flatten(frame: Image, layout: PanoLayout): Image {
            val scale = minOf(1f, MAX_FLATTEN_SOURCE.toFloat() / frame.width)
            val w = maxOf(1, Math.round(frame.width * scale))
            val h = maxOf(1, Math.round(frame.height * scale))
            val pixels = Surface.makeRasterN32Premul(w, h).use { surface ->
                surface.canvas.drawImageRect(
                    frame, Rect.makeWH(frame.width.toFloat(), frame.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true,
                )
                val bitmap = org.jetbrains.skia.Bitmap()
                bitmap.allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
                surface.readPixels(bitmap, 0, 0)
                val bgra = bitmap.readPixels(bitmap.imageInfo, w * 4, 0, 0)!!
                bitmap.close()
                IntArray(w * h) { i ->
                    val o = i * 4
                    (0xFF shl 24) or ((bgra[o + 2].toInt() and 0xFF) shl 16) or ((bgra[o + 1].toInt() and 0xFF) shl 8) or (bgra[o].toInt() and 0xFF)
                }
            }
            val outW = FLAT_WIDTH
            val outH = FLAT_WIDTH * 3 / 4
            val view = PanoFlatten.view(pixels, w, h, layout.meshless(w, h), outW, outH)
            val out = ByteArray(outW * outH * 4)
            for (i in view.indices) {
                val v = view[i]
                out[i * 4] = (v and 0xFF).toByte()
                out[i * 4 + 1] = ((v shr 8) and 0xFF).toByte()
                out[i * 4 + 2] = ((v shr 16) and 0xFF).toByte()
                out[i * 4 + 3] = 0xFF.toByte()
            }
            return Image.makeRaster(ImageInfo(outW, outH, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), out, outW * 4)
        }

        private const val MAX_FLATTEN_SOURCE = 2048
        private const val FLAT_WIDTH = 800

        /**
         * The poster: the frame cover-scaled, blurred and darkened as the ground; the frame itself
         * inset at full width, rounded, a little above the middle, with a soft shadow under it.
         */
        fun compose(frame: Image): ByteArray {
            val surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
            try {
                val c: Canvas = surface.canvas
                val scale = maxOf(WIDTH.toFloat() / frame.width, HEIGHT.toFloat() / frame.height)
                val cw = frame.width * scale
                val ch = frame.height * scale
                val cover = Rect.makeXYWH((WIDTH - cw) / 2f, (HEIGHT - ch) / 2f, cw, ch)
                c.drawImageRect(
                    frame, Rect.makeWH(frame.width.toFloat(), frame.height.toFloat()), cover,
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                    Paint().apply { imageFilter = ImageFilter.makeBlur(36f, 36f, org.jetbrains.skia.FilterTileMode.CLAMP) },
                    true,
                )
                c.drawRect(Rect.makeWH(WIDTH.toFloat(), HEIGHT.toFloat()), Paint().apply { color = 0x80000000.toInt() })
                val inset = 30f
                val fw = WIDTH - 2 * inset
                val fh = fw * frame.height / frame.width
                val top = HEIGHT * 0.42f - fh / 2f
                val dst = Rect.makeXYWH(inset, top, fw, fh)
                // Shadow, then the frame clipped to rounded corners.
                c.drawRRect(
                    RRect.makeXYWH(inset, top + 10f, fw, fh, 18f),
                    Paint().apply { color = 0x66000000; imageFilter = ImageFilter.makeBlur(18f, 18f, org.jetbrains.skia.FilterTileMode.DECAL) },
                )
                c.save()
                c.clipRRect(RRect.makeXYWH(inset, top, fw, fh, 18f), true)
                c.drawImageRect(frame, Rect.makeWH(frame.width.toFloat(), frame.height.toFloat()), dst, FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true)
                c.restore()
                return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 88)!!.bytes
            } finally {
                surface.close()
            }
        }
    }
}

/**
 * A headless, silent mpv for taking frames out of videos — posters for cards nothing identifies,
 * pictures for episodes no database has — reused across the files of one run, and with its own
 * small read-ahead so a film playing meanwhile is not touched and a NAS is not read for nothing.
 */
internal class FrameGrabber(private val graph: AppGraph) {
    private var player: MpvMediampPlayer? = null
    private val opener = MediaOpen(graph.sources, graph.streamServer)
    private val dir = File(graph.filesDir, "aura/cache/frames").apply { mkdirs() }

    private fun mp(): MpvMediampPlayer = player ?: MpvMediampPlayer(Unit, EmptyCoroutineContext, Dispatchers.Main) { h ->
        h.option("ao", "null")
        h.option("aid", "no")
        h.option("sid", "no")
        h.option("sub-auto", "no")
        h.option("hr-seek", "yes")
        h.option("hwdec", "auto-safe")
        h.option("keep-open", "yes")
        h.option("screenshot-format", "png")
        // One frame is wanted, not a film's worth of read-ahead over the NAS.
        h.option("cache", "no")
        h.option("demuxer-readahead-secs", "0")
        h.option("demuxer-max-bytes", "${8 * 1024 * 1024}")
    }.also { p ->
        p.javaClass.getMethod("createRenderContext\$mediamp_mpv").invoke(p)
        player = p
    }

    /**
     * The frame [fraction] of the way in (kept between [minMs] and [maxMs]), or 20 s later if
     * that one is black.
     */
    suspend fun frame(item: MediaItem, fraction: Double, minMs: Long, maxMs: Long): Image? = withContext(Dispatchers.Main) {
        val p = mp()
        val media = opener.open(item)
        // What the file says of itself as a panorama, read beside the frame: a panorama's frame is laid out
        // as a look into it (a sphere laid flat is a smear, its poles across the top), and the library learns
        // it — the card's 360°/180° tag before the film is ever played.
        val said = withContext(Dispatchers.IO) { Projection.spherical(media.uri) }
        val known = graph.media.get(item.id) ?: item
        if (!item.id.startsWith("adhoc:")) {
            said?.learnedFlags(known.video)?.takeIf { it != known.video }?.let { learned ->
                withContext(Dispatchers.IO) { graph.media.learnVideoFlags(item.id, learned) }
                Log.i(FramePosters.TAG, "${item.fileName}: the library learns $learned")
            }
        }
        val pano = PanoLayout.forFilm(known.video, item.fileName, said)
        val duration = item.runtime?.let { it * 60_000L } ?: 0L
        fun place(length: Long) = (length * fraction).toLong().coerceIn(minMs, maxMs.coerceAtLeast(minMs))
        val at = if (duration > 0) place(duration) else 30_000L
        p.setMediaData(media, playWhenReady = false, startPositionMillis = at)
        withTimeoutOrNull(20_000) { p.state.first { it.mediaStatus == MediaStatus.Ready || it.mediaStatus is MediaStatus.Error } }
            ?: return@withContext null
        if (p.state.value.mediaStatus is MediaStatus.Error) return@withContext null
        // Now that the length is known, the place in it, if the library did not know it.
        val length = p.mediaProperties.value?.durationMillis ?: 0L
        if (duration <= 0 && length > 0) p.seekTo(place(length))
        var image = shot(p)
        if (image != null && FramePosters.isBlack(image)) {
            image.close()
            p.seekTo(p.currentPositionMillis.value + 20_000)
            image = shot(p)
        }
        if (image != null && pano != null) {
            val view = withContext(Dispatchers.Default) { FramePosters.flatten(image, pano) }
            image.close()
            image = view
        }
        image
    }

    private suspend fun shot(p: MpvMediampPlayer): Image? {
        delay(700) // the seek lands and the frame is decoded
        // A file of its own: two grabbers (posters, episode pictures) may work at once.
        val f = File(dir, "frame-${System.identityHashCode(this)}.png")
        f.delete()
        runCatching { p.features[Screenshots]?.takeScreenshot(f.absolutePath) }
        return withContext(Dispatchers.IO) {
            f.takeIf { it.isFile && it.length() > 0 }?.let { runCatching { Image.makeFromEncoded(it.readBytes()) }.getOrNull() }
        }
    }

    fun close() {
        runCatching { player?.close() }
        player = null
        opener.release()
    }

    companion object {
        /** A file a frame can be taken from: on this computer, a share or a server (media servers bring their own pictures). */
        fun reachable(item: MediaItem): Boolean {
            val p = item.path
            return p.startsWith("/") || com.therealityteam.videoplayer.data.playback.LocalStreamServer.serves(p) ||
                Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(p) || p.startsWith("http")
        }
    }
}
