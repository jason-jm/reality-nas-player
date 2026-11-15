package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.desktop.player.MediaOpen
import com.therealityteam.videoplayer.desktop.ui.shell.isAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** File › Open Address…: what an address is called, and whose credentials it plays with. */
class OpenAddressTest {

    @Test
    fun `an address is called by its last part, read`() {
        assertEquals("My Film.mkv", MediaOpen.displayName("https://example.com/films/My%20Film.mkv?token=abc#t=10"))
        assertEquals("a+b.mkv", MediaOpen.displayName("https://example.com/a+b.mkv"))
        assertEquals("example.com", MediaOpen.displayName("https://example.com/"))
        assertEquals("live", MediaOpen.displayName("rtsp://camera.local:554/live"))
        assertEquals("Dune.mkv", MediaOpen.displayName("/Users/me/Movies/Dune.mkv"))
        assertEquals("My Film", MediaOpen.adHocItem("https://example.com/My%20Film.mkv").title)
    }

    @Test
    fun `an address under a share plays with the share's credentials`() {
        val share = MediaSourceConfig("s1", SourceType.SMB, "NAS", "smb://nas.local/video", username = "me")
        val deeper = MediaSourceConfig("s2", SourceType.SMB, "Films", "smb://nas.local/video/Films")
        val dav = MediaSourceConfig("d", SourceType.WEBDAV, "Dav", "https://dav.example.com/remote.php/dav/files/me")
        val all = listOf(share, deeper, dav)
        assertEquals("s2", MediaOpen.owner("smb://nas.local/video/Films/Dune.mkv", all)?.id)
        assertEquals("s1", MediaOpen.owner("SMB://NAS.local/video/TV/Show.mkv", all)?.id)
        assertEquals("d", MediaOpen.owner("https://dav.example.com/remote.php/dav/files/me/x.mkv", all)?.id)
        assertNull(MediaOpen.owner("smb://nas.local/videos/x.mkv", all), "a share whose name merely starts the same")
        assertNull(MediaOpen.owner("https://example.com/x.mkv", all))
        assertEquals("s2", MediaOpen.adHocItem("smb://nas.local/video/Films/Dune.mkv", deeper).sourceId)
    }

    @Test
    fun `only something playable is an address`() {
        assertTrue(isAddress("https://example.com/a.mkv"))
        assertTrue(isAddress("smb://nas/video/a.mkv"))
        assertFalse(isAddress("example.com/a.mkv"))
        assertFalse(isAddress("https:// spaced.mkv"))
        assertFalse(isAddress(""))
    }
}
