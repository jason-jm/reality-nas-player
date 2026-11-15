package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.playback.QualityTags
import com.therealityteam.videoplayer.ui.library.DetailPlay
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One film filed twice, identified once by each database — TMDB off the anime shelf, AniList on it —
 * is one card, not two cards with one name and one year. Names, ids and folders as the real library
 * has them.
 */
class OneFilmTwoDatabasesTest {

    private fun tmdb(
        id: String, path: String, title: String, original: String, year: Int, tmdbId: Int,
        genres: List<String> = listOf("动画", "科幻", "动作"), extra: Boolean = false,
    ) = MediaItem(
        id = id, sourceId = "s", path = path, fileName = path.substringAfterLast('/'),
        title = title, originalTitle = original, year = year, tmdbId = tmdbId, collectionKey = "id:$tmdbId",
        kind = MediaKind.MOVIE, genres = genres, scrapeStatus = ScrapeStatus.MATCHED,
        contentTag = if (extra) ContentKind.EXTRA else ContentKind.MAIN,
    )

    private fun anilist(id: String, path: String, title: String, native: String, year: Int, anilistId: Int) = MediaItem(
        id = id, sourceId = "s", path = path, fileName = path.substringAfterLast('/'),
        title = title, originalTitle = native, year = year, tmdbId = anilistId, collectionKey = "anilist:$anilistId",
        kind = MediaKind.MOVIE, genres = listOf("Action", "Drama", "Mecha", "Sci-Fi"), scrapeStatus = ScrapeStatus.MATCHED,
        contentTag = ContentKind.MAIN,
    )

    private val hathawayDir = "smb://n/迅雷/下载/[冷番补完字幕组&白恋字幕组][机动战士高达 闪光的哈萨维][機動戦士ガンダム 閃光のハサウェイ][Mobile Suit Gundam Hathaway][2021][Movie][2160p][内封简繁中字]"

    @Test fun `a film identified by TMDB in one folder and AniList in another is one card`() {
        val release = listOf(
            tmdb("h1", "$hathawayDir/机动战士高达 闪光的哈萨维.Mobile.Suit.Gundam.Hathaway.2021.2160p.mkv", "机动战士高达 闪光的哈萨维", "機動戦士ガンダム 閃光のハサウェイ", 2021, 685274),
            tmdb("h2", "$hathawayDir/Extras/機動戦士ガンダム 閃光のハサウェイ 特報.mkv", "机动战士高达 闪光的哈萨维", "機動戦士ガンダム 閃光のハサウェイ", 2021, 685274, extra = true),
            tmdb("h3", "$hathawayDir/Extras/機動戦士ガンダム 閃光のハサウェイ 本予告.mkv", "机动战士高达 闪光的哈萨维", "機動戦士ガンダム 閃光のハサウェイ", 2021, 685274, extra = true),
        )
        val bdrip = anilist(
            "a1", "smb://n/video/Anime/Mobile Suit Gundam Hathaway's Flash/Mobile Suit Gundam - Hathaway's Flash [BDRip 1080p 10-bit Flacx2 x265].mkv",
            "机动战士高达 闪光的哈萨维", "機動戦士ガンダム 閃光のハサウェイ", 2021, 105595,
        )
        val cards = LibraryGrouping.group(release + bdrip)
        assertEquals(1, cards.size)
        // The release with its 特典 keeps its identity; the BDRip is another copy of the film.
        assertEquals("id:685274|movie", cards[0].key)
        assertEquals(setOf("h1", "h2", "h3", "a1"), cards[0].items.map { it.id }.toSet())
        assertEquals("机动战士高达 闪光的哈萨维", cards[0].title)
    }

    @Test fun `a copy each way is one card under TMDB's identity`() {
        val cards = LibraryGrouping.group(
            listOf(
                tmdb(
                    "d", "smb://n/video/Anime/借物少女艾莉媞[1080P]/[DBD-Raws][借物少女艾莉媞][1080P][BDRip][HEVC-10bit][国日粤三语][内封中日字幕][DTS-HDMA+DTSx2].mkv",
                    "借东西的小人阿莉埃蒂", "借りぐらしのアリエッティ", 2010, 51739, genres = listOf("奇幻", "动画", "家庭"),
                ),
                anilist(
                    "t", "smb://n/video/Anime/借物少女艾莉缇 The Borrowers Arrietty 1080P BDRIP/The_Borrowers_Arrietty_(2010)_[1080p,BluRay,x264,DTS]_-_THORA-UTW.mkv",
                    "借东西的小人阿莉埃蒂", "借りぐらしのアリエッティ", 2010, 7711,
                ),
            ),
        )
        assertEquals(listOf("id:51739|movie"), cards.map { it.key })
        assertEquals(2, cards[0].items.size)
    }

