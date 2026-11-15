package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import com.therealityteam.videoplayer.util.Http
import org.junit.Test
import java.io.File

/**
 * Does keeping the artwork connection alive actually pay?
 *
 * Artwork is the busiest host in a scrape — one 1,080-file shelf asked image.tmdb.org 1,944 times —
 * and until now every one of those downloads closed its socket in a `finally`, so each image bought
 * a fresh TCP and TLS handshake. This probe downloads the same real posters twice, once with the
 * JVM's connection pool on and once with it off, in alternating blocks of ten so that neither arm
 * gets all the warm network or all the cold.
 *
 * Not a unit test: it talks to the live CDN and is skipped unless -Daura.net=1.
 */
class ArtworkKeepAliveProbe {

    @Test
    fun keepingTheArtworkConnectionAliveIsWorthMeasuring() {
        if (System.getProperty("aura.net") != "1") { println("skipped (pass -Daura.net=1)"); return }
        val urls = posterUrls(40)
        if (urls.size < 20) { println("only ${urls.size} posters found; not enough to measure"); return }
        println("measuring ${urls.size} posters in blocks of 10")

        val dir = File(System.getProperty("java.io.tmpdir"), "aura-art-probe")
        var pooledMs = 0L
        var closedMs = 0L
        var bytes = 0L

        urls.chunked(10).forEachIndexed { block, chunk ->
            // Alternate which arm runs first: a block that always measured the pool second would be
            // measuring the CDN's warm cache, not the handshake.
            val poolFirst = block % 2 == 0
            val arms = if (poolFirst) listOf(true, false) else listOf(false, true)
            arms.forEach { pool ->
                System.setProperty("http.keepAlive", if (pool) "true" else "false")
                dir.deleteRecursively(); dir.mkdirs()
                val t0 = System.nanoTime()
                chunk.forEachIndexed { i, u -> Http.download(u, File(dir, "$i.jpg")) }
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (pool) pooledMs += ms else closedMs += ms
                if (pool) bytes += dir.listFiles().orEmpty().sumOf { it.length() }
            }
            println("  block $block: pooled=${pooledMs}ms closed=${closedMs}ms (running totals)")
        }
        System.clearProperty("http.keepAlive")
        dir.deleteRecursively()

        val saved = 100.0 * (closedMs - pooledMs) / closedMs
        println("pooled  ${pooledMs}ms")
        println("closed  ${closedMs}ms")
        println("bytes   ${bytes / 1024}KB")
        println("saving  ${"%.1f".format(saved)}%")
    }

    /** Real poster paths, taken from whatever TMDB is currently showing as popular. */
    private fun posterUrls(want: Int): List<String> {
        val key = TmdbConfig.effectiveKey
        if (key.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        for (page in 1..4) {
            val body = Http.get("https://api.themoviedb.org/3/movie/popular?api_key=$key&page=$page") ?: continue
            Regex("\"poster_path\":\"(\\\\?/[^\"]+)\"").findAll(body).forEach { m ->
                val path = m.groupValues[1].replace("\\/", "/")
                out += "https://image.tmdb.org/t/p/w500$path"
            }
            if (out.size >= want) break
        }
        return out.distinct().take(want)
    }
}
