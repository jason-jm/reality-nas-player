package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.FilenameParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `parseFromPath` decides whether a file's own name or its release FOLDER names the work. Getting
 * that backwards is expensive: every file under a broadcaster folder inherits the broadcaster's
 * name and they all scrape to the same wrong title.
 */
class PathTitleTest {

    private fun title(path: String) = FilenameParser.parseFromPath(path).title

    private fun parse(path: String) = FilenameParser.parseFromPath(path)

    @Test
    fun aSpacedDashBeforeTheNumberIsAnEpisodeMarker() {
        // The dominant fansub convention worldwide, and nothing recognised it: the number stayed
        // glued to the title, so every episode of a show asked the database a different question.
        // 160 of 581 works in the reference library fragmented this way.
        parse("smb://nas/video/Anime/Fate stay night UBW/[Kamigami] Fate stay night UBW - 02 [1080p].mkv").let {
            assertEquals("Fate stay night UBW", it.title)
            assertEquals(2, it.episode)
        }
        parse("smb://nas/video/Anime/Ryvius/Mugen no Ryvius - 26 END.mkv").let {
            assertEquals("Mugen no Ryvius", it.title)
            assertEquals(26, it.episode)
        }
        parse("smb://nas/video/Anime/E7/Eureka Seven - 39 (BDRip 1920x1080 x264 FLAC).mkv").let {
            assertEquals("Eureka Seven", it.title)
            assertEquals(39, it.episode)
        }
    }

    @Test
    fun theEpisodeNumberNeedNotBeAtTheEndOfTheName() {
        // Release tags often follow the episode number, so anchoring the dashed form at the end
        // of the name alone left `- 03 - - kewenyu` sitting in the query.
        parse("smb://nas/video/Anime/SEED/Mobile Suit Gundam SEED Destiny HD Remaster - 03 - [AVC_10bit_1080P FLAC] - kewenyu.mkv").let {
            assertEquals("Mobile Suit Gundam SEED Destiny HD Remaster", it.title)
            assertEquals(3, it.episode)
        }
        // …and the marker spelled out in full, which only `E`/`EP` were matching before.
        parse("smb://nas/video/Anime/Origin/MOBILE SUIT GUNDAM THE ORIGIN - Episode 1.mkv").let {
            assertEquals("MOBILE SUIT GUNDAM THE ORIGIN", it.title)
            assertEquals(1, it.episode)
        }
    }

    @Test
    fun aQuotedJapaneseSubtitleBelongsToTheEpisodeNotTheProgramme() {
        // Japanese broadcast names the programme, then quotes that episode's own subtitle.
        // Leaving it in asked the database a different question for all 190 files of two
        // recorded series.
        assertEquals(
            "鎌倉殿の１３人",
            title("smb://nas/video/TV/鎌倉殿/鎌倉殿の１３人（８）「いざ、鎌倉」 - [1920].mkv"),
        )
        // The programme name repeated before a segment title is still just the programme.
        assertEquals(
            "青天を衝け",
            title("smb://nas/video/TV/青天/青天を衝け（９）青天を衝け紀行「旧江戸城 桜田門」 - [1920].mkv"),
        )
        // …but a name that IS a quote has nothing in front of it and must survive whole.
        assertEquals(
            "「彼女と彼女の猫」",
            title("smb://nas/video/Anime/「彼女と彼女の猫」/「彼女と彼女の猫」.mkv"),
        )
    }

