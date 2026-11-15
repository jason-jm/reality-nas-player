package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.Matcher
import com.therealityteam.videoplayer.data.scrape.ParsedName
import com.therealityteam.videoplayer.data.scrape.TmdbMetadataClient
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Dev tool: what TMDB answers for a title, and what the matcher makes of it — from the JVM, so a
 * wrong identity can be chased without a headset. Hits the network; skipped unless
 * `-Daura.tmdb.probe=<file>` names a file of `title<TAB>year` lines (year may be empty).
 */
class TmdbProbeTest {
    @Test
    fun probe() {
        val path = System.getProperty("aura.tmdb.probe") ?: return
        val client = TmdbMetadataClient()
        java.io.File(path).readLines().filter { it.isNotBlank() }.forEach { line ->
            val (title, year) = line.split('\t').let { it[0] to it.getOrNull(1)?.toIntOrNull() }
            val parsed = ParsedName(title, year = year)
            // Both endpoints, as the engine asks them: films first, then TV (season = 1 selects it).
            for ((label, q) in listOf("movie" to parsed, "tv" to parsed.copy(season = 1))) {
                val hits = runBlocking { client.search(q) }
                val best = Matcher.best(q, hits)
                println("PROBE[$label] ${parsed.title} (${year ?: "-"}) -> ${hits.size} hits; best=${best?.let { "${it.title} (${it.year}) #${it.providerId}" } ?: "none"}")
                // -Daura.tmdb.details=1: the name the entry is shown by, once its details are read.
                if (best != null && System.getProperty("aura.tmdb.details") != null) {
                    println("   details: " + runBlocking { client.details(best.providerId, best.kind) }?.let { "${it.title} / ${it.originalTitle}" })
                }
                Matcher.ranked(q, hits).take(3).forEach { s ->
                    println("   ${"%.2f".format(s.score)}  ${s.result.title} / ${s.result.originalTitle} (${s.result.year}) pop=${"%.1f".format(s.result.popularity)} #${s.result.providerId}")
                    // -Daura.tmdb.alts=1: the other names TMDB knows each by, which a search result does not carry.
                    if (System.getProperty("aura.tmdb.alts") != null) {
                        println("      alts: " + runBlocking { client.alternativeTitles(s.result.providerId, s.result.kind) }.joinToString(" | "))
                    }
                }
            }
        }
    }
}
