package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AniListClient
import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import com.therealityteam.videoplayer.util.Http
import org.junit.Test

/** The whole vocabulary the databases can label a work with — what a shelf could be built on. */
class GenreVocabProbe {
    @Test fun everything() {
        if (System.getProperty("aura.net") != "1") { println("skipped"); return }
        val key = TmdbConfig.effectiveKey
        listOf("movie", "tv").forEach {
            println("TMDB-$it ${Http.get("https://api.themoviedb.org/3/genre/$it/list?api_key=$key&language=zh-CN")}")
        }
        val q = """{"query":"{ GenreCollection MediaTagCollection { name category isAdult } }"}"""
        val r = AniListClient.httpTransport().post(q)?.body
        println("ANILIST ${r?.take(1400)}")
    }
}
