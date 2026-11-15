package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AnimeParse
import com.therealityteam.videoplayer.data.scrape.AnimeQueries
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the search-query construction that decides which title an anime file is identified as.
 * The regression these guard: a folder that parses down to release words ("OVA TV") was searched
 * before the filename's own title, and its fuzzy hit stuck — filing all 221 files of the 逮捕令
 * franchise under 玉响 (see eval/README.md and DEVLOG.md).
 */
class AnimeQueryTest {

    private fun queries(path: String): List<String> {
        val fileName = path.substringAfterLast('/')
        return AnimeQueries.build(path, FilenameParser.parse(fileName), AnimeParse.parse(fileName))
    }

    @Test
    fun signallessQueriesAreRejected() {
        assertTrue(AnimeQueries.isSignalless("OVA TV"))
        assertTrue(AnimeQueries.isSignalless("1st Season"))
        assertTrue(AnimeQueries.isSignalless("BD 1080P"))
        assertTrue(AnimeQueries.isSignalless("The Movie"))
        assertTrue(AnimeQueries.isSignalless("特典 映像特典"))
    }

    @Test
    fun realTitlesAreNotSignalless() {
        assertFalse(AnimeQueries.isSignalless("You're Under Arrest"))
        assertFalse(AnimeQueries.isSignalless("逮捕しちゃうぞ"))
        assertFalse(AnimeQueries.isSignalless("Patlabor 機動警察パトレイバー"))
        assertFalse(AnimeQueries.isSignalless("Cowboy Bebop"))
        // a title that merely CONTAINS a release word still has signal
        assertFalse(AnimeQueries.isSignalless("Sword Art Online Movie"))
        // acronym titles are all-short-tokens but are NOT noise
        assertFalse(AnimeQueries.isSignalless("R O D"))
        assertFalse(AnimeQueries.isSignalless("D C II"))
    }

    @Test
    fun junkFolderNeverOutranksTheFilenameTitle() {
        val q = queries(
            "smb://nas/video/Anime/逮捕令_逮捕しちゃうぞ_OVA.TV.MOVIE/" +
                "[1996-1997] Taiho_逮捕令(逮捕你喲,逮捕しちゃうぞ)_TV/You're.Under.Arrest.DVDRip.01.avi",
        )
        assertFalse("signal-less folder query must be dropped", q.contains("OVA TV"))
        assertTrue("the file's own title must be searched", q.any { it.contains("Under Arrest") })
    }

    @Test
    fun cleanFolderStillWinsOverTheFilename() {
        // The folder-first order exists so a season folder matches its own season entry — keep it.
        val q = queries(
            "smb://nas/video/Anime/狼与香辛料II/[SOSG&KTXP][Spice_and_Wolf_II][01][GB][RV10_AAC].rmvb",
        )
        assertTrue(q.isNotEmpty())
        assertTrue("folder title leads", q.first().contains("狼与香辛料"))
    }

    @Test
    fun releaseGroupIsNeverMistakenForTheTitle() {
        // `[Group] Title [tags]` with a CJK title: the title has no spaces, so it used to lose the
        // "more words, then longer" comparison to the group name — and the GROUP became the query.
        val q = queries("smb://nas/video/Anime/追逐繁星的孩子/[Kamigami] 追逐繁星的孩子 [BD x264 1920x1080 DTS-HD].mkv")
        assertFalse("the fansub group must never be searched", q.contains("Kamigami"))
        assertTrue("the CJK title leads", q.first().contains("追逐繁星的孩子"))
        assertFalse("no signal-less leftovers", q.any { AnimeQueries.isSignalless(it) })
    }

    @Test
    fun allBracketNamesStillResolveTheirTitle() {
        val q = queries("smb://nas/video/Anime/Tales Of Phantasy/[POPGO][FREEWIND][TALES_OF_PHANTASIA][DVDRIP][01].avi")
        // The real title must be searched BEFORE any group name Anitomy mistook for a title.
        val title = q.indexOfFirst { it.contains("PHANTAS", ignoreCase = true) }
        val group = q.indexOfFirst { it.equals("FREEWIND", ignoreCase = true) || it.equals("POPGO", ignoreCase = true) }
        assertTrue("title must be present", title >= 0)
        assertTrue("group must not outrank the title", group < 0 || group > title)
    }

