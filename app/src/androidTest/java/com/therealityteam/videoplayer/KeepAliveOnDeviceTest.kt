package com.therealityteam.videoplayer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import com.therealityteam.videoplayer.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Is keeping the connection alive worth anything ON ANDROID?
 *
 * The saving was first measured on a laptop JVM, which is not the stack that ships: Android's
 * HttpURLConnection is OkHttp underneath, with its own idle pool, and a desktop JDK result is not
 * evidence about it. This asks the real CDN from the device, both at one request at a time (where a
 * single connection is enough to be reused) and at the twelve the scrape actually runs (where the
 * idle pool has several hosts to serve at once and may simply thrash).
 *
 * The two arms are the mechanism itself — read the body to the end and leave the socket, against
 * disconnect() — so no production seam is needed, and they alternate in blocks because a phone's
 * radio and a CDN's cache both warm up.
 */
@RunWith(AndroidJUnit4::class)
class KeepAliveOnDeviceTest {

    private val dir by lazy {
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ka-probe")
    }

    @Test
    fun oneAtATime() = measure(workers = 1)

    @Test
    fun atScrapeConcurrency() = measure(workers = 12)

    private fun measure(workers: Int) {
        val urls = posters(BLOCK * BLOCKS)
        if (urls.size < BLOCK * 2) {
            println("KABENCH($workers) only ${urls.size} posters — skipped"); return
        }
        var pooledMs = 0L
        var closedMs = 0L
        urls.chunked(BLOCK).forEachIndexed { block, chunk ->
            val arms = if (block % 2 == 0) listOf(true, false) else listOf(false, true)
            arms.forEach { pool ->
                dir.deleteRecursively(); dir.mkdirs()
                val ms = fetchAll(chunk, workers, pool)
                if (pool) pooledMs += ms else closedMs += ms
            }
        }
        dir.deleteRecursively()
        val saving = 100.0 * (closedMs - pooledMs) / closedMs
        println("KABENCH workers=$workers pooled=${pooledMs}ms closed=${closedMs}ms saving=${"%.1f".format(saving)}%")
    }

    private fun fetchAll(urls: List<String>, workers: Int, pool: Boolean): Long = runBlocking {
        val gate = Semaphore(workers)
        val t0 = SystemClock.elapsedRealtime()
        coroutineScope {
            urls.mapIndexed { i, u ->
                async(Dispatchers.IO) { gate.withPermit { fetch(u, File(dir, "$i.jpg"), pool) } }
            }.awaitAll()
        }
        SystemClock.elapsedRealtime() - t0
    }

    /** The two shapes under test: leave a fully-read connection alone, or close it. */
    private fun fetch(url: String, dest: File, pool: Boolean) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8_000
                conn.readTimeout = 20_000
                if (conn.responseCode !in 200..299) {
                    conn.errorStream?.use { it.readBytes() }
                    return@runCatching
                }
                conn.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            } finally {
                if (!pool) conn.disconnect()
            }
        }
    }

    private fun posters(want: Int): List<String> {
        val key = TmdbConfig.effectiveKey
        if (key.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        for (page in 1..6) {
            val body = Http.get("https://api.themoviedb.org/3/movie/popular?api_key=$key&page=$page") ?: continue
            Regex("\"poster_path\":\"(\\\\?/[^\"]+)\"").findAll(body).forEach {
                out += "https://image.tmdb.org/t/p/w500" + it.groupValues[1].replace("\\/", "/")
            }
            if (out.size >= want) break
        }
        return out.distinct().take(want)
    }

    private companion object {
        const val BLOCK = 12
        const val BLOCKS = 6
    }
}
