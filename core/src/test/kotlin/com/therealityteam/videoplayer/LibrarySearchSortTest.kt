package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.library.LibSection
import com.therealityteam.videoplayer.ui.library.LibSort
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import com.therealityteam.videoplayer.ui.library.LibrarySort
import com.therealityteam.videoplayer.ui.library.SearchKeys
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Search answers to pinyin initials, romanisation, the original title, the folder and the cast;
 * the wall sorts by what the user picked. The romaniser is faked — on device it is ICU.
 */
class LibrarySearchSortTest {

    private val pinyin = mapOf(
        "刀剑神域" to "dāo jiàn shén yù",
        "你的名字。" to "nǐ de míng zì",
        "松冈祯丞" to "sōng gāng zhēn chéng",
    )
    private fun rom(s: String): String? = pinyin[s]

    private fun item(
        id: String, title: String, path: String, originalTitle: String? = null,
        cast: List<Person> = emptyList(), addedAt: Long = 0L, year: Int? = null,
        ratings: List<RatingScore> = emptyList(), watchedAt: Long = 0L,
        aliases: List<String> = emptyList(),
    ) = MediaItem(
        id = id, sourceId = "s", path = path, fileName = path.substringAfterLast('/'), title = title,
        originalTitle = originalTitle, aliases = aliases, cast = cast, addedAt = addedAt, year = year, ratings = ratings,
        kind = MediaKind.MOVIE, scrapeStatus = ScrapeStatus.MATCHED, collectionKey = "id:$id", tmdbId = id.toInt(),
        progress = PlaybackProgress(positionMs = if (watchedAt > 0) 1_000 else 0, durationMs = 5_000, updatedAt = watchedAt),
    )

    private val sao = item(
        "1", "刀剑神域", "smb://nas/video/Anime/Sword Art Online 1080P BDRIP/SAO 01.mkv",
        originalTitle = "Sword Art Online", cast = listOf(Person("松冈祯丞", "桐人", null)),
        addedAt = 3, year = 2012, ratings = listOf(RatingScore(RatingSource.TMDB, "8.0")),
    )
    private val yourName = item(
        "2", "你的名字。", "smb://nas/video/Anime/你的名字/Kimi no Na wa.mkv",
        originalTitle = "君の名は。", addedAt = 2, year = 2016, ratings = listOf(RatingScore(RatingSource.IMDB, "8.4")),
    )
    private val heat = item(
        "3", "Heat", "smb://nas/video/Movies/Heat (1995)/Heat.mkv",
        addedAt = 1, year = 1995, ratings = listOf(RatingScore(RatingSource.METACRITIC, "76")), watchedAt = 99,
    )
    private val groups = LibraryGrouping.romanized(LibraryGrouping.group(listOf(sao, yourName, heat)), ::rom)

    private fun titles(query: String, section: LibSection = LibSection.ALL, sort: LibSort = LibSort.RECENT) =
        LibraryGrouping.filter(groups, section, query, sort).map { it.title }

    @Test
    fun `the adult shelf is hidden everywhere it is hidden, not only on the sidebar`() {
        // Switching it off took the tab off the sidebar and left every card on it sitting in 全部,
        // in search results, in 收藏 and in 最近 — the four places somebody who switched it off is
        // actually looking.
        val r18 = item("4", "R18 Work", "smb://nas/video/Anime/x/e1.mkv").copy(certification = "R18+")
        val all = LibraryGrouping.romanized(
            LibraryGrouping.group(listOf(sao, yourName, heat, r18)), ::rom,
        )
        fun shown(section: LibSection, q: String = "", showAdult: Boolean) =
            LibraryGrouping.filter(all, section, q, LibSort.RECENT, showAdult).map { it.title }

        assertEquals(true, "R18 Work" in shown(LibSection.ALL, showAdult = true))
        listOf(LibSection.ALL, LibSection.MOVIE, LibSection.ANIME).forEach {
            assertEquals("$it", false, "R18 Work" in shown(it, showAdult = false))
        }
        assertEquals(emptyList<String>(), shown(LibSection.ALL, "R18", showAdult = false))
        // …and the shelf itself still shows its own cards, for whoever switched it on.
        assertEquals(listOf("R18 Work"), shown(LibSection.ADULT, showAdult = true))
    }

