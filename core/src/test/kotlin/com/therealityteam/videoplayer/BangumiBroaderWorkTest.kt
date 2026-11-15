package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.BangumiClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Bangumi answering a continuation with the franchise it continues.
 *
 * Its keyword search is fuzzy in exactly one direction, and the Chinese name it hands back then
 * replaces the right title with a shorter one. On a real wall that produced two cards called
 * 「R.O.D」 and two called 「Love Live!」, neither pair saying which was which.
 *
 * The pairs below are what bgm.tv actually returned for these queries.
 */
class BangumiBroaderWorkTest {

    private val client = BangumiClient(File(System.getProperty("java.io.tmpdir"), "aura-bgm-test"))

    @Test fun `a hit that shortens the name asked for is the franchise, not the work`() {
        listOf(
            "ラブライブ! School idol project 2期" to "ラブライブ!",
            "R.O.D -THE TV-" to "R.O.D",
            "R.O.D -READ OR DIE-" to "R.O.D",
        ).forEach { (q, hit) -> assertTrue("$q -> $hit", client.isBroaderWork(q, hit)) }
    }

    @Test fun `a hit that answers to the letter is the work`() {
        listOf(
            // Whitespace differs and nothing else — Bangumi writes 無職転生Ⅱ without the space.
            "無職転生 Ⅱ ～異世界行ったら本気だす～" to "無職転生Ⅱ ～異世界行ったら本気だす～",
            "魔法少女リリカルなのはA's" to "魔法少女リリカルなのはA's",
            "ラブライブ! School idol project" to "ラブライブ!  School idol project",
        ).forEach { (q, hit) -> assertFalse("$q -> $hit", client.isBroaderWork(q, hit)) }
    }

    @Test fun `a hit that says MORE than was asked is still the work`() {
        // The other direction is the one the fuzzy search exists for: a query is a prefix of the
        // official title. 银河英雄传说 ⊂ 银河英雄传说 我的征途是星辰大海.
        assertFalse(client.isBroaderWork("銀河英雄伝説", "銀河英雄伝説 わが征くは星の大海"))
        assertFalse(client.isBroaderWork("Steins;Gate", "Steins;Gate 0"))
    }

    @Test fun `the one form word that means the series itself`() {
        // bgm.tv's subject for a franchise name IS its TV series, so these are one work…
        assertFalse(client.isBroaderWork("機動警察パトレイバー ON TELEVISION", "機動警察パトレイバー"))
        assertFalse(client.isBroaderWork("Patlabor TV", "Patlabor"))
        // …and every other form beside it is something else under the same name. This franchise
        // answers 劇場版 with the TV series too, and the film must not take the series' name.
        assertTrue(client.isBroaderWork("機動警察パトレイバー 劇場版", "機動警察パトレイバー"))
        assertTrue(client.isBroaderWork("勇者王ガオガイガー 特典Disc", "勇者王ガオガイガー"))
        assertTrue(client.isBroaderWork("ゼロの使い魔 ～双月の騎士～", "ゼロの使い魔"))
    }

    @Test fun `nothing at all is not an answer`() {
        assertTrue(client.isBroaderWork("R.O.D -THE TV-", null))
        assertTrue(client.isBroaderWork("R.O.D -THE TV-", "   "))
    }
}
