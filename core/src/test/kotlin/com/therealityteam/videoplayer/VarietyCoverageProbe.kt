package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import com.therealityteam.videoplayer.util.Http
import org.json.JSONObject
import org.junit.Test

/**
 * Can the databases we use identify a variety show at all?
 *
 * A shelf is only worth having if what lands on it can be named. Variety is the one category in the
 * sidebar with nothing on it here, so before deciding whether it earns its place, ask TMDB about the
 * best-known variety shows from the three countries that make most of them.
 */
class VarietyCoverageProbe {
    @Test fun doTheyExist() {
        if (System.getProperty("aura.net") != "1") { println("skipped"); return }
        val key = TmdbConfig.effectiveKey
        listOf(
            "奔跑吧" to "zh", "极限挑战" to "zh", "向往的生活" to "zh", "奇葩说" to "zh",
            "乘风破浪的姐姐" to "zh", "王牌对王牌" to "zh",
            "Running Man" to "ko", "무한도전" to "ko", "신서유기" to "ko", "아는 형님" to "ko",
            "水曜日のダウンタウン" to "ja", "ガキの使いやあらへんで" to "ja", "VS嵐" to "ja",
        ).forEach { (name, lang) ->
            val url = "https://api.themoviedb.org/3/search/tv?api_key=$key&language=zh-CN&query=" +
                java.net.URLEncoder.encode(name, "UTF-8")
            val body = Http.get(url)
            val first = runCatching { JSONObject(body!!).optJSONArray("results")?.optJSONObject(0) }.getOrNull()
            if (first == null) {
                println("VARIETY %-22s [%s]  找不到".format(name, lang))
            } else {
                val ids = first.optJSONArray("genre_ids")
                val genres = (0 until (ids?.length() ?: 0)).map { ids!!.optInt(it) }
                println(
                    "VARIETY %-22s [%s]  -> %-28s (%s) genres=%s".format(
                        name, lang, first.optString("name").take(28),
                        first.optString("first_air_date").take(4), genres,
                    ),
                )
            }
            Thread.sleep(250)
        }
        // What TMDB's TV genre ids mean, so the numbers above can be read.
        val g = Http.get("https://api.themoviedb.org/3/genre/tv/list?api_key=$key&language=zh-CN")
        println("TVGENRES $g")
    }
}
