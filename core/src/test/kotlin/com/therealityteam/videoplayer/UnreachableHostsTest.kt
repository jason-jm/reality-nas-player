package com.therealityteam.videoplayer

import com.sun.net.httpserver.HttpServer
import com.therealityteam.videoplayer.util.Http
import com.therealityteam.videoplayer.util.HttpResult
import com.therealityteam.videoplayer.util.UnreachableHosts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A host this network could not reach, remembered for the next launch: asked one call at a time until it
 * answers, set aside at its first failure, forgotten at its first answer.
 */
class UnreachableHostsTest {

    private val file = File.createTempFile("unreachable", ".tsv").also { it.delete() }

    @After fun clear() {
        UnreachableHosts.file = null
        Http.forgetRateLimits()
        file.delete()
    }

    @Test fun `a host is remembered across launches, until it answers or a month has gone by`() {
        UnreachableHosts.file = file
        UnreachableHosts.reload()
        UnreachableHosts.remember("api.jikan.moe")
        UnreachableHosts.reload() // a new launch
        assertTrue(UnreachableHosts.contains("api.jikan.moe"))
        UnreachableHosts.forget("api.jikan.moe")
        UnreachableHosts.reload()
        assertFalse(UnreachableHosts.contains("api.jikan.moe"))

        file.writeText("old.example\t${System.currentTimeMillis() - 31L * 24 * 3600 * 1000}\n")
        UnreachableHosts.reload()
        assertFalse(UnreachableHosts.contains("old.example"))
    }

    @Test fun `a JSON POST goes as one, with its body, and comes back as a GET's answer does`() {
        var seen: Pair<String, String>? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v0/search/subjects") { ex ->
            seen = ex.requestMethod to ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val body = "{\"data\":[]}".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val answer = Http.postJson("http://127.0.0.1:${server.address.port}/v0/search/subjects?limit=5", "{\"keyword\":\"君の名は。\"}")
            assertEquals("{\"data\":[]}", answer)
            assertEquals("POST" to "{\"keyword\":\"君の名は。\"}", seen)
        } finally {
            server.stop(0)
        }
    }

    @Test fun `calls to a remembered host wait for the first, which answering clears it for them`() {
        val starts = java.util.Collections.synchronizedList(ArrayList<Long>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { ex ->
            starts += System.nanoTime()
            Thread.sleep(300)
            val body = "{}".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        UnreachableHosts.file = file
        Http.forgetRateLimits()
        UnreachableHosts.remember("127.0.0.1")
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (1..4).map { i -> pool.submit<HttpResult> { Http.fetch("http://127.0.0.1:${server.address.port}/$i") } }.map { it.get() }
            assertTrue(results.all { it is HttpResult.Ok })
            val sorted = starts.sorted()
            assertTrue("the others waited for the first call's answer", (sorted[1] - sorted[0]) / 1_000_000 >= 250)
            assertFalse(UnreachableHosts.contains("127.0.0.1"))
        } finally {
            pool.shutdownNow()
            server.stop(0)
        }
    }

    @Test fun `a remembered host that fails again is set aside at once, its other calls not made`() {
        val accepts = AtomicInteger()
        val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val acceptor = Thread {
            while (!socket.isClosed) runCatching { socket.accept().use { accepts.incrementAndGet() } }
        }.apply { isDaemon = true; start() }
        UnreachableHosts.file = file
        Http.forgetRateLimits()
        UnreachableHosts.remember("127.0.0.1")
        val pool = Executors.newFixedThreadPool(3)
        try {
            val results = (1..3).map { i -> pool.submit<HttpResult> { Http.fetch("http://127.0.0.1:${socket.localPort}/$i") } }.map { it.get() }
            assertTrue(results.all { it == HttpResult.Transient })
            val oneCall = accepts.get()
            // A call's attempts reached the server; the two calls that waited on it made none of their own.
            val more = Http.fetch("http://127.0.0.1:${socket.localPort}/again")
            assertEquals(HttpResult.Transient, more)
            assertEquals("set aside: not asked again", oneCall, accepts.get())
            assertTrue("one call's attempts, not three calls'", oneCall in 1..8)
            UnreachableHosts.reload()
            assertTrue(UnreachableHosts.contains("127.0.0.1"))
        } finally {
            pool.shutdownNow()
            socket.close()
            acceptor.interrupt()
        }
    }
}