    @Test
    fun discStructureFoldersAreNeverSearched() {
        // A BDMV tree bottoms out at …/BDMV/STREAM/00002.m2ts, and "STREAM" matched 洛克人EXE STREAM.
        val q = queries(
            "smb://nas/video/Anime/Sword Art Online 1080P BDRIP/[philosophy-raws][Sword Art Online]/" +
                "CDs/LiSA LANDSPACE/BD_ROM/BDMV/STREAM/00002.m2ts",
        )
        assertFalse(q.any { it.equals("STREAM", ignoreCase = true) })
        assertFalse(q.any { it.equals("BDMV", ignoreCase = true) || it.equals("BD_ROM", ignoreCase = true) })
        assertFalse(q.contains("00002"))
    }

    @Test
    fun abbreviationPlusFullTitleIsReducedFirst() {
        // "EVA Evangelion" is a user-invented concatenation: AniList returns only Petit Eva for it
        // and never Neon Genesis Evangelion, so the reduced form has to be tried first.
        val q = queries("smb://nas/video/Anime/EVA Evangelion/[Sephirotic] Evangelion - 01 [BD 1080p].mkv")
        val reduced = q.indexOf("Evangelion")
        val original = q.indexOf("EVA Evangelion")
        assertTrue("reduced query present", reduced >= 0)
        assertTrue("reduced query leads", original < 0 || reduced < original)
    }

    @Test
    fun unrelatedTokensAreNotDropped() {
        val q = queries("smb://nas/video/Anime/Code Geass 反叛的鲁路修 1080P BDRIP/[Coalgirls]_Code_Geass_01.mkv")
        assertTrue(q.any { it.contains("Code Geass") })
    }

    @Test
    fun bareEpisodeNumbersAreNeverSearched() {
        val q = queries("smb://nas/video/Anime/Remember11/Remember11中文影像版/悟篇/01.avi")
        assertFalse(q.contains("01"))
        assertFalse(q.any { AnimeQueries.isSignalless(it) })
    }

    @Test
    fun aReleaseFolderNamesTheWorkInsideItsParentheses() {
        // A franchise box keeps the FRANCHISE outside the parentheses and the WORK inside them,
        // so every subfolder searched "Patlabor 機動警察" and nine works became one card.
        val movie = queries(
            "smb://n/video/Anime/Patlabor 機動警察パトレイバー/" +
                "[1989] Patlabor_機動警察(機動警察パトレイバー the Movie)_MOVIE.U2 (raw,東京毀滅戰)/x.mkv",
        )
        assertEquals("機動警察パトレイバー the Movie", movie.first())
        val wxiii = queries(
            "smb://n/video/Anime/Patlabor 機動警察パトレイバー/" +
                "[2002] Patlabor_機動警察(WXIII 機動警察パトレイバー)_MOVIE.U2 (raw,13號廢棄物)/x.mkv",
        )
        assertEquals("WXIII 機動警察パトレイバー", wxiii.first())
    }

    @Test
    fun aParentheticalIsReadFromTheWorkFolderAboveAQualityDirectory() {
        // …(逮捕你喲,逮捕しちゃうぞ)_OVA/DVDRip.AVC/file — the work folder is a level up, and the
        // parenthetical holds BOTH names, so it is split rather than searched as one string.
        val q = queries(
            "smb://n/video/Anime/逮捕令_逮捕しちゃうぞ_OVA.TV.MOVIE/" +
                "[1994-1995] Taiho_逮捕令(逮捕你喲,逮捕しちゃうぞ)_OVA/DVDRip.AVC/[KTXP][Taiho Shichauzo][01].mkv",
        )
        // The folder marks the form as well, and where the parenthetical says no more than the box
        // does, that form is the only thing telling this folder from its siblings — so it leads.
        // (For THIS box the database has no answer for it — measured: AniList "逮捕しちゃうぞ OVA" -> []
        // — so the bare name follows immediately behind and the OVA stays on the TV entry.)
        assertEquals("逮捕しちゃうぞ OVA", q.first())
        assertEquals("逮捕しちゃうぞ", q[1])
        assertTrue("both names are candidates: $q", q.contains("逮捕你喲"))
        // …and the film in the same box resolves to the film, not the OVA.
        val movie = queries(
            "smb://n/video/Anime/逮捕令_逮捕しちゃうぞ_OVA.TV.MOVIE/" +
                "[1999] Taiho_逮捕令(逮捕令劇場版,逮捕しちゃうぞ THE MOVIE)_MOVIE/x.mkv",
        )
        assertEquals("逮捕しちゃうぞ THE MOVIE", movie.first())
    }

