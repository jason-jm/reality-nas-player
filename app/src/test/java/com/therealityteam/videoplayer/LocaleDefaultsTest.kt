package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.GenreCn
import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app assumes about who is reading it.
 *
 * The metadata language used to be `zh-CN` for everyone, and the things that hang off it — where a
 * work's NAME comes from, what its genres are called — followed. A viewer in Munich got Chinese
 * titles for their own films.
 */
class LocaleDefaultsTest {

    private val original = TmdbConfig.language

    @After fun restore() { TmdbConfig.language = original }

    @Test fun `a chinese reader gets the chinese vocabulary`() {
        TmdbConfig.language = "zh-CN"
        assertTrue(TmdbConfig.isChinese)
        assertEquals("科幻", GenreCn.zh("Sci-Fi"))
    }

    @Test fun `everyone else reads AniList's English genres in their language, as TMDB names them`() {
        listOf("en-US" to "Sci-Fi", "de-DE" to "Science Fiction", "ja-JP" to "サイエンスフィクション", "pt-BR" to "Ficção científica", "fr" to "Science-Fiction").forEach { (tag, sciFi) ->
            TmdbConfig.language = tag
            assertFalse(tag, TmdbConfig.isChinese)
            // AniList answers in English; the genre tables (core resources /genres) name it as TMDB does in the
            // reader's language, so an anime's genres file beside the films' in the 筛选.
            assertEquals(tag, sciFi, GenreCn.zh("Sci-Fi"))
        }
    }

    @Test fun `the traditional and simplified tags both read as chinese`() {
        listOf("zh", "zh-CN", "zh-TW", "zh-Hant-HK").forEach {
            TmdbConfig.language = it
            assertTrue(it, TmdbConfig.isChinese)
        }
    }

    @Test fun `subtitles prefer the reader's language in both of its three-letter codes`() {
        fun langs(tag: String) =
            com.therealityteam.videoplayer.data.playback.ExoPlaybackManager
                .preferredTextLanguages(java.util.Locale.forLanguageTag(tag)).toList()
        // Two codes exist for twenty-odd languages and containers use both; asking for only the one
        // Java hands out misses half the tracks in exactly the languages this matters for.
        assertTrue(langs("zh-CN").toString(), langs("zh-CN").containsAll(listOf("zho", "chi", "zh")))
        assertTrue(langs("de-DE").toString(), langs("de-DE").containsAll(listOf("deu", "ger", "de")))
        assertTrue(langs("fr").toString(), langs("fr").containsAll(listOf("fra", "fre", "fr")))
        // English is the fallback for everyone, and a language with one code is not padded.
        assertTrue(langs("ja").toString(), langs("ja").containsAll(listOf("jpn", "ja", "eng")))
        // …and a German reader no longer has Chinese ahead of their own tracks.
        assertFalse(langs("de-DE").toString(), langs("de-DE").any { it in setOf("zho", "chi") })
    }
}
