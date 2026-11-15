package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.ui.library.DetailPlay
import org.junit.Assert.assertEquals
import org.junit.Test

/** The detail page's big button: what it plays and what it says. */
class DetailPlayTest {

    private fun ep(n: Int, pos: Long = 0, dur: Long = 1_440_000, watched: Boolean = false, at: Long = 0, tag: ContentKind = ContentKind.MAIN) = MediaItem(
        id = "e$n", sourceId = "s", path = "/tv/Show/Show S01E${"%02d".format(n)}.mkv", fileName = "Show S01E${"%02d".format(n)}.mkv",
        title = "Show", showTitle = "Show", episode = n, kind = MediaKind.EPISODE, contentTag = tag,
        progress = PlaybackProgress(pos, dur, watched, at),
    )

    @Test
    fun theButtonPlaysTheNextThingNotEpisodeOneAgain() {
        // The KIND is asserted, not the sentence: the words are the reader's language and live in
        // strings.xml (LocalizedStringsTest renders them). What the button DOES must hold anywhere.
        fun chosen(items: List<MediaItem>) = DetailPlay.choose(items)!!.let { it.item.id to it.kind }
        assertEquals("e1" to DetailPlay.Kind.START, chosen(listOf(ep(1), ep(2), ep(3))))
        assertEquals("e3" to DetailPlay.Kind.NEXT, chosen(listOf(ep(1, watched = true), ep(2, watched = true), ep(3), ep(4))))
        val partWay = listOf(ep(1, watched = true), ep(2, pos = 600_000, at = 5), ep(3, pos = 100_000, at = 9), ep(4))
        assertEquals("e3" to DetailPlay.Kind.RESUME, chosen(partWay))
        assertEquals("e1" to DetailPlay.Kind.RESTART, chosen(listOf(ep(1, watched = true), ep(2, watched = true))))
        // an extra after the last watched episode is never "next"
        val extraNext = listOf(ep(1, watched = true), ep(9, tag = ContentKind.EXTRA), ep(2))
        assertEquals("e2", DetailPlay.choose(extraNext)!!.item.id)
        assertEquals(null, DetailPlay.choose(emptyList()))
        assertEquals(DetailPlay.RowState.Left(14), DetailPlay.rowState(ep(5, pos = 600_000)))
        assertEquals(DetailPlay.RowState.Watched, DetailPlay.rowState(ep(5, watched = true)))
        assertEquals(null, DetailPlay.rowState(ep(5)))
    }

    @Test
    fun markingAllCountsTheEpisodesNotTheExtras() {
        // Every episode seen, the creditless OP not: the card IS all watched, and the way back is
        // to mark the episodes unwatched — the extra is left as it is.
        val seen = listOf(ep(1, watched = true), ep(2, watched = true), ep(9, tag = ContentKind.EXTRA))
        val back = DetailPlay.markAll(seen)
        assertEquals(true, back.allWatched)
        assertEquals(listOf("e1", "e2"), back.targets.map { it.id })
        assertEquals(true, back.needsConfirm) // two files' progress would be cleared

        // Half-way through a season: only the unwatched episodes change, and the one being watched
        // loses its place — that alone is worth asking about.
        val partWay = listOf(ep(1, watched = true), ep(2, pos = 600_000), ep(3))
        val forward = DetailPlay.markAll(partWay)
        assertEquals(false, forward.allWatched)
        assertEquals(listOf("e2", "e3"), forward.targets.map { it.id })
        assertEquals(1, forward.partWatched)
        assertEquals(true, forward.needsConfirm)

        // One untouched film: nothing to lose, nothing to ask.
        val film = DetailPlay.markAll(listOf(ep(1)))
        assertEquals(listOf("e1"), film.targets.map { it.id })
        assertEquals(false, film.needsConfirm)
        // …and a part-watched one is asked about, even alone.
        assertEquals(true, DetailPlay.markAll(listOf(ep(1, pos = 60_000))).needsConfirm)
    }

    @Test
    fun filesTheParserCannotTellApartKeepTheirOwnNames() {
        fun names(vararg files: String) = DetailPlay.distinctNames(files.toList())
        // An album: the shared title goes, the track's own words stay.
        assertEquals(
            listOf("01 - Intro", "02 - Theme"),
            names("2003 Sound Horizon Pico Magic - 01 - Intro.mkv", "2003 Sound Horizon Pico Magic - 02 - Theme.mkv"),
        )
        // Numbers are not split: "Track 0" is not a shared word.
        assertEquals(listOf("01", "02"), names("Track 01.mkv", "Track 02.mkv"))
        // A bracket is kept whole, and a shared tail goes as well as a shared head.
        assertEquals(
            listOf("[01] Intro", "[02] Theme"),
            names("[Group] Album [01] Intro [1080p].mkv", "[Group] Album [02] Theme [1080p].mkv"),
        )
        assertEquals(listOf("Part1", "Part2"), names("Movie.Part1.mkv", "Movie.Part2.mkv"))
        // Nothing shared: the names as they are.
        assertEquals(listOf("Alpha", "Beta"), names("Alpha.mkv", "Beta.mkv"))
        // A file that is nothing BUT the shared part keeps its whole name, and so do the others.
        assertEquals(listOf("Film", "Film Extended"), names("Film.mkv", "Film Extended.mkv"))
        // Two files of one name stay as they are rather than becoming two blanks.
        assertEquals(listOf("Same", "Same"), names("Same.mkv", "Same.mkv"))
    }

    @Test
    fun `a film's part is named by its number`() {
        assertEquals(1, DetailPlay.partNumber("The.Fellowship.of.the.Ring.2001.Extended.Editions.Part.1.Blu-ray.Remux.1080p.m2ts"))
        assertEquals(2, DetailPlay.partNumber("影视帝国(bbs.cnxp.com).华氏911.CD2.rmvb"))
        assertEquals(2, DetailPlay.partNumber("Movie.2004.DVDRip.XviD-cd2.avi"))
        assertEquals(2, DetailPlay.partNumber("Kill Bill Part Two.mkv"))
        assertEquals(1, DetailPlay.partNumber("赤壁(上).mkv"))
        assertEquals(2, DetailPlay.partNumber("赤壁 下集.mkv"))
        assertEquals(null, DetailPlay.partNumber("Spotlight.2015.1080p.BluRay.mkv"))
        assertEquals(null, DetailPlay.partNumber("Terminator 2.mkv"))
    }
}
