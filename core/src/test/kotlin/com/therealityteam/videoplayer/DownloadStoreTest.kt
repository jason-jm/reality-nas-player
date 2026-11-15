package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.download.Download
import com.therealityteam.videoplayer.data.download.DownloadPlan
import com.therealityteam.videoplayer.data.download.DownloadStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 下载到本机: the list of copies and the rules they go by. */
class DownloadStoreTest {

    private fun dl(id: String, added: Long, state: Download.State = Download.State.QUEUED, dir: File = File("/x")) =
        Download(id, "$id.mkv", "smb://nas/video/$id.mkv", File(dir, "$id.mkv").path, state = state, addedAt = added)

    @Test fun `the oldest queued goes next, unless one was left running`() {
        assertEquals("b", DownloadPlan.next(listOf(dl("a", 5), dl("b", 2), dl("c", 9)))?.itemId)
        assertEquals("c", DownloadPlan.next(listOf(dl("a", 5), dl("b", 2, Download.State.DONE), dl("c", 9, Download.State.RUNNING)))?.itemId)
        assertNull(DownloadPlan.next(listOf(dl("a", 1, Download.State.DONE), dl("b", 2, Download.State.PAUSED))))
    }

    @Test fun `a copy keeps its own name, in a folder of its item's`() {
        val f = DownloadPlan.localFile(File("/d"), "item1", "[Group] Show - 01 [1080p].mkv")
        assertEquals("[Group] Show - 01 [1080p].mkv", f.name)
        assertTrue(f.parentFile!!.parentFile!!.path == "/d")
        assertEquals("a_b_c.mkv", DownloadPlan.localFile(File("/d"), "x", "a/b:c.mkv").name)
        assertTrue(DownloadPlan.localFile(File("/d"), "1", "01.mkv") != DownloadPlan.localFile(File("/d"), "2", "01.mkv"))
    }

    @Test fun `a copy's subtitles still find it under the copy's names`() {
        val dir = File("/d")
        fun local(name: String, video: String) = DownloadPlan.localFile(dir, "item", name, video).name
        val cases = listOf(
            "Show: Part?1.mkv" to listOf("Show: Part?1.chs.ass", "Show: Part?1.srt"),
            // A release name past the length a copy keeps: cut inside its stem, never through ".mkv".
            ("[Group] " + "Very Long Title ".repeat(14) + "[1080p].mkv") to
                listOf("[Group] " + "Very Long Title ".repeat(14) + "[1080p].sc.ass", "[Group] " + "Very Long Title ".repeat(14) + "[1080p].tc.ass"),
            // A Chinese name: 255 bytes is 85 characters.
            ("【字幕组】" + "非常长的中文片名".repeat(12) + " 第01集.mkv") to listOf("【字幕组】" + "非常长的中文片名".repeat(12) + " 第01集.简体.ass"),
        )
        for ((video, subs) in cases) {
            val copy = local(video, video)
            assertTrue(copy, copy.endsWith(".mkv"))
            val locals = subs.map { local(it, video) }
            (locals + copy).forEach { assertTrue(it, it.toByteArray().size <= 255) }
            assertEquals(video, subs.size, locals.distinct().size)
            val found = com.therealityteam.videoplayer.data.subtitle.SubtitleEngine.matchSidecars(copy, locals)
            assertEquals(video, subs.size, found.size)
        }
    }

    @Test fun `room is checked with some kept free`() {
        val gb = 1024L * 1024 * 1024
        assertTrue(DownloadPlan.fits(4 * gb, 10 * gb))
        assertFalse(DownloadPlan.fits(9 * gb, 10 * gb))
    }

    @Test fun `the list is kept across launches and a copy counts once whole`() {
        val dir = Files.createTempDirectory("dl").toFile()
        val store = DownloadStore(dir)
        assertEquals(2, store.enqueue(listOf(dl("a", 1, dir = dir), dl("b", 2, dir = dir))))
        assertEquals(0, store.enqueue(listOf(dl("a", 3, dir = dir))))
        store.update("a") { it.copy(state = Download.State.DONE, totalBytes = 10, doneBytes = 10) }
        // Done on the list, but no file there: not a copy to play.
        assertNull(store.localCopy("a"))
        File(dir, "a.mkv").writeText("x")
        assertEquals(File(dir, "a.mkv"), store.localCopy("a"))

        val again = DownloadStore(dir)
        assertEquals(listOf("a", "b"), again.all.value.map { it.itemId })
        assertEquals(Download.State.DONE, again.get("a")?.state)
        assertEquals(1f, again.get("a")!!.fraction)
        again.remove("b")
        assertEquals(listOf("a"), DownloadStore(dir).all.value.map { it.itemId })
    }

    @Test fun `a full disk is told from other write failures`() {
        assertTrue(DownloadPlan.isDiskFull("write failed: ENOSPC (No space left on device)"))
        assertTrue(DownloadPlan.isDiskFull("No space left on device"))
        assertFalse(DownloadPlan.isDiskFull("write failed: EIO (I/O error)"))
        assertFalse(DownloadPlan.isDiskFull(null))
    }
}
