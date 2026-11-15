package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import kotlinx.coroutines.flow.first
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The id lookup must never answer from a list it no longer describes.
 *
 * Finding an item by id is a map lookup now rather than a walk, which is only safe while the map
 * and the list agree. Every operation that changes the list's SHAPE has to invalidate it; these are
 * those operations, each followed by the question the index would get wrong.
 */
class RepoIndexTest {


    private fun item(n: Int, source: String = "s") = MediaItem(
        id = "id$n", sourceId = source, path = "/v/$n.mkv", fileName = "$n.mkv",
        title = "Film $n", kind = MediaKind.MOVIE,
    )

    private fun repo(): JsonMediaRepository {
        val dir = Files.createTempDirectory("aura-index").toFile()
        return JsonMediaRepository(dir).apply { upsertAll((0 until 20).map(::item)); flush() }
    }

    @Test fun `every item is found by its own id`() {
        val r = repo()
        (0 until 20).forEach { assertEquals("id$it", r.get("id$it")?.id) }
        assertNull(r.get("nobody"))
    }

    @Test fun `a new item at the head does not shift everyone else's answer`() {
        val r = repo()
        r.upsert(item(99))
        assertEquals("id99", r.get("id99")?.id)
        (0 until 20).forEach { assertEquals("id$it", r.get("id$it")?.id) }
    }

    @Test fun `a removed item is gone and the rest still answer`() {
        val r = repo()
        r.remove("id5")
        assertNull(r.get("id5"))
        assertEquals("id6", r.get("id6")?.id)
        assertEquals("id19", r.get("id19")?.id)
    }

    @Test fun `removing a whole source leaves the others findable`() {
        val r = repo()
        r.upsert(item(100, source = "other"))
        r.removeBySource("s")
        assertNull(r.get("id3"))
        assertEquals("id100", r.get("id100")?.id)
    }

    @Test fun `retaining a subset leaves exactly those findable`() {
        val r = repo()
        r.retainForSource("s", setOf("id1", "id2"))
        assertEquals("id1", r.get("id1")?.id)
        assertEquals("id2", r.get("id2")?.id)
        assertNull(r.get("id3"))
    }

    @Test fun `a scan that keeps every file it had does not write the library again`() {
        val dir = Files.createTempDirectory("aura-retain").toFile()
        val r = JsonMediaRepository(dir).apply { upsertAll((0 until 20).map(::item)); flush() }
        val file = File(dir, "aura/library.json")
        file.setLastModified(1_000_000L)
        r.retainForSource("s", (0 until 20).map { "id$it" }.toSet())
        r.removeBySource("nobody")
        r.remove("nobody")
        Thread.sleep(2_500) // past the quiet window a write would wait for
        assertEquals("nothing removed, nothing written", 1_000_000L, file.lastModified())
        r.retainForSource("s", setOf("id1"))
        r.flush()
        org.junit.Assert.assertTrue("a removal is written", file.lastModified() != 1_000_000L)
        val again = JsonMediaRepository(dir)
        kotlinx.coroutines.runBlocking { again.loaded.first { it } }
        assertEquals(listOf("id1"), again.all().map { it.id })
    }

    @Test fun `a forgotten file reads as never played, and stays so`() {
        val dir = Files.createTempDirectory("aura-forget").toFile()
        val r = JsonMediaRepository(dir).apply { upsertAll((0 until 3).map(::item)); flush() }
        r.updateProgress("id1", 60_000, 100_000)
        r.updateProgress("id2", 99_000, 100_000, watched = true)
        r.forgetProgress(listOf("id1", "id2"))
        assertEquals(com.therealityteam.videoplayer.data.model.PlaybackProgress(), r.get("id1")!!.progress)
        assertEquals(com.therealityteam.videoplayer.data.model.PlaybackProgress(), r.get("id2")!!.progress)
        r.updateProgress("id0", 5_000, 100_000)
        r.flush()
        Thread.sleep(1_500) // the positions' sidecar is written a second after
        val again = JsonMediaRepository(dir)
        kotlinx.coroutines.runBlocking { again.loaded.first { it } }
        assertEquals(0L, again.get("id1")!!.progress.updatedAt)
        assertEquals(false, again.get("id2")!!.progress.watched)
        assertEquals(5_000L, again.get("id0")!!.progress.positionMs)
    }

    @Test fun `clearing answers nothing`() {
        val r = repo()
        r.clear()
        assertNull(r.get("id0"))
    }

    @Test fun `a re-upsert replaces in place and is read back`() {
        val r = repo()
        r.upsert(item(7).copy(title = "changed"))
        assertEquals("changed", r.get("id7")?.title)
        assertEquals(20, r.all().size)
    }

    @Test fun `a bulk upsert that reorders still answers correctly`() {
        val r = repo()
        // upsertAll re-sorts by addedAt, so every position moves.
        r.upsertAll((20 until 40).map { item(it).copy(addedAt = it.toLong()) })
        (0 until 40).forEach { assertEquals("id$it", r.get("id$it")?.id) }
    }

    @Test fun `re-scanning a source that has not changed changes nothing`() {
        // A scan of a source nothing has happened to hands back exactly what is already there.
        // Nothing must move: the list is not republished, so no write is scheduled and nobody
        // watching is told the library changed. (Asserting on the FILE would prove less — flush()
        // writes whether or not anything asked it to.)
        val r = repo()
        val same = r.all()
        r.upsertAll(same)
        assertSame("the library was not republished", same, r.all())
    }

    @Test fun `a change is still published`() {
        val r = repo()
        val before = r.all()
        r.upsertAll(listOf(item(99)))
        assertNotSame(before, r.all())
        assertEquals("id99", r.get("id99")?.id)
    }

    @Test fun `replacing items with themselves changes nothing`() {
        val r = repo()
        val same = r.all()
        r.replaceAll(same.take(5))
        assertSame("the library was not republished", same, r.all())
    }
}
