package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.repository.ContinueHiddenStore
import com.therealityteam.videoplayer.ui.library.ContinueWatching
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaGroup
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 从「继续观看」中移除: out of the row until it is watched again — its progress untouched. */
class ContinueHiddenTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun film(key: String, position: Long, at: Long) = MediaGroup(
        key = key, title = key, year = null, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null, ratings = emptyList(), isSeries = false, seasonal = false,
        category = LibCategory.MOVIE, count = 1,
        items = listOf(MediaItem("$key-1", "s", "/v/$key.mkv", "$key.mkv", title = key, progress = PlaybackProgress(position, 6_000_000, false, at))),
    )

    @Test fun `a hidden card stays out of the row until it is watched again`() {
        val a = film("a", 1_000_000, at = 100)
        val b = film("b", 2_000_000, at = 200)
        assertEquals(listOf("b", "a"), ContinueWatching.of(listOf(a, b)).map { it.group.key })
        val hidden = mapOf("b" to 200L)
        assertEquals(listOf("a"), ContinueWatching.of(listOf(a, b), hidden = hidden).map { it.group.key })
        // Played again later: back, at the front.
        val bAgain = film("b", 2_100_000, at = 300)
        assertEquals(listOf("b", "a"), ContinueWatching.of(listOf(a, bAgain), hidden = hidden).map { it.group.key })
    }

    @Test fun `the store keeps the latest moment and reads back what it wrote`() {
        val store = ContinueHiddenStore(tmp.newFolder())
        store.hide("x", 500)
        store.hide("x", 400) // an older entry does not bring an earlier moment back
        assertEquals(mapOf("x" to 500L), store.all.value)
        val all = mapOf("x" to 500L, "dir:/anime/Show" to 42L)
        assertEquals(all, ContinueHiddenStore.decode(ContinueHiddenStore.encode(all)))
    }
}
