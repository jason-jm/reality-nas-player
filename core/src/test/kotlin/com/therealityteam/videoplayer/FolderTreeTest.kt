package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.ui.library.FolderTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 文件夹: the library as the shares hold it. Paths as the SMB scan stores them. */
class FolderTreeTest {

    private fun file(path: String, source: String = "smb_v") =
        MediaItem(id = path, sourceId = source, path = path, fileName = path.substringAfterLast('/'), title = path.substringAfterLast('/'))

    private val roots = listOf(
        FolderTree.Root("smb_v", "video", "smb://192.168.1.126/video"),
        FolderTree.Root("smb_d", "迅雷/下载", "smb://192.168.1.126/迅雷/下载"),
        FolderTree.Root("empty", "空的", "smb://192.168.1.126/empty"),
    )

    private val tree = FolderTree(
        listOf(
            file("smb://192.168.1.126/video/Anime/东方万花镜/[H20] Ep10.mp4"),
            file("smb://192.168.1.126/video/Anime/东方万花镜/[H20] Ep9.mp4"),
            file("smb://192.168.1.126/video/Anime/东方万花镜/SP/Menu.mkv"),
            file("smb://192.168.1.126/video/Movie/1917/1917.mkv"),
            file("smb://192.168.1.126/video/loose.avi"),
            file("smb://192.168.1.126/迅雷/下载/a.mkv", "smb_d"),
        ),
        roots,
    )

    @Test fun `the sources that hold anything, with how much`() {
        assertEquals(listOf("video" to 5, "迅雷/下载" to 1), tree.sources().map { it.name to it.files })
    }

    @Test fun `a folder lists its folders, then its files, in reading order`() {
        val top = tree.list(FolderTree.Path("smb_v"))!!
        assertEquals(listOf("Anime" to 3, "Movie" to 1), top.folders.map { it.name to it.files })
        assertEquals(listOf("loose.avi"), top.files.map { it.fileName })
        val show = tree.list(FolderTree.Path("smb_v", listOf("Anime", "东方万花镜")))!!
        assertEquals(listOf("SP"), show.folders.map { it.name })
        // Ep9 before Ep10: numbers read as numbers.
        assertEquals(listOf("[H20] Ep9.mp4", "[H20] Ep10.mp4"), show.files.map { it.fileName })
        assertEquals(listOf("video", "Anime", "东方万花镜"), tree.crumbs(show.folders.single().path.parent!!))
        assertNull(tree.list(FolderTree.Path("smb_v", listOf("Gone"))))
    }

    @Test fun `natural order and paths outside the root`() {
        assertEquals(listOf("EP09", "ep10", "Extras"), listOf("Extras", "ep10", "EP09").sortedWith(FolderTree.NaturalOrder))
        assertEquals(listOf("第2集", "第10集"), listOf("第10集", "第2集").sortedWith(FolderTree.NaturalOrder))
        assertEquals(listOf("other", "x.mkv"), FolderTree.relative("smb://host/other/x.mkv", "smb://host/video"))
        assertEquals(listOf("a", "b.mkv"), FolderTree.relative("smb://host/video/a/b.mkv", "smb://host/video/"))
    }

    @Test fun `a folder goes on from the file left half watched, else the first not seen`() {
        val a = file("smb://h/video/s/01.mkv").copy(progress = com.therealityteam.videoplayer.data.model.PlaybackProgress(positionMs = 1_000, durationMs = 1_000, watched = true, updatedAt = 1))
        val b = file("smb://h/video/s/02.mkv")
        val c = file("smb://h/video/s/03.mkv").copy(progress = com.therealityteam.videoplayer.data.model.PlaybackProgress(positionMs = 300_000, durationMs = 1_400_000, updatedAt = 2))
        assertEquals(c, FolderTree.nextUp(listOf(a, b, c)))
        assertEquals(b, FolderTree.nextUp(listOf(a, b)))
        assertEquals(a, FolderTree.nextUp(listOf(a)))
        assertNull(FolderTree.nextUp(emptyList()))
    }

    @Test fun `files found by every word of their own name, with their folder`() {
        val found = tree.search("h20 ep")
        assertEquals(listOf("[H20] Ep9.mp4", "[H20] Ep10.mp4"), found.map { it.item.fileName })
        assertEquals(listOf("Anime", "东方万花镜"), found.first().folder.segments)
        assertEquals(listOf("Menu.mkv"), tree.search("MENU").map { it.item.fileName })
        assertEquals(1, tree.search("mkv", limit = 1).size)
        assertEquals(emptyList<Any>(), tree.search("  "))
    }
}
