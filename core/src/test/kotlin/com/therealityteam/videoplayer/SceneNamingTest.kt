package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.FilenameParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The all-bracket layouts, read the way a person reads them.
 *
 * Two conventions collide here and they put the release group at OPPOSITE ends:
 *  - Japanese fansub:  `[Group][Title][DVDRIP][01]`      — group FIRST
 *  - Chinese scene:    `[中文名.English.Name][S05E01][CN][624X352][人人影视]` — group LAST
 *
 * Blindly dropping the leading bracket as "the group" therefore throws away the title on every
 * Chinese release, and the leftovers (a site stamp, a subtitle-language tag) become the search
 * query. What actually disambiguates the two is the episode code: whichever segment carries it
 * sits next to — or inside — the title.
 */
class SceneNamingTest {

    private fun title(name: String) = FilenameParser.parse(name).title

    @Test
    fun chineseSceneKeepsTheLeadingTitleAndDropsTheTrailingGroup() {
        assertEquals(
            "Two And A Half Men",
            title("[好汉两个半.Two.And.A.Half.Men][S05E01][CN][TVrip][624X352][YYeTs人人影视].rmvb"),
        )
        assertEquals(
            "How I Met Your Mother",
            title("[老爸老妈浪漫史.How.I.Met.Your.Mother][S03E12][Chi_Eng][HDTVrip][640X368][YYeTs人人影视].rmvb"),
        )
    }

    @Test
    fun aSiteStampAndASubtitleTagAreNeverTheTitle() {
        // Every bracket is filled, so "richest segment" picks the site. The title is the segment
        // the episode code lives in — here, twice over (第一季 and S01E01).
        assertEquals(
            "Friends",
            title("[六人行.第一季][Friends.S01E01][圣城家园dream2008.cn][中英双字幕].rmvb"),
        )
    }

    @Test
    fun theEpisodeCodeStillParsesOutOfTheTitleSegment() {
        val p = FilenameParser.parse("[六人行.第五季][Friends.S05E23-24][圣城家园dream2008.cn][中英双字幕].rmvb")
        assertEquals(5, p.season)
        assertEquals(23, p.episode)
        val q = FilenameParser.parse("[好汉两个半.Two.And.A.Half.Men][S05E11][Chi_Eng][HDTVrip][640X368][YYeTs人人影视].rmvb")
        assertEquals(5, q.season)
        assertEquals(11, q.episode)
    }

    @Test
    fun aBareAbbreviationDoesNotBeatItsChineseTitle() {
        // The Latin run only wins when it reads like a full title — "EVA" is an abbreviation the
        // Chinese name spells out, and AniList/TMDB both resolve the spelled-out one far better.
        assertEquals("新世纪福音战士", title("[EVA][新世纪福音战士][01][BDRIP][1080P].mkv"))
    }

    @Test
    fun aLeadingBracketThatEchoesItsFolderIsTheTitle() {
        // `[Patlabor_OVA][NEW_OVA][BDRip]` under a Patlabor folder: dropping the first bracket as
        // "the group" left "NEW OVA", which AniList answers with 恶魔高校 D×D NEW, and its sibling
        // left "EARLY DAYS" → The Place Promised in Our Early Days. A group name doesn't echo the
        // folder it sits in; a title does.
        assertEquals(
            "Patlabor OVA",
            FilenameParser.fansubTitle(
                "[Patlabor_OVA][NEW_OVA][BDRip][1080P][X264_FLAC][01].mkv",
                "[1990-1992] Patlabor_機動警察(機動警察パトレイバー)_OVA.U2 (後期OVA,TV系列第2期)",
            ),
        )
        assertEquals(
            "Patlabor OVA",
            FilenameParser.fansubTitle(
                "[Patlabor_OVA][EARLY_DAYS][BDRip][1080P][X264_FLAC][03].mkv",
                "[1988-1989] Patlabor_機動警察(機動警察パトレイバー Early Days)_OVA.U2 (前期OVA)",
            ),
        )
        // …and with no folder to compare against, the leading bracket is still read as the group.
        assertEquals("Munto", FilenameParser.fansubTitle("[SumiSora][Munto][01][GB].mkv"))
    }

    @Test
    fun theJapaneseFansubLayoutIsUnchanged() {
        assertEquals("TALES OF PHANTASIA", title("[POPGO][FREEWIND][TALES_OF_PHANTASIA][DVDRIP][01].mkv"))
        assertEquals("Munto", title("[SumiSora][Munto][01][GB].mkv"))
    }
}
