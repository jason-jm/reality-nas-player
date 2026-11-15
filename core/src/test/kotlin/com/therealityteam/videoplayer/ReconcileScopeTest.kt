package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.scrape.FolderReconcile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconciling only the folders a pass touched must decide the same thing it would have decided
 * looking at everything.
 *
 * That holds because both passes partition the library by a folder and each folder's answer is
 * settled by its own files. These are the cases that would break if that stopped being true.
 */
class ReconcileScopeTest {

    private var n = 0

    private fun file(
        folder: String,
        name: String,
        id: Int? = null,
        matched: Boolean = id != null,
    ) = MediaItem(
        id = "i${n++}", sourceId = "s", path = "smb://nas/video/$folder/$name", fileName = name,
        title = name.substringBeforeLast('.'), kind = MediaKind.EPISODE, tmdbId = id,
        scrapeStatus = if (matched) ScrapeStatus.MATCHED else ScrapeStatus.UNMATCHED,
    )

    /** One folder with a matched feature and an orphan extra that should be adopted onto it. */
    private fun folderNeedingWork(folder: String, id: Int) = listOf(
        file(folder, "Show - 01.mkv", id),
        file(folder, "Show - 02.mkv", id),
        file(folder, "Show - 03.mkv", id),
        file(folder, "Show NCOP.mkv", matched = false),
    )

    @Test fun `scoping to a folder decides what the full pass decides for that folder`() {
        val a = folderNeedingWork("Anime/Alpha", 100)
        val b = folderNeedingWork("Anime/Beta", 200)
        val all = a + b

        val full = FolderReconcile.plan(all).associateBy { it.id }
        val scoped = FolderReconcile.plan(all, touched = a.map { it.id }.toSet()).associateBy { it.id }

        assertTrue("the full pass had work to do in both folders", full.keys.any { it in a.map { x -> x.id } })
        a.forEach { assertEquals("Alpha: ${it.fileName}", full[it.id], scoped[it.id]) }
    }

    @Test fun `a folder nobody touched is left completely alone`() {
        val a = folderNeedingWork("Anime/Alpha", 100)
        val b = folderNeedingWork("Anime/Beta", 200)
        val scoped = FolderReconcile.plan(a + b, touched = a.map { it.id }.toSet())
        assertTrue("nothing from Beta was rewritten", scoped.none { it.id in b.map { x -> x.id } })
    }

    @Test fun `touching one file brings its whole folder into scope`() {
        // Only the NCOP was scraped this pass, but deciding what it belongs to needs its siblings.
        val a = folderNeedingWork("Anime/Alpha", 100)
        val full = FolderReconcile.plan(a).associateBy { it.id }
        val scoped = FolderReconcile.plan(a, touched = setOf(a.last().id)).associateBy { it.id }
        assertEquals(full.keys, scoped.keys)
        a.forEach { assertEquals(full[it.id], scoped[it.id]) }
    }

    @Test fun `no scope at all is every folder, as the evaluation replays it`() {
        val all = folderNeedingWork("Anime/Alpha", 100) + folderNeedingWork("Anime/Beta", 200)
        assertEquals(FolderReconcile.plan(all, touched = null), FolderReconcile.plan(all))
    }

    @Test fun `an empty scope changes nothing`() {
        val all = folderNeedingWork("Anime/Alpha", 100)
        assertEquals(emptyList<MediaItem>(), FolderReconcile.plan(all, touched = emptySet()))
    }
}
