package com.therealityteam.videoplayer.desktop.ui.images

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.data.scrape.ArtworkCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File

/**
 * Artwork off the disk and onto the screen: decoded with Skia at the width it is drawn at (a
 * 500-px poster on a 170-dp card at 2× is left alone; a 1280-px backdrop behind a detail page is
 * not decoded twice), kept in memory within a budget, fetched first when it is a still or a
 * headshot the scraper deliberately left online.
 */
object Images {
    /** A quarter of the heap, at most 384 MB: a whole wall of posters at 2×, with room to scroll. */
    private val budget: Long = (Runtime.getRuntime().maxMemory() / 4).coerceAtMost(384L shl 20)

    private val cache = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {}
    private var bytes = 0L

    /** At most four decodes at once: a wall scrolling fast must not queue up forty. */
    private val decoding = Semaphore(4)

    @Volatile
    var artwork: ArtworkCache? = null

    fun cached(path: String, widthPx: Int): ImageBitmap? = synchronized(cache) { cache[key(path, bucket(widthPx))] }

    suspend fun load(path: String?, widthPx: Int): ImageBitmap? {
        if (path.isNullOrBlank()) return null
        val bucket = bucket(widthPx)
        cached(path, widthPx)?.let { return it }
        return decoding.withPermit {
            cached(path, widthPx) ?: withContext(Dispatchers.IO) {
                runCatching { decode(File(path), bucket) }.getOrNull()
            }?.also { put(key(path, bucket), it) }
        }
    }

    /** Pictures taken from the videos themselves (FrameStills), set at start. */
    @Volatile
    var frameStills: (suspend (MediaItem) -> String?)? = null

    /**
     * An episode's still: the one on disk, else fetched now into the artwork cache — else, with
     * [frames], a frame out of the video itself.
     */
    suspend fun still(item: MediaItem, widthPx: Int, frames: Boolean = false): ImageBitmap? {
        item.stillPath?.let { load(it, widthPx)?.let { img -> return img } }
        item.stillUrl?.let { url -> load(artwork?.cache(url, ArtworkCache.stillKey(url)), widthPx)?.let { return it } }
        if (!frames) return null
        return load(frameStills?.invoke(item), widthPx)
    }

    /** A person's headshot, fetched the first time it is shown. */
    suspend fun profile(person: Person, widthPx: Int): ImageBitmap? {
        person.profilePath?.let { load(it, widthPx)?.let { img -> return img } }
        val url = person.profileUrl ?: return null
        return load(artwork?.cache(url, ArtworkCache.personKey(url)), widthPx)
    }

    /** A picture only known by its address (a search result's poster), fetched into the cache. */
    suspend fun remote(url: String?, widthPx: Int): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        return load(artwork?.cache(url, "remote_" + url.hashCode().toUInt().toString(16)), widthPx)
    }

    private fun decode(file: File, maxWidth: Int): ImageBitmap? {
        if (!file.isFile) return null
        val image = Image.makeFromEncoded(file.readBytes())
        if (image.width <= maxWidth) return image.toComposeImageBitmap()
        try {
            val w = maxWidth
            val h = (image.height.toLong() * w / image.width).toInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(w, h)
            try {
                surface.canvas.drawImageRect(
                    image,
                    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    Rect.makeWH(w.toFloat(), h.toFloat()),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                    null,
                    true,
                )
                return surface.makeImageSnapshot().toComposeImageBitmap()
            } finally {
                surface.close()
            }
        } finally {
            image.close()
        }
    }

    private fun put(key: String, img: ImageBitmap) = synchronized(cache) {
        cache.put(key, img)?.let { bytes -= it.sizeBytes() }
        bytes += img.sizeBytes()
        val it = cache.entries.iterator()
        while (bytes > budget && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            bytes -= e.value.sizeBytes()
            it.remove()
        }
    }

    private fun ImageBitmap.sizeBytes(): Long = width.toLong() * height * 4

    /** Widths in steps of 64 px, so neighbouring columns share one decode. */
    private fun bucket(px: Int): Int = ((px.coerceIn(64, 2560) + 63) / 64) * 64

    private fun key(path: String, width: Int) = "$path@$width"
}

/** An image for [path] at [widthPx], null until it has loaded (and if it cannot). */
@Composable
fun rememberImage(path: String?, widthPx: Int): State<ImageBitmap?> =
    produceState(initialValue = path?.let { Images.cached(it, widthPx) }, path, widthPx) {
        value = Images.load(path, widthPx)
    }