    @Test
    fun aSpacedNumberIsAnEpisodeOnlyWhenTheTitleIsLongEnoughToBeSure() {
        // Plenty of real titles end in a number, so this form needs guards. The three that hold:
        // a lone digit is sequel numbering, `00` is 机动战士高达00 itself, and a title with only one
        // word in front of the number is the shape almost every numeric film title takes.
        parse("smb://nas/video/Anime/Code Geass/Code Geass 01.mkv").let {
            assertEquals("Code Geass", it.title)
            assertEquals(1, it.episode)
        }
        parse("smb://nas/video/Anime/LOGH/Legend of Galactice Heroes 002.mkv").let {
            assertEquals("Legend of Galactice Heroes", it.title)
            assertEquals(2, it.episode)
        }
        // …and the titles that must survive it untouched.
        assertEquals("apollo 11", title("smb://nas/video/Movie/apollo 11.mkv"))
        assertEquals("Apollo 13", title("smb://nas/video/Movie/Apollo 13.mkv"))
        assertEquals("Ocean's 11", title("smb://nas/video/Movie/Ocean's 11.mkv"))
        assertEquals("Catch 22", title("smb://nas/video/Movie/Catch 22.mkv"))
        assertEquals("Toy Story 3", title("smb://nas/video/Movie/Toy Story 3.mkv"))
        assertEquals("Mobile Suit Gundam 00", title("smb://nas/video/Anime/G00/Mobile Suit Gundam 00.mkv"))
    }

    @Test
    fun aLoneDigitAfterADashIsSequelNumbering() {
        // `Kill Bill - 2` is the second film, not the second episode. Sequel numbering is almost
        // always a bare digit; episode numbering is almost always zero-padded or past nine —
        // which is the whole guard, and the reason the pattern cannot just take any number.
        assertEquals("Kill Bill - 2", title("smb://nas/video/Movie/Kill Bill - 2.mkv"))
        assertEquals("Rocky - 3", title("smb://nas/video/Movie/Rocky - 3.mkv"))
    }

    @Test
    fun aChineseTitleIsNotOverriddenByItsBroadcasterFolder() {
        assertEquals("秋叶原物语", title("smb://nas/video/Documentary/NHK/秋叶原物语.avi"))
        assertEquals(
            "中国力量 第一集",
            title("smb://nas/video/Documentary/NHK/[NHK纪录片]中国力量.第一集.avi"),
        )
    }

    @Test
    fun aGarbledFileStillTakesItsReleaseFolderTitle() {
        // The classic scene layout: the folder is authoritative, the file is junk.
        assertEquals(
            "Cloud Atlas",
            title("smb://nas/video/Movie/Cloud.Atlas.2012.BluRay.x264-AiHD/cloudatlas-x264.mkv"),
        )
    }

    @Test
    fun aSiteStampedReleaseStillTakesTheFolderTitle() {
        val t = title(
            "smb://nas/video/Movie/Life.Is.Beautiful.1997.BluRay.720p/" +
                "影视帝国(bbs.cnxp.com).美丽人生.Life.Is.Beautiful.1997.BluRay.720p.rmvb",
        )
        assertEquals("Life Is Beautiful", t)
    }

    @Test
    fun anEpisodeKeepsItsShowFolder() {
        assertEquals("The Bear", title("smb://nas/video/TV/The Bear/Season 1/The.Bear.S01E05.mkv"))
    }

    @Test
    fun aDottedFolderNameKeepsItsLastWord() {
        // Folders have no extension, and `substringBeforeLast('.')` amputated the last word of
        // every dotted one: `2001.A.Space.Odyssey` became "2001 A Space", TMDB was asked for a
        // 2001 film by that name, and the wall showed 2001: A Space TRAVESTY.
        assertEquals(
            "2001 A Space Odyssey",
            title("smb://nas/video/Movie/2001.A.Space.Odyssey/影视帝国(bbs.cnxp.com).2001：太空漫游.2001.A.Space.Odyssey.Blu-ray.720p.rmvb"),
        )
        // …while a REAL extension still goes.
        assertEquals("Cloud Atlas", title("smb://nas/video/Movie/Cloud.Atlas.2012.BluRay.x264-AiHD/Cloud.Atlas.mkv"))
    }

