package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.scrape.ContentClassifier
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wall for libraries that look nothing like the one the rules were tuned against.
 *
 * Every case here is a layout convention this project's own evaluation library does not contain —
 * Plex and Kodi naming, scene releases, a flat dump, Japanese folder names with no Chinese anywhere,
 * a film split into parts. The evaluation harness can only ever measure the shelf it was built from;
 * these are the shapes every other user's shelf is made of.
 */
class ForeignLayoutTest {

    private var n = 0
    private fun file(
        path: String,
        key: String? = null,
        title: String = path.substringAfterLast('/').substringBeforeLast('.'),
        kind: MediaKind = MediaKind.MOVIE,
        season: Int? = null,
        episode: Int? = null,
        status: ScrapeStatus = if (key == null) ScrapeStatus.UNMATCHED else ScrapeStatus.MATCHED,
    ) = MediaItem(
        id = "i${n++}", sourceId = "s", path = path, fileName = path.substringAfterLast('/'),
        title = title, kind = kind, season = season, episode = episode,
        collectionKey = key, scrapeStatus = status, contentTag = ContentKind.MAIN,
    )

    private fun cards(items: List<MediaItem>) = LibraryGrouping.group(items)

    // ── Plex / Jellyfin: one folder per film, name and year in both ───────────────────────────
    @Test fun plexMovieFoldersAreOneCardEach() {
        val c = cards(
            listOf(
                file("/mnt/media/Movies/The Matrix (1999)/The Matrix (1999).mkv", "id:603", "The Matrix"),
                file("/mnt/media/Movies/Inception (2010)/Inception (2010).mkv", "id:27205", "Inception"),
                file("/mnt/media/Movies/Arrival (2016)/Arrival (2016).mkv", "id:329865", "Arrival"),
            ),
        )
        assertEquals(c.map { it.title }.toString(), 3, c.size)
        assertTrue(c.map { it.title }.containsAll(listOf("The Matrix", "Inception", "Arrival")))
    }

    // ── Kodi / Jellyfin TV: Season folders, SxxExx in the file ────────────────────────────────
    @Test fun aSeasonFolderedShowIsOneCard() {
        val eps = (1..2).flatMap { s ->
            (1..6).map { e ->
                file(
                    "/mnt/media/TV Shows/Breaking Bad/Season 0$s/Breaking Bad - S0${s}E0$e - Episode.mkv",
                    "id:1396", "Breaking Bad", MediaKind.EPISODE, season = s, episode = e,
                )
            }
        }
        val c = cards(eps)
        assertEquals(c.map { "${it.title} ${it.count}" }.toString(), 1, c.size)
        assertEquals(12, c.single().count)
        assertTrue(c.single().isSeries)
    }

    // ── Scene release: the folder repeats the file, dots for spaces, a group suffix ───────────
    @Test fun aSceneReleaseIsOneCardNamedByTheWork() {
        val c = cards(
            listOf(
                file(
                    "/data/Movies/The.Matrix.1999.1080p.BluRay.x264-AMIABLE/the.matrix.1999.1080p.bluray.x264-amiable.mkv",
                    "id:603", "The Matrix",
                ),
                file(
                    "/data/Movies/Sicario.2015.2160p.UHD.BluRay.x265-TERMiNAL/sicario.2015.2160p.uhd.bluray.x265-terminal.mkv",
                    "id:273481", "Sicario",
                ),
            ),
        )
        assertEquals(c.map { it.title }.toString(), 2, c.size)
    }

    // ── A flat dump with no folders at all ────────────────────────────────────────────────────
    @Test fun aFlatFolderStillGivesOneCardPerFilm() {
        val c = cards(
            listOf(
                file("/sdcard/Movies/The Matrix 1999.mkv", "id:603", "The Matrix"),
                file("/sdcard/Movies/Inception 2010.mkv", "id:27205", "Inception"),
                file("/sdcard/Movies/Arrival 2016.mkv", "id:329865", "Arrival"),
            ),
        )
        assertEquals(c.map { it.title }.toString(), 3, c.size)
    }

    // ── Japanese shelf, no Chinese anywhere ───────────────────────────────────────────────────
    @Test fun aJapaneseNamedShelfGroupsLikeAnyOther() {
        val eps = (1..12).map {
            file(
                "/nas/アニメ/進撃の巨人/[Group] Shingeki no Kyojin - ${"%02d".format(it)} [1080p].mkv",
                "anilist:16498", "進撃の巨人", MediaKind.EPISODE, season = 1, episode = it,
            )
        }
        val c = cards(eps)
        assertEquals(c.map { "${it.title} ${it.count}" }.toString(), 1, c.size)
        assertEquals(12, c.single().count)
    }

