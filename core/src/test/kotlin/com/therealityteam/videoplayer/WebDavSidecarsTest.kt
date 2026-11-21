package com.therealityteam.videoplayer

import com.sun.net.httpserver.HttpServer
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.repository.SourceRepository
import com.therealityteam.videoplayer.data.source.SourceSidecarReader
import com.therealityteam.videoplayer.data.source.WebDavSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress

/**
 * A film's nfo and poster on a WebDAV share: found by the walk (their addresses as the listing
 * spells them, a space and all) and read back with the source's own account.
 */
class WebDavSidecarsTest {

    private fun multistatus(vararg hrefs: Pair<String, Boolean>) = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">""")
        hrefs.forEach { (href, dir) ->
            append("<D:response><D:href>$href</D:href><D:propstat><D:prop><D:resourcetype>")
            if (dir) append("<D:collection/>")
            append("</D:resourcetype></D:prop></D:propstat></D:response>")
        }
        append("</D:multistatus>")
    }

    @Test fun `one root scan visits folders beyond the former limits and preserves disc stream sizes`() = runBlocking {
        val origin = "https://nas.test"
        val previous = WebDavSource.sendPropfind
        WebDavSource.sendPropfind = { url, _, request ->
            require("<d:getcontentlength/>" in request)
            if (url == "$origin/video/") {
                multistatus(*(1..220).map { "/video/$it/" to true }.toTypedArray())
            } else {
                val dir = url.removePrefix(origin)
                val entries = (1..11).map { "${dir}${it.toString().padStart(5, '0')}.m2ts" to false }
                multistatus(*entries.toTypedArray()).replace("</D:prop>", "<D:getcontentlength>8589934592</D:getcontentlength></D:prop>")
            }
        }
        try {
            val share = MediaSourceConfig("dav", SourceType.WEBDAV, "video", "$origin/video/")
            val listing = WebDavSource().listing(share)
            assertFalse(listing.cut)
            assertEquals(2420, listing.videos.size)
            assertEquals(8589934592L, listing.videos.last().size)
        } finally {
            WebDavSource.sendPropfind = previous
        }
    }

    @Test fun `a WebDAV folder's nfo and poster are found and read`(): Unit = runBlocking {
        val nfo = """<movie><title>Heat</title><uniqueid type="tmdb">949</uniqueid></movie>""".toByteArray()
        val poster = "POSTER".toByteArray()
        val served = mapOf("/Films/Heat%20(1995)/movie.nfo" to nfo, "/Films/Heat%20(1995)/poster.jpg" to poster)
        var auth: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            auth = ex.requestHeaders.getFirst("Authorization")
            val body = served[ex.requestURI.rawPath]
            if (body == null) ex.sendResponseHeaders(404, -1) else {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        server.start()
        val origin = "http://127.0.0.1:${server.address.port}"
        val was = WebDavSource.sendPropfind
        WebDavSource.sendPropfind = { url, _, _ ->
            when (url) {
                "$origin/Films/" -> multistatus("/Films/" to true, "/Films/Heat%20(1995)/" to true)
                "$origin/Films/Heat%20(1995)/" -> multistatus(
                    "/Films/Heat%20(1995)/" to true,
                    "/Films/Heat%20(1995)/Heat.1995.1080p.mkv" to false,
                    "/Films/Heat%20(1995)/movie.nfo" to false,
                    "/Films/Heat%20(1995)/poster.jpg" to false,
                    "/Films/Heat%20(1995)/heat.1995.1080p.jpg" to false,
                )
                else -> null
            }
        }
        try {
            val config = MediaSourceConfig("dav", SourceType.WEBDAV, "dav", "$origin/Films/", username = "anna", password = "pw")
            val heat = WebDavSource().list(config).single()
            val local = heat.local!!
            assertEquals("$origin/Films/Heat%20(1995)/movie.nfo", local.nfo)
            assertEquals("$origin/Films/Heat%20(1995)/poster.jpg", local.poster)
            assertNull(local.fanart)

            val sources = object : SourceRepository {
                override val sources: StateFlow<List<MediaSourceConfig>> = MutableStateFlow(listOf(config))
                override fun all() = listOf(config)
                override fun get(id: String) = config.takeIf { it.id == id }
                override fun upsert(source: MediaSourceConfig) {}
                override fun remove(id: String) {}
            }
            val item = MediaItem("x", "dav", heat.path, heat.fileName, title = "Heat")
            val reader = SourceSidecarReader(sources)
            assertArrayEquals(nfo, reader.read(item, local.nfo!!, 1 shl 20))
            assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("anna:pw".toByteArray()), auth)
            assertArrayEquals(poster, reader.read(item, local.poster!!, 1 shl 20))
            // Larger than asked for is not read at all.
            assertNull(reader.read(item, local.poster!!, 3))
        } finally {
            WebDavSource.sendPropfind = was
            server.stop(0)
        }
    }
}
