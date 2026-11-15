package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import com.therealityteam.videoplayer.util.Http
import com.therealityteam.videoplayer.util.HttpResult
import com.therealityteam.videoplayer.util.Offline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Offline means none of the internet's databases — and nothing less than that, nothing more. */
class OfflineTest {
    @After fun online() { TmdbConfig.offline = false }

    @Test fun `offline refuses every database and picture host, and nothing on the LAN`() {
        TmdbConfig.offline = true
        listOf(
            "https://api.themoviedb.org/3/search/movie?query=x", "https://image.tmdb.org/t/p/w500/a.jpg",
            "https://graphql.anilist.co", "https://s4.anilist.co/file/anilistcdn/character/large/b1.png",
            "https://api.bgm.tv/v0/search/subjects", "https://lain.bgm.tv/pic/cover/l/1.jpg",
            "https://api.jikan.moe/v4/anime?q=x", "https://www.omdbapi.com/?i=tt1", "https://datasets.imdbws.com/title.ratings.tsv.gz",
            "https://m.media-amazon.com/images/M/x.jpg",
        ).forEach { assertTrue(it, Offline.refuses(it)) }
        listOf(
            "http://192.168.1.10:8096/Items/1/Download", "https://nas.local:5006/video/", "http://127.0.0.1:32400/library/sections",
            "https://mytmdb.org.example.com/x",
        ).forEach { assertFalse(it, Offline.refuses(it)) }
        TmdbConfig.offline = false
        assertFalse(Offline.refuses("https://api.themoviedb.org/3/x"))
    }

    @Test fun `a refused request never leaves the machine`() {
        TmdbConfig.offline = true
        val t0 = System.nanoTime()
        assertEquals(HttpResult.Transient, Http.fetch("https://api.themoviedb.org/3/configuration"))
        assertFalse(Http.download("https://image.tmdb.org/t/p/w92/x.jpg", File.createTempFile("aura", ".jpg")))
        assertTrue("answered without a connection", (System.nanoTime() - t0) / 1_000_000 < 50)
    }
}
