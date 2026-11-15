package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.scrape.FolderReconcile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 特典 clip adopted onto a show's card must stop answering to the work it briefly matched.
 *
 * Reconciliation already took the clip's TITLE back from its file name, which is what the card
 * shows — so nothing looked wrong. It kept the other work's original title, aliases, year and
 * synopsis, all of which are read: the library search matches on original title and aliases, and
 * reconciliation itself reads them when deciding whether a sibling is a work of its own. Measured
 * on a real shelf of 9,597 files, 156 files wore another work's original title and 147 another
 * work's year; five G-Reco bonus clips answered a search for `Fate/strange Fake`.
 */
class ReconcileNamesTest {

    private val folder = "smb://nas/video/Anime/劇場版 GのレコンギスタⅣ[BDRIP]/"
    private val CLIP = "特典DISC·Ⅳ MENU.mkv"

    private fun film() = MediaItem(
        id = "${folder}film.mkv", sourceId = "s", path = "${folder}film.mkv", fileName = "film.mkv",
        title = "高达 G之复国运动 剧场版IV", originalTitle = "劇場版 ガンダム Ｇのレコンギスタ Ⅳ", year = 2022,
        overview = "Belri…", kind = MediaKind.MOVIE, collectionKey = "id:994311", tmdbId = 994311,
        contentTag = ContentKind.MAIN, scrapeStatus = ScrapeStatus.MATCHED,
    )

    private fun clip(key: String?, sub: String = "特典DISC/") = MediaItem(
        id = "$folder$sub" + CLIP, sourceId = "s", path = "$folder$sub" + CLIP,
        fileName = CLIP,
        title = "勇者王GaoGaiGar", originalTitle = "勇者王ガオガイガー 特典Disc",
        aliases = listOf("The King of Braves GaoGaiGar"), year = 2005, overview = "Gai Shishioh…",
        kind = MediaKind.EPISODE, collectionKey = key, contentTag = ContentKind.EXTRA,
        scrapeStatus = ScrapeStatus.MATCHED,
    )

    @Test fun aRejectedMatchTakesItsNameAndYearWithIt() {
        val planned = FolderReconcile.plan(listOf(film(), clip("anilist:5776"))).associateBy { it.id }
        val out = planned.values.single { it.fileName.startsWith("特典DISC") }
        assertEquals("id:994311", out.collectionKey)
        assertNull("original title", out.originalTitle)
        assertEquals("aliases", emptyList<String>(), out.aliases)
        assertEquals("year", 2022, out.year)
        assertFalse("title", out.title.contains("GaoGaiGar"))
    }

    @Test fun anEarlierPassesDamageIsRepairedWithoutARescrape() {
        // The clip already carries the film's id — an earlier reconciliation adopted it — so
        // "is the identity changing?" answers no. Asked of the NAME instead, the residue is found.
        val planned = FolderReconcile.plan(listOf(film(), clip("id:994311")))
        val out = planned.single { it.fileName.startsWith("特典DISC") }
        assertNull(out.originalTitle)
        assertEquals(2022, out.year)
    }

    @Test fun anExtraThatWearsTheWorksOwnNameKeepsIt() {
        // Reconciliation still re-homes an extras-subfolder file (it tags it, and gives it the
        // show's poster and cast) — what it must not do is take a name that is already right.
        val ownName = clip("id:994311").copy(
            originalTitle = "劇場版 ガンダム Ｇのレコンギスタ Ⅳ", aliases = listOf("G-Reco IV"), year = 2022,
        )
        val out = FolderReconcile.plan(listOf(film(), ownName)).single { it.id == ownName.id }
        assertEquals("劇場版 ガンダム Ｇのレコンギスタ Ⅳ", out.originalTitle)
        assertEquals(listOf("G-Reco IV"), out.aliases)
        assertEquals(2022, out.year)
    }

    @Test fun aCjkExtrasFolderIsFoundWithoutADelimiterToLeanOn() {
        // Japanese and Chinese write no delimiters, so the token test that finds `X.Extras-Grym`
        // cannot find 本篇特典 — those files stayed a release of their own and never reconciled.
        listOf("本篇特典", "特典DISC", "映像特典", "特典映像", "BD特典", "花絮").forEach {
            assertTrue(it, FolderReconcile.isExtraFolder(it))
        }
    }

    @Test fun aReleaseFolderThatMerelyListsItsExtrasIsNotOne() {
        // …and the cap that keeps the substring safe. Both of these are the folder the whole card
        // lives in; climbing out of them would empty it.
        listOf(
            "[DBD-Raws][药屋少女的呢喃][01-24TV全集+特典映像][1080P][BDRip][HEVC-10bit][简繁日双语外挂][FLAC][MKV]",
            "GUNBUSTER&DIEBUSTER&合体劇場版 全12話+劇場版x2+特典+Scans(1080p HEVC 10bit FLACx3+AAC)",
            // `映像` and `预告` are deliberately not substring markers: 映像の世紀 is a real programme.
            "映像の世紀バタフライエフェクト「銀座 百年の記憶」",
        ).forEach { assertFalse(it.take(40), FolderReconcile.isExtraFolder(it)) }
    }

    @Test fun anUnmatchedFeatureIsNotFiledAsAnExtraOfItsOwnBonusDisc() {
        // `劇場版 GのレコンギスタⅤ 死線を越えて` drew a blank from every database — AniList answers all
        // four of its queries with nothing. Its bonus disc hit 勇者王ガオガイガー 特典Disc, and because
        // only extras had matched, the folder took ITS identity: the film was filed as an extra of a
        // GaoGaiGar bonus disc, on a card named after the file and dated 2005.
        val film = film().copy(collectionKey = null, tmdbId = null, originalTitle = null, year = null,
            title = "劇場版 GのレコンギスタⅤ 死線を越えて", scrapeStatus = ScrapeStatus.UNMATCHED)
        val discs = (1..3).map {
            clip("anilist:5776").let { c -> c.copy(id = c.id + it, path = c.path + it) }
        }
        val planned = FolderReconcile.plan(listOf(film) + discs).associateBy { it.id }
        assertNull("the feature keeps its own (absent) identity", planned[film.id]?.collectionKey)
        assertTrue("nothing is adopted onto the bonus disc", planned.values.none { it.collectionKey == "anilist:5776" })
    }

    @Test fun aFolderOfNothingButExtrasStillTakesTheirIdentity() {
        // …and the fallback that rule narrows keeps its own case: with no feature to speak for the
        // folder, what its extras matched is all there is to go on.
        val discs = (1..3).map {
            clip("anilist:5776").let { c -> c.copy(id = c.id + it, path = c.path + it) }
        }
        val planned = FolderReconcile.plan(discs)
        assertTrue(planned.map { it.collectionKey }.toString(), planned.all { it.collectionKey == "anilist:5776" })
    }
}
