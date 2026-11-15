package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AniListClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AniList switches itself off under load and answers every request with a 403 for hours. A scan
 * must notice and stop asking, or every anime file pays the request spacing for nothing.
 */
class AniListOutageTest {

    private val disabled = AniListClient.Reply(
        403,
        """{"errors":[{"message":"The AniList API has been temporarily disabled due to severe stability issues.","status":403}],"data":null}""",
    )
    private val ok = AniListClient.Reply(200, """{"data":{"Page":{"media":[]}}}""")

    @Test
    fun aDisabledServiceTripsTheBreakerAndLookupsStopHittingIt() = runBlocking {
        var calls = 0
        val client = AniListClient(cacheDir = null, transport = { calls++; disabled }, minIntervalMs = 0)
        repeat(3) { assertTrue(client.search("query $it").isEmpty()) }
        assertEquals(3, calls)
        assertTrue(client.isDown)
        assertTrue(client.search("another query").isEmpty())
        assertEquals(3, calls) // skipped, not sent
        assertEquals(1, client.skipped.get())
    }

    @Test
    fun aSuccessResetsTheCount() = runBlocking {
        val replies = ArrayDeque(listOf(disabled, disabled, ok, disabled, disabled))
        val client = AniListClient(cacheDir = null, transport = { replies.removeFirst() }, minIntervalMs = 0)
        repeat(5) { client.search("q$it") }
        assertFalse(client.isDown) // never three in a row
        assertTrue(replies.isEmpty())
    }

    @Test
    fun beingRateLimitedIsNotAnOutage() = runBlocking {
        val limited = AniListClient.Reply(429, "", retryAfterSec = 0)
        var calls = 0
        val client = AniListClient(cacheDir = null, transport = { calls++; limited }, minIntervalMs = 0)
        repeat(3) { client.search("q$it") }
        assertTrue(calls >= 3)
        assertFalse(client.isDown)
    }

    @Test
    fun everyRequestSaysWhoWeAreAndCarriesAReferer() {
        val id = AniListClient.IDENTITY
        // Without a Referer the API answers 403 with an outage sentence, whatever the User-Agent.
        val referer = id["Referer"]
        assertTrue("a Referer must be sent", !referer.isNullOrBlank())
        // …and it names this app rather than impersonating AniList's own web client.
        assertFalse("must not pose as the site's web client", referer!!.contains("anilist", ignoreCase = true))
        val ua = id["User-Agent"]
        assertTrue("a User-Agent must name the app", ua != null && ua.startsWith("RealityNASPlayer/"))
    }

    @Test
    fun theBreakerKeepsTheServicesOwnWordsForTheNextPersonToLook() {
        val disabled = """{"errors":[{"message":"The AniList API has been temporarily disabled due to severe stability issues.","status":403}]}"""
        val client = AniListClient(transport = { AniListClient.Reply(403, disabled, null) }, minIntervalMs = 0)
        runBlocking { client.search("anything") }
        val why = client.lastFailure
        assertTrue("the reason is kept", why != null)
        assertTrue("it carries the status", why!!.startsWith("403"))
        assertTrue("and the service's own sentence", why.contains("temporarily disabled"))
        // A later success clears it, so a stale reason never explains a healthy run.
        val ok = AniListClient(transport = { AniListClient.Reply(200, """{"data":{"Page":{"media":[]}}}""", null) }, minIntervalMs = 0)
        runBlocking { ok.search("anything") }
        assertEquals(null, ok.lastFailure)
    }
}