    @Test
    fun aJunkParentheticalIsNotSearched() {
        // "(raw,東京毀滅戰)" is a release note; the signal-less guard drops "raw" and the remaining
        // Chinese subtitle is allowed through as a LATER candidate, never ahead of the real title.
        val q = queries(
            "smb://n/video/Anime/Patlabor 機動警察パトレイバー/" +
                "[1989] Patlabor_機動警察(機動警察パトレイバー the Movie)_MOVIE.U2 (raw,東京毀滅戰)/x.mkv",
        )
        assertFalse("a bare 'raw' is never a query: $q", q.contains("raw"))
        assertTrue(q.indexOf("機動警察パトレイバー the Movie") < q.indexOf("東京毀滅戰"))
    }

    @Test
    fun aSeasonTheTitleLostIsSearchedInAFormTheDatabaseKnows() {
        // Two failures met here. `摇曳露营 Yuru Camp Season 2 […]` parses to "Yuru Camp" — the season
        // becomes a NUMBER and leaves the title — so the query was the base form and season 2 was
        // filed under season 1 (58 files). And where the marker did survive it survived in the
        // folder's notation: AniList answers "Mushoku Tensei S2" with nothing, "Mushoku Tensei
        // Season 2" with the right entry.
        val yuru = queries(
            "smb://n/video/Anime/摇曳露营 Yuru Camp Season 2 [Ma10p_1080p]/" +
                "[Airota] Yuru Camp Season 2 [01][Ma10p_1080p].mkv",
        )
        assertEquals("Yuru Camp Season 2", yuru.first())
        val mushoku = queries(
            "smb://n/迅雷/下载/[VCB-Studio] Mushoku Tensei S2 ~Isekai Ittara Honki Dasu~ [Hi10p_1080p]/" +
                "[VCB-Studio] Mushoku Tensei S2 [01][Hi10p_1080p][x265_flac].mkv",
        )
        // The file spells its own season, so that goes first and the synthesized form waits behind
        // it: AniList answers the spelled one with nothing, the search moves on, and "Season 2"
        // resolves it. One wasted call buys the case where only the spelled name is right —
        // `[POPGO][Ghost_in_the_Shell][S.A.C._2nd_GIG]` asked "Ghost in the Shell Season 2" first
        // and was answered with SAC_2045's second season, a 2020 series.
        assertEquals("Mushoku Tensei S2", mushoku.first())
        assertTrue(mushoku.toString(), mushoku.contains("Mushoku Tensei Season 2"))
        // …and the synthesized form still outranks the bare franchise name, which knows no seasons.
        assertTrue(
            mushoku.toString(),
            mushoku.indexOf("Mushoku Tensei Season 2") < (mushoku.indexOf("Mushoku Tensei").takeIf { it >= 0 } ?: Int.MAX_VALUE),
        )
        // …and a FIRST season is not given a season query it never asked for.
        val s1 = queries(
            "smb://n/video/Anime/摇曳露营 Yuru Camp [Ma10p_1080p]/[Airota] Yuru Camp [01][Ma10p_1080p].mkv",
        )
        assertEquals(listOf("Yuru Camp"), s1)
    }

    @Test
    fun aFileThatNamesMoreThanItsFolderIsAskedFirst() {
        // `EVA Evangelion/` holds the TV series AND its films, so the folder query answered every
        // file with the TV entry and 70 files — End of Evangelion among them — shared one card.
        // The file says what it is, and AniList has an entry for it (32 / 31).
        val eoe = queries(
            "smb://n/video/Anime/EVA Evangelion/EVA 1-26 DC+EoE [MULTI][BD 1080p][Sephirotic]/" +
                "[Sephirotic] Evangelion - The End of Evangelion [BD 1080p].mkv",
        )
        assertEquals("Evangelion - The End of Evangelion", eoe.first())
        // …but an ordinary episode extends its folder by an episode NUMBER, which names no work;
        // asking that first would waste a lookup on every episode and risk a fuzzy hit.
        val ep = queries(
            "smb://n/video/Anime/EVA Evangelion/EVA 1-26 DC+EoE [MULTI][BD 1080p][Sephirotic]/" +
                "[Sephirotic] Evangelion - 01 [BD 1080p].mkv",
        )
        assertFalse("an episode number is never the first query: $ep", ep.first().contains("01"))
    }

