package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three rules of key resolution. BUILT_IN_KEY is a compile-time constant (empty until a
 * release key is registered), so the built-in branch is asserted in the form that holds either
 * way: the user's key always wins, and offline silences everything — INCLUDING the built-in,
 * which is the bug this design exists to rule out (blanking only the user key would quietly
 * fall back to the built-in and keep making network calls 离线刮削 promised not to).
 */
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
        assertEquals(TmdbConfig.BUILT_IN_KEY, TmdbConfig.effectiveKey)
    }

    @Test
    fun offlineSilencesTheBuiltInKeyToo() {
        TmdbConfig.apiKey = "user-key"
        TmdbConfig.offline = true
        assertEquals("offline must mean no key at all, not fall-back-to-built-in", "", TmdbConfig.effectiveKey)
    }
}
