package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.repository.LibraryRepair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** What an older pass wrote wrong is put right as the library loads — and nothing else is touched. */
class LibraryRepairTest {

    private val mando = "smb://192.168.1.126/video/The.Mandalorian.S01.UHD.BluRay.2160p.TrueHD.Atmos.7.1.DV.HEVC.HYBRID.REMUX-FraMeSToR/" +
        "The.Mandalorian.S01E01.Chapter.1.The.Mandalorian.UHD.BluRay.2160p.TrueHD.Atmos.7.1.DV.HEVC.HYBRID.REMUX-FraMeSToR.mkv"
    private val blackMirror = "smb://192.168.1.126/迅雷/下载/Black.Mirror.S01.1080p.WEBRip.x265-KONTRAST/Black.Mirror.S01E03.1080p.WEBRip.x265-KONTRAST.mkv"

    /** As the main TMDB path left an episode: the database's name, the file's series name. */
    private fun episode(path: String, title: String, show: String, tmdb: Int = 82856) = MediaItem(
        id = path.hashCode().toString(), sourceId = "s", path = path, fileName = path.substringAfterLast('/'),
        kind = MediaKind.EPISODE, title = title, showTitle = show, tmdbId = tmdb,
        scrapeStatus = ScrapeStatus.MATCHED, season = 1, episode = 1,
    )

    @Test fun `a series named after its files takes the database's name`() {
        val (items, named) = LibraryRepair.seriesTitles(
            listOf(episode(mando, "曼达洛人", "The Mandalorian"), episode(blackMirror, "黑镜", "Black Mirror", 42009)),
        )
        assertEquals(2, named)
        assertEquals(listOf("曼达洛人", "黑镜"), items.map { it.showTitle })
    }

    @Test fun `what carries another name on purpose keeps it`() {
        val extra = episode(
            "smb://192.168.1.126/video/The.Mandalorian.S01/Featurettes/Making.Of.Season.One.mkv", "Making Of Season One", "The Mandalorian",
        )
        val jellyfin = episode(mando, "Chapter 1", "The Mandalorian").copy(collectionKey = "jellyfin:abc")
        val anime = episode(mando, "曼达洛人", "The Mandalorian").copy(collectionKey = "anilist:1")
        val manual = episode(mando, "曼达洛人", "The Mandalorian").copy(userMatched = true)
        val film = episode(mando, "曼达洛人", "The Mandalorian").copy(kind = MediaKind.MOVIE)
        val unmatched = episode(mando, "曼达洛人", "The Mandalorian").copy(scrapeStatus = ScrapeStatus.UNMATCHED)
        val already = episode(mando, "曼达洛人", "曼达洛人")
        val input = listOf(extra, jellyfin, anime, manual, film, unmatched, already)
        val (items, named) = LibraryRepair.seriesTitles(input)
        assertEquals(0, named)
        assertSame(input, items)
    }
}