    @Test
    fun aBoxNamedForTheSeasonLosesToTheFileThatNamesTheWork() {
        // `Full Metal Panic! 2/` holds `Full Metal Panic Fumoffu 01.mkv`. Folder-first asked
        // AniList for "Full Metal Panic! 2" and got One Night Stand (115916, a 2023 short); the
        // file's own title gives Fumoffu (72). The folder names a SEASON, the file names the WORK.
        val q = queries(
            "smb://n/video/Anime/Full Metal Panic! 2 1080P BDRIP/Full Metal Panic Fumoffu 1080p  01.mkv",
        )
        assertEquals("Full Metal Panic Fumoffu", q.first())
        // …and a season folder whose files agree with it is untouched.
        val zero = queries("smb://n/video/Anime/零之使魔2/[SumiSora][Zero_no_Tsukaima_2][01].mkv")
        assertEquals("零之使魔2", zero.first())
    }

    @Test
    fun cjkBridgeSkipsSignallessFolders() {
        val path = "smb://nas/video/Anime/逮捕令_逮捕しちゃうぞ_OVA.TV.MOVIE/" +
            "[アニメ DVD] 逮捕しちゃうぞ 1st Season 第01話.wmv"
        val fileName = path.substringAfterLast('/')
        val bridge = AnimeQueries.cjkBridgeQuery(path, FilenameParser.parse(fileName), AnimeParse.parse(fileName))
        assertTrue(bridge == null || !AnimeQueries.isSignalless(bridge))
    }
    @Test
    fun theNameBeforeADashedSubtitleIsAskedLastOfAll() {
        // R.O.D -The Complete- OVA Vol.1 is asked whole, in Anitomy's truncated reading, and by its
        // folder. AniList knows none of them; plain "R.O.D" returns both the TV entry and the OVA.
        val rod = queries(
            "smb://n/video/Anime/R.O.D. OVA/[QTS] R.O.D -The Complete- OVA Vol.1 (BD H264 960x720 VFR FLAC 2.0).mkv",
        )
        assertEquals(rod.toString(), "R O D", rod.last())
        // …and it goes LAST, because for many works the dashes hold part of the real name.
        val eightySix = queries(
            "smb://n/video/Anime/86 -不存在的战区-/[Nekomoe kissaten] 86 -不存在的战区- [01][1080p].mkv",
        )
        assertTrue(eightySix.toString(), eightySix.first().contains("86"))
        assertTrue(eightySix.toString(), eightySix.first() != "86")
    }

    @Test
    fun aFolderWhoseFirstBracketLostItsOpenerIsStillABracketedName() {
        // These folder names are copies truncated at the front. Read as written, the leading run is
        // plain text and the bracket naming THIS work is discarded as a tag: four Solid State
        // Society files asked for the franchise (#467) instead of the film (#1566).
        val sss = queries(
            "smb://n/video/Anime/Ghost_in_the_Shell_Stand_Alone_Complex][Solid_State_Society][BDRIP][1080P]/" +
                "[POPGO][Stand_Alone_Complex][Solid_State_Society][Main_Movie][AVC_AC3].mkv",
        )
        assertEquals("Solid State Society", sss.first())
        // …while a plain title followed by ordinary tag brackets is untouched.
        val rod = queries(
            "smb://n/video/Anime/读或死R.O.D [10bit][Lv.1]/R.O.D －THE TV－ 第07話 (BD 1440x1080).mp4",
        )
        assertTrue(rod.toString(), rod.first().contains("R O D"))
    }

    @Test
    fun aBoxThatListsItsFormsNamesNoneOfThem() {
        // `逮捕令_逮捕しちゃうぞ_OVA.TV.MOVIE/` is an inventory of what the box holds. Its first word
        // was read as this work's form, so 97 TV episodes asked for "逮捕しちゃうぞ OVA", found
        // nothing, fell back to the bare title — and AniList answers that with the OVA (1577),
        // not the 1996 TV series (1372). The folder that names THIS work sits between the two.
        val tv = queries(
            "smb://n/video/Anime/逮捕令_逮捕しちゃうぞ_OVA.TV.MOVIE/" +
                "[1996-1997] Taiho_逮捕令(逮捕你喲,逮捕しちゃうぞ)_TV/DVDRip.AVI.640x480.U2/" +
                "You're.Under.Arrest.DVDRip.01.avi",
        )
        assertEquals("逮捕しちゃうぞ TV", tv.first())
        // …and a folder that says OVA twice while mentioning the TV run beside it still means OVA.
        val ova = queries(
            "smb://n/video/Anime/Patlabor 機動警察パトレイバー/" +
                "[1990-1992] Patlabor_機動警察(機動警察パトレイバー)_OVA.U2 (後期OVA,TV系列第2期)/" +
                "[Patlabor_OVA][NEW_OVA][BDRip][1080P][X264_FLAC][01].mkv",
        )
        assertTrue(ova.toString(), ova.any { it.contains("OVA") })
    }

