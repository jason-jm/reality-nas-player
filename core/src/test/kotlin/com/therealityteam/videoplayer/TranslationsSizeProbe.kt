package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import com.therealityteam.videoplayer.util.Http
import org.junit.Test

/**
 * Is one bigger request cheaper than two small ones?
 *
 * The English title of a work whose name is only Chinese costs a second full details request —
 * about a third of all the TMDB traffic in a scrape. TMDB can append every translation to the first
 * request instead, but a translation carries that language's whole overview, so the saved round
 * trip may cost more bytes than it is worth. This prints both so the choice is made on numbers.
 */
class TranslationsSizeProbe {
    @Test fun compare() {
        if (System.getProperty("aura.net") != "1") { println("skipped"); return }
        val key = TmdbConfig.effectiveKey
        val api = "https://api.themoviedb.org/3"
        // A blockbuster (many translations), a Chinese film, and a Japanese series.
        listOf("movie/27205" to "Inception", "movie/12477" to "Grave of the Fireflies", "tv/1429" to "Attack on Titan")
            .forEach { (path, name) ->
                val ratings = if (path.startsWith("movie")) "release_dates" else "content_ratings"
                val base = "$api/$path?api_key=$key&language=zh-CN&append_to_response=credits,external_ids,$ratings"
                val now = Http.get(base)?.length ?: -1
                val withT = Http.get("$base,translations")?.length ?: -1
                val second = Http.get("$api/$path?api_key=$key&language=en-US")?.length ?: -1
                println("$name: details=$now  details+translations=$withT  (+${withT - now})   separate en call=$second")
            }
    }
}
