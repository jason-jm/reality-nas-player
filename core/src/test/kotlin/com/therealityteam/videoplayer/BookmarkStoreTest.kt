package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.repository.BookmarkStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 书签: kept in playback order, a double press is one mark, and the file on disk reads back. */
class BookmarkStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `marks keep playback order and a second press at the same moment is ignored`() {
        val store = BookmarkStore(tmp.newFolder())
        assertTrue(store.add("f", 600_000, now = 1))
        assertTrue(store.add("f", 60_000, now = 2))
        assertFalse(store.add("f", 61_500, now = 3))
        assertEquals(listOf(60_000L, 600_000L), store.of("f").map { it.positionMs })
    }

    @Test fun `removing the last mark forgets the file`() {
        val store = BookmarkStore(tmp.newFolder())
        store.add("f", 10_000)
        store.remove("f", 10_000)
        assertTrue(store.all.value.isEmpty())
    }

    @Test fun `a file keeps only its latest marks`() {
        val store = BookmarkStore(tmp.newFolder())
        repeat(BookmarkStore.MAX_PER_FILE + 5) { i -> store.add("f", i * 10_000L, now = i.toLong()) }
        val kept = store.of("f")
        assertEquals(BookmarkStore.MAX_PER_FILE, kept.size)
        assertEquals(5 * 10_000L, kept.first().positionMs) // the five oldest went
    }

    @Test fun `marks pressed in quick succession all reach the disk`() {
        val dir = tmp.newFolder()
        val store = BookmarkStore(dir)
        repeat(8) { i -> store.add("f", 60_000L * (i + 1), now = i.toLong()) }
        val deadline = System.currentTimeMillis() + 3_000
        var onDisk = emptyList<Long>()
        while (System.currentTimeMillis() < deadline) {
            onDisk = BookmarkStore(dir).of("f").map { it.positionMs }
            if (onDisk.size == 8) break
            Thread.sleep(20)
        }
        assertEquals((1..8).map { 60_000L * it }, onDisk)
    }

    @Test fun `the store reads back what it wrote`() {
        val all = mapOf(
            "a" to listOf(BookmarkStore.Bookmark(1_000, 10), BookmarkStore.Bookmark(9_000, 11)),
            "b" to listOf(BookmarkStore.Bookmark(5_000, 12)),
        )
        assertEquals(all, BookmarkStore.decode(BookmarkStore.encode(all)))
        assertTrue(BookmarkStore.decode("not json").isEmpty())
    }
}