    @Test fun `the film word comes off only after the name that spells it`() {
        // The name a release spells is asked first; the same name without the film word follows it,
        // ahead of the box's franchise name — both Macross films sat on the TV entry because the
        // folder answered once the film's own spelling had not.
        val qs = queries(
            "/anime/Macross Frontier/[Sakura] Macross Frontier The Movie - The False Songstress [BDRip].mkv"
        )
        val kept = qs.indexOfFirst { it.contains("The Movie", ignoreCase = true) }
        val dropped = qs.indexOfFirst { it.contains("False Songstress") && !it.contains("Movie", ignoreCase = true) }
        val folder = qs.indexOfFirst { it.equals("Macross Frontier", ignoreCase = true) }
        assertTrue("a query keeping the film word is asked: $qs", kept >= 0)
        assertTrue("one without it is asked too: $qs", dropped >= 0)
        assertTrue("after the name that spells it: $qs", dropped > kept)
        assertTrue("but before the folder's franchise name: $qs", folder < 0 || dropped < folder)
    }

    @Test fun `dropping the film word leaves the rest of the title`() {
        // The separators around the word come off with it — no dangling dash left behind.
        assertEquals("Macross Frontier The False Songstress",
            AnimeQueries.withoutFilmWord("Macross Frontier The Movie-The False Songstress"))
        assertEquals("空之境界", AnimeQueries.withoutFilmWord("劇場版 空之境界"))
        assertEquals("Turn A Gundam II", AnimeQueries.withoutFilmWord("Turn A Gundam Movie II"))
    }

    @Test fun `a title with no film word asks nothing extra`() {
        assertNull(AnimeQueries.withoutFilmWord("Ghost in the Shell"))
        // "Moving" is not "Movie": the word boundary has to hold.
        assertNull(AnimeQueries.withoutFilmWord("The Moving Castle"))
    }

    @Test fun `an abbreviation that opens a different work's name comes off`() {
        // `EVA Death and Rebirth` is indexed by nobody; `Death and Rebirth` is the film (anilist 31).
        assertEquals("Death and Rebirth", AnimeQueries.withoutLeadingAbbreviation("EVA Death and Rebirth"))
        assertEquals("My Conquest is the Sea of Stars",
            AnimeQueries.withoutLeadingAbbreviation("LOGH My Conquest is the Sea of Stars"))
    }

    @Test fun `an ordinary first word is not an abbreviation`() {
        // Measured over the whole shelf: allowing lower case here took the head off every title whose
        // first word happens to be short — Code Geass, Full Metal Panic!, Zero no Tsukaima, Yuru Camp.
        assertNull(AnimeQueries.withoutLeadingAbbreviation("Code Geass R2"))
        assertNull(AnimeQueries.withoutLeadingAbbreviation("Full Metal Panic! The Second Raid"))
        assertNull(AnimeQueries.withoutLeadingAbbreviation("Zero no Tsukaima"))
    }

    @Test fun `what is left has to read like a title`() {
        // Each of these came out of the unguarded version, standing in front of the franchise name
        // that would otherwise have answered.
        assertNull(AnimeQueries.withoutLeadingAbbreviation("THE NEXT GENERATION パトレイバー"))  // a word, not an abbreviation
        assertNull(AnimeQueries.withoutLeadingAbbreviation("EVA 1-26 DC+EoE"))                  // does not start with a letter
        assertNull(AnimeQueries.withoutLeadingAbbreviation("XVID VFR AAC"))                     // more capitals, not a title
        assertNull(AnimeQueries.withoutLeadingAbbreviation("GITS SAC"))                         // one word after it
    }

