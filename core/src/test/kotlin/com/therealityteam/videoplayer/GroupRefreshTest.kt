package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.ui.library.GroupRefresh
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A position saved while a film plays changes the cards' files, never the cards. */
class GroupRefreshTest {

    private fun ep(n: Int) = MediaItem(
        id = "e$n", sourceId = "s", path = "/tv/Show/Show.S01E0$n.mkv", fileName = "Show.S01E0$n.mkv",
        title = "Show", year = 2020, season = 1, episode = n,
    )

    @Test fun `watch state and picks keep the grouping, anything else does not`() {
        val items = (1..3).map(::ep)
        val played = items.toMutableList().also { it[1] = it[1].copy(progress = PlaybackProgress(positionMs = 60_000, durationMs = 1_800_000, updatedAt = 5), subtitleTrackId = "2:0", favorite = true) }
        assertTrue(GroupRefresh.groupsAlike(items, played))
        val renamed = items.toMutableList().also { it[1] = it[1].copy(title = "Other") }
        assertFalse(GroupRefresh.groupsAlike(items, renamed))
        assertFalse(GroupRefresh.groupsAlike(items, items.dropLast(1)))
    }

    @Test fun `refreshed cards carry the files as they are now`() {
        val items = (1..3).map(::ep)
        val groups = LibraryGrouping.group(items)
        val played = items.toMutableList().also { it[0] = it[0].copy(progress = PlaybackProgress(positionMs = 1_800_000, durationMs = 1_800_000, watched = true, updatedAt = 5)) }
        val refreshed = GroupRefresh.refreshed(groups, played)
        assertTrue(GroupRefresh.sameCards(groups, refreshed))
        assertEquals(groups.map { it.key }, refreshed.map { it.key })
        assertTrue(refreshed.flatMap { it.items }.first { it.id == "e1" }.progress.watched)
        assertEquals(LibraryGrouping.group(played).map { it.key to it.items.map { i -> i.id } }, refreshed.map { it.key to it.items.map { i -> i.id } })
    }

    @Test fun `the real cards replace revived shells, keeping only the keys already romanised`() {
        val items = (1..3).map { ep(it).copy(backdropPath = "/art/show_bd.jpg", scrapeStatus = com.therealityteam.videoplayer.data.model.ScrapeStatus.MATCHED, tmdbId = 42, showTitle = "Show") }
        val fresh = LibraryGrouping.group(items)
        // A revived card: the snapshot's shell, which keeps no backdrop, with its keys worked out.
        val revived = fresh.map { it.copy(backdropPath = null, titleKey = "show", searchKeys = listOf("show", "xiu")) }
        val out = GroupRefresh.carryKeys(revived, fresh)
        assertEquals("/art/show_bd.jpg", out.single().backdropPath)
        assertEquals("show", out.single().titleKey)
        assertEquals(listOf("show", "xiu"), out.single().searchKeys)
        assertEquals(fresh.single().items, out.single().items)
    }
}