    @Test
    fun aCardAnswersToPinyinInitialsRomanisationOriginalTitleFolderAndCast() {
        assertEquals(listOf("刀剑神域"), titles("djsy"))
        assertEquals(listOf("刀剑神域"), titles("daojian"))
        assertEquals(listOf("刀剑神域"), titles("sword art"))
        assertEquals(listOf("刀剑神域"), titles("松冈"))
        assertEquals(listOf("刀剑神域"), titles("刀剑 神域"))
        assertEquals(listOf("你的名字。"), titles("nidemingzi"))
        assertEquals(listOf("你的名字。"), titles("kimi no na")) // the file's own name still counts
        assertEquals(emptyList<String>(), titles("zzz"))
        assertEquals(3, titles("").size)
        // Before romanisation lands, the plain keys already answer to the characters and the original title.
        val plain = LibraryGrouping.group(listOf(sao, yourName, heat))
        assertEquals(listOf("刀剑神域"), LibraryGrouping.filter(plain, LibSection.ALL, "sword art").map { it.title })
        assertEquals(emptyList<String>(), LibraryGrouping.filter(plain, LibSection.ALL, "djsy").map { it.title })
    }

    @Test
    fun theWallSortsByWhatWasPicked() {
        assertEquals(listOf("刀剑神域", "你的名字。", "Heat"), titles("", sort = LibSort.RECENT))
        assertEquals(listOf("刀剑神域", "Heat", "你的名字。"), titles("", sort = LibSort.TITLE)) // dao… < heat < ni…
        assertEquals(listOf("你的名字。", "刀剑神域", "Heat"), titles("", sort = LibSort.RATING)) // 8.4 > 8.0 > 7.6
        assertEquals(listOf("你的名字。", "刀剑神域", "Heat"), titles("", sort = LibSort.YEAR))
        assertEquals("Heat", titles("", sort = LibSort.WATCHED).first())
        // 历史 lists what was watched, most recent first, whatever the default order is
        assertEquals(listOf("Heat"), titles("", section = LibSection.HISTORY))
    }

    @Test
    fun theAlphabetPutsNamedWorksBeforeUnnamedFiles() {
        // "00 Footage" sorts ahead of everything by the alphabet and means nothing to a viewer.
        val junk = MediaItem(
            id = "9", sourceId = "s", path = "smb://nas/video/下载/B-roll/00 Footage.mp4",
            fileName = "00 Footage.mp4", title = "00 Footage", kind = MediaKind.MOVIE,
            scrapeStatus = ScrapeStatus.UNMATCHED, addedAt = 9,
        )
        val withJunk = LibraryGrouping.romanized(LibraryGrouping.group(listOf(sao, yourName, heat, junk)), ::rom)
        assertEquals(
            listOf("刀剑神域", "Heat", "你的名字。", "B-roll"), // the folder names the unidentified card
            LibraryGrouping.filter(withJunk, LibSection.ALL, "", LibSort.TITLE).map { it.title },
        )
        // …and it is still first when the wall is ordered by what arrived last.
        assertEquals("B-roll", LibraryGrouping.filter(withJunk, LibSection.ALL, "", LibSort.RECENT).first().title)
    }

    @Test
    fun ratingsAreComparedOnOneScale() {
        assertEquals(7.6, LibrarySort.ratingValue(listOf(RatingScore(RatingSource.METACRITIC, "76"))), 1e-9)
        assertEquals(9.1, LibrarySort.ratingValue(listOf(RatingScore(RatingSource.ROTTEN_TOMATOES, "91%"))), 1e-9)
        assertEquals(8.2, LibrarySort.ratingValue(listOf(RatingScore(RatingSource.TMDB, "8.2"), RatingScore(RatingSource.IMDB, "8.2"))), 1e-9)
        assertEquals(0.0, LibrarySort.ratingValue(emptyList()), 1e-9)
        // a lone TMDB 10.0 is one person's vote, with or without the count
        assertEquals(0.0, LibrarySort.ratingValue(listOf(RatingScore(RatingSource.TMDB, "10.0"))), 1e-9)
        assertEquals(0.0, LibrarySort.ratingValue(listOf(RatingScore(RatingSource.TMDB, "10.0", votes = 3))), 1e-9)
        assertEquals(8.0, LibrarySort.ratingValue(listOf(RatingScore(RatingSource.TMDB, "8.0", votes = 500))), 1e-9)
        assertEquals(9.6, LibrarySort.ratingValue(listOf(RatingScore(RatingSource.TMDB, "9.6", votes = 12000))), 1e-9)
        assertEquals(8.9, LibrarySort.ratingValue(listOf(RatingScore(RatingSource.TMDB, "9.8"), RatingScore(RatingSource.IMDB, "8.9"))), 1e-9)
        assertEquals("djsy", SearchKeys.of(listOf("刀剑神域"), ::rom).last())
    }

