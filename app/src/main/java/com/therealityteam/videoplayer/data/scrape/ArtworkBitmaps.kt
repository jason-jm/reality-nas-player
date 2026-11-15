package com.therealityteam.videoplayer.data.scrape

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.min

/** The artwork cache under the app's files dir, reading posters with [BitmapSampler]. */
fun ArtworkCache(context: Context): ArtworkCache = ArtworkCache(context.filesDir, BitmapSampler)

/** A poster the app drew itself (see FramePoster), stored as JPEG under the item like a scraped one. */
suspend fun ArtworkCache.store(bitmap: Bitmap, key: String, tag: String): String? =
    store(key, tag) { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out) }

/** A quarter-size decode, scaled down (unfiltered) to the grid the colour pick reads. */
object BitmapSampler : ArtworkCache.ImageSampler {
    override fun sample(path: String, maxWidth: Int, maxHeight: Int): ArtworkCache.Pixels? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        try {
            val w = min(bmp.width, maxWidth)
            val h = min(bmp.height, maxHeight)
            if (w <= 0 || h <= 0) return null
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, false)
            val px = IntArray(w * h)
            scaled.getPixels(px, 0, w, 0, 0, w, h)
            if (scaled != bmp) scaled.recycle()
            return ArtworkCache.Pixels(w, h, px)
        } finally {
            bmp.recycle()
        }
    }
}
