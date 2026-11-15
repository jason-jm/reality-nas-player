package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.util.Http
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/** A service known by two names: the one that does not answer is passed over, the one that does is kept. */
class HttpAlternatesTest {

    @Test fun `a name that does not answer gives way to the one that does, which is asked first after`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var asked = 0
        server.createContext("/3/configuration") { ex ->
            asked++
            val body = "{\"ok\":true}".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            // "localhost:1" refuses at once; the live server is the second name.
            Http.alternates["api.example.test"] = listOf("localhost:1", "127.0.0.1:${server.address.port}")
            assertEquals("{\"ok\":true}", Http.get("http://api.example.test/3/configuration"))
            assertEquals(1, asked)
            val started = System.nanoTime()
            assertEquals("{\"ok\":true}", Http.get("http://api.example.test/3/configuration"))
            assertTrue("the name that answered is asked first now", (System.nanoTime() - started) / 1_000_000 < 500)
            assertEquals(2, asked)
        } finally {
            Http.alternates.remove("api.example.test")
            server.stop(0)
        }
    }

    @Test fun `TMDB's API is asked for under both its names`() {
        assertEquals(setOf("api.tmdb.org", "api.themoviedb.org"), Http.alternates["api.themoviedb.org"]!!.toSet())
    }
}
