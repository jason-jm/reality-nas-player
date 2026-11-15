package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.repository.ProgressSidecar
import org.junit.Assert.assertEquals
import org.junit.Test

/** Positions saved on their own must come back newer-wins over the library and stay bounded. */
class ProgressSidecarTest {

    private fun item(id: String, at: Long, pos: Long = 0L) =
        MediaItem(id = id, sourceId = "s", path = "/v/$id.mkv", fileName = "$id.mkv", title = id, progress = PlaybackProgress(pos, 100L, false, at))

    @Test
    fun newerEntriesOverrideTheLibraryOlderOnesDoNot() {
        val entries = listOf(
            ProgressSidecar.Entry("a", 40L, 100L, false, updatedAt = 200L), // newer than the library's 100
            ProgressSidecar.Entry("b", 90L, 100L, true, updatedAt = 50L),   // older than the library's 100
            ProgressSidecar.Entry("zzz", 1L, 1L, false, 1L),                // unknown file: ignored
        )
        val back = ProgressSidecar.decode(ProgressSidecar.encode(entries))
        assertEquals(entries.sortedByDescending { it.updatedAt }, back)
        val merged = ProgressSidecar.merge(listOf(item("a", 100L, 10L), item("b", 100L, 10L)), back)
        assertEquals(40L, merged.first { it.id == "a" }.progress.positionMs)
        assertEquals(10L, merged.first { it.id == "b" }.progress.positionMs)
    }

    @Test
    fun theSidecarKeepsOnlyTheMostRecentEntries() {
        val many = (0 until ProgressSidecar.MAX_ENTRIES + 50).map { ProgressSidecar.Entry("f$it", 1L, 2L, false, it.toLong()) }
        val back = ProgressSidecar.decode(ProgressSidecar.encode(many))
        assertEquals(ProgressSidecar.MAX_ENTRIES, back.size)
        assertEquals("f${ProgressSidecar.MAX_ENTRIES + 49}", back.first().id)
        assertEquals(emptyList<ProgressSidecar.Entry>(), ProgressSidecar.decode("not json"))
    }
}
