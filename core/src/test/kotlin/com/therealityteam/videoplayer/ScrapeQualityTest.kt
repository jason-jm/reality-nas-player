package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.scrape.AnimeMatch
import com.therealityteam.videoplayer.data.scrape.AnimeParse
import com.therealityteam.videoplayer.data.scrape.AnimeQueries
import com.therealityteam.videoplayer.data.scrape.ContentClassifier
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.FolderReconcile
import com.therealityteam.videoplayer.data.scrape.MetaResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions from the ground-truth evaluation (eval/README.md), one per failure mode the poster
 * wall showed: each case here is a real library file that landed on the wrong card.
 */
class ScrapeQualityTest {

    // ── parser ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aLatinWordSaidTwiceIsTheTitle() {
        assertEquals("Tick Tick Boom", FilenameParser.parse("Tick.Tick.Boom.2021.1080p.WEB.H264-CUPCAKES.mkv").title)
        val p = FilenameParser.parse("tick.tick..BOOM.2021.1080p.WEB.H264-CUPCAKES[rarbg].mkv")
        assertEquals("tick tick boom", p.title.lowercase())
        assertEquals(2021, p.year)
        assertEquals("Boom Boom Room", FilenameParser.parse("Boom.Boom.Room.2021.1080p.mkv").title)
        // …while a CJK programme name repeated before its segment title still collapses
        assertEquals("青天を衝け", FilenameParser.parse("青天を衝け 青天を衝け紀行.mp4").title)
    }

    @Test
    fun aCodecTagIsNotASeason() {
        val p = FilenameParser.parse("[POPGO][Stand_Alone_Complex][25][1080P][BluRay][x264_FLACx2_AC3x2][chs_jpn][88BC1234].mkv")
        assertNull(p.season)
        assertEquals(25, p.episode)
        val q = FilenameParser.parse("Show.Name.1x05.mkv")
        assertEquals(1, q.season)
        assertEquals(5, q.episode)
    }

    @Test
    fun nestedFansubBracketsReadAsOneTitle() {
        assertEquals(
            "Stand Alone Complex Solid State Society",
            FilenameParser.fansubTitle("[POPGO][Stand_Alone_Complex][Solid_State_Society][Main_Movie][AVC_FLAC+AAC][FEEE1234].mkv"),
        )
        assertEquals("TALES OF PHANTASIA", FilenameParser.fansubTitle("[POPGO][FREEWIND][TALES_OF_PHANTASIA][DVDRIP][01].mkv"))
        assertEquals("Stand Alone Complex", FilenameParser.fansubTitle("[POPGO][Stand_Alone_Complex][BOX1_SP][1080P][BluRay][x264_FLAC][839472AC].mkv"))
    }

    @Test
    fun aLatinRunOfFillerDoesNotReplaceTheCjkTitle() {
        assertEquals("魔法少女奈叶 The Movie 2nd A's", FilenameParser.parse("魔法少女奈叶 The Movie 2nd A's 1080P BDRIP").title)
        assertFalse(FilenameParser.parse("读或死R.O.D [10bit][Lv.1]").title == "O D")
        // …while a real embedded title still wins over the localized name
        assertEquals("Line Walker", FilenameParser.parse("使徒行者.电影版.Line.Walker.2016.mkv").title)
    }

    @Test
    fun aSceneGroupClosingTheNameIsNotPartOfTheTitle() {
        val arrietty = "The_Borrowers_Arrietty_(2010)_[1080p,BluRay,x264,DTS]_-_THORA-UTW.mkv"
        assertEquals("THORA-UTW", FilenameParser.trailingGroup(arrietty.substringBeforeLast('.')))
        assertEquals("The Borrowers Arrietty", FilenameParser.fansubTitle(arrietty))
        val anime = com.therealityteam.videoplayer.data.scrape.AnimeParse.parse(arrietty)
        assertEquals("The Borrowers Arrietty", anime.title)
        assertEquals("THORA-UTW", anime.releaseGroup)
        // Only after the tags: anywhere else a dash is followed by the name's own words.
        listOf("B&T - Nirvana", "Kizumonogatari - Tekketsu-hen", "[SubsPlease] Show - 01", "[Group] Show [1080p] - 01")
            .forEach { assertEquals(it, null, FilenameParser.trailingGroup(it)) }
    }

    @Test
    fun aLatinRunKeepsNoSeparatorItWasCutAt() {
        assertEquals("Luna Sea", FilenameParser.parse("Luna Sea - 聖夜の奇跡.復活ライブGOD.BLESS.YOU.avi").title)
        assertEquals("Sound Horizon", FilenameParser.parse("Sound Horizon - Roman～僕達が繋がる物語～").title)
        assertEquals("Concert Tour", FilenameParser.parse("松隆子 - Concert Tour 2001.RMVB").title)
    }

    @Test
    fun aNameAsWrittenKeepsBothScripts() {
        // What a folder nothing identified is called: its own name, release tags cut, both halves kept.
        assertEquals("Sound Horizon - Roman～僕達が繋がる物語～", FilenameParser.writtenTitle("Sound Horizon - Roman～僕達が繋がる物語～"))
        assertEquals("Sound Horizon - 其他", FilenameParser.writtenTitle("Sound Horizon - 其他"))
        assertEquals("借物少女艾莉缇 The Borrowers Arrietty", FilenameParser.writtenTitle("借物少女艾莉缇 The Borrowers Arrietty 1080P BDRIP"))
    }

    @Test
    fun aLatinPhraseInsideACjkNameIsNotTheTitle() {
        // With a CJK name on both sides the Latin run is a subtitle: it searched as "in china" and
        // found the film Made in China.
        assertEquals("世界市场 财富攻防战 第二回：made in china 中国的战略",
            FilenameParser.parse("[道兰][NHK纪录片]世界市场.财富攻防战.第二回：made.in.china.中国的战略.avi").title)
        // A release group's signature after it is not a name…
        // (Eng is the release's subtitle language, a scene tag since the release words of every language were added.)
        assertEquals("Downton Abbey Behind The Drama Chi",
            FilenameParser.parse("唐顿庄园幕后故事.Downton.Abbey.Behind.The.Drama.Chi_Eng.HDTVrip.720X400-YYeTs人人影视.rmvb").title)
        // …nor is a short group tag before a romaji title with its Chinese name after.
        assertEquals("Hoshi no Koe",
            FilenameParser.parse("星海城 - [Kamigami] Hoshi no Koe 星之声 [TV x264 1920x1080 AAC Sub(GB,BIG5,JP,EN)].mkv").title)
    }

    @Test
    fun aBoxContentsListIsNotPartOfTheName() {
        assertEquals("交响诗篇", FilenameParser.parse("交响诗篇 - TV + SP").title)
        assertEquals("翼神传说", FilenameParser.parse("翼神传说 BD-BOX - TV + Movie + OVA").title)
        assertEquals("天空之艾斯嘉科尼", FilenameParser.parse("天空之艾斯嘉科尼 TV + Movie [1080p] [DUAL-AUDIO] [x265]").title)
        assertEquals("言叶之庭", FilenameParser.parse("言叶之庭 [POPGO][FREEWIND][The Garden of Words][Full HD BDRIP] Movie+All SP+OST+BK").title)
        assertEquals("GUNBUSTER&DIEBUSTER&合体劇場版", FilenameParser.parse("GUNBUSTER&DIEBUSTER&合体劇場版 全12話+劇場版x2+特典+Scans(1080p HEVC 10bit FLACx3+AAC softSub(chi+eng+fra) chap)").title)
        // a single form word is the one that says which entry is meant
        assertEquals("Kidou Keisatsu Patlabor - Movie", FilenameParser.parse("Kidou.Keisatsu.Patlabor.-.Movie.[Salender-Raws][0287417C].mkv").title)
    }

    @Test
    fun nestedParenthesesAreStrippedWhole() {
        val p = FilenameParser.parse("トップをねらえ2! DIEBUSTER 第06話「あなたの人生の物語」(1080p HEVC 10bit FLACx3+AAC softSub(chi+eng) chap).mkv")
        assertEquals("トップをねらえ2! DIEBUSTER", p.title)
        assertEquals(6, p.episode)
    }

    // ── classifier ───────────────────────────────────────────────────────────────────────────

    @Test
    fun strongMarkersAreStrippedFromTheQuery() {
        assertEquals("Ginga Eiyuu Densetsu Die Neue These - Kaikou", ContentClassifier.stripMarkers("Ginga Eiyuu Densetsu Die Neue These - Kaikou NCOP"))
        assertEquals("Show Name", ContentClassifier.stripMarkers("Show Name - TV Spots"))
        assertEquals("Gundam G no Reconguista", ContentClassifier.stripMarkers("Gundam G no Reconguista Trailer"))
        assertEquals("Collection", ContentClassifier.stripMarkers("Eyecatch Collection"))
        assertEquals("Vol", ContentClassifier.stripMarkers("Menu Vol"))
        assertEquals("EP24 15sec", ContentClassifier.stripMarkers("EP24 15sec Next EP Yokoku"))
        assertEquals("Show Name", ContentClassifier.stripMarkers("Show Name PV2"))
        assertEquals("Show Name", ContentClassifier.stripMarkers("Show Name Preview07"))
        assertEquals("NCOP Ver 1", ContentClassifier.stripMarkers("NCOP Ver 1"))
        // a marker that opens a real title stays
        assertEquals("Trailer Park Boys", ContentClassifier.stripMarkers("Trailer Park Boys"))
        assertEquals("The Menu", ContentClassifier.stripMarkers("The Menu"))
        assertEquals("Kidou Keisatsu Patlabor - Movie", ContentClassifier.stripMarkers("Kidou Keisatsu Patlabor - Movie"))
    }

    @Test
    fun strongVersusWeakExtras() {
        assertTrue(ContentClassifier.isStrongExtra("[POPGO&SumiSora&TxxZ] Ginga Eiyuu Densetsu Die Neue These - Kaikou TV Spots (BDRip 1080P).mkv"))
        assertTrue(ContentClassifier.isStrongExtra("[QTS] U.C.0083 Mobile Suit GUNDAM 0083 THE AFTERGLOW OF ZEON Eizou Tokuten - Gekijou Tokokuhen.mkv"))
        assertTrue(ContentClassifier.isStrongExtra("[Shftp&CASO][Mobile_Suit_Gundam_00_2nd_Season][Movie_Promo][BDRIP][1920x1080].mkv"))
        assertTrue(ContentClassifier.isStrongExtra("Eyecatch Collection.mkv"))
        assertFalse(ContentClassifier.isStrongExtra("R.O.D －READ OR DIE－ OVA 第03話 (BD 1440x1080).mp4"))
        assertFalse(ContentClassifier.isStrongExtra("[Patlabor_OVA][NEW_OVA][BDRip][1080P][X264_FLAC][05].mkv"))
        assertFalse(ContentClassifier.isStrongExtra("The.Menu.2022.1080p.mkv"))
        assertFalse(ContentClassifier.isStrongExtra("Ed.Edd.n.Eddy.S01E01.mkv"))
    }

    // ── queries ──────────────────────────────────────────────────────────────────────────────

    private fun queries(path: String, siblings: List<String> = emptyList()): List<String> {
        val fileName = path.substringAfterLast('/')
        return AnimeQueries.build(path, FilenameParser.parse(fileName), AnimeParse.parse(fileName), siblings)
    }

    @Test
    fun anExtraSearchesForTheShowItBelongsTo() {
        val q = queries(
            "smb://nas/video/Anime/银河英雄传说 全新命题 (01-12) BDRip 1080P X265 Main10p/" +
                "[POPGO&SumiSora&TxxZ] Ginga Eiyuu Densetsu Die Neue These - Kaikou NCOP (BDRip 1080P X265 Main10p Flac)[BF003484].mkv",
        )
        assertTrue(q.toString(), q.contains("Ginga Eiyuu Densetsu Die Neue These - Kaikou"))
        assertTrue(q.toString(), q.none { it.contains("NCOP") })
        val spots = queries(
            "smb://nas/video/Anime/银河英雄传说 全新命题 (01-12) BDRip 1080P X265 Main10p/" +
                "[POPGO&SumiSora&TxxZ] Ginga Eiyuu Densetsu Die Neue These - Kaikou TV Spots (BDRip 1080P X265 Main10p Flac)[BF003484].mkv",
        )
        assertTrue(spots.toString(), spots.none { it.contains("Spots") })
    }

    @Test
    fun aNameDecimalIsNotASeasonQuery() {
        val q = queries("smb://nas/video/Anime/Ghost in the Shell 2.0 1080P BDRIP/[philosophy-raws][Ghost in the Shell 2.0][Movie][BDRIP][Hi10P DTS-HD MA FLAC][1920X1080].mkv")
        assertTrue(q.toString(), q.none { it.contains("Season 2") })
        val exact = q.indexOf("Ghost in the Shell 2.0")
        assertTrue(q.toString(), exact >= 0)
        val base = q.indexOf("Ghost in the Shell")
        assertTrue(q.toString(), base < 0 || exact < base)
    }

    @Test
    fun aShorthandTitleBorrowsItsSiblingsFullName() {
        val path = "smb://nas/video/Anime/学生会的一己之见/[HKG&CASO][Seitokai][01][BIG5][848x480][RV10].rmvb"
        val nanoha = queries("smb://nas/video/Anime/魔法少女奈叶 The Movie 2nd A's 1080P BDRIP/MAIN MOVIE/[FLsnow][Magical_Girl_Lyrical_NANOHA_The_MOVIE_2nd_A's][BDRIP][AVC_FLAC].mkv")
        assertTrue(nanoha.toString(), nanoha.none { it == "The Movie 2nd A's" || it.endsWith("Season 2") })
        val q = queries(path, listOf("Seitokai no Ichizon", "Seitokai"))
        assertTrue(q.toString(), q.indexOf("Seitokai no Ichizon") in 0 until q.indexOf("Seitokai"))
        // …and nothing changes without such a sibling
        assertFalse(queries(path).contains("Seitokai no Ichizon"))
        assertNull(AnimeQueries.siblingExpansion("Fate", listOf("Fateful Encounter")))
        assertEquals("学生会的一己之见", AnimeQueries.siblingExpansion("学生会", listOf("学生会的一己之见", "学生会 2")))
    }

    @Test
    fun aBoxNamedByItsContentsListAsksNothingOfIt() {
        val q = queries(
            "smb://nas/video/Anime/GUNBUSTER&DIEBUSTER&合体劇場版 全12話+劇場版x2+特典+Scans(1080p HEVC 10bit FLACx3+AAC softSub(chi+eng+fra) chap)/" +
                "トップをねらえ2! DIEBUSTER 第06話「あなたの人生の物語」(1080p HEVC 10bit FLACx3+AAC softSub(chi+eng) chap).mkv",
        )
        assertTrue(q.toString(), q.none { it.contains("1080p") || it.contains("softSub") || it.contains("chap") })
        assertTrue(q.toString(), q.contains("トップをねらえ2! DIEBUSTER"))
        assertTrue(AnimeQueries.isSignalless("1080p HEVC 10bit FLACx3+AAC softSub(chi+eng+fra"))
    }

    @Test
    fun aQuotedWorkNameOutranksTheBoxItSitsIn() {
        val q = queries(
            "smb://nas/video/Anime/Ghost in the Shell Stand Alone Complex Special Edition 1080P BDRIP/" +
                "[アニメ.BD].攻殻機動隊.S.A.C.OVA「Individual Eleven」(BD.1920x1080.h264.AAC).mkv",
        )
        val quoted = q.indexOf("Individual Eleven")
        val box = q.indexOfFirst { it.contains("Special Edition") }
        assertTrue(q.toString(), quoted >= 0 && (box < 0 || quoted < box))
        // …a quote that OPENS the name is the group's signature, and a song clip names its song
        val group = queries("smb://nas/video/Anime/最后的大魔王 1080P BDRIP/Bonus/『スカーRaws』Ichiban Ushiro no Daimaou Special Content 01 (BD 1920x1080).mkv")
        assertFalse(group.toString(), group.any { it.contains("スカー") })
        val song = queries("smb://nas/video/Anime/Patlabor 機動警察パトレイバー/bonus_映像特典.U2/[アニメ BD] 機動警察パトレイバー ON TELEVISION 映像特典 ノンクレジットOP／ED集03 OP「そのままの君でいて」Ver.2 (x264 1440x1080).mkv")
        assertFalse(song.toString(), song.any { it == "そのままの君でいて" })
        // …but a quoted EPISODE title beside an episode number is not asked about
        val ep = queries("smb://nas/video/Anime/读或死R.O.D [10bit][Lv.1]/R.O.D －THE TV－ 第07話 ｢藪の中｣ (BD 1440x1080 H.264@Hi10P ALAC+AACx2).mp4")
        assertFalse(ep.toString(), ep.any { it.contains("藪の中") })
    }

    @Test
    fun aDirectorsBoxIsAShelf() {
        assertTrue(FilenameParser.isShelfFolder("新海诚（Makoto Shinkai）全集 1080P BDRIP"))
        assertTrue(FilenameParser.isShelfFolder("宮崎駿全集 Miyazaki Hayao All Movies [BD x264 1080p DTS-HD Audio×N Sub×N]"))
        assertTrue(FilenameParser.isShelfFolder("今敏 Kon Satoshi All Movies BDRip 1080p"))
        assertTrue(FilenameParser.isShelfFolder("The.Dark.Knight.Collection.2005-2012.BluRay.1080p.DTS"))
        assertFalse(FilenameParser.isShelfFolder("The Collection"))
        assertFalse(FilenameParser.isShelfFolder("Cowboy Bebop 1080P BDRIP"))
        // …so its parenthetical is no query, and the film inside asks by its own name
        val q = queries("smb://nas/video/Anime/新海诚（Makoto Shinkai）全集 1080P BDRIP/星海城 - [Kamigami] Hoshi no Koe 星之声 [TV x264 1920x1080 AAC Sub(GB,BIG5,JP,EN)].mkv")
        assertTrue(q.toString(), q.none { it.contains("Shinkai", ignoreCase = true) || it.contains("新海诚") })
        assertTrue(q.toString(), q.any { it.contains("Hoshi no Koe") })
    }

    @Test
    fun aFilmBracketJoinsItsFranchiseBracket() {
        assertEquals(
            "Turn A Gundam Movie I Earth Light",
            FilenameParser.fansubTitle("[A.I.R.nesSub][Turn_A_Gundam][Movie_I_Earth_Light][BDRIP][1080p][AVC-Hi10p_FLACx2][0C1D3B6B].mkv"),
        )
        val q = queries("smb://nas/video/Anime/Mobile Suit Gundam Turn_A_Gundam Movie 1080P BDRIP/[A.I.R.nesSub][Turn_A_Gundam][Movie_II_Moon_Butterfly][BDRIP][1080p][AVC-Hi10p_FLACx2][0C1D3B6B].mkv")
        val own = q.indexOf("Turn A Gundam Movie II Moon Butterfly")
        val box = q.indexOf("Mobile Suit Gundam Turn A Gundam Movie")
        assertTrue(q.toString(), own >= 0 && (box < 0 || own < box))
    }

    // ── match guard ──────────────────────────────────────────────────────────────────────────

    private fun show(id: Int, title: String, romaji: String? = null, english: String? = null, year: Int? = null, pop: Double = 0.0, format: String = "TV") =
        MetaResult(id, MediaKind.SHOW, title = title, romajiTitle = romaji, englishTitle = english, year = year, popularity = pop, format = format)

    private fun film(id: Int, title: String, english: String? = null, year: Int? = null, pop: Double = 0.0) =
        MetaResult(id, MediaKind.MOVIE, title = title, englishTitle = english, year = year, popularity = pop, format = "MOVIE")

    @Test
    fun aBareOrdinalNamesTheSeason() {
        // The results page omits season 1, so "the second entry by air date" would be the recap film.
        val results = listOf(
            show(2448, "攻壳机动队 S.A.C. 2nd GIG", "Koukaku Kidoutai: S.A.C. 2nd GIG", "Ghost in the Shell: Stand Alone Complex 2nd GIG", 2004, 50000.0),
            show(3485, "攻壳机动队 笑面男", "Koukaku Kidoutai: Stand Alone Complex - The Laughing Man", "Ghost in the Shell: Stand Alone Complex - The Laughing Man", 2005, 20000.0, "SPECIAL"),
        )
        assertEquals(2448, AnimeMatch.pick("Ghost in the Shell Stand Alone Complex 2nd GIG Season 2", results, season = 2)?.providerId)
    }

    @Test
    fun anAbbreviationCountsAsItsWord() {
        val dr = film(32, "新世纪福音战士剧场版 DEATH & REBIRTH", "Neon Genesis Evangelion: Death & Rebirth", 1997, 30000.0)
        assertTrue(AnimeMatch.corresponds("EVA Death and Rebirth", dr))
        assertFalse(AnimeMatch.corresponds("Murder Princess", show(1, "Murder Mystery", "Murder Mystery")))
    }

    @Test
    fun theEntryThatExplainsMoreOfTheQueryWins() {
        val eoe = film(32, "新世纪福音战士剧场版 THE END OF EVANGELION", "Neon Genesis Evangelion: The End of Evangelion", 1997, 60000.0)
            .copy(synonyms = listOf("End of Evangelion", "EoE"))
        val tv = show(30, "新世纪福音战士", "Shin Seiki Evangelion", "Neon Genesis Evangelion", 1995, 480000.0)
        assertEquals(32, AnimeMatch.pick("zx end of evangelion renewal v3", listOf(tv, eoe))?.providerId)
        assertEquals(30, AnimeMatch.pick("Evangelion", listOf(tv, eoe))?.providerId)
        // a film asked for by its English subtitle beats the far more popular series
        val mfTv = show(3572, "超时空要塞Frontier", "Macross Frontier", "Macross Frontier", 2008, 90000.0)
        val mf2 = film(6045, "超时空要塞F 剧场版2 恋离飞翼", "Macross Frontier: The Wings of Goodbye", 2011, 15000.0)
        assertEquals(6045, AnimeMatch.pick("Macross Frontier The Movie-The Wings of Goodbye", listOf(mfTv, mf2))?.providerId)
    }

    @Test
    fun anUnaskedSequelLosesToTheFirstFilm() {
        val results = listOf(
            film(1095, "机动警察 剧场版", "Kidou Keisatsu Patlabor the Movie", 1989, 20000.0),
            film(1096, "机动警察剧场版2 和平保卫战", "Kidou Keisatsu Patlabor 2 the Movie", 1993, 30000.0),
        )
        assertEquals(1095, AnimeMatch.pick("Kidou Keisatsu Patlabor Movie", results, wantMovie = true)?.providerId)
        assertEquals(1096, AnimeMatch.pick("Kidou Keisatsu Patlabor 2 Movie", results, wantMovie = true)?.providerId)
    }

    @Test
    fun aFormWordPrefersTheEntryOfThatForm() {
        val results = listOf(
            show(209, "R.O.D", "R.O.D the TV", "R.O.D the TV", 2003, 40000.0, "TV"),
            show(208, "R.O.D", "R.O.D: Read or Die", "Read or Die", 2001, 30000.0, "OVA"),
        )
        assertEquals(208, AnimeMatch.pick("R O D OVA", results)?.providerId)
        assertEquals(209, AnimeMatch.pick("R O D", results)?.providerId)
    }

    // ── folder reconciliation ────────────────────────────────────────────────────────────────


    @Test
    fun aLoneWorkThatAnnouncesItselfKeepsItsOwnCard() {
        // `guilty crown - lost christmas ova` sits alone in an OVA/ subfolder of the TV release and
        // matched Guilty Crown: Lost Christmas because that is what it says it is. Counting files
        // read it as a stray featurette and folded it onto the series. Both cards are named in
        // Chinese here, as the real library has them — the Latin evidence is in the aliases.
        val f = "smb://nas/video/Anime/罪恶王冠 Guilty Crown [BD 1920x1080 x264 DualFLAC(24bit)]/"
        val tv = (1..22).map {
            item(
                "$f[Kamigami] Guilty Crown - ${"%02d".format(it)} (BD 1920x1080).mkv", "anilist:10793",
                ep = it, title = "罪恶王冠", aliases = listOf("Guilty Crown"),
            )
        }
        val ova = item(
            "${f}OVA/[Kamigami] guilty crown - lost christmas ova (dvd 720x480 x264 flac).mkv",
            "anilist:13411", tag = ContentKind.EXTRA,
            title = "罪恶王冠 失落的圣诞节", aliases = listOf("Guilty Crown: Lost Christmas"),
        )
        assertTrue(FolderReconcile.plan(tv + ova).map { it.fileName }.toString(), FolderReconcile.plan(tv + ova).isEmpty())
    }

    @Test
    fun aLoneFileThatNamesSomethingElseEntirelyStillFolds() {
        // Both halves of "announces itself" earn their keep. SHIROBAKO's credit variants of its
        // in-story `Exodus!` DO name what they matched — but `Exodus!` is not a SHIROBAKO, and a
        // sibling filed inside a release folder always says whose sibling it is.
        val f = "smb://nas/video/Anime/白箱 SHIROBAKO [Ma10p_1080p]/"
        val tv = (1..24).map {
            item("$f[VCB-Studio] SHIROBAKO [${"%02d".format(it)}][Ma10p_1080p].mkv", "anilist:20812", ep = it, title = "SHIROBAKO")
        }
        val exodus = (1..2).map {
            item("$f[VCB-Studio] Exodus! [01($it Staff Credit Ver.)][Ma10p_1080p].mkv", "anilist:20938", ep = 1, title = "Exodus!")
        }
        assertEquals(2, FolderReconcile.plan(tv + exodus).size)

        // …and a lone file whose name carries nothing of what it matched is the fuzzy hit the count
        // always assumed: a featurette that happened to land on an unrelated entry.
        val stray = item("${f}Special Report (BD).mkv", "anilist:9999", title = "Special A")
        assertEquals(1, FolderReconcile.plan(tv + stray).size)
    }

    private fun item(
        path: String,
        key: String?,
        tag: ContentKind? = ContentKind.MAIN,
        kind: MediaKind = MediaKind.EPISODE,
        ep: Int? = null,
        status: ScrapeStatus = ScrapeStatus.MATCHED,
        // What the database called it. Most cases here do not depend on it; the ones that ask
        // whether a lone file announces itself as a work of its own do.
        title: String = "t",
        aliases: List<String> = emptyList(),
    ) = MediaItem(
        id = path, sourceId = "s", path = path, fileName = path.substringAfterLast('/'), title = title,
        aliases = aliases,
        kind = kind, episode = ep, collectionKey = key, contentTag = tag, scrapeStatus = status,
    )

    @Test
    fun aMusicClipIsABonusReelLikeAMusicVideo() {
        // `Anime Music Clip -Director\'s Concept- 「Fields of hope」` sits on a SEED Destiny disc.
        // Unrecognised, its quoted SONG title became the first query and carried two files off to
        // Kino no Tabi; "music video" was known, "music clip" was not.
        listOf(
            "Anime Music Clip -Director's Concept- 「Fields of hope」 [AVC_10bit_1080P FLAC].mkv",
            "Music Clips 「tears」 [BDRip].mkv",
            "Creditless ED2 「Life Goes On ~ReMix2013」 [AVC_10bit_1080P FLAC].mkv",
        ).forEach { assertTrue(it, ContentClassifier.isStrongExtra(it)) }
        // …while a film whose own name carries the word is still a work.
        assertFalse(ContentClassifier.isStrongExtra("Interstella 5555 The 5tory of the 5ecret 5tar 5ystem.mkv"))
    }

    @Test
    fun strongExtrasNeverKeepAnIdentityOfTheirOwn() {
        val f = "smb://nas/video/Anime/银河英雄传说 全新命题 (01-12) BDRip 1080P X265 Main10p/"
        val items = (1..12).map { item("$f[POPGO] Ginga Eiyuu Densetsu Die Neue These - Kaikou ${"%02d".format(it)} (BDRip).mkv", "anilist:20628", ep = it) } +
            listOf("TV Spots", "NCOP", "NCED", "Menu").map { item("$f[POPGO] Ginga Eiyuu Densetsu Die Neue These - Kaikou $it (BDRip).mkv", "id:74018", tag = ContentKind.MAIN) }
        val plan = FolderReconcile.plan(items)
        assertEquals(4, plan.size)
        assertTrue(plan.all { it.collectionKey == "anilist:20628" && it.contentTag == ContentKind.EXTRA })
    }

    @Test
    fun aNumberedOvaBesideTheSeriesStaysAWork() {
        val f = "smb://nas/video/Anime/读或死R.O.D [10bit][Lv.1]/"
        val items = (1..26).map { item("${f}R.O.D －THE TV－ 第${"%02d".format(it)}話 (BD).mp4", "anilist:209", ep = it) } +
            (1..3).map { item("${f}R.O.D －READ OR DIE－ OVA 第0${it}話 (BD).mp4", "anilist:208", tag = ContentKind.EXTRA, ep = it) }
        assertTrue(FolderReconcile.plan(items).isEmpty())
    }

    @Test
    fun aFilmBundledWithTheSeriesKeepsItsOwnCard() {
        // 劇場版 魔法科高校の劣等生 ships as one file inside the 26-episode TV folder and matches its
        // own AniList entry. "Several files" used to be checked before "is it a film", so the film
        // was folded onto the show — and then merged with its own standalone BDRip encode's card.
        val f = "smb://nas/video/Anime/魔法科高校の劣等生 (BDRip 1920x1080 x264 FLAC)/"
        val items = (1..26).map { item("$f[ReinForce] Mahouka Koukou no Rettousei - ${"%02d".format(it)} (BDRip 1920x1080 x264 FLAC).mkv", "anilist:20458", ep = it) } +
            listOf(item("$f[ReinForce] Gekijouban Mahouka Koukou no Rettousei - Hoshi wo Yobu Shoujo (BDRip 1920x1080 x264 FLAC).mkv", "anilist:21684", kind = MediaKind.MOVIE))
        assertTrue(FolderReconcile.plan(items).map { it.fileName }.toString(), FolderReconcile.plan(items).isEmpty())
        // …but a lone film-kind file the classifier calls an extra (a "making of") still folds.
        val extra = items.dropLast(1) + listOf(
            item("${f}Making of the Movie (BDRip).mkv", "anilist:21684", tag = ContentKind.EXTRA, kind = MediaKind.MOVIE),
        )
        assertEquals(1, FolderReconcile.plan(extra).size)
    }

    @Test
    fun twoUnnumberedClipsAreTheShowsExtras() {
        val show = "smb://nas/video/Anime/白箱 SHIROBAKO [Ma10p_1080p]/"
        val items = (1..24).map { item("$show[VCB-Studio] SHIROBAKO [${"%02d".format(it)}][Ma10p_1080p][x265_aac].mkv", "anilist:20812", ep = it) } +
            listOf("Musani", "Original").map {
                item("$show[VCB-Studio] Exodus! [Ma10p_1080p]/[VCB-Studio] Exodus! [01($it Staff Credit Ver.)][Ma10p_1080p][x265_flac_aac].mkv", "anilist:20938", ep = 1)
            } +
            listOf(item("$show[VCB-Studio] SHIROBAKO Movie [Ma10p_1080p]/[VCB-Studio] SHIROBAKO Movie [Ma10p_1080p][x265_flac].mkv", "anilist:112151", kind = MediaKind.MOVIE))
        val plan = FolderReconcile.plan(items)
        assertEquals(plan.map { it.fileName }.toString(), 2, plan.size)
        assertTrue(plan.all { it.collectionKey == "anilist:20812" && it.contentTag == ContentKind.EXTRA })
    }
}
