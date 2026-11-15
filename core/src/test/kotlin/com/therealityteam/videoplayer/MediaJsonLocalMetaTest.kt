package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.LocalMeta
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** What a scan found beside a file, and whether its identity came from there, survive a restart. */
class MediaJsonLocalMetaTest {
    private val local = LocalMeta(
        nfo = "smb://nas/video/TV/Shogun/Season 1/ep.nfo",
        showNfo = "smb://nas/video/TV/Shogun/tvshow.nfo",
        poster = "smb://nas/video/TV/Shogun/poster.jpg",
        fanart = null,
        stamp = "smb://nas/video/TV/Shogun/Season 1/ep.nfo:812;smb://nas/video/TV/Shogun/tvshow.nfo:2048",
    )

    @Test fun `the files beside it round-trip`() {
        val item = MediaItem("x", "src", "smb://nas/video/TV/Shogun/Season 1/ep.mkv", "ep.mkv", title = "幕府将军",
            local = local, localApplied = local.stamp, fromNfo = true)
        assertEquals(item, MediaJson.item(MediaJson.item(item)))
    }

    @Test fun `a file with nothing beside it writes nothing for it`() {
        val json = MediaJson.item(MediaItem("y", "src", "/v/a.mkv", "a.mkv", title = "A"))
        assertFalse(json.has("local"))
        assertFalse(json.has("localApplied"))
        assertFalse(json.has("fromNfo"))
        val back = MediaJson.item(json)
        assertNull(back.local)
        assertFalse(back.fromNfo)
    }
}
