package com.therealityteam.videoplayer.desktop

import com.sun.net.httpserver.HttpServer
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.playback.LocalStreamServer
import com.therealityteam.videoplayer.data.repository.SourceRepository
import com.therealityteam.videoplayer.desktop.player.MediaOpen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A Jellyfin / Emby film opens in mpv with the subtitle file its server keeps beside it, as on the headset. */
class ServerSubtitlesTest {

    private class Sources(vararg s: MediaSourceConfig) : SourceRepository {
        override val sources = MutableStateFlow(s.toList())
        override fun all() = sources.value
        override fun get(id: String) = sources.value.firstOrNull { it.id == id }
        override fun upsert(source: MediaSourceConfig) { sources.value = sources.value.filterNot { it.id == source.id } + source }
        override fun remove(id: String) { sources.value = sources.value.filterNot { it.id == id } }
    }

    @Test
    fun `a media server's film opens with its server's external subtitle`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/Users/u1/Items/abc") { ex ->
            val body = """{"Id":"abc","MediaSources":[{"Id":"abc","MediaStreams":[
                {"Type":"Video","Index":0,"Codec":"hevc","IsExternal":false},
                {"Type":"Subtitle","Index":2,"Codec":"subrip","Language":"chi","IsExternal":true,"Path":"/media/Film (2019)/Film (2019).chs.srt"}]}]}""".toByteArray()
            ex.sendResponseHeaders(if (ex.requestHeaders.getFirst("X-Emby-Authorization")?.contains("Token=\"tok\"") == true) 200 else 401, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val source = MediaSourceConfig("jf", SourceType.JELLYFIN, "Jellyfin", base, token = "tok", serverUserId = "u1")
        val item = MediaItem("i1", "jf", "$base/Videos/abc/stream?static=true", "Film (2019).mkv", title = "Film")
        val proxy = LocalStreamServer()
        try {
            val opened = MediaOpen(Sources(source), proxy).open(item)
            assertTrue(opened.uri.endsWith("&api_key=tok"), opened.uri)
            val subs = opened.extraFiles.subtitles
            assertEquals(1, subs.size)
            assertEquals("Film (2019).chs.srt", subs[0].label)
            assertEquals("chs", subs[0].language)
            assertEquals("$base/Videos/abc/abc/Subtitles/2/Stream.srt?api_key=tok", subs[0].uri)
        } finally {
            proxy.shutdown()
            server.stop(0)
        }
    }
}
