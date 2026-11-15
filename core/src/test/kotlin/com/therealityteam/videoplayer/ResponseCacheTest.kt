package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.ResponseCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The cache that lets a re-scan skip AniList's throttle. Getting it wrong is quiet and expensive:
 * a bad entry is served to every later scrape without anyone noticing.
 */
class ResponseCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache(ttlMs: Long = 60_000) = ResponseCache(tmp.root, ttlMs)

    @Test
    fun whatWasPutComesBack() {
        val c = cache()
        c.put("yuru camp", """{"data":{"Page":{"media":[]}}}""")
        assertEquals("""{"data":{"Page":{"media":[]}}}""", c.get("yuru camp"))
    }

    @Test
    fun aKeyNeverWrittenIsAMiss() {
        assertNull(cache().get("nothing here"))
    }

    @Test
    fun anExpiredEntryIsAMissAndIsRemoved() {
        val c = ResponseCache(tmp.root, ttlMs = -1) // everything is already older than the TTL
        c.put("k", "v")
        assertNull(c.get("k"))
        // …and it does not linger: the next lookup should not have to re-check a dead file.
        val dir = java.io.File(tmp.root, "aura/cache/responses")
        assertTrue("expired entry should be deleted", dir.listFiles().orEmpty().none { !it.name.endsWith(".tmp") })
    }

    @Test
    fun keysAreNotFilenames() {
        // Queries carry slashes, colons and CJK — `Fate/stay night`, `ＢＳ１スペシャル`. Hashing the
        // key is what keeps those from becoming paths of their own.
        val c = cache()
        c.put("Fate/stay night [Heaven's Feel]", "A")
        c.put("ＢＳ１スペシャル「山本五十六」", "B")
        assertEquals("A", c.get("Fate/stay night [Heaven's Feel]"))
        assertEquals("B", c.get("ＢＳ１スペシャル「山本五十六」"))
    }

    @Test
    fun anEmptyBodyIsNotServedAsAHit() {
        val c = cache()
        c.put("k", "")
        assertNull(c.get("k"))
    }
}
