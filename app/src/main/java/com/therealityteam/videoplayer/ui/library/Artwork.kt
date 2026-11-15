package com.therealityteam.videoplayer.ui.library

import android.content.Context
import android.graphics.BitmapFactory
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.data.scrape.ArtworkCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decode a cached artwork file off the main thread, at roughly [reqWidthPx] wide.
 *
 * Posters can be very large now (Bangumi covers are ~2000px across); decoding them full-size would
 * use ~25 MB each and OOM the poster wall. We read just the bounds first, then pick the largest
 * power-of-two `inSampleSize` that still keeps the decoded width ≥ [reqWidthPx], and let the
 * decoder finish the remainder of the reduction itself.
 *
 * That last step matters more than it sounds. Sub-sampling alone only halves, so a 500px poster in
 * a card asking for 400 came back at 500 and a 2000px one at 500 too — measured on a real library,
 * the average card held 1.2 MB for a picture it drew at 400px. Handing the decoder an explicit
 * source/target density makes it scale during the decode, so each poster costs what it shows.
 */
/**
 * How to get from a [srcWidth]-wide file to a [reqWidthPx]-wide bitmap: how far the decoder may
 * sub-sample, and the width it should land on. Pure arithmetic, kept apart from the decoder so the
 * sizing rule can be checked without a device.
 */
internal data class Reduction(val sample: Int, val targetWidth: Int)

internal fun reductionFor(srcWidth: Int, reqWidthPx: Int): Reduction {
    // A caller with no width to offer gets the picture as it is. Guarding here rather than trusting
    // the loop: with a request of zero it doubles until the sample overflows and then divides by it.
    if (srcWidth <= 0 || reqWidthPx <= 0) return Reduction(sample = 1, targetWidth = srcWidth)
    var sample = 1
    while (srcWidth / (sample * 2) >= reqWidthPx) sample *= 2
    val sampled = srcWidth / sample
    // Only ever downwards: a poster smaller than the card is left alone rather than blown up.
    val target = if (reqWidthPx in 1 until sampled) reqWidthPx else sampled
    return Reduction(sample, target)
}

private fun optionsFor(srcWidth: Int, reqWidthPx: Int): BitmapFactory.Options {
    val (sample, target) = reductionFor(srcWidth, reqWidthPx)
    return BitmapFactory.Options().apply {
        inSampleSize = sample
        val sampled = srcWidth / sample
        if (target < sampled) {
            inScaled = true
            inDensity = sampled
            inTargetDensity = target
        }
    }
}

/**
 * Decoded bitmaps kept by bytes, so a card scrolled back into view is drawn from memory instead of
 * decoded from disk again — the wall re-decoded every poster on every pass over it.
 *
 * Sized from the heap this device actually granted us rather than a fixed number: the same eighth
 * that Android's own bitmap-cache guidance uses. A fixed 24 MB held nineteen of the sixty posters
 * a couple of screenfuls of wall had just decoded, so scrolling back always went to disk; a headset
 * with a large heap can hold the whole visible wall several times over, and a small one still gets
 * the old budget.
 */
private val decoded = object : android.util.LruCache<String, ImageBitmap>(cacheBudgetBytes()) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

private fun cacheBudgetBytes(): Int =
    (Runtime.getRuntime().maxMemory() / 8).coerceIn(24L * 1024 * 1024, 96L * 1024 * 1024).toInt()

/** Drops [path]'s decoded copies — for a file written over in place (ResumeFrame). */
fun forgetArtwork(path: String) {
    decoded.snapshot().keys.filter { it.startsWith("$path@") }.forEach { decoded.remove(it) }
}

/** The bitmap if it was decoded already — for a first frame without a flash of placeholder. */
fun cachedArtwork(path: String?, reqWidthPx: Int): ImageBitmap? = path?.let { decoded.get("$it@$reqWidthPx") }

/**
 * A person's headshot, fetched on first sight.
 *
 * The scraper no longer downloads these — see [Person.profileUrl]. The file it would have written
 * is named after the URL, so a library scraped before the change finds its existing file here
 * instead of fetching again, and a face nobody opens is never fetched at all. Returns null with no
 * network (or none on record), which the cast row draws as an initials circle.
 */
/**
 * The one artwork cache the UI fetches through.
 *
 * [decodeProfile] and [decodeStill] are called from `produceState`, whose block runs in the
 * composition's context — the MAIN thread. Building an ArtworkCache there touched the disk once per
 * avatar, twenty times over for one detail page, on the thread that draws it. One instance, made
 * off-main the first time anybody asks, and held against the application context.
 */
private val artworkLock = Any()

@Volatile
private var sharedArtwork: ArtworkCache? = null

private suspend fun artwork(context: Context): ArtworkCache =
    sharedArtwork ?: withContext(Dispatchers.IO) {
        synchronized(artworkLock) {
            sharedArtwork ?: ArtworkCache(context.applicationContext).also { sharedArtwork = it }
        }
    }

/**
 * An episode still, fetched the first time a card wants to draw it.
 *
 * Like a headshot (see [decodeProfile]) the scraper no longer downloads these; unlike a headshot
 * there is always something to show meanwhile — the title's backdrop — so the caller draws that
 * first and swaps this in when it arrives. Returns null when there is no still on record or no
 * network, and the fallback simply stays.
 */
suspend fun decodeStill(context: Context, item: MediaItem, reqWidthPx: Int): ImageBitmap? {
    item.stillPath?.let { existing -> decodeArtwork(existing, reqWidthPx)?.let { return it } }
    val url = item.stillUrl ?: return null
    return decodeArtwork(artwork(context).cache(url, ArtworkCache.stillKey(url)), reqWidthPx)
}

/**
 * A search result's poster, for the 修正匹配 list — fetched into the artwork cache the first time a
 * row shows it, and nowhere else. TMDB serves its posters at several widths; a 48dp thumbnail has
 * no use for the 500px one the scraper asks for, so the small size is fetched instead.
 */
suspend fun decodeMatchPoster(context: Context, url: String?, reqWidthPx: Int): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    val small = url.replace("/t/p/w500/", "/t/p/w185/")
    val key = "match_" + small.hashCode().toUInt().toString(16)
    val path = runCatching { artwork(context).cache(small, key) }.getOrNull()
    return decodeArtwork(path, reqWidthPx)
}

/**
 * AniList's stand-in for a person without a photo is itself a picture — "NO IMAGE" on navy, at
 * `…/staff/large/default.jpg`. It is no face, and the initials avatar says that better.
 */
internal fun isPlaceholderPhoto(url: String?): Boolean =
    url != null && url.substringBefore('?').substringAfterLast('/').startsWith("default.", ignoreCase = true)

suspend fun decodeProfile(context: Context, person: Person, reqWidthPx: Int): ImageBitmap? {
    if (isPlaceholderPhoto(person.profileUrl)) return null
    person.profilePath?.let { existing -> decodeArtwork(existing, reqWidthPx)?.let { return it } }
    val url = person.profileUrl ?: return null
    val path = artwork(context).cache(url, ArtworkCache.personKey(url))
    return decodeArtwork(path, reqWidthPx)
}

suspend fun decodeArtwork(path: String?, reqWidthPx: Int): ImageBitmap? {
    if (path == null) return null
    val key = "$path@$reqWidthPx"
    decoded.get(key)?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val srcWidth = bounds.outWidth
            if (srcWidth <= 0) return@runCatching null
            BitmapFactory.decodeFile(path, optionsFor(srcWidth, reqWidthPx))
                ?.asImageBitmap()?.also { decoded.put(key, it) }
        }.getOrNull()
    }
}
