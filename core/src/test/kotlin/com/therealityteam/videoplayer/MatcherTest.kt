package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.Matcher
import com.therealityteam.videoplayer.data.scrape.MetaResult
import com.therealityteam.videoplayer.data.scrape.ParsedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the metadata matcher is allowed to accept. A wrong match is worse than none: it puts a
 * confident poster and title on the card, and grouping downstream trusts it.
 */
class MatcherTest {

    private fun hit(id: Int, title: String, original: String? = null, pop: Double = 0.0, year: Int? = null) =
        MetaResult(id, MediaKind.SHOW, title = title, originalTitle = original, popularity = pop, year = year)

    @Test
    fun aSameScriptNameThatBarelyAgreesIsNotCarriedByPopularity() {
        // `Documentary/Conversations with History/Conversations with History Robert O. Keohane.flv`
        // — a university interview series no database lists. TMDB's top hit shared the word
        // "History"; rank, popularity and the cross-language bonus carried it over the bar and
        // four interviews were filed under "Chance, History, Art...".
        val parsed = FilenameParser.parseFromPath("smb://nas/video/Documentary/Conversations with History/Conversations with History Robert O. Keohane.flv")
        val chance = hit(472581, "Chance, History, Art...", "Chance, History, Art...", pop = 25.0, year = 2018)
        assertNull(Matcher.best(parsed, listOf(chance)))
        // A romaji query against a localized title is the case the bonus exists for, and still hits.
        val titan = hit(1429, "进击的巨人", "進撃の巨人", pop = 80.0, year = 2013)
        assertNotNull(Matcher.best(ParsedName("Shingeki no Kyojin", year = 2013), listOf(titan)))
        // A same-script name that agrees needs no year to be accepted.
        assertNotNull(Matcher.best(ParsedName("Heat"), listOf(hit(949, "Heat", pop = 30.0, year = 1995))))
    }

    @Test
    fun aProgrammeNameMustBeNamedByTheHitNotResembled() {
        // What the programme fallback demands of a hit for a short name with no year.
        assertEquals(false, Matcher.namesAgree("美国魂", hit(381170, "美国招魂", "American Conjuring", pop = 0.9, year = 2016)))
        assertEquals(true, Matcher.namesAgree("激流中国", hit(75629, "激流中国", "ＮＨＫスペシャル　激流中国", pop = 1.1, year = 2007)))
        assertEquals(true, Matcher.namesAgree("沸腾都市", hit(215996, "沸腾都市", "ＮＨＫスペシャル 沸騰都市", pop = 1.3, year = 2008)))
        assertEquals(true, Matcher.namesAgree("Rick Steves Europe", hit(62970, "Rick Steves' Europe", "Rick Steves' Europe", pop = 6.2, year = 2000)))
        // A short name inside a much longer title is a shared phrase, not the work.
        assertEquals(false, Matcher.namesAgree("金融危机", hit(356839, "坑爹：金融危机真相", null, pop = 1.0, year = 2017)))
        // …while a label in front of the query, or an exact title, still agrees.
        assertEquals(true, Matcher.namesAgree("NHK映像的世纪", hit(1, "映像的世纪", null, pop = 1.0, year = 1995)))
        assertEquals(true, Matcher.namesAgree("映像の世紀バタフライエフェクト", hit(201738, "映像的世纪 蝴蝶效应", "映像の世紀バタフライエフェクト", pop = 4.4, year = 2022)))
    }

    @Test
    fun aTwoCharacterNameDoesNotMatchAMuchLongerTitle() {
        // `Documentary/NHK/[NHK][纪录片]明治.第1集.教育.wmv` searched "明治". Jaro's m/|query| term is
        // 1.0 whenever the whole query appears in the candidate, so the 2-character name scored
        // 0.49 against 浪客剑心－明治剑客浪漫谭－; the rank and popularity bonuses carried it over the
        // acceptance bar and five NHK documentaries were filed under Rurouni Kenshin.
        val parsed = FilenameParser.parseFromPath(
            "smb://nas/video/Documentary/NHK/[NHK][纪录片]明治.第1集.教育.wmv",
        )
        val kenshin = hit(210879, "浪客剑心－明治剑客浪漫谭－", "るろうに剣心 -明治剣客浪漫譚-", pop = 50.0, year = 2023)
        assertNull(Matcher.best(parsed, listOf(kenshin)))
    }

    @Test
    fun aShortNameStillMatchesItsOwnEntry() {
        // The guard is about length comparability, not about short names being unmatchable.
        assertNotNull(Matcher.best(ParsedName("明治", year = 2005), listOf(hit(1, "明治", pop = 8.0, year = 2005))))
        assertNotNull(Matcher.best(ParsedName("1917", year = 2019), listOf(hit(2, "1917", pop = 30.0, year = 2019))))
        assertNotNull(
            Matcher.best(ParsedName("东京教父"), listOf(hit(3, "东京教父 Tokyo Godfathers", pop = 20.0))),
        )
    }

    @Test
    fun aDocumentaryShelfTakesOnlyADocumentary() {
        val fit = com.therealityteam.videoplayer.data.scrape.ShelfFit::documentary
        assertEquals(true, fit(listOf("纪录")))
        assertEquals(true, fit(listOf("Documentary", "History")))
        assertEquals(true, fit(emptyList()))          // a small production with no genres at all
        assertEquals(false, fit(listOf("剧情")))        // 毛泽东 the 2013 drama, not the NHK four-parter
        assertEquals(false, fit(listOf("Drama", "War")))
    }

    @Test
    fun oneSharedWordOfTwoIsNotAgreement() {
        // The Berkeley interview series versus a 2017 documentary that merely has "history" in it.
        assertEquals(null, Matcher.best(ParsedName("Conversations with History"), listOf(hit(472581, "Chance, History, Art...", "Chance, History, Art...", pop = 1.2, year = 2017))))
        assertEquals(false, Matcher.namesAgree("Conversations with History", hit(472581, "Chance, History, Art...", null, pop = 1.2, year = 2017)))
        // A one-word side is judged by that word; two shared words of two still agree.
        assertEquals(true, Matcher.namesAgree("Léon", hit(101, "Léon: The Professional", null, pop = 30.0, year = 1994)))
        assertEquals(true, Matcher.namesAgree("Kill Bill", hit(24, "Kill Bill: Vol. 1", null, pop = 40.0, year = 2003)))
        assertEquals(false, Matcher.namesAgree("Blue Velvet", hit(25, "Blue Jasmine", null, pop = 20.0, year = 2013)))
        // Character overlap is no agreement between short names: Jarecki's 2005 film is not Capra's.
        assertEquals(false, Matcher.namesAgree("美国为何而战", hit(162865, "我们为何而战：美国的参战", null, pop = 1.0, year = 1945)))
    }
}
