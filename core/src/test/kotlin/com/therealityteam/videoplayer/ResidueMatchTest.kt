package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.scrape.AnimeMatch
import com.therealityteam.videoplayer.data.scrape.MetaResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tie-in inside a series' box is in no database under its own name, so a broader query answers
 * for it with the umbrella work. The residue — the words the FILE says that the ENTRY does not
 * know — is what tells the two apart.
 */
class ResidueMatchTest {

    private fun entry(vararg names: String) = MetaResult(
        providerId = 1, kind = MediaKind.SHOW, title = names.first(), synonyms = names.drop(1),
    )

    private val sao = entry("刀剑神域", "Sword Art Online", "ソードアート・オンライン", "SAO")
    private val eva = entry("新世纪福音战士", "Neon Genesis Evangelion", "新世紀エヴァンゲリオン")

    @Test fun aTieInInsideTheBoxIsNotTheShow() {
        listOf(
            "Sword Art Online -Hollow Fragment-",
            "Sword Art Online Infinity Moment",
            "Sword Art Online Lost Song",
        ).forEach { assertTrue(it, AnimeMatch.namesAnotherWork(it, sao)) }
    }

    @Test fun theShowsOwnFilesAreTheShow() {
        listOf(
            "Sword Art Online",
            "Sword Art Online NCOP1",          // a 特典 marker is not another work
            "Sword Art Online BD Extra Edition",
            "刀剑神域",
            "SAO",
        ).forEach { assertFalse(it, AnimeMatch.namesAnotherWork(it, sao)) }
    }

    @Test fun aVersionOfAWorkIsStillThatWork() {
        // A director's cut of Evangelion is Evangelion; so is a renewal re-encode.
        listOf(
            "Neon Genesis Evangelion Directors Cut",
            "eva renewal 23",
            "Evangelion Complete Collection Disc 2",
        ).forEach { assertFalse(it, AnimeMatch.namesAnotherWork(it, eva)) }
    }

    @Test fun oneUnknownWordIsASpellingTwoAreAName() {
        // A single stray word is how the same work gets written differently; the rule asks for two.
        assertFalse(AnimeMatch.namesAnotherWork("Sword Art Online Extra", sao))
        assertTrue(AnimeMatch.namesAnotherWork("Sword Art Online Hollow Fragment", sao))
    }

    @Test fun aMarkerInTheMiddleIsStillAMarker() {
        // stripMarkers only takes them off the ends: `… R2 Omake Flash` names the show's own reels.
        val geass = entry("Code Geass: Lelouch of the Rebellion R2", "コードギアス 反逆のルルーシュ R2")
        assertFalse(AnimeMatch.namesAnotherWork("Code Geass Lelouch of the Rebellion R2 Omake Flash", geass))
    }

    @Test fun aMarkerWrittenAsTwoWordsIsStillOneMarker() {
        // `Picture Drama 9.34` names the FORM of a 特典; neither word is a marker on its own, so a
        // test asked one word at a time called 13 of the show's own picture dramas another work.
        val geass = entry("Code Geass: Lelouch of the Rebellion R2", "コードギアス 反逆のルルーシュ R2")
        listOf(
            "Code Geass R2 Picture Drama 9.34",
            "Code Geass R2 Picture Drama 0.56",
            "Code Geass Lelouch of the Rebellion R2 TV Spot 3",
        ).forEach { assertFalse(it, AnimeMatch.namesAnotherWork(it, geass)) }
        // …and taking the marker out must not take the name with it.
        assertTrue(AnimeMatch.namesAnotherWork("Code Geass R2 Picture Drama Hollow Fragment", geass))
    }

    @Test fun anEntryWithNoNamesAccusesNobody() {
        assertFalse(AnimeMatch.namesAnotherWork("anything at all here", entry("")))
    }

    @Test fun extraWordsCountOnlyWhenTheRestOfTheNameAgrees() {
        // With no word in common there is nothing to have added them TO. `浪客剑心 追忆篇` shares not
        // one token with `Rurouni Kenshin OVA: Trust & Betrayal`, which is the very same work under
        // a Chinese name; `Lucky Star` shares none with `Lucky☆Star`, because ☆ is not ASCII
        // punctuation and so never became a space. Judged by residue alone, both read as a stranger.
        val kenshin = entry("Rurouni Kenshin OVA: Trust & Betrayal", "るろうに剣心 追憶編")
        assertFalse(AnimeMatch.namesAnotherWork("浪客剑心 追忆篇", kenshin))
        assertFalse(AnimeMatch.namesAnotherWork("Lucky Star OVA", entry("Lucky☆Star OVA")))
        // …and the guard must not swallow the case it was added around.
        assertTrue(AnimeMatch.namesAnotherWork("Rurouni Kenshin Fight Grand Tournament", kenshin))
    }
}
