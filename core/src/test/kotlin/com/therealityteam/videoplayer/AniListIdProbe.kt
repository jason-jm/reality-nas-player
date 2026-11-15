package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AniListClient
import org.junit.Test

/**
 * What a database entry actually IS, when a card's title leaves it ambiguous.
 *
 * Two cards on the wall can share a name for two very different reasons: the right entries whose
 * localized names happen to collide, or one of them matched to the wrong entry. Only the entry
 * itself settles which, so this asks.
 */
class AniListIdProbe {
    @Test fun whatAreThese() {
        if (System.getProperty("aura.net") != "1") { println("skipped"); return }
        val t = AniListClient.httpTransport()
        (System.getProperty("aura.ids") ?: "21403,3572,76,19836,4985").split(",").forEach { id ->
            val q = "{\"query\":\"{ Media(id: $id, type: ANIME) { id format episodes " +
                "startDate { year } title { romaji english native } } }\"}"
            println("$id -> ${t.post(q)?.body?.take(360)}")
            Thread.sleep(2_200)
        }
    }
}