    @Test
    fun aTitleThatEndsInAYearKeepsItWhenTheReleaseYearFollows() {
        // `The.Legend.of.1900.1998` read 1900 as the release year and "The Legend of" as the film.
        parse("smb://nas/video/Movie/The.Legend.of.1900.1998.海上钢琴师/The.Legend.of.1900.1998.海上钢琴师.蓝光加长版.双语字幕.HR-HDTV.AC3.1024X576.x264.mkv").let {
            assertEquals("The Legend of 1900", it.title)
            assertEquals(1998, it.year)
        }
        parse("smb://nas/video/Movie/Death.Race.2000.1975.1080p.BluRay.x264/Death.Race.2000.1975.1080p.BluRay.x264.mkv").let {
            assertEquals("Death Race 2000", it.title)
            assertEquals(1975, it.year)
        }
        // A span is not two adjacent years: the box is dated by its first film.
        parse("smb://nas/video/Movie/The.Dark.Knight.Collection.2005-2012.BluRay.1080p.DTS-HD.MA.5.1.x264-beAst/Batman.Begins.2005.1080p.BluRay.x264.mkv").let {
            assertEquals("Batman Begins", it.title)
            assertEquals(2005, it.year)
        }
        // …and a re-release tag after the real year is still not the film's year.
        assertEquals(1991, parse("smb://nas/video/Movie/T2/Terminator.2.1991.Lionsgate.2015.BluRay.mkv").year)
    }

    @Test
    fun aResolutionGluedToTheLastWordIsStillNoise() {
        parse("smb://nas/video/Movie/The Last Samuria (2003)/The Last Samurai720p.mkv").let {
            assertEquals("The Last Samurai", it.title)
            assertEquals(2003, it.year)
        }
        // A code that merely ends in a letter is not a resolution.
        assertEquals("C0823I", parse("smb://nas/video/Movie/codes/C0823I.mkv").title)
    }

    @Test
    fun aContainerOrASiteStampIsNeverTheTitleOfABracketedName() {
        // `[GB_MP4]` and `[52movieba]` each counted as "another bracket title", so the first bracket
        // — the only real name — was thrown away as the release group: 39 files called "GB MP4".
        parse("smb://nas/video/Anime/Mobile Suit Gundam X.1996 1080P BDRIP/[After War Gundam X.1996][01][BDrip][GB_MP4][1440X1080][52movieba].mp4").let {
            assertEquals("After War Gundam X", it.title)
            assertEquals(1, it.episode)
            assertEquals(1996, it.year)
        }
    }

    @Test
    fun aHyphenatedTokenThatOpensWithAReleaseWordIsNoise() {
        // `债务美国.I.O.U.S.A.L.DVD-RMVB-人人影视-bddiudiu.rmvb` — the whole tail is one token, and no
        // release word matched it whole, so the group and the site went into the search.
        assertEquals("债务美国 I O U S A L", parse("smb://nas/video/Documentary/I.O.U.S.A/债务美国.I.O.U.S.A.L.DVD-RMVB-人人影视-bddiudiu.rmvb").title)
        // A name that merely CONTAINS a hyphen is untouched.
        assertEquals("Spider-Man Into the Spider-Verse", parse("smb://nas/video/Movie/Spider-Man.Into.the.Spider-Verse.2018.1080p.BluRay.x264/Spider-Man.Into.the.Spider-Verse.2018.1080p.BluRay.x264.mkv").title)
        assertEquals("X-Men", parse("smb://nas/video/Movie/X-Men.2000.1080p.BluRay/X-Men.2000.1080p.BluRay.mkv").title)
    }

    @Test
    fun aCombinedCutIsNamedForTheWorkNotItsParts() {
        // TMDB answers nothing for "Red Cliff I&II"; "Red Cliff" gets the film (and its sequel next).
        parse("smb://nas/video/Movie/Red.Cliff.I&II.2009.BluRay.720p.x264.DTS-WiKi/Red.Cliff.I&II.2009.BluRay.720p.x264.DTS-WiKi.mkv").let {
            assertEquals("Red Cliff", it.title)
            assertEquals(2009, it.year)
        }
        assertEquals("Kill Bill", parse("smb://nas/video/Movie/Kill.Bill.1&2.2004.1080p/Kill.Bill.1&2.2004.1080p.mkv").title)
        // A single numeral is a sequel number and stays.
        assertEquals("Red Cliff II", parse("smb://nas/video/Movie/Red.Cliff.II.2009.1080p/Red.Cliff.II.2009.1080p.mkv").title)
    }

