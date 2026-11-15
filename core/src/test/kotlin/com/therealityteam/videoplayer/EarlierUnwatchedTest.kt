package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.ui.library.DetailPlay
import org.junit.Assert.assertEquals
import org.junit.Test

/** 标为已看（含之前各集）: the episodes before this one not seen yet; extras never reach back. */
class EarlierUnwatchedTest {

    private fun ep(n: Int, watched: Boolean = false) = MediaItem(
        "e$n", "s", "/v/Show S01E0$n.mkv", "Show S01E0$n.mkv", title = "Show",
        progress = PlaybackProgress(if (watched) 100 else 0, 100, watched, 1),
    )
    private val ncop = MediaItem("x", "s", "/v/Show NCOP.mkv", "Show NCOP.mkv", title = "Show")

    @Test fun `up to an episode means the unseen ones before it`() {
        val list = listOf(ep(1), ep(2, watched = true), ep(3), ep(4), ncop)
        assertEquals(listOf("e1", "e3"), DetailPlay.earlierUnwatched(list, list[3]).map { it.id })
        assertEquals(emptyList<String>(), DetailPlay.earlierUnwatched(list, list[0]).map { it.id })
        // An extra marks nothing before it.
        assertEquals(emptyList<String>(), DetailPlay.earlierUnwatched(list, ncop).map { it.id })
    }
}
