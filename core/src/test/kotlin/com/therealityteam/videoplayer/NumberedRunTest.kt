package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.library.DetailPlay
import com.therealityteam.videoplayer.ui.library.DetailSections
import com.therealityteam.videoplayer.ui.library.EpisodeNaming
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A show a database named whose files number themselves by their names alone — 激流中国's
 * `[激流中国].01.喉舌与职责` — is a run of episodes, each called what its file calls it. Names as the
 * real library has them.
 */
class NumberedRunTest {

    private fun show(id: String, fileName: String, tmdbId: Int, title: String, tag: ContentKind? = null) = MediaItem(
        id = id, sourceId = "s", path = "smb://n/video/Documentary/NHK/$fileName", fileName = fileName,
        title = title, year = 2007, tmdbId = tmdbId, kind = MediaKind.SHOW, contentTag = tag, scrapeStatus = ScrapeStatus.MATCHED,
    )

    private val jiliu = listOf(
        "[激流中国].(引子).富人与穷人.avi", "[激流中国].01.喉舌与职责.avi", "[激流中国].02.青岛养老院的故事.avi",
        "[激流中国].04.民告官.土地攻防战.avi", "[激流中国].08.五年级1班.小皇帝的眼泪.wmv", "[激流中国].12.(最终回).环保攻防战.avi",
    ).mapIndexed { i, f -> show("j$i", f, 75629, "激流中国") }

    @Test fun `a show whose files only number themselves is a numbered run, and is listed as episodes`() {
        assertTrue(EpisodeNaming.numberedByNames(jiliu))
        val card = LibraryGrouping.group(jiliu).single()
        assertTrue(card.numberedRun)
        val episodes = DetailSections.of(card.items, card.numberedRun).first { it.kind == DetailSections.Kind.EPISODES }
        assertEquals(listOf("j1", "j2", "j3", "j4", "j5"), episodes.items.map { it.id })
        // The prologue is a special of the run, and 播放 starts its first episode.
        assertEquals(listOf("j0"), DetailSections.of(card.items, true).first { it.kind == DetailSections.Kind.EXTRAS }.items.map { it.id })
        assertEquals("j1", DetailPlay.choose(card.items, card.numberedRun, card.seasonal)!!.item.id)
    }

    @Test fun `a run's specials stay specials, and a number every file holds is no run`() {
        val raws = listOf(
            "『スカーRaws』Ichiban Ushiro no Daimaou ~ 01 ~ (BDRip 1920x1080 x264 FLAC).mkv",
            "『スカーRaws』Ichiban Ushiro no Daimaou ~ 02 ~ (BDRip 1920x1080 x264 FLAC).mkv",
            "『スカーRaws』Ichiban Ushiro no Daimaou ~ Special 01 ~ (BDRip 1920x1080 x264 FLAC).mkv",
            "『スカーRaws』Ichiban Ushiro no Daimaou ~ NCOP ~ (BDRip 1920x1080 x264 FLAC).mkv",
        ).mapIndexed { i, f -> show("s$i", f, 7088, "Daimaou").copy(kind = MediaKind.EPISODE) }
        assertTrue(EpisodeNaming.numberedByNames(raws))
        assertEquals(listOf(EpisodeNaming.Cat.EPISODE, EpisodeNaming.Cat.EPISODE, EpisodeNaming.Cat.EXTRA, EpisodeNaming.Cat.EXTRA),
            raws.map { EpisodeNaming.parse(it, numberedRun = true).cat })
        val gits = listOf(
            "[アニメ.BD].攻殻機動隊.S.A.C.OVA「Individual.Eleven」(x264.1920x1040.AAC.5.1ch).mp4",
            "[アニメ.BD].攻殻機動隊.S.A.C.OVA「The.Laughing.Man」(x264.1920x1040.AAC.5.1ch).mp4",
        ).mapIndexed { i, f -> show("g$i", f, 2449, "攻殻機動隊 S.A.C.") }
        assertFalse(EpisodeNaming.numberedByNames(gits))
    }

