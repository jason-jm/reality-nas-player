package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaJsonTrackChoiceTest {
    private fun item(audio: String?, sub: String?) = MediaItem(
        id = "x1", sourceId = "src", path = "/v/a.mkv", fileName = "a.mkv", title = "A",
        audioTrackId = audio, subtitleTrackId = sub,
    )

    @Test
    fun trackChoicesRoundTrip() {
        val back = MediaJson.item(MediaJson.item(item("0:1", "off")))
        assertEquals("0:1", back.audioTrackId)
        assertEquals("off", back.subtitleTrackId)
    }

    @Test
    fun absentChoicesStayNull() {
        val json = MediaJson.item(item(null, null)).apply { remove("audioTrackId"); remove("subtitleTrackId") }
        val back = MediaJson.item(json)
        assertNull(back.audioTrackId); assertNull(back.subtitleTrackId)
    }
}
