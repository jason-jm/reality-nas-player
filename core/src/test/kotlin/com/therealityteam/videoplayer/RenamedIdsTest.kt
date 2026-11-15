package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.download.Download
import com.therealityteam.videoplayer.data.download.DownloadStore
import com.therealityteam.videoplayer.data.playback.FrameCrop
import com.therealityteam.videoplayer.data.repository.BookmarkStore
import com.therealityteam.videoplayer.data.repository.CropStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What is kept by an entry's id follows it to a new id when a share added again takes its files over. */
class RenamedIdsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun `downloads move to the new ids, except one running and one whose new id has its own`() {
        val store = DownloadStore(tmp.root)
        store.enqueue(listOf(
            Download("old1", "a.mkv", "smb://n/a.mkv", "/c/a.mkv", state = Download.State.DONE),
            Download("old2", "b.mkv", "smb://n/b.mkv", "/c/b.mkv", state = Download.State.RUNNING),
            Download("old3", "c.mkv", "smb://n/c.mkv", "/c/c.mkv", state = Download.State.DONE),
            Download("new3", "c.mkv", "smb://n/c.mkv", "/c/c2.mkv", state = Download.State.DONE),
        ))
        store.renamed(mapOf("old1" to "new1", "old2" to "new2", "old3" to "new3"))
        assertEquals("/c/a.mkv", store.get("new1")?.localPath)
        assertNull(store.get("old1"))
        assertEquals("old2", store.get("old2")?.itemId) // its job still writes to this one
        assertEquals("/c/c2.mkv", store.get("new3")?.localPath)
        // and the list survives a reopen
        assertEquals("/c/a.mkv", DownloadStore(tmp.root).get("new1")?.localPath)
    }

    @Test fun `bookmarks and crops move too, never over what the new id has`() {
        val marks = BookmarkStore(tmp.root)
        marks.add("old1", 60_000, now = 1)
        marks.add("old2", 90_000, now = 1)
        marks.add("new2", 30_000, now = 1)
        marks.renamed(mapOf("old1" to "new1", "old2" to "new2"))
        assertEquals(listOf(60_000L), marks.of("new1").map { it.positionMs })
        assertEquals(listOf(30_000L), marks.of("new2").map { it.positionMs })
        assertEquals(emptyList<Long>(), marks.of("old1").map { it.positionMs })

        val crops = CropStore(tmp.root)
        val bars = FrameCrop(0f, 0.12f, 0f, 0.12f)
        crops.put("old1", bars)
        crops.renamed(mapOf("old1" to "new1"))
        assertEquals(bars, crops.of("new1"))
        assertNull(crops.of("old1"))
    }
}
