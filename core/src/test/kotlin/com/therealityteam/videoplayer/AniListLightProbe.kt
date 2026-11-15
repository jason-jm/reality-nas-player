package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AniListClient
import org.junit.Test

/**
 * What a search costs when it also drags back the cast of every result it will not use.
 *
 * The search asks for characters, their voice actors and the staff — for all TWELVE entries on the
 * page, of which the picker keeps one. None of it is read while choosing: that is decided on titles,
 * synonyms, format, year and popularity. This times the page as it is against the page the picker
 * actually needs, on real searches, and prints AniList's own idea of what each costs.
 */
class AniListLightProbe {

    private val heavy = """
        query (${'$'}search: String) {
          Page(perPage: 12) {
            media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
              id format seasonYear episodes duration isAdult averageScore popularity genres
              title { romaji english native }
              synonyms
              description(asHtml: false)
              coverImage { extraLarge large }
              characters(perPage: 12, sort: [ROLE, RELEVANCE]) {
                edges {
                  role
                  node { name { full native } }
                  voiceActors(language: JAPANESE, sort: [RELEVANCE]) { name { full native } image { large } }
                }
              }
              staff(perPage: 12, sort: [RELEVANCE]) {
                edges { role node { name { full native } image { large } } }
              }
            }
          }
        }
    """.trimIndent()

    private val light = """
        query (${'$'}search: String) {
          Page(perPage: 12) {
            media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
              id format seasonYear episodes duration isAdult averageScore popularity genres
              title { romaji english native }
              synonyms
            }
          }
        }
    """.trimIndent()

    @Test fun howMuchOfASearchIsWasted() {
        if (System.getProperty("aura.net") != "1") { println("skipped"); return }
        val t = AniListClient.httpTransport()
        listOf("Attack on Titan", "Lucky Star", "Ghost in the Shell", "Macross Frontier").forEach { term ->
            val h = timed(t, heavy, term)
            Thread.sleep(2_400)
            val l = timed(t, light, term)
            Thread.sleep(2_400)
            println("LIGHTPROBE %-22s heavy=%6d bytes %5dms   light=%5d bytes %5dms   %.1fx smaller"
                .format(term, h.first, h.second, l.first, l.second, h.first.toDouble() / l.first.coerceAtLeast(1)))
        }
    }

    private fun timed(t: AniListClient.Transport, query: String, term: String): Pair<Int, Long> {
        val body = org.json.JSONObject()
            .put("query", query)
            .put("variables", org.json.JSONObject().put("search", term))
            .toString()
        val t0 = System.nanoTime()
        val r = t.post(body)
        return (r?.body?.length ?: -1) to ((System.nanoTime() - t0) / 1_000_000)
    }
}
