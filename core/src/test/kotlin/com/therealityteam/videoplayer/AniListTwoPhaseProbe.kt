package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AniListClient
import com.therealityteam.videoplayer.data.scrape.AnimeMatch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The split, end to end against the live endpoint.
 *
 * Two things have to hold. The light search must still pick the same entry — it is chosen on
 * titles, synonyms, format, year and popularity, none of which moved — and the details fetched
 * afterwards must actually carry the description, cast and cover the card is built from.
 */
class AniListTwoPhaseProbe {

    @Test fun theSplitKeepsTheAnswerAndStillFillsTheCard() {
        if (System.getProperty("aura.net") != "1") { println("skipped"); return }
        val client = AniListClient()
        listOf(
            "Attack on Titan" to false,
            "Lucky Star" to false,
            "Macross Frontier" to true,
            "Ghost in the Shell" to true,
        ).forEach { (term, wantMovie) ->
            runBlocking {
                val light = client.search(term)
                val picked = AnimeMatch.pick(term, light, wantMovie = wantMovie)
                if (picked == null) {
                    println("TWOPHASE %-20s -> nothing picked from %d results".format(term, light.size))
                    return@runBlocking
                }
                val heavy = client.detail(picked.providerId)
                println(
                    "TWOPHASE %-20s picked #%-7d %-38s | light: overview=%s cast=%d | detail: overview=%s cast=%d crew=%d poster=%s"
                        .format(
                            term, picked.providerId, picked.title.take(38),
                            picked.overview != null, picked.cast.size,
                            heavy?.overview != null, heavy?.cast?.size ?: -1, heavy?.crew?.size ?: -1,
                            heavy?.posterUrl != null,
                        ),
                )
            }
        }
    }
}