    @Test fun `the abbreviation query follows the name that spells it`() {
        val qs = queries("/anime/EVA Evangelion/EVA Death and Rebirth [BR-Rip 1080p]/[Sephirotic] Death.mkv")
        val kept = qs.indexOfFirst { it.contains("EVA Death and Rebirth") }
        val dropped = qs.indexOfFirst { it == "Death and Rebirth" }
        val franchise = qs.indexOfFirst { it.equals("Evangelion", ignoreCase = true) }
        assertTrue("both are asked: $qs", kept >= 0 && dropped >= 0)
        assertTrue("after the name as written: $qs", dropped > kept)
        assertTrue("but before the franchise name: $qs", franchise < 0 || dropped < franchise)
    }

    @Test fun `a CJK folder numbers its season without a space`() {
        // 零之使魔 and 零之使魔2 shared one card: the digit sits straight on the name, the season
        // pattern wanted a separator in front of it, and so the path named no season at all.
        assertEquals(2, AnimeQueries.seasonOf("smb://n/video/Anime/零之使魔2/ep01.mkv"))
        assertEquals(3, AnimeQueries.seasonOf("smb://n/video/Anime/零之使魔3/ep01.mkv"))
        assertEquals(2, AnimeQueries.seasonOf("smb://n/video/Anime/初音岛2 720P TVRIP/ep01.mkv"))
    }

    @Test fun `a digit on the form word says which FILM this is`() {
        // The second Macross F film asked "Macross Frontier" and got the TV series: its own name is
        // no help (the fansub says The Wings of Goodbye, AniList says The Wings of Farewell), and
        // 版2 offered no separator for the season pattern to find. AnimeMatch.byAirDate was built to
        // order a franchise's films by year once it is told the number — this is what tells it.
        assertEquals(2, AnimeQueries.seasonOf("smb://n/video/Anime/Macross Frontier 剧场版2 1080P BDRIP/a.mkv"))
        assertEquals(3, AnimeQueries.seasonOf("smb://n/video/Anime/名探偵コナン 劇場版3/a.mkv"))
        assertEquals(2, AnimeQueries.seasonOf("smb://n/video/Anime/パトレイバー 映画2/a.mkv"))
    }

    @Test fun `a film CALLED a number keeps its name`() {
        // 攻殻機動隊2 無罪 is the film's name, not its place in a series — there is no form word in
        // front of the digit, which is the whole difference. A two-digit run is a long-running
        // series' own numbering and is left alone rather than read as its 21st entry.
        assertNull(AnimeQueries.seasonOf("smb://n/video/Anime/攻殻機動隊2 無罪/movie.mkv"))
        assertNull(AnimeQueries.seasonOf("smb://n/video/Anime/名探偵コナン 劇場版21/a.mkv"))
        assertNull(AnimeQueries.seasonOf("smb://n/video/Anime/Macross Frontier 剧场版 1080P BDRIP/a.mkv"))
    }

    @Test fun `a number inside a CJK name is not a season`() {
        // 攻殻機動隊2 無罪 is a film called that; 頭文字D2 mixes scripts. Neither is this pattern.
        assertNull(AnimeQueries.seasonOf("smb://n/video/Anime/攻殻機動隊2 無罪/movie.mkv"))
        assertNull(AnimeQueries.seasonOf("smb://n/video/Anime/頭文字D2/ep01.mkv"))
        assertNull(AnimeQueries.seasonOf("smb://n/video/Anime/零之使魔/ep01.mkv"))
    }

    @Test fun `a trailing form word comes off when a subtitle survives`() {
        // `guilty crown - lost christmas ova` finds nothing; without the `ova` it is the work
        // (anilist 13411). Measured both halves: the dash is fine, the trailing form word is not.
        assertEquals("guilty crown - lost christmas",
            AnimeQueries.withoutTrailingFormWord("guilty crown - lost christmas ova"))
        // The decoration before it goes too — `－ OVA` is one separator run, not a closing bracket.
        assertEquals("R O D －READ OR DIE",
            AnimeQueries.withoutTrailingFormWord("R O D －READ OR DIE－ OVA"))
    }

    @Test fun `a name that is only its franchise and its form keeps the form`() {
        // Otherwise the rule hands back the bare franchise name and puts it in FRONT of the
        // franchise fallback — the very thing a re-spelling exists to get ahead of. Measured over
        // the shelf, the unguarded version did this to 71 Patlabor files and 15 Shingeki ones.
        assertNull(AnimeQueries.withoutTrailingFormWord("Patlabor OVA"))
        assertNull(AnimeQueries.withoutTrailingFormWord("Shingeki no Kyojin OVA"))
        assertNull(AnimeQueries.withoutTrailingFormWord("逮捕しちゃうぞ SP"))
    }

