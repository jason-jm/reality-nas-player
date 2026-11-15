package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.library.LibSection
import com.therealityteam.videoplayer.ui.library.LibSort
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import com.therealityteam.videoplayer.ui.library.WallSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wall as written at the end of a session must draw, filter and sort the same the next morning. */
class WallSnapshotTest {

    private fun item(id: String, title: String, path: String, year: Int?, addedAt: Long, fav: Boolean = false, watchedAt: Long = 0L) = MediaItem(
        id = id, sourceId = "s", path = path, fileName = path.substringAfterLast('/'), title = title, year = year,
        kind = MediaKind.MOVIE, scrapeStatus = ScrapeStatus.MATCHED, collectionKey = "id:$id", tmdbId = id.toInt(),
        posterPath = "/cache/$id.jpg", dominantColor = 0xFF336699.toInt(), addedAt = addedAt, favorite = fav,
        ratings = listOf(RatingScore(RatingSource.IMDB, "8.$id", votes = 1000)),
        progress = PlaybackProgress(positionMs = if (watchedAt > 0) 5_000 else 0, durationMs = 9_000, updatedAt = watchedAt),
    )

    private val live = LibraryGrouping.romanized(
        LibraryGrouping.group(
            listOf(
                item("1", "刀剑神域", "smb://nas/video/Anime/Sword Art Online/SAO.mkv", 2012, 30L, fav = true),
                item("2", "Heat", "smb://nas/video/Movies/Heat (1995)/Heat.mkv", 1995, 20L, watchedAt = 77L),
                item("3", "你的名字。", "smb://nas/video/Anime/你的名字/Kimi no Na wa.mkv", 2016, 10L),
            ),
        ),
    ) { s -> mapOf("刀剑神域" to "dao jian shen yu", "你的名字。" to "ni de ming zi")[s] }

    @Test
    fun aSnapshotDrawsFiltersAndSortsLikeTheLiveWall() {
        val back = WallSnapshot.decode(WallSnapshot.encode(live))
        assertEquals(live.map { it.key }, back.map { it.key })
        assertEquals(live.map { it.title }, back.map { it.title })
        assertEquals(live.map { it.posterPath }, back.map { it.posterPath })
        assertEquals(live.map { it.year }, back.map { it.year })
        assertEquals(live.map { it.category }, back.map { it.category })
        assertEquals(live.map { it.titleKey }, back.map { it.titleKey })
        assertTrue(back.all { WallSnapshot.isStub(it) })
        // sections and orders answer the same from the stubs
        for (sort in LibSort.entries) {
            assertEquals(sort.name, LibraryGrouping.filter(live, LibSection.ALL, "", sort).map { it.key }, LibraryGrouping.filter(back, LibSection.ALL, "", sort).map { it.key })
        }
        assertEquals(listOf("刀剑神域"), LibraryGrouping.filter(back, LibSection.FAVORITES, "").map { it.title })
        assertEquals(listOf("Heat"), LibraryGrouping.filter(back, LibSection.HISTORY, "").map { it.title })
        assertEquals(listOf("刀剑神域"), LibraryGrouping.filter(back, LibSection.ALL, "djsy").map { it.title })
        assertEquals(emptyList<String>(), LibraryGrouping.filter(back, LibSection.MOVIE, "").map { it.title }.filter { it == "刀剑神域" })
    }

    @Test
    fun anEmptyOrBrokenSnapshotIsJustEmpty() {
        assertEquals(emptyList<String>(), WallSnapshot.decode("[]").map { it.key })
        assertEquals(emptyList<String>(), WallSnapshot.decode("""[{"title":"no key"}]""").map { it.key })
    }

    @Test
    fun aStubRemembersWhetherAnythingNamedIt() {
        // A→Z puts the works first and the files nobody could name after them. Every stub used to
        // claim it was named, so the snapshot wall showed one flat alphabet and the order changed
        // under the reader the moment the real library arrived.
        val unnamed = item("4", "00 Footage", "smb://nas/video/dump/00 Footage/clip.mkv", null, 5L)
            .copy(scrapeStatus = ScrapeStatus.UNMATCHED, collectionKey = null, tmdbId = null, posterPath = null)
        val wall = LibraryGrouping.romanized(LibraryGrouping.group(live.flatMap { it.items } + unnamed)) { null }
        val back = WallSnapshot.decode(WallSnapshot.encode(wall))
        assertEquals(
            LibraryGrouping.filter(wall, LibSection.ALL, "", LibSort.TITLE).map { it.title },
            LibraryGrouping.filter(back, LibSection.ALL, "", LibSort.TITLE).map { it.title },
        )
        assertEquals("00 Footage", LibraryGrouping.filter(back, LibSection.ALL, "", LibSort.TITLE).last().title)
    }
}
