package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.util.Http
import com.therealityteam.videoplayer.util.TransientHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions in [Http] that do not need a network: which answers are worth asking again,
 * and how long a server asked us to wait. The retry loop itself is sockets, and only a real
 * server can answer for that.
 */
class HttpRetryTest {

    @Test
    fun aRateLimitIsWorthAskingAgainAndAMissIsNot() {
        // The whole point of the split: 429 used to be indistinguishable from 404, so a throttled
        // scrape wrote its items off as "no such film" and cleared identities they already had.
        assertTrue("429 is the one that started this", Http.isTransient(429))
        assertTrue(Http.isTransient(500))
        assertTrue(Http.isTransient(503))
        assertFalse("404 is the server's final answer", Http.isTransient(404))
        assertFalse("a bad key is not fixed by waiting", Http.isTransient(401))
        assertFalse(Http.isTransient(400))
        assertFalse(Http.isTransient(200))
    }

    @Test
    fun aProviderFailureOnlyNamesTheHost() {
        val url = "https://viewer:test-password@example.org/private-path?api_key=test-key"
        val message = TransientHttpException(url).message.orEmpty()
        assertTrue(message.endsWith("example.org"))
        assertFalse(message.contains("test-"))
        assertFalse(message.contains("private-path"))
        assertFalse(TransientHttpException("invalid url?api_key=test-key").toString().contains("test-key"))
    }

    @Test
    fun retryAfterInSecondsBecomesMilliseconds() {
        assertEquals(5_000L, Http.retryAfterMs("5"))
        assertEquals(0L, Http.retryAfterMs("0"))
        assertEquals("whitespace is not the server being unparseable", 2_000L, Http.retryAfterMs(" 2 "))
    }

    @Test
    fun retryAfterAsAnHttpDateBecomesTheDelayUntilThen() {
        // Both forms are in the wild; the seconds form is commoner but the date form is legal.
        val now = 1_700_000_000_000L
        val tenSecondsLater = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .format(java.time.Instant.ofEpochMilli(now + 10_000).atZone(java.time.ZoneOffset.UTC))
        assertEquals(10_000L, Http.retryAfterMs(tenSecondsLater, now))
    }

    @Test
    fun aDateAlreadyPastMeansGoNowNotGoBackwards() {
        val now = 1_700_000_000_000L
        val tenSecondsAgo = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .format(java.time.Instant.ofEpochMilli(now - 10_000).atZone(java.time.ZoneOffset.UTC))
        assertEquals(0L, Http.retryAfterMs(tenSecondsAgo, now))
    }

    @Test
    fun nothingUsableMeansFallBackToOurOwnBackoff() {
        assertNull(Http.retryAfterMs(null))
        assertNull(Http.retryAfterMs(""))
        assertNull(Http.retryAfterMs("   "))
        assertNull("a header we cannot read must not become a zero-length wait", Http.retryAfterMs("soon"))
    }
}
