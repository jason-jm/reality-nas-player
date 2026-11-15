package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The metadata language as TMDB is asked for it: no script, no extensions, Norwegian as TMDB files it. */
class MetadataLanguageTest {

    private val saved = TmdbConfig.language

    @After fun restore() { TmdbConfig.language = saved }

    @Test fun `the headset's own tags are cleaned for TMDB`() {
        assertEquals("zh-CN", TmdbConfig.requestTag("zh-Hans-CN"))
        assertEquals("zh-TW", TmdbConfig.requestTag("zh-Hant-TW"))
        assertEquals("zh-HK", TmdbConfig.requestTag("zh-Hant-HK"))
        assertEquals("zh-TW", TmdbConfig.requestTag("zh-Hant"))
        assertEquals("zh-CN", TmdbConfig.requestTag("zh"))
        assertEquals("de-DE", TmdbConfig.requestTag("de-DE-u-mu-celsius"))
        assertEquals("no-NO", TmdbConfig.requestTag("nb-NO"))
        assertEquals("no-NO", TmdbConfig.requestTag("nb"))
        assertEquals("fr", TmdbConfig.requestTag("fr"))
        assertEquals("pt-BR", TmdbConfig.requestTag("pt_BR"))
        assertEquals("en-US", TmdbConfig.requestTag(""))
    }

    @Test fun `certifications are read for the reader's country`() {
        TmdbConfig.language = "fr"
        assertEquals("FR", TmdbConfig.region)
        TmdbConfig.language = "de-AT"
        assertEquals("AT", TmdbConfig.region)
        TmdbConfig.language = "zh-Hans-CN"
        assertEquals("zh-CN", TmdbConfig.language)
        assertEquals("CN", TmdbConfig.region)
        TmdbConfig.language = "nb-NO"
        assertEquals("NO", TmdbConfig.region)
    }
}
