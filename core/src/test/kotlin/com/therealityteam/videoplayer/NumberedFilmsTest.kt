package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.scrape.AnimeMatch
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.MetaResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A box of films ripped as if it were a series.
 *
 * `Ghost in the Shell Arise[01]` … `[04]` are four films numbered like four episodes; the query is
 * the box's name, and AniList answers it with all four Borders. Popularity chose among them, so all
 * four files became Border:1 — and all seven chapters of 空の境界 became 俯瞰風景. The titles below
 * are what AniList actually returns for those two boxes.
 */
class NumberedFilmsTest {

    private fun film(id: Int, en: String, romaji: String, year: Int, pop: Int = 100, format: String = "MOVIE") = MetaResult(
        providerId = id, kind = if (format == "MOVIE") MediaKind.MOVIE else MediaKind.SHOW,
        title = en, englishTitle = en, romajiTitle = romaji, year = year, popularity = pop.toDouble(), format = format,
    )

    private val arise = listOf(
        film(21056, "Ghost in the Shell Arise: Alternative Architecture", "Koukaku Kidoutai ARISE: ALTERNATIVE ARCHITECTURE", 2015, pop = 900, format = "TV"),
        film(17187, "Ghost in the Shell: Arise - Border:1 Ghost Pain", "Koukaku Kidoutai ARISE: GHOST IN THE SHELL - border:1 Ghost Pain", 2013, pop = 5000),
        film(19191, "Ghost in the Shell: Arise - Border:2 Ghost Whispers", "Koukaku Kidoutai ARISE: GHOST IN THE SHELL - border:2 Ghost Whispers", 2013, pop = 3000),
        film(19193, "Ghost in the Shell: Arise - Border:3 Ghost Tears", "Koukaku Kidoutai ARISE: GHOST IN THE SHELL - border:3 Ghost Tears", 2014, pop = 2500),
        film(19195, "Ghost in the Shell: Arise - Border:4 Ghost Stands Alone", "Koukaku Kidoutai ARISE: GHOST IN THE SHELL - border:4 Ghost Stands Alone", 2014, pop = 2400),
    )

    @Test fun `the Nth numbered file is the Nth numbered film`() {
        val q = "Ghost in the Shell Arise"
        assertEquals(17187, AnimeMatch.pick(q, arise, filmNumber = 1)?.providerId)
        assertEquals(19191, AnimeMatch.pick(q, arise, filmNumber = 2)?.providerId)
        assertEquals(19195, AnimeMatch.pick(q, arise, filmNumber = 4)?.providerId)
    }

    @Test fun `a chapter number written in Chinese counts too`() {
        val kara = listOf(
            film(2593, "the Garden of sinners Chapter 1: Thanatos", "Kara no Kyoukai: Fukan Fuukei", 2007, pop = 9000),
            film(3782, "the Garden of sinners Chapter 2: …and nothing heart", "Kara no Kyoukai: Satsujin Kousatsu (Zen)", 2008),
            film(5205, "the Garden of sinners Chapter 7: ……not nothing heart", "Kara no Kyoukai: Satsujin Kousatsu (Kou)", 2009),
            film(14807, "the Garden of sinners -recalled out summer-", "Kara no Kyoukai: Mirai Fukuin", 2013),
        )
        assertEquals(5205, AnimeMatch.pick("Kara no Kyoukai", kara, filmNumber = 7)?.providerId)
        assertEquals(7, AnimeMatch.filmOrdinal(film(1, "x", "x", 2000).copy(originalTitle = "空の境界 第七章 殺人考察")))
    }

    @Test fun `without a file number nothing changes`() {
        // The most popular Border still wins a plain query, exactly as before.
        assertEquals(17187, AnimeMatch.pick("Ghost in the Shell Arise", arise)?.providerId)
    }

    @Test fun `a number the box does not have is not a film`() {
        assertEquals(17187, AnimeMatch.pick("Ghost in the Shell Arise", arise, filmNumber = 9)?.providerId)
    }

    @Test fun `one entry that happens to say Part 2 is a title, not a numbering`() {
        // Only a SET of numbered films is a numbering; a lone "Part 2" must not catch episode 2 of a series.
        val series = listOf(
            film(1, "Some Show", "Some Show", 2010, pop = 5000, format = "TV"),
            film(2, "Some Show Part 2", "Some Show Part 2", 2012, pop = 100),
        )
        assertEquals(1, AnimeMatch.pick("Some Show", series, filmNumber = 2)?.providerId)
        assertNull(AnimeMatch.filmOrdinal(film(3, "Mobile Suit Gundam 00", "Kidou Senshi Gundam 00", 2007)))
        assertNull(AnimeMatch.filmOrdinal(film(4, "Ghost in the Shell 2.0", "Koukaku Kidoutai 2.0", 2008)))
    }

    @Test fun `a numbered spin-off beside a series does not take the series' episodes`() {
        // `Soukyuu no Fafner` answers with the 26-episode series and THE BEYOND Parts 2–4. On a real
        // library episodes [02]…[04] became Parts 2–4. The series answers the name exactly; the
        // films only contain it — so the files are its episodes.
        val fafner = listOf(
            film(75, "Fafner in the Azure", "Soukyuu no Fafner", 2004, pop = 8000, format = "TV"),
            film(112308, "Fafner in the Azure: THE BEYOND Part 2", "Soukyuu no Fafner: Dead Aggressor - THE BEYOND Part 2", 2019, pop = 400),
            film(117758, "Fafner in the Azure: THE BEYOND Part 3", "Soukyuu no Fafner: Dead Aggressor - THE BEYOND Part 3", 2021, pop = 300),
            film(130033, "Fafner in the Azure: THE BEYOND Part 4", "Soukyuu no Fafner: Dead Aggressor - THE BEYOND Part 4", 2021, pop = 300),
        )
        assertEquals(75, AnimeMatch.pick("Soukyuu no Fafner", fafner, filmNumber = 2)?.providerId)
        assertEquals(75, AnimeMatch.pick("Soukyuu no Fafner", fafner, filmNumber = 4)?.providerId)
        // …while a recut TV series that is no closer to the name than the films leaves the box a box.
        assertEquals(19191, AnimeMatch.pick("Ghost in the Shell Arise", arise, filmNumber = 2)?.providerId)
    }

    // ── a folder that says live-action ──────────────────────────────────────────────────────

    @Test fun `a folder that says live-action means it`() {
        listOf(
            "smb://n/video/Anime/Patlabor 機動警察 実写映画/[J.X&WiKi]The.Next.Generation.Patlabor.E08.2014.mkv",
            "smb://n/video/Anime/浪客剑心 真人版/x.mkv",
            "smb://n/video/Anime/Rurouni Kenshin Live Action/x.mkv",
        ).forEach { assertTrue(it, FilenameParser.saysLiveAction(it)) }
    }

    @Test fun `the word has to be on a FOLDER, and an ordinary anime folder does not say it`() {
        assertFalse(FilenameParser.saysLiveAction("smb://n/video/Anime/摇曳露营/[VCB] Yuru Camp [01].mkv"))
        // The file's own name is not a filing decision.
        assertFalse(FilenameParser.saysLiveAction("smb://n/video/Anime/Kenshin/Rurouni Kenshin Live Action.mkv"))
    }
}