    @Test
    fun theProgrammeAFileBelongsToIsWhatItsInstalmentMarkPrecedes() {
        val pt = FilenameParser::programmeTitle
        assertEquals("激流中国", pt("[激流中国].12.环保攻防战.avi", "NHK"))
        assertEquals("毛泽东", pt("毛泽东-03.革命不是请客吃饭.avi", "NHK"))
        assertEquals("Rick Steves Europe", pt("[PBS.里奇·史蒂夫斯游欧洲].Rick.Steves.Europe.10of11.Spain.Madrid.720p.avi", "PBS"))
        assertEquals("Rick Steves Europe", pt("Rick.Steves.Europe.01of11.Spain.Toledo.and.Salamanca.720p.Divx6.Ac3.avi", "PBS"))
        // A bare instalment number and an air year: the programme is still the name before the number.
        assertEquals("Rick Steves Europe", pt("Rick.Steves.Europe.17.Provence.Legendary.Light.Wind.and.Wine.2004.720p.HDTV.AC3-SoS.avi", "PBS"))
        assertEquals("映像的世纪", pt("[NHK.映像的世纪].第01集.20世纪的开幕.avi", "NHK"))
        // A film's name marks no instalment: nothing to ask for beyond the name itself.
        assertEquals(null, pt("The.Last.Samurai.2003.1080p.BluRay.mkv", "Movie"))
    }

    @Test
    fun aYearThatOpensTheTitleIsNotAReleaseYear() {
        // The name repeats the title, so its second "2001" sits where a release year sits — and
        // TMDB was asked for a 2001 film called 2001 A Space Odyssey. Kubrick's is 1968.
        val p = FilenameParser.parseFromPath(
            "smb://nas/video/Movie/2001.A.Space.Odyssey/影视帝国(bbs.cnxp.com).2001：太空漫游.2001.A.Space.Odyssey.Blu-ray.720p.rmvb",
        )
        assertEquals("2001 A Space Odyssey", p.title)
        assertEquals(null, p.year)
        // …while a film that IS a number still takes the release year beside it.
        assertEquals(2019, FilenameParser.parseFromPath("smb://nas/video/Movie/1917.2019.BluRay/1917.2019.1080p.mkv").year)
        assertEquals(2009, FilenameParser.parseFromPath("smb://nas/video/Movie/2012.2009.BluRay/2012.2009.1080p.mkv").year)
    }
}

/**
 * A short decimal standing alone is part of a NAME — the rebuild films, GITS 2.0, picture-drama
 * timestamps — while the same dot inside scene runs and SMB hosts keeps splitting. The whole-
 * library dump moved exactly 27 titles on this change, every one toward the database's spelling.
 */
class NameDecimalTest {
    private fun t(fn: String) = com.therealityteam.videoplayer.data.scrape.FilenameParser.parse(fn).title

    @org.junit.Test
    fun aStandaloneDecimalSurvivesTheSeparatorPass() {
        org.junit.Assert.assertEquals("Evangelion 1.11 You Are Alone", t("Evangelion.1.11.You.Are.(Not).Alone.2009.mkv"))
        org.junit.Assert.assertEquals("Code Geass Picture Drama 0.515", t("[Coalgirls]_Code_Geass_Picture_Drama_0.515_(1920x1080_Blu-ray_FLAC)_[BFFB8D6C].mkv"))
    }

    @org.junit.Test
    fun sceneRunsAndHostsKeepSplitting() {
        // 2049.1080p is year.resolution; an IP's segments always touch another dot or digit.
        org.junit.Assert.assertEquals("Blade Runner 2049", t("Blade.Runner.2049.1080p.BluRay.x264.mkv"))
        org.junit.Assert.assertFalse(t("[YYeTs][192.168.1.126][sample].mkv").contains("."))
    }
}