    // ── A film split into parts is one card, not two ──────────────────────────────────────────
    @Test fun aFilmSplitIntoPartsIsOneCard() {
        val c = cards(
            listOf(
                file("/data/Movies/Gone with the Wind (1939)/Gone with the Wind (1939) - part1.mkv", "id:770", "Gone with the Wind"),
                file("/data/Movies/Gone with the Wind (1939)/Gone with the Wind (1939) - part2.mkv", "id:770", "Gone with the Wind"),
            ),
        )
        assertEquals(c.map { "${it.title} ${it.count}" }.toString(), 1, c.size)
    }

    // ── Nothing scraped yet: the wall still has to be usable ──────────────────────────────────
    @Test fun anUnscrapedLibraryGroupsByItsFolders() {
        val c = cards(
            listOf(
                file("/mnt/media/Movies/The Matrix (1999)/The Matrix (1999).mkv"),
                file("/mnt/media/Movies/Inception (2010)/Inception (2010).mkv"),
            ) + (1..6).map {
                file(
                    "/mnt/media/TV Shows/Breaking Bad/Season 01/Breaking Bad - S01E0$it - Episode.mkv",
                    kind = MediaKind.EPISODE, season = 1, episode = it,
                )
            },
        )
        // Two films and one show — never nine loose cards, and never one card for everything.
        assertEquals(c.map { "${it.title}:${it.count}" }.toString(), 3, c.size)
        assertEquals(6, c.first { it.count > 1 }.count)
    }

    // ── Jellyfin / Kodi mark a film's extras on the file's own name ───────────────────────────
    @Test fun theExtrasConventionEveryWesternLibraryUses() {
        val dir = "/mnt/media/Movies/Blade Runner 2049 (2017)/"
        val film = "Blade Runner 2049 (2017)"
        // All eight of the documented suffixes. Five of them read as FEATURES before: a library
        // organised by either tool had its behind-the-scenes and deleted scenes on the wall.
        listOf(
            "behindthescenes", "deleted", "featurette", "interview",
            "scene", "short", "trailer", "other",
        ).forEach {
            val name = "$film-$it.mkv"
            assertEquals(name, ContentKind.EXTRA, ContentClassifier.classify(name, dir + name))
        }
        // The film itself is not one of them (MAIN — the kind comes from the database, not the
        // name; MOVIE here means the NAME says "the Movie"), and neither is a film whose name
        // happens to be one of the words.
        assertEquals(ContentKind.MAIN, ContentClassifier.classify("$film.mkv", "$dir$film.mkv"))
        assertEquals(
            ContentKind.MAIN,
            ContentClassifier.classify("The Short (2019).mkv", "/mnt/media/Movies/The Short (2019).mkv"),
        )
    }

    @Test fun sceneAndWesternNamingParses() {
        // Nothing on this project's own shelf is named any of these ways.
        fun p(n: String) = FilenameParser.parse(n)
        assertEquals("Show Name", p("Show.Name.S01E02.1080p.WEB-DL.DDP5.1.H.264-NTb.mkv").title)
        assertEquals(1 to 2, p("Show.Name.S01E02.1080p.WEB-DL.mkv").let { it.season to it.episode })
        assertEquals(1 to 5, p("Show Name 1x05.avi").let { it.season to it.episode })
        assertEquals(2019, p("Movie.Name.2019.PROPER.REPACK.1080p.BluRay.x264-GROUP.mkv").year)
        // A Jellyfin id tag and an audio layout must not be read as a title or a year.
        assertEquals("Blade Runner 2049", p("Blade Runner 2049 (2017) [imdb-tt1856101].mkv").title)
        assertEquals(2019, p("Movie.Name.2019.2160p.HDR.DV.TrueHD.7.1.Atmos-GROUP.mkv").year)
        // Absolute numbering, a release revision, and a Korean name.
        assertEquals(125, p("[Group] Show - 125 [1080p].mkv").episode)
        assertEquals(1, p("Show - 01v2.mkv").episode)
        assertEquals("시그널", p("시그널.E01.mkv").title)
    }
}
