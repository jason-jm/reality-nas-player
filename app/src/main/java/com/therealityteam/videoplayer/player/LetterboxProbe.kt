package com.therealityteam.videoplayer.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import com.therealityteam.videoplayer.data.playback.Letterbox
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A small copy of the frame the engine last put on the picture's Surface, as luma for
 * [Letterbox.detect]: the frame already decoded and shown — no second decoder reading the file
 * (a 4K film's decoder is busy enough), whichever engine plays it.
 */
object LetterboxProbe {

    /** [surface]'s latest frame at [width]×[height], or null when it has none to give. */
    suspend fun sample(surface: Surface, width: Int = WIDTH, height: Int = HEIGHT): Letterbox.Frame? {
        val pixels = copy(surface, width, height) ?: return null
        val luma = IntArray(pixels.size) { i ->
            val p = pixels[i]
            (299 * (p shr 16 and 0xff) + 587 * (p shr 8 and 0xff) + 114 * (p and 0xff)) / 1000
        }
        return Letterbox.Frame(width, height, luma)
    }

    /** [surface]'s latest frame at [width]×[height] as ARGB pixels, or null when it has none to give. */
    suspend fun copy(surface: Surface, width: Int, height: Int): IntArray? {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { done ->
            try {
                PixelCopy.request(surface, bitmap, { result ->
                    try {
                        if (done.isActive) {
                            val pixels = if (result == PixelCopy.SUCCESS) IntArray(width * height).also {
                                bitmap.getPixels(it, 0, width, 0, 0, width, height)
                            } else null
                            done.resume(pixels)
                        }
                    } finally {
                        // PixelCopy owns the destination until this callback, even after cancellation.
                        bitmap.recycle()
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: IllegalArgumentException) {
                // A Surface released under us, or one with no frame yet.
                bitmap.recycle()
                done.resume(null)
            }
        }
    }

    /** Small enough to copy in a moment, fine enough for a bar's edge: 1 row = 1 % of a 16:9 frame. */
    private const val WIDTH = 192
    private const val HEIGHT = 108
}