    @Test
    fun aCardNamedInChineseIsFoundByTheNameTheWorldKnowsItBy() {
        // 30 of 322 anime works on a real shelf carried no Latin letters in any field or in the
        // folder they were filed under. Romanising the Chinese gives pinyin — `hundunwushi` — which
        // is not what anyone types, and on a headset the Latin name is the easy one to type.
        val champloo = item(
            "77", "混沌武士", "smb://nas/video/Anime/混沌武士/01.mkv",
            originalTitle = "サムライチャンプルー", aliases = listOf("Samurai Champloo"),
        )
        val wolf = item(
            "78", "狼与香辛料", "smb://nas/video/Anime/狼与辛香料/01.mkv",
            originalTitle = "狼と香辛料", aliases = listOf("Ookami to Koushinryou", "Spice and Wolf"),
        )
        val groups = LibraryGrouping.romanized(LibraryGrouping.group(listOf(champloo, wolf))) { null }
        fun find(q: String) = LibraryGrouping.filter(groups, LibSection.ALL, q, LibSort.TITLE).map { it.title }

        assertEquals(listOf("混沌武士"), find("samurai champloo"))
        assertEquals(listOf("混沌武士"), find("champloo"))
        assertEquals(listOf("狼与香辛料"), find("spice and wolf"))
        assertEquals(listOf("狼与香辛料"), find("ookami"))
        // …and the names it already answered to still work.
        assertEquals(listOf("混沌武士"), find("混沌"))
        assertEquals(listOf("狼与香辛料"), find("狼と香辛料"))
    }

    @Test fun `a card whose own name begins with the query comes before one that only contains it`() {
        val ghost = item("10", "攻壳机动队：新剧场版", "smb://nas/video/Anime/Koukaku Kidoutai Shin Gekijouban.mkv", originalTitle = "Koukaku Kidoutai Shin Gekijouban", addedAt = 9)
        val titan = item("11", "进击的巨人", "smb://nas/video/Anime/Shingeki no Kyojin/01.mkv", originalTitle = "Shingeki no Kyojin", addedAt = 1)
        val both = LibraryGrouping.romanized(LibraryGrouping.group(listOf(ghost, titan)), ::rom)
        assertEquals(listOf("进击的巨人", "攻壳机动队：新剧场版"), LibraryGrouping.filter(both, LibSection.ALL, "shingeki", LibSort.RECENT).map { it.title })
        // Without a query the wall keeps the order asked for (newest first).
        assertEquals(listOf("攻壳机动队：新剧场版", "进击的巨人"), LibraryGrouping.filter(both, LibSection.ALL, "", LibSort.RECENT).map { it.title })
    }
}

class MonogramTest {
    private fun mono(s: String) = com.therealityteam.videoplayer.ui.library.monogram(s)

    @Test
    fun aTileWithNoPosterShowsTheTitlesFirstLetter() {
        assertEquals("激", mono("激流中国"))
        assertEquals("T", mono("take me over"))
        assertEquals("N", mono("[NHK][纪录片]圆的战争"))
        assertEquals("", mono("   "))
    }

    @Test
    fun aLeadingNumberIsSkippedForTheLetterBehindIt() {
        // A→Z opened on a column of identical `0`s and `1`s: release folders lead with numbers far
        // more often than titles do, and the monogram exists to tell tiles apart.
        assertEquals("F", mono("00 Footage"))
        assertEquals("F", mono("00-Footage"))
        assertEquals("P", mono("01-Pick"))
        assertEquals("B", mono("03-BROLL Extra"))
        assertEquals("太", mono("2001太空漫游"))
        assertEquals("欢", mono("2 欢迎您访问"))
    }

    @Test
    fun aTitleThatIsOnlyANumberIsShownWhole() {
        // There the number IS the name, and `1917` says what `1` cannot.
        assertEquals("1917", mono("1917"))
        assertEquals("1944", mono("1944"))
        assertEquals("2012", mono("2012"))
    }

}
