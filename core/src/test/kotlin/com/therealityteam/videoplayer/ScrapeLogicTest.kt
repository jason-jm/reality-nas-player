package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import com.therealityteam.videoplayer.ui.library.LibSection
import com.therealityteam.videoplayer.data.scrape.Matcher
import com.therealityteam.videoplayer.data.scrape.MetaResult
import com.therealityteam.videoplayer.data.scrape.ParsedName
import com.therealityteam.videoplayer.data.scrape.StringSimilarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validates the headline scraping logic (runs on the JVM, no device needed). */
class ScrapeLogicTest {

    @Test
    fun movieWithYearAndNoise() {
        val p = FilenameParser.parse(
            "Interstellar.2014.2160p.BluRay.x264.8bit.DoVi.DTS-HD.MA.5.1-BiG.mkv",
        )
        assertEquals("Interstellar", p.title)
        assertEquals(2014, p.year)
        assertFalse(p.isEpisode)
    }

    @Test
    fun tvEpisodeSxxExx() {
        val p = FilenameParser.parse("The.Bear.S01E05.1080p.WEB-DL.mkv")
        assertEquals("The Bear", p.title)
        assertEquals(1, p.season)
        assertEquals(5, p.episode)
        assertTrue(p.isEpisode)
    }

    @Test
    fun bracketedYearSurvives() {
        val p = FilenameParser.parse("Avengers Endgame (2019) [1080p].mkv")
        assertEquals("Avengers Endgame", p.title)
        assertEquals(2019, p.year)
    }

    @Test
    fun altEpisodeFormat() {
        val p = FilenameParser.parse("Some.Show.3x08.HDTV.x264.mkv")
        assertEquals(3, p.season)
        assertEquals(8, p.episode)
    }

    @Test
    fun uhdHdrMovie() {
        val p = FilenameParser.parse("Dune.Part.Two.2024.2160p.UHD.BluRay.x265.10bit.HDR.mkv")
        assertEquals("Dune Part Two", p.title)
        assertEquals(2024, p.year)
    }

    @Test
    fun yearInTitleNotMistakenForYear() {
        // "2049" is part of the title; the real release year (2017) trails it.
        val p = FilenameParser.parse("Blade.Runner.2049.2017.2160p.BluRay.x264.mkv")
        assertEquals("Blade Runner 2049", p.title)
        assertEquals(2017, p.year)
    }

    @Test
    fun futureLikeNumberInTitleIsNotAYear() {
        // No release year present; "2049" is implausible as a year so it stays in the title.
        val p = FilenameParser.parse("Blade.Runner.2049.2160p.BluRay.mkv")
        assertEquals("Blade Runner 2049", p.title)
        assertNull(p.year)
    }

    @Test
    fun matcherPrefersPopularOverSimilarObscure() {
        // zh-CN-style ordering put the obscure look-alike first; popularity must win.
        val parsed = ParsedName(title = "Your Name", year = 2016)
        val wrong = MetaResult(2, MediaKind.MOVIE, title = "What's Your Name?", year = 2016, popularity = 0.0)
        val correct = MetaResult(1, MediaKind.MOVIE, title = "Your Name.", year = 2016, popularity = 24.9)
        assertEquals(1, Matcher.best(parsed, listOf(wrong, correct))?.providerId)
    }

    @Test
    fun matcherRejectsWhenNothingMatches() {
        val parsed = ParsedName(title = "feishu test")
        val junk = MetaResult(9, MediaKind.MOVIE, title = "Completely Different Film", year = 1990, popularity = 0.2)
        assertNull(Matcher.best(parsed, listOf(junk)))
    }

    @Test
    fun folderRescuesGenericFilename() {
        val p = FilenameParser.parseBest("video1.mkv", "Interstellar (2014)")
        assertEquals("Interstellar", p.title)
        assertEquals(2014, p.year)
    }

    @Test
    fun folderProvidesYearWhenFileLacksIt() {
        val p = FilenameParser.parseBest("Interstellar.mkv", "Interstellar (2014)")
        assertEquals("Interstellar", p.title)
        assertEquals(2014, p.year)
    }

    @Test
    fun goodFilenameIgnoresFolder() {
        val p = FilenameParser.parseBest("Dune.Part.Two.2024.2160p.mkv", "Movies")
        assertEquals("Dune Part Two", p.title)
        assertEquals(2024, p.year)
    }

    @Test
    fun folderNameFromPath() {
        assertEquals("Interstellar (2014)", FilenameParser.folderName("/storage/Movies/Interstellar (2014)/movie.mkv"))
        assertEquals("Blade Runner 2049", FilenameParser.folderName("share\\Blade Runner 2049\\file.mkv"))
    }

    @Test
    fun librarySectionsAndSearchOverGroups() {
        fun item(
            id: String,
            path: String,
            fileName: String,
            title: String,
            kind: MediaKind,
            fav: Boolean = false,
            started: Long = 0L,
        ) = MediaItem(
            id = id, sourceId = "s", path = path, fileName = fileName, title = title, kind = kind,
            favorite = fav,
            progress = PlaybackProgress(positionMs = started, durationMs = if (started > 0) 100L else 0L),
        )
        val items = listOf(
            item("a", "/lib/Interstellar (2014)/Interstellar.mkv", "Interstellar.mkv", "Interstellar", MediaKind.MOVIE, fav = true, started = 40L),
            item("b1", "/lib/The Bear/Season 1/The.Bear.S01E01.mkv", "The.Bear.S01E01.mkv", "The Bear", MediaKind.EPISODE),
            item("b2", "/lib/The Bear/Season 1/The.Bear.S01E02.mkv", "The.Bear.S01E02.mkv", "The Bear", MediaKind.EPISODE),
            item("c", "/lib/Dune (2021)/Dune.mkv", "Dune.mkv", "Dune", MediaKind.MOVIE),
        )
        val groups = LibraryGrouping.group(items)
        // The show's two episodes collapse into ONE card; the movies stay separate.
        assertEquals(3, groups.size)
        assertEquals(2, groups.single { it.title == "The Bear" }.count)

        assertEquals(3, LibraryGrouping.filter(groups, LibSection.ALL, "").size)
        assertEquals(2, LibraryGrouping.filter(groups, LibSection.MOVIE, "").size)    // a, c
        assertEquals(1, LibraryGrouping.filter(groups, LibSection.TV, "").size)       // the bear
        assertEquals(1, LibraryGrouping.filter(groups, LibSection.FAVORITES, "").size) // a
        // History = anything started, most recent first.
        assertEquals("Interstellar", LibraryGrouping.filter(groups, LibSection.HISTORY, "").single().title)
        assertEquals("The Bear", LibraryGrouping.filter(groups, LibSection.ALL, "bear").single().title)
    }

    @Test
    fun levenshteinDistance() {
        assertEquals(3, StringSimilarity.levenshtein("kitten", "sitting"))
        assertEquals(0, StringSimilarity.levenshtein("same", "same"))
    }

    @Test
    fun jaroWinklerKnownValue() {
        assertEquals(0.961, StringSimilarity.jaroWinkler("MARTHA", "MARHTA"), 0.01)
    }

    @Test
    fun titleSimilarityIgnoresCaseAndPunctuation() {
        assertEquals(1.0, StringSimilarity.titleSimilarity("The Bear", "the bear!"), 0.0001)
        assertTrue(StringSimilarity.titleSimilarity("Interstellar", "interstellar") > 0.99)
        assertTrue(StringSimilarity.titleSimilarity("Inception", "The Matrix") < 0.6)
    }
}