    @Test fun `a live-action film of an anime film's name and year is another film`() {
        // TMDB knows the 2017 Ghost in the Shell is no animation; and it is not even of the year.
        val cards = LibraryGrouping.group(
            listOf(
                tmdb("l", "smb://n/video/Movies/Ghost.in.the.Shell.2017.1080p.BluRay.x264.mkv", "攻壳机动队", "Ghost in the Shell", 2017, 315837, genres = listOf("动作", "科幻")),
                anilist("g", "smb://n/video/Anime/攻殻機動隊 1995/GHOST IN THE SHELL 攻殻機動隊 [BDRip 1080p].mkv", "攻壳机动队", "GHOST IN THE SHELL / 攻殻機動隊", 1995, 43),
                // Same name, same year, but live action by TMDB's account.
                tmdb("x", "smb://n/video/Movies/Hathaway.Live.2021.1080p.mkv", "闪光的哈萨维", "機動戦士ガンダム 閃光のハサウェイ", 2021, 999001, genres = listOf("剧情")),
                anilist("y", "smb://n/video/Anime/Hathaway/Hathaway [BDRip 1080p].mkv", "机动战士高达 闪光的哈萨维", "機動戦士ガンダム 閃光のハサウェイ", 2021, 105595),
            ),
        )
        assertEquals(4, cards.size)
    }

    @Test fun `a name two entries of one database share is no pair`() {
        // Two AniList films claim one TMDB film of their name and year: which one TMDB means is not
        // the wall's to guess, so all three keep their cards.
        val cards = LibraryGrouping.group(
            listOf(
                tmdb("t", "smb://n/video/Movies/Patlabor.1989.1080p.mkv", "机动警察 剧场版", "機動警察パトレイバー 劇場版", 1989, 4296),
                anilist("a", "smb://n/video/Anime/Patlabor Movie/Patlabor the Movie [BDRip 1080p].mkv", "机动警察 剧场版", "機動警察パトレイバー 劇場版", 1989, 1096),
                anilist("b", "smb://n/video/Anime/Patlabor Movie Remaster/Patlabor the Movie Remaster [BDRip 1080p].mkv", "机动警察 剧场版", "機動警察パトレイバー 劇場版", 1989, 99999),
            ),
        )
        assertEquals(3, cards.size)
    }

    @Test fun `series are never joined across databases`() {
        // A TMDB series is all of a show's seasons, an AniList entry one of them, numbered apart.
        fun ep(id: String, key: String, dir: String) = MediaItem(
            id = id, sourceId = "s", path = "smb://n/video/$dir/$id.mkv", fileName = "$id.mkv",
            title = "无职转生", originalTitle = "無職転生 ～異世界行ったら本気だす～", year = 2021, collectionKey = key,
            tmdbId = id.hashCode(), kind = MediaKind.EPISODE, episode = 1, season = 1, showTitle = "无职转生",
            genres = listOf("动画"), scrapeStatus = ScrapeStatus.MATCHED,
        )
        val cards = LibraryGrouping.group(listOf(ep("s1", "anilist:108465", "Anime/S1"), ep("s2", "id:94664", "下载/S2")))
        assertEquals(2, cards.size)
    }
}

/**
 * …and on its card the two copies are two versions of one film, though their names share no word:
 * one film to play and count, told apart by what each is, and 播放 starting the release the card is
 * named for until the other is played.
 */
class OneFilmTwoDatabasesVersionsTest {

    private val uhd = MediaItem(
        id = "uhd", sourceId = "s", path = "smb://n/迅雷/下载/H/机动战士高达 闪光的哈萨维.Mobile.Suit.Gundam.Hathaway.2021.2160p.BDRip.x265.DDP7.1.Atmos-CoolFansSub&Shirokoi.mkv",
        fileName = "机动战士高达 闪光的哈萨维.Mobile.Suit.Gundam.Hathaway.2021.2160p.BDRip.x265.DDP7.1.Atmos-CoolFansSub&Shirokoi.mkv",
        title = "机动战士高达 闪光的哈萨维", originalTitle = "機動戦士ガンダム 閃光のハサウェイ", year = 2021, tmdbId = 685274,
        kind = MediaKind.MOVIE, genres = listOf("动画"), scrapeStatus = ScrapeStatus.MATCHED,
    )
    private val bdrip = MediaItem(
        id = "bd", sourceId = "s", path = "smb://n/video/Anime/H/Mobile Suit Gundam - Hathaway's Flash [BDRip 1080p 10-bit Flacx2 x265].mkv",
        fileName = "Mobile Suit Gundam - Hathaway's Flash [BDRip 1080p 10-bit Flacx2 x265].mkv",
        title = "机动战士高达 闪光的哈萨维", originalTitle = "機動戦士ガンダム 閃光のハサウェイ", year = 2021, tmdbId = 105595,
        collectionKey = "anilist:105595", kind = MediaKind.MOVIE, scrapeStatus = ScrapeStatus.MATCHED, contentTag = ContentKind.MAIN,
    )
    private fun extra(n: Int) = uhd.copy(
        id = "pv$n", path = "smb://n/迅雷/下载/H/Extras/Hathaway.2021.PV0$n.1080p.mkv", fileName = "Hathaway.2021.PV0$n.1080p.mkv",
        kind = MediaKind.EPISODE, contentTag = ContentKind.EXTRA,
    )

