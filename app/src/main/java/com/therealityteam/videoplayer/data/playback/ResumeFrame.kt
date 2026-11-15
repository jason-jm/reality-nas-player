package com.therealityteam.videoplayer.data.playback

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * The picture where a file was left, for 继续观看: a file nothing named — a concert off a share, a
 * documentary no database lists, a fansub's special — had only its title on a gradient there, and
 * one with a poster but no backdrop had the poster's middle cropped to 16:9. A frame of the film at
 * the place it stopped is what the card is about.
 *
 * Taken from the picture's own Surface, as [com.therealityteam.videoplayer.player.LetterboxProbe]
 * does — the frame already decoded and shown, whichever engine — and kept beside the artwork, one
 * file per item, overwritten as the film moves on.
 */
object ResumeFrame {

    /**
     * Whether [item] wants one: no still of its own, and no backdrop a card would show instead — or an
     * episode, whose row on its show's page has only its number otherwise (an anime episode never has
     * a still: no database it is matched on keeps them), whatever the show's backdrop.
     */
    fun wanted(item: MediaItem): Boolean =
        item.stillPath == null && item.stillUrl == null && (item.backdropPath == null || item.kind == MediaKind.EPISODE)

    /**
     * Whether a frame already kept for [item] is kept from here on: past [KEEP_AFTER] of it the picture
     * is the ending's — the same credits for every episode of a show, which made a season's rows all
     * one picture.
     */
    fun settled(positionMs: Long, durationMs: Long): Boolean = durationMs > 0 && positionMs > durationMs * KEEP_AFTER

    /** Where [itemId]'s frame is kept (there or not). */
    fun file(filesDir: File, itemId: String): File =
        File(filesDir, "aura/artwork/resume_" + itemId.hashCode().toUInt().toString(16) + ".jpg")

    /**
     * Copies [surface]'s latest frame — the picture at [aspect], [crop]'s bars cut off — into [dest].
     * False when the Surface gave nothing, or nothing to see: a dark frame (a fade, a night scene's
     * black) or a flat one (an opening's grey card, which 东方万花镜's large card wore whole).
     */
    suspend fun capture(surface: Surface, aspect: Float, crop: FrameCrop, dest: File): Boolean {
        if (aspect <= 0f) return false
        val h = (WIDTH / aspect).toInt().coerceIn(1, WIDTH * 2)
        val whole = copy(surface, WIDTH, h) ?: return false
        return withContext(Dispatchers.IO) {
            val picture = if (crop.crops) {
                val x = (crop.left * WIDTH).toInt()
                val y = (crop.top * h).toInt()
                val w = (WIDTH - x - (crop.right * WIDTH).toInt()).coerceAtLeast(1)
                val ch = (h - y - (crop.bottom * h).toInt()).coerceAtLeast(1)
                Bitmap.createBitmap(whole, x, y, w, ch).also { if (it !== whole) whole.recycle() }
            } else whole
            try {
                if (isBlank(picture)) return@withContext false
                write(picture, dest)
            } finally {
                picture.recycle()
            }
        }
    }

    /**
     * A panorama's frame from [surface] as the window looks into it ([look]: PanoFlatten, a stereo frame's
     * left eye), 16:9, into [dest] — its sphere laid flat would be a smear on the card. Copied larger, since
     * the view is a part of the frame ([sourceWidth]: the frame's own width, when known, which it need not pass).
     */
    suspend fun captureView(surface: Surface, aspect: Float, layout: PanoLayout, look: PanoViewport.Look, dest: File, sourceWidth: Int = 0): Boolean {
        if (aspect <= 0f) return false
        // In the frame's own shape (a 1×6 cubemap strip is six times as tall as wide), its longer side at
        // most PANO_SOURCE — and no larger than the frame itself, when its size is known.
        val longest = if (sourceWidth > 0) minOf(PANO_SOURCE, maxOf(sourceWidth, (sourceWidth / aspect).toInt())) else PANO_SOURCE
        val w = (if (aspect >= 1f) longest.toFloat() else longest * aspect).toInt().coerceAtLeast(2)
        val h = (w / aspect).toInt().coerceAtLeast(2)
        val whole = copy(surface, w, h) ?: return false
        return withContext(Dispatchers.IO) {
            val pixels = IntArray(w * h)
            whole.getPixels(pixels, 0, w, 0, 0, w, h)
            whole.recycle()
            val outH = WIDTH * 9 / 16
            val view = PanoFlatten.view(pixels, w, h, layout.meshless(w, h), WIDTH, outH, look)
            val picture = Bitmap.createBitmap(view, WIDTH, outH, Bitmap.Config.ARGB_8888)
            try {
                if (isBlank(picture)) return@withContext false
                write(picture, dest)
            } finally {
                picture.recycle()
            }
        }
    }

    /**
     * [surface]'s latest frame, as shown, [width] wide at [aspect], into [dest] — whatever it holds (a dark
     * frame too). For looking at what the headset decodes, which no screenshot of a spatial panel shows.
     */
    suspend fun dump(surface: Surface, aspect: Float, width: Int, dest: File): Boolean {
        if (aspect <= 0f) return false
        val whole = copy(surface, width, (width / aspect).toInt().coerceIn(1, width * 2)) ?: return false
        return withContext(Dispatchers.IO) {
            try {
                write(picture = whole, dest = dest)
            } finally {
                whole.recycle()
            }
        }
    }

    /** [surface]'s latest frame at [w] × [h] as a lossless PNG in [dest] (截图); false when it gave none. */
    suspend fun png(surface: Surface, w: Int, h: Int, dest: File): Boolean {
        val whole = copy(surface, w, h) ?: return false
        return withContext(Dispatchers.IO) {
            try {
                dest.parentFile?.mkdirs()
                runCatching { dest.outputStream().use { whole.compress(Bitmap.CompressFormat.PNG, 100, it) } }.getOrDefault(false)
            } finally {
                whole.recycle()
            }
        }
    }

    /** [surface]'s latest frame at [w] × [h]; null when it gave none. */
    private suspend fun copy(surface: Surface, w: Int, h: Int): Bitmap? {
        val whole = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val copied = suspendCancellableCoroutine { done ->
            try {
                PixelCopy.request(surface, whole, { result -> done.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
            } catch (e: IllegalArgumentException) {
                done.resume(false) // a Surface released under us, or one with no frame yet
            }
        }
        if (!copied) whole.recycle()
        return whole.takeIf { copied }
    }

    private fun write(picture: Bitmap, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val ok = runCatching { tmp.outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) } }.getOrDefault(false)
        return ok && tmp.renameTo(dest)
    }

    /** Next to no light in it, or one shade all over. */
    private fun isBlank(frame: Bitmap): Boolean {
        val probe = Bitmap.createScaledBitmap(frame, 8, 8, true)
        var sum = 0L
        var lo = 255
        var hi = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val c = probe.getPixel(x, y)
            val l = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
            sum += l
            lo = minOf(lo, l)
            hi = maxOf(hi, l)
        }
        if (probe !== frame) probe.recycle()
        return sum / 64 < DARK || hi - lo < FLAT
    }

    /** Wide enough for the large 继续观看 card's 1600px picture to be no more than softened. */
    private const val WIDTH = 960
    /** A panorama's frame is copied at most this large to look into: the view is about a third of it. */
    private const val PANO_SOURCE = 3072
    private const val KEEP_AFTER = 0.7
    private const val QUALITY = 85
    private const val DARK = 16
    /** The spread of light across the frame's 64 cells below which it shows nothing. */
    private const val FLAT = 24
}
