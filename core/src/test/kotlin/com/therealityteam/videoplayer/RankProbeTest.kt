package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AniListClient
import com.therealityteam.videoplayer.data.scrape.AnimeMatch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Dev tool: what a single query actually brings back, and what the guard makes of it.
 *
 * [AnimeMatch.pick] weighs half a dozen signals in order, so "why did this file land there" cannot
 * be reasoned out from the chosen entry alone — you need the candidates it chose BETWEEN. Prints
 * every result with the fields the ranking reads, whether `corresponds` lets it through, and which
 * one `pick` takes.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*RankProbeTest*' \
 *       -Daura.rank.query='機動警察パトレイバー the Movie' -Daura.rank.movie=1
 */
class RankProbeTest {

    @Test fun rank() {
        val query = System.getProperty("aura.rank.query") ?: return
        val wantMovie = System.getProperty("aura.rank.movie") == "1"
        val season = System.getProperty("aura.rank.season")?.toIntOrNull()
        val results = runBlocking { AniListClient().search(query) }
        println("QUERY [$query] wantMovie=$wantMovie season=$season → ${results.size} results")
        results.take(12).forEach { r ->
            val ok = if (AnimeMatch.corresponds(query, r)) "keep" else "drop"
            println(
                "  $ok #${r.providerId} ${r.kind} fmt=${r.format} y=${r.year} pop=${r.popularity}\n" +
                    "        title=${r.title}\n        romaji=${r.romajiTitle}  eng=${r.englishTitle}\n" +
                    "        native=${r.originalTitle}  syn=${r.synonyms.take(4)}"
            )
        }
        val picked = AnimeMatch.pick(query, results, season = season, wantMovie = wantMovie)
        println("PICKED → #${picked?.providerId} ${picked?.title}")
    }
}
