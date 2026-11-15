package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.source.LocalSource
import com.therealityteam.videoplayer.data.source.SkippedFolders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkippedFoldersTest {

    @Test fun `housekeeping, hidden folders and app insides are skipped`() {
        listOf(
            "#recycle", "@eaDir", "\$RECYCLE.BIN", "System Volume Information", ".Trashes", ".git",
            "User Data", "Cache", "iMovie Library.imovielibrary", "Photos Library.photoslibrary", "VLC.app",
        ).forEach { assertTrue(it, SkippedFolders.skip(it)) }
    }

    @Test fun `a library's own folders are not`() {
        listOf(
            "Movies", "Anime", "Mr. Robot S01", "Movie.2012.1080p.BluRay", "Season 1", "Extras", "特典",
            "BDMV", "Cached Memories (2019)", "Dune.Part.Two.2024.UHD.BluRay.2160p.REMUX-FraMeSToR.mkv",
        ).forEach { assertFalse(it, SkippedFolders.skip(it)) }
    }

    @Test fun `a local walk steps around them but still reads a hidden root it was given`() {
        val root = Files.createTempDirectory(".hidden-root").toFile()
        fun video(rel: String) = File(root, rel).apply { parentFile.mkdirs(); writeText("x") }
        video("Film (2020)/Film.2020.mkv")
        video("#recycle/Deleted.2019.mkv")
        video("JianyingPro/User Data/Cache/video/Guide/freeLevel.mp4")
        video("iMovie Library.imovielibrary/clip.mov")
        val found = runBlocking {
            LocalSource().list(MediaSourceConfig(id = "l", type = SourceType.LOCAL, name = "l", uri = root.path))
        }
        assertEquals(listOf("Film.2020.mkv"), found.map { it.fileName })
    }
}