    private fun card() = LibraryGrouping.group(listOf(uhd, bdrip, extra(1), extra(2))).single()

    @Test fun `the two copies are one film to play and count`() {
        val card = card()
        assertEquals(1, card.shownCount)
        assertEquals(
            setOf("uhd", "bd"),
            DetailPlay.versionsOf(card.items, card.items.first { it.id == "bd" }).map { it.id }.toSet(),
        )
    }

    @Test fun `play starts the card's own release, and then whichever was played last`() {
        val card = card()
        // The BDRip sorts first by name; the 2160p release is what the card is, and what 播放 played.
        assertEquals("uhd", DetailPlay.choose(card.items)!!.item.id)
        val played = card.items.map {
            if (it.id == "bd") it.copy(progress = PlaybackProgress(positionMs = 4_000, durationMs = 10_000, updatedAt = 5)) else it
        }
        assertEquals("bd", DetailPlay.choose(played)!!.item.id)
    }

    @Test fun `on a tie TMDB's copy stands for the film, as the card is TMDB's`() {
        val thora = bdrip.copy(
            id = "thora", fileName = "The_Borrowers_Arrietty_(2010)_[1080p,BluRay,x264,DTS]_-_THORA-UTW.mkv",
            originalTitle = "借りぐらしのアリエッティ", year = 2010, collectionKey = "anilist:7711", tmdbId = 7711,
        )
        val dbd = uhd.copy(
            id = "dbd", fileName = "[DBD-Raws][借物少女艾莉媞][1080P][BDRip][HEVC-10bit][国日粤三语][内封中日字幕][DTS-HDMA+DTSx2].mkv",
            originalTitle = "借りぐらしのアリエッティ", year = 2010, tmdbId = 51739, collectionKey = "id:51739",
        )
        // Whichever is listed first.
        assertEquals(mapOf("thora" to "dbd"), DetailPlay.alternates(listOf(thora, dbd)))
        assertEquals(mapOf("thora" to "dbd"), DetailPlay.alternates(listOf(dbd, thora)))
    }

    @Test fun `each copy is told apart by what it is`() {
        val labels = DetailPlay.versionLabels(listOf(bdrip, uhd))
        assertEquals(
            DetailPlay.VersionLabel.Tags(listOf(QualityTags.Tag.FHD)),
            labels[0],
        )
        assertEquals(
            DetailPlay.VersionLabel.Tags(
                listOf(QualityTags.Tag.UHD_4K, QualityTags.Tag.ATMOS),
            ),
            labels[1],
        )
    }

    @Test fun `a featurette filed with the release is no copy of the film`() {
        val gathering = uhd.copy(
            id = "fan", fileName = "機動戦士ガンダム 閃光のハサウェイ.Mobile.Suit.Gundam.Hathaway.FAN.GATHERING.Heirs.to.GUNDAM.2021.1080p.BDRip.x265.FLAC-CoolFansSub&Shirokoi.mkv",
            kind = MediaKind.EPISODE, contentTag = ContentKind.EXTRA,
        )
        // All three read 正片 by name; only the two films are versions, each told by what it is.
        val labels = DetailPlay.versionLabels(listOf(bdrip, uhd, gathering))
        assertEquals(listOf(listOf(0, 1)), DetailPlay.versionSetsOf(listOf(bdrip, uhd, gathering)))
        assertEquals(DetailPlay.VersionLabel.Tags(listOf(QualityTags.Tag.FHD)), labels[0])
        assertEquals(null, labels[2])
    }

    @Test fun `a part of a film is no copy of it`() {
        val cd1 = bdrip.copy(id = "cd1", fileName = "Hathaway.CD1.BDRip.1080p.mkv")
        val cd2 = bdrip.copy(id = "cd2", fileName = "Hathaway.CD2.BDRip.1080p.mkv")
        assertEquals(emptyList<List<Int>>(), DetailPlay.versionSetsOf(listOf(uhd, cd1, cd2)))
    }
}
