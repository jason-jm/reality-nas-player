package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.source.JellyfinClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A media server's external subtitles: which of an item's streams are files beside its video, and where
 * the server serves each (the shape of Jellyfin's / Emby's BaseItemDto, `MediaSources[].MediaStreams[]`).
 */
class JellyfinSubtitlesTest {

    private val item = """
        {"Id":"abc","Name":"Film","MediaSources":[{"Id":"src1","Path":"D:\\Films\\Film (2019)\\Film (2019).mkv","MediaStreams":[
          {"Type":"Video","Index":0,"Codec":"hevc","IsExternal":false},
          {"Type":"Audio","Index":1,"Codec":"truehd","Language":"eng","IsExternal":false},
          {"Type":"Subtitle","Index":2,"Codec":"subrip","Language":"chi","IsExternal":false},
          {"Type":"Subtitle","Index":3,"Codec":"ass","Language":"chi","Title":null,"IsExternal":true,
           "Path":"D:\\Films\\Film (2019)\\Film (2019).chs&eng.ass"},
          {"Type":"Subtitle","Index":4,"Codec":"srt","IsExternal":true,"Path":"/media/Film (2019)/Film (2019).srt"},
          {"Type":"Subtitle","Index":5,"Codec":"PGSSUB","IsExternal":true,"Path":"/media/Film (2019)/Film (2019).sup"}
        ]}]}
    """.trimIndent()

    @Test fun `only the files beside the video, each at the route its server serves it from`() {
        val subs = JellyfinClient.parseExternalSubtitles("http://nas:8096", "abc", "tok en", item)
        assertEquals(2, subs.size)
        assertEquals("http://nas:8096/Videos/abc/src1/Subtitles/3/Stream.ass?api_key=tok+en", subs[0].url)
        assertEquals("Film (2019).chs&eng.ass", subs[0].fileName)
        assertEquals("chi", subs[0].language)
        assertEquals("srt", subs[1].format)
        assertEquals("Film (2019).srt", subs[1].fileName)
        assertNull(subs[1].language)
    }

    @Test fun `the item a stream address plays`() {
        assertEquals("abc", JellyfinClient.itemIdOf("http://nas:8096/Videos/abc/stream?static=true&api_key=t"))
        assertNull(JellyfinClient.itemIdOf("http://nas:8096/library/parts/1/file.mkv"))
    }
}
