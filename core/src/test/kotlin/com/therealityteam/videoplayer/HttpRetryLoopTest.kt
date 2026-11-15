package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.util.Http
import com.therealityteam.videoplayer.util.HttpResult
import com.therealityteam.videoplayer.util.TransientHttpException
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The retry loop against a real socket, because the interesting behaviour is across requests:
 * a 429 must be asked again, a 404 must not be, and a wall of 429s must end as Transient rather
 * than as the empty answer the scrape engine would believe and act on.
 *
 * Every canned response carries `Retry-After: 0`, which keeps the exhaustion cases instant while
 * still exercising the header path — the built-in backoff would otherwise put ~8s of sleeping
 * into the unit-test suite.
 */
class HttpRetryLoopTest {

    private val server = ServerSocket(0)
    private val hits = AtomicInteger(0)

    /** Serves [statuses] in order, repeating the last one forever. */
    private fun serve(vararg statuses: Int, body: String = "{}") {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { return@thread }
                socket.use {
                    // Drain the request line + headers so the client's write completes.
                    val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val code = statuses[minOf(hits.getAndIncrement(), statuses.size - 1)]
                    val payload = if (code in 200..299) body else ""
                    val response = buildString {
                        append("HTTP/1.1 $code X\r\n")
                        append("Retry-After: 0\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${payload.toByteArray().size}\r\n")
                        append("Connection: close\r\n\r\n")
                        append(payload)
                    }
                    it.getOutputStream().apply { write(response.toByteArray()); flush() }
                }
            }
        }
    }

    private val url get() = "http://127.0.0.1:${server.localPort}/x"

    @Before
    fun clearLimits() = Http.forgetRateLimits()

    @After
    fun stop() = server.close()

    @Test
    fun aRateLimitIsRetriedAndTheSecondAnswerIsKept() {
        serve(429, 200, body = """{"ok":true}""")
        val r = Http.fetch(url)
        assertTrue("a 429 then a 200 must end as Ok, not as a miss", r is HttpResult.Ok)
        assertEquals("""{"ok":true}""", (r as HttpResult.Ok).body)
        assertEquals("exactly one retry", 2, hits.get())
    }

    @Test
    fun aMissIsNotRetried() {
        // Asking a database four times about a film it does not have is pure load — across a big
        // library that is thousands of pointless requests, and it is how you earn the 429.
        serve(404)
        assertEquals(HttpResult.Missing, Http.fetch(url))
        assertEquals("a definitive answer is asked exactly once", 1, hits.get())
    }

    @Test
    fun aWallOfRateLimitsEndsAsTransientNotAsAnEmptyAnswer() {
        serve(429)
        assertEquals(HttpResult.Transient, Http.fetch(url))
        // Three, not four: the third consecutive 429 holds the host off, and the attempt that would
        // have been the fourth is the first one not made. A rate limit is answered by waiting.
        assertEquals("gives up after a bounded number of attempts", 3, hits.get())
    }

    @Test
    fun aHeldOffHostIsNotAskedAgainAtAll() {
        // Each request used to retry a wall of 429s four times on its own, so a throttled service
        // cost 15.8 seconds PER FILE — and every one of those attempts made the throttling worse.
        serve(429)
        Http.fetch(url)
        val afterFirst = hits.get()
        assertEquals(HttpResult.Transient, Http.fetch(url))
        assertEquals("the second caller sends nothing while the host is held off", afterFirst, hits.get())
    }

    @Test
    fun anAnswerForgivesTheRateLimitsBeforeIt() {
        // Two 429s and then a 200 is an ordinary bad moment, not a wall: the host must not be held
        // off, and the next caller must be served normally.
        serve(429, 429, 200, body = "fine")
        assertEquals("fine", (Http.fetch(url) as HttpResult.Ok).body)
        // …and the host is not held off afterwards: the next caller is served, not turned away.
        assertTrue("an answer clears the rate-limit history", Http.fetch(url) is HttpResult.Ok)
    }

    @Test
    fun aServerErrorIsTreatedLikeARateLimit() {
        serve(503, 200, body = "up")
        assertEquals("up", (Http.fetch(url) as HttpResult.Ok).body)
    }

    @Test
    fun getFlattensBothToNullWhileGetOrThrowSeparatesThem() {
        serve(429)
        // The old shape, kept for callers that genuinely cannot act on the difference.
        assertNull(Http.get(url))
        // The new one: a rate limit must be distinguishable from "no such title".
        assertThrows(TransientHttpException::class.java) { Http.getOrThrow(url) }
    }

    @Test
    fun aMissStaysNullThroughGetOrThrow() {
        serve(404)
        assertNull("404 is an answer, not a failure", Http.getOrThrow(url))
    }

    @Test
    fun `a host that failed outright is left alone for a moment`() {
        // 503 for every attempt: the call is spent, and the host has had its four chances.
        serve(503)
        assertEquals(HttpResult.Transient, Http.fetch(url))
        val asked = hits.get()
        assertEquals("the whole ladder was climbed once", 4, asked)
        assertEquals(HttpResult.Transient, Http.fetch(url))
        assertEquals("the second call never reached the server", asked, hits.get())
    }

    @Test
    fun `a host that answers is never held off`() {
        // The hold must cost a working host nothing at all: every call reaches the server.
        serve(200, body = "up")
        repeat(3) { assertEquals(HttpResult.Ok("up"), Http.fetch(url)) }
        assertEquals("every call went to the server", 3, hits.get())
    }

    @Test
    fun `one failure inside a call is not a failed call`() {
        // 503 then 200 is what the retry exists for; it must not leave the host held off.
        serve(503, 200, body = "up")
        assertEquals(HttpResult.Ok("up"), Http.fetch(url))
        assertEquals(HttpResult.Ok("up"), Http.fetch(url))
        assertEquals("the second call reached the server too", 3, hits.get())
    }
}
