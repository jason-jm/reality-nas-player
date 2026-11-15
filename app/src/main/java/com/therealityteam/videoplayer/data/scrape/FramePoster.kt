package com.therealityteam.videoplayer.data.scrape

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.playback.LocalStreamServer
import com.therealityteam.videoplayer.data.repository.SourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A poster made from the film itself, for files nothing identifies. The long tail of a library
 * — an NHK documentary with a Chinese title no database lists, a home recording, a stray
 * extra — used to be a grey placeholder on the wall. Now a frame from early in the picture
 * sits on a blurred, darkened copy of itself with the title set beneath, so the card shows
 * what the film looks like. A real match later replaces it: the artwork cache keys the file
 * by the item and drops the frame poster when a scraped one arrives.
 *
 * Local files are read directly; SMB files through the playback proxy, the same way they
 * play. Anything else (DLNA, media servers, which bring their own artwork) is left alone.
 */
class FramePoster(
    context: Context,
    private val artwork: ArtworkCache,
    private val sources: SourceRepository,
    private val streamServer: LocalStreamServer,
) {
    private val appContext = context.applicationContext

    /** The poster's path, or null when the file gave no frame. Reuses a poster already made. */
    suspend fun generate(item: MediaItem): String? = withContext(Dispatchers.IO) {
        artwork.existing(item.id, TAG)?.let { return@withContext it }
        val frame = grab(item)
        if (frame == null) return@withContext null
        val poster = try {
            compose(frame, item.title.ifBlank { item.fileName.substringBeforeLast('.') }, item.year)
        } finally {
            frame.recycle()
        }
        try {
            artwork.store(poster, item.id, TAG)
        } finally {
            poster.recycle()
        }
    }

    private fun grab(item: MediaItem): Bitmap? {
        val retriever = MediaMetadataRetriever()
        var proxied: String? = null
        try {
            // Where the file is read from — and its own word on being a panorama, read there too.
            val header: com.therealityteam.videoplayer.data.playback.ByteSource
            when {
                item.path.startsWith("/") || item.path.startsWith("file://") -> {
                    val path = item.path.removePrefix("file://")
                    retriever.setDataSource(path)
                    header = com.therealityteam.videoplayer.data.playback.FileByteSource(java.io.File(path))
                }
                com.therealityteam.videoplayer.data.playback.LocalStreamServer.serves(item.path) -> {
                    val src = sources.get(item.sourceId)
                    val url = streamServer.register(item.path, src?.username, src?.password) ?: return null
                    proxied = item.path
                    retriever.setDataSource(url, emptyMap())
                    header = com.therealityteam.videoplayer.data.playback.HttpByteSource(url)
                }
                else -> return null
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = frameTimeUs(durationMs)
            // The exact frame, as the snapshot feature takes it (the nearest keyframe is often a
            // fade-in black); a black frame gets one more try a little later in the film.
            val first = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)
            val frame = if (first != null && isBlack(first)) {
                first.recycle()
                retriever.getFrameAtTime(atUs + 20_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
            } else first
            val small = frame?.let { shrink(it) } ?: return null
            // A panorama laid flat is a smear: its poster shows a look into it instead.
            val pano = panoramaOf(item, header) ?: return small
            return flatten(small, pano)
        } catch (e: Exception) {
            android.util.Log.w("AuraFrame", "grab failed for ${item.fileName}", e)
            return null
        } finally {
            runCatching { retriever.release() }
            proxied?.let { streamServer.unregister(it) }
        }
    }

    /**
     * How [item]'s picture surrounds the viewer, if it does: the viewer's word or what the file said as
     * it played (its flags), else the file's own metadata, else its name. Null for a flat film.
     */
    private fun panoramaOf(item: MediaItem, header: com.therealityteam.videoplayer.data.playback.ByteSource): com.therealityteam.videoplayer.data.playback.PanoLayout? {
        val f = item.video
        val sphere = com.therealityteam.videoplayer.data.playback.PanoLayout.SPHERICAL
        val eyes = f.stereo.takeIf { it == com.therealityteam.videoplayer.data.model.StereoMode.SBS || it == com.therealityteam.videoplayer.data.model.StereoMode.OU }
            ?: com.therealityteam.videoplayer.data.model.StereoMode.MONO
        if (f.projection in sphere) {
            return com.therealityteam.videoplayer.data.playback.PanoLayout.of(f.projection, eyes, f.lensFov.toFloat())
                .copy(eyesSwapped = f.rightFirst && eyes != com.therealityteam.videoplayer.data.model.StereoMode.MONO)
        }
        if (f.userOverridden) return null
        val info = runCatching { com.therealityteam.videoplayer.data.playback.SphericalMetadata.read(header) }.getOrNull()
        return info?.takeIf { it.isSpherical }?.layout(fallbackStereo = eyes)
    }

    /** [frame] seen straight ahead through a wide window ([PanoFlatten]), 4:3. */
    private fun flatten(frame: Bitmap, layout: com.therealityteam.videoplayer.data.playback.PanoLayout): Bitmap {
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)
        val outW = FRAME_WIDTH
        val outH = FRAME_WIDTH * 3 / 4
        // Without a mesh (a file's own is taken for EAC), in the frame's own pixels (EAC's border, a cubemap's faces).
        val view = com.therealityteam.videoplayer.data.playback.PanoFlatten.view(pixels, w, h, layout.meshless(w, h), outW, outH)
        frame.recycle()
        return Bitmap.createBitmap(view, outW, outH, Bitmap.Config.ARGB_8888)
    }

    /** Down to the working width; the retriever's full frame can be 4K. */
    private fun shrink(frame: Bitmap): Bitmap {
        if (frame.width <= FRAME_WIDTH) return frame
        val h = (frame.height.toLong() * FRAME_WIDTH / frame.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(frame, FRAME_WIDTH, h, true)
        if (small !== frame) frame.recycle()
        return small
    }

    /** A frame with next to no light in it — a fade, a title card gone dark. */
    private fun isBlack(frame: Bitmap): Boolean {
        val probe = Bitmap.createScaledBitmap(frame, 8, 8, true)
        var sum = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val c = probe.getPixel(x, y)
            sum += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
        }
        probe.recycle()
        return sum / 64 < 12
    }

    companion object {
        const val TAG = "frame"
        const val WIDTH = 600
        const val HEIGHT = 900
        private const val FRAME_WIDTH = 640

        /**
         * Where to take the frame: a tenth of the way in — past the studio logos, before the
         * story gives anything away — but never beyond two minutes, and thirty seconds into a
         * film whose length is unknown.
         */
        fun frameTimeUs(durationMs: Long): Long {
            val ms = if (durationMs <= 0L) 30_000L else (durationMs / 10).coerceIn(5_000L, 120_000L)
            return ms * 1000L
        }

        /**
         * The poster: the frame scaled to cover the card, blurred by a heavy down-and-up scale
         * and darkened; the frame itself inset at full width with rounded corners a little
         * above centre; a gradient at the foot and the title in it, with the year when known.
         */
        fun compose(frame: Bitmap, title: String, year: Int?): Bitmap {
            val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            // Backdrop: cover-scale the frame, blur it by way of a 12px-wide copy.
            val tiny = Bitmap.createScaledBitmap(frame, 12, 18, true)
            val cover = coverRect(tiny.width, tiny.height)
            c.drawBitmap(tiny, null, cover, paint)
            tiny.recycle()
            c.drawColor(0x73000000) // design-style: fixed-figma-color poster bitmap scrim (an image, not UI)

            // The frame, inset, rounded.
            val inset = 30f
            val fw = WIDTH - 2 * inset
            val fh = fw * frame.height / frame.width
            val top = HEIGHT * 0.36f - fh / 2f
            val dst = RectF(inset, top, inset + fw, top + fh)
            val rounded = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            Canvas(rounded).apply {
                drawRoundRect(dst, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
                drawBitmap(frame, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
                })
            }
            c.drawBitmap(rounded, 0f, 0f, paint)
            rounded.recycle()

            // Foot: a gradient to black so the title reads on any frame.
            val footTop = HEIGHT * 0.62f
            c.drawRect(0f, footTop, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
                shader = LinearGradient(0f, footTop, 0f, HEIGHT.toFloat(), 0x00000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
            })

            // Title, up to three lines, then the year in a quieter line.
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 44f
                isFakeBoldText = true
            }
            val width = WIDTH - 2 * inset.toInt()
            val layout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0f, 1.08f)
                .build()
            val yearPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xB3FFFFFF.toInt()
                textSize = 30f
            }
            val yearHeight = if (year != null) 44f else 0f
            val textTop = HEIGHT - 44f - yearHeight - layout.height
            c.save()
            c.translate(inset, textTop)
            layout.draw(c)
            c.restore()
            if (year != null) c.drawText(year.toString(), inset, HEIGHT - 44f, yearPaint)
            return out
        }

        private fun coverRect(w: Int, h: Int): RectF {
            val scale = maxOf(WIDTH.toFloat() / w, HEIGHT.toFloat() / h)
            val dw = w * scale
            val dh = h * scale
            val left = (WIDTH - dw) / 2f
            val top = (HEIGHT - dh) / 2f
            return RectF(left, top, left + dw, top + dh)
        }
    }
}