    @Test fun `a show the scan numbered is not one`() {
        val numbered = (1..3).map { n ->
            show("m$n", "The.Mandalorian.S01E0$n.Chapter.$n.2160p.mkv", 82856, "曼达洛人").copy(kind = MediaKind.EPISODE, season = 1, episode = n)
        }
        assertFalse(EpisodeNaming.numberedByNames(numbered))
        assertFalse(LibraryGrouping.group(numbered).single().numberedRun)
    }

    @Test fun `each instalment is called by what follows its number`() {
        assertEquals(
            mapOf(
                "j0" to "引子·富人与穷人", "j1" to "喉舌与职责", "j2" to "青岛养老院的故事", "j3" to "民告官·土地攻防战",
                "j4" to "五年级1班·小皇帝的眼泪", "j5" to "最终回·环保攻防战",
            ),
            EpisodeNaming.instalmentNames(jiliu),
        )
    }

    @Test fun `up to the release's own words`() {
        val rick = listOf(
            "Rick.Steves.Europe.01of11.Spain.Toledo.and.Salamanca.720p.Divx6.Ac3.avi",
            "Rick.Steves.Europe.04of11.Spain.Sevilla.and.Andalusia.FIXED.720p.Divx6.Ac3.avi",
            "Rick.Steves.Europe.16.European.Christmas.2005.720p.HDTV.XviD.AC3-SoS.avi",
            "StarshipOperators-05-GreatEscape-PartI(anime-mx)[04C07908].mkv",
        ).mapIndexed { i, f -> show("r$i", f, 62970, "Rick Steves' Europe") }
        val names = EpisodeNaming.instalmentNames(rick)
        assertEquals("Spain Toledo and Salamanca", names["r0"])
        assertEquals("Spain Sevilla and Andalusia", names["r1"])
        assertEquals("European Christmas 2005", names["r2"])
        assertEquals("Great Escape Part I", names["r3"])
    }

    @Test fun `without the words every instalment ends with, and none where that is all there is`() {
        val victoria = listOf(
            "[PBS.维多利亚女王的帝国].Empires.queen.victoria's.empire.1of4.engines.of.change.festering.leper.avi",
            "[PBS.维多利亚女王的帝国].Empires.queen.victoria's.empire.2of4.passage.to.india.festering.leper.avi",
            "[PBS.维多利亚女王的帝国].Empires.queen.victoria's.empire.4of4.the.scramble.for.africa.festering.leper.avi",
        ).mapIndexed { i, f -> show("v$i", f, 79178, "Queen Victoria's Empire") }
        assertEquals(
            mapOf("v0" to "Engines of Change", "v1" to "Passage to India", "v2" to "The Scramble for Africa"),
            EpisodeNaming.instalmentNames(victoria),
        )
        val jane = (1..4).map { n -> show("e$n", "BBC Jane Eyre [2006] $n of 4 DaRmEtH.avi", 13851, "Jane Eyre") }
        assertEquals(emptyMap<String, String>(), EpisodeNaming.instalmentNames(jane))
        val dorrit = listOf("Little Dorrit (2008) 1 and 2.avi", "Little Dorrit (2008) 10.avi", "Little Dorrit (2008) 11.avi")
            .mapIndexed { i, f -> show("d$i", f, 13561, "Little Dorrit") }
        assertEquals(emptyMap<String, String>(), EpisodeNaming.instalmentNames(dorrit))
        val raws = (1..3).map { n -> show("s$n", "『スカーRaws』Ichiban Ushiro no Daimaou ~ 0$n ~ (BDRip 1920x1080 x264 FLAC).mkv", 7088, "Daimaou") }
        assertEquals(emptyMap<String, String>(), EpisodeNaming.instalmentNames(raws))
    }
}