    @Test fun `a bit depth is release metadata at any depth`() {
        // A folder tagged `DualFLAC(24bit)` asked AniList about `24bit` first, for 103 files.
        assertTrue(AnimeQueries.isSignalless("24bit"))
        assertTrue(AnimeQueries.isSignalless("16bits"))
        assertTrue(AnimeQueries.isSignalless("24bit OVA"))
        assertTrue(AnimeQueries.isSignalless("10bit"))
        assertFalse(AnimeQueries.isSignalless("Bit Hunter"))
    }

    @Test fun `a number that opens a name is not a season`() {
        // A shelf named in Latin is full of them, and this one has none — which is exactly why it
        // went unnoticed: every one was read as a season, sending the picker after a third or fifth
        // entry of a franchise that has neither.
        assertNull(AnimeQueries.seasonOf("smb://n/video/Anime/3-gatsu no Lion/3-gatsu no Lion - 11.mkv"))
        assertNull(AnimeQueries.seasonOf("smb://n/video/Anime/5 Centimeters per Second/movie.mkv"))
        assertNull(AnimeQueries.seasonOf("smb://n/video/Anime/2 Broke Girls/ep01.mkv"))
    }

    @Test fun `a segment that says which season it is still counts`() {
        assertEquals(2, AnimeQueries.seasonOf("smb://n/video/Anime/Gundam 00/2nd_Season/ep01.mkv"))
        assertEquals(2, AnimeQueries.seasonOf("smb://n/video/Anime/Yuru Camp Season 2/ep01.mkv"))
        assertEquals(3, AnimeQueries.seasonOf("smb://n/video/Anime/Show/第3期/ep01.mkv"))
        assertEquals(3, AnimeQueries.seasonOf("smb://n/video/Anime/进击的巨人/第三季/ep01.mkv"))
        assertEquals(2, AnimeQueries.seasonOf("smb://n/video/Anime/Show/第２期/ep01.mkv"))
        // …but not the first, which no database gives an entry of its own, and not a film's part.
        assertNull(AnimeQueries.seasonOf("smb://n/video/Anime/Patlabor (TV系列第1期)/ep01.mkv"))
        assertNull(AnimeQueries.seasonOf("smb://n/video/Anime/空之境界/第三部/movie.mkv"))
        // …and a library that files its seasons as bare numbers still says it that way.
        assertEquals(2, AnimeQueries.seasonOf("smb://n/video/Anime/Some Show/2/ep01.mkv"))
    }
}

/**
 * `Version 2/` is a rip-REVISION folder — the same work re-encoded — not a second season.
 * Read as one, seasonFit preferred a picture-drama whose title ends in 2 (宇宙のカゲロウ2)
 * over the series itself, and 13 episodes of Gundam 0083 wore the wrong card.
 */
class RipRevisionFolderTest {
    private val path = "smb://nas/video/Anime/Mobile Suit Gundam 0083 Stardust Memory/Version 2/[EG]Gundam_0083_13_BD(1080p_10bit)[3CB11F40].mp4"

    @org.junit.Test
    fun aRevisionFolderIsNotASeason() {
        org.junit.Assert.assertNull(com.therealityteam.videoplayer.data.scrape.AnimeQueries.seasonOf(path))
        // …while a real season folder still reads.
        org.junit.Assert.assertEquals(2, com.therealityteam.videoplayer.data.scrape.AnimeQueries.seasonOf(
            "smb://nas/video/Anime/Mobile Suit Gundam 00 1080P BDRIP/2nd_Season/ep01.mkv"))
    }

    @org.junit.Test
    fun theClimbPassesThroughARevisionFolderToTheRelease() {
        // The nearest MEANINGFUL folder names the work; `Version 2` must not become the query.
        val parsed = com.therealityteam.videoplayer.data.scrape.FilenameParser.parseFromPath(path)
        val anime = com.therealityteam.videoplayer.data.scrape.AnimeParse.parse(path.substringAfterLast('/'))
        val queries = com.therealityteam.videoplayer.data.scrape.AnimeQueries.build(path, parsed, anime)
        org.junit.Assert.assertTrue(queries.toString(), queries.none { it.equals("Version 2", ignoreCase = true) })
        org.junit.Assert.assertTrue(queries.toString(),
            queries.any { it.contains("Stardust Memory") })
    }
}
