package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.util.Offline
import com.therealityteam.videoplayer.data.model.RatingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Free, key-less IMDb ratings via IMDb's official public datasets (datasets.imdbws.com). Downloads
 * the small `title.ratings.tsv.gz` once (a few MB), parses it into a compact sorted index — numeric
 * tconst → rating×10, ~9 MB held — and binary-searches it per IMDb id. The cached file is reused
 * across sessions and refreshed only when older than a week.
 */
class ImdbDatasetClient(filesDir: File) {

    private val file = File(filesDir, "aura/imdb.ratings.tsv.gz").apply { parentFile?.mkdirs() }
    private val mutex = Mutex()

    // Each entry packs (numeric tconst << 16) | (rating × 10), sorted ascending → sorted by tconst.
    @Volatile private var packed: LongArray? = null
    @Volatile private var failed = false              // give up after one failed load this session

    suspend fun rating(imdbId: String?): RatingScore? = withContext(Dispatchers.IO) {
        val tconst = imdbId?.trim()?.removePrefix("tt")?.toIntOrNull() ?: return@withContext null
        if (packed == null && !failed) ensureLoaded()
        val arr = packed ?: return@withContext null
        val key = tconst.toLong() shl 16
        val raw = arr.binarySearch(key)
        val pos = if (raw >= 0) raw else -(raw + 1)
        if (pos >= arr.size || (arr[pos] ushr 16).toInt() != tconst) return@withContext null
        RatingScore(RatingSource.IMDB, RatingScore.tenths((arr[pos] and 0xFFFF).toInt() / 10.0))
    }

    private suspend fun ensureLoaded() {
        mutex.withLock {
            if (packed != null || failed) return
            val fresh = file.exists() && file.length() > 0 &&
                System.currentTimeMillis() - file.lastModified() < REFRESH_MS
            if (!fresh) runCatching { download() }
            if (!file.exists() || file.length() == 0L) {
                failed = true
                return
            }
            runCatching { parse() }.onFailure { failed = true }
            Unit
        }
    }

    private fun download() {
        if (Offline.refuses(URL)) return
        val tmp = File(file.parentFile, file.name + ".tmp")
        val conn = (URL(URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 60_000
        }
        try {
            if (conn.responseCode !in 200..299) return
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        if (tmp.length() > 0) { file.delete(); tmp.renameTo(file) } else tmp.delete()
    }

    /** Parse "tconst\taverageRating\tnumVotes" lines into packed longs, then sort by tconst. */
    private fun parse() {
        var arr = LongArray(1 shl 21) // primitive, no boxing
        var n = 0
        GZIPInputStream(file.inputStream().buffered()).bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val tab = line.indexOf('\t')
                if (tab <= 2) return@forEach
                val tconst = line.substring(2, tab).toIntOrNull() ?: return@forEach
                val tab2 = line.indexOf('\t', tab + 1)
                val ratingStr = if (tab2 > 0) line.substring(tab + 1, tab2) else line.substring(tab + 1)
                val r = ratingStr.toDoubleOrNull() ?: return@forEach
                if (n == arr.size) arr = arr.copyOf(arr.size * 2)
                arr[n++] = (tconst.toLong() shl 16) or ((r * 10).toLong() and 0xFFFF)
            }
        }
        val result = arr.copyOf(n)
        result.sort()
        packed = result
    }

    private companion object {
        const val URL = "https://datasets.imdbws.com/title.ratings.tsv.gz"
        const val REFRESH_MS = 7L * 24 * 3600 * 1000
    }
}
