package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import com.therealityteam.videoplayer.util.elapsedMillis

/**
 * Step ④: download posters/backdrops to a local cache and extract a poster's dominant
 * color (drives Vibrant ambiance + Stage IBL). Avoids re-loading remote images while
 * the poster wall scrolls.
 */
class ArtworkCache(filesDir: File, private val sampler: ImageSampler) {

    /**
     * Decodes a cached image small, for its colour — BitmapFactory on the headset, the desktop's own
     * decoder there. Returns the image scaled to at most [maxWidth]×[maxHeight] as ARGB pixels, or
     * null if it cannot be read.
     */
    fun interface ImageSampler {
        fun sample(path: String, maxWidth: Int, maxHeight: Int): Pixels?
    }

    class Pixels(val width: Int, val height: Int, val argb: IntArray)

    private val dir = File(filesDir, "aura/artwork").apply { mkdirs() }

    suspend fun cache(url: String?, key: String): String? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null
        // Key the file by the URL too: posters are immutable per URL, so a changed match
        // (wrong -> corrected, or a manual re-match) yields a new file instead of serving the
        // stale cached image. "__" separates key from url-tag so the poster's cleanup never
        // matches the backdrop's "<id>_bd__*" files.
        val tag = url.hashCode().toUInt().toString(16)
        val dest = File(dir, "${key}__$tag.jpg")
        if (dest.exists() && dest.length() > 0) return@withContext dest.absolutePath
        dir.listFiles { f -> f.name.startsWith("${key}__") && f.name != dest.name }?.forEach { it.delete() }
        if (Http.download(url, dest)) dest.absolutePath else null
    }

    /**
     * A poster the app drew itself (see FramePoster), stored under the item like a scraped one.
     * [writeJpeg] encodes the image into the stream and says whether it managed to.
     */
    suspend fun store(key: String, tag: String, writeJpeg: (java.io.OutputStream) -> Boolean): String? = withContext(Dispatchers.IO) {
        val dest = File(dir, "${key}__$tag.jpg")
        dir.listFiles { f -> f.name.startsWith("${key}__") && f.name != dest.name }?.forEach { it.delete() }
        val ok = runCatching { dest.outputStream().use(writeJpeg) }.getOrDefault(false)
        if (ok) dest.absolutePath else { dest.delete(); null }
    }

    /** Where a headshot for [url] is filed. Shared by the scraper and the cast row. */
    companion object {
        fun personKey(url: String): String = "person_" + url.hashCode().toUInt().toString(16)

        /** Where an episode still for [url] is filed. */
        fun stillKey(url: String): String = "still_" + url.hashCode().toUInt().toString(16)

        /** What reading posters' ambiance colours costs a pass, counted rather than assumed. */
        val decodes = java.util.concurrent.atomic.AtomicLong()
        val decodeMillis = java.util.concurrent.atomic.AtomicLong()

        fun resetWork() { decodes.set(0); decodeMillis.set(0) }

        fun workReport(): String = "poster colours: ${decodes.get()} decoded in ${decodeMillis.get()}ms"
    }

    /** The cached file for [key] and [tag], if it is there. */
    fun existing(key: String, tag: String): String? =
        File(dir, "${key}__$tag.jpg").takeIf { it.isFile && it.length() > 0 }?.absolutePath

    /** Delete every cached poster/backdrop (for a from-zero library rebuild). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    // One poster now serves a whole show, so its colour was being computed once per episode —
    // a JPEG decode each time, for an answer that cannot differ.
    private val colors = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Saturation/value-weighted dominant color of a poster, as an ARGB int. */
    suspend fun dominantColor(path: String?): Int? = withContext(Dispatchers.IO) {
        if (path == null) return@withContext null
        colors[path]?.let { return@withContext it }
        val startedAt = elapsedMillis()
        val px = runCatching { sampler.sample(path, 48, 72) }.getOrNull() ?: return@withContext null
        try {
            computeDominant(px)?.also { colors[path] = it }
        } finally {
            decodes.incrementAndGet()
            decodeMillis.addAndGet(elapsedMillis() - startedAt)
        }
    }



    private fun computeDominant(src: Pixels): Int? {
        if (src.width <= 0 || src.height <= 0) return null
        val px = src.argb

        val buckets = HashMap<Int, Double>()
        for (c in px) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val maxc = max(r, max(g, b))
            val minc = min(r, min(g, b))
            val v = maxc / 255.0
            val s = if (maxc == 0) 0.0 else (maxc - minc) / maxc.toDouble()
            // Skip near-black, and washed-out near-white — we want a vivid ambiance color.
            if (v < 0.15 || (s < 0.12 && v > 0.9)) continue
            val bucket = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            buckets[bucket] = (buckets[bucket] ?: 0.0) + (0.5 + s * 0.8 + v * 0.4)
        }
        val best = buckets.maxByOrNull { it.value }?.key ?: return null
        val r = ((best shr 8) and 0xF) * 17
        val g = ((best shr 4) and 0xF) * 17
        val b = (best and 0xF) * 17
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
