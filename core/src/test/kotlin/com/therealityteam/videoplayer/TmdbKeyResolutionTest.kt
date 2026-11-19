package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Key selection is the same for publisher builds and source builds with no bundled key. */
class TmdbKeyResolutionTest {

    @After
    fun reset() {
        TmdbConfig.apiKey = ""
        TmdbConfig.offline = false
    }

    @Test
    fun theUsersOwnKeyAlwaysWins() {
        TmdbConfig.apiKey = "  user-key  "
        assertEquals("user-key", TmdbConfig.effectiveKey)
    }

    @Test
    fun aBlankUserKeyFallsBackToTheBuiltIn() {
        TmdbConfig.apiKey = "   "
        assertTrue("Blank user key selects the bundled key", TmdbConfig.effectiveKey == TmdbConfig.BUILT_IN_KEY)
    }

    @Test
    fun offlineSilencesTheBuiltInKeyToo() {
        TmdbConfig.apiKey = "user-key"
        TmdbConfig.offline = true
        assertEquals("offline must mean no key at all, not fall-back-to-built-in", "", TmdbConfig.effectiveKey)
    }
}
