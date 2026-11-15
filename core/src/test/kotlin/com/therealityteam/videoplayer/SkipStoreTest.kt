package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.repository.SkipStore
import com.therealityteam.videoplayer.data.repository.SkipStore.Marks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 跳过片头片尾: an opening skipped only from inside it, a closing counted back from each file's end. */
class SkipStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `an episode starting inside the opening starts after it, one resumed past it stays put`() {
        val marks = Marks(introEndMs = 90_000)
        assertEquals(90_000L, marks.startAt(0))
        assertEquals(90_000L, marks.startAt(45_000))
        assertEquals(600_000L, marks.startAt(600_000))
        assertEquals(30_000L, Marks().startAt(30_000))
    }

    @Test fun `the closing is the same length at the end of every episode`() {
        val marks = Marks(introEndMs = 90_000, outroLengthMs = 105_000)
        assertEquals(1_430_000L - 105_000L, marks.outroAt(1_430_000))
        assertEquals(1_445_000L - 105_000L, marks.outroAt(1_445_000))
        // A file shorter than opening + closing (a recap, an extra) has no closing to skip.
        assertNull(marks.outroAt(150_000))
        assertNull(marks.outroAt(0))
    }

    @Test fun `marks belong to the show, and the folder stands in for an unnamed one`() {
        val named = MediaItem("e1", "s", "smb://nas/anime/Show/S01E01.mkv", "S01E01.mkv", title = "Show", collectionKey = "anilist:123")
        val unnamed = MediaItem("e2", "s", "smb://nas/anime/Fansub/[Grp] Show - 02.mkv", "[Grp] Show - 02.mkv", title = "Show")
        assertEquals("anilist:123", SkipStore.keyOf(named))
        assertEquals("dir:smb://nas/anime/Fansub", SkipStore.keyOf(unnamed))
    }

    @Test fun `set, clear and read back`() {
        val dir = tmp.newFolder()
        val store = SkipStore(dir)
        store.setIntroEnd("k", 80_000)
        store.setOutroLength("k", 95_000)
        assertEquals(Marks(80_000, 95_000), store.of("k"))
        store.clear("k")
        assertTrue(store.all.value.isEmpty())
        val all = mapOf("a" to Marks(introEndMs = 1_000), "b" to Marks(outroLengthMs = 2_000))
        assertEquals(all, SkipStore.decode(SkipStore.encode(all)))
    }
}
