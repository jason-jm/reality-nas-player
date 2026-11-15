package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.ui.library.EpisodeNaming
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How files no database could name are keyed on a documentary shelf — a flat folder of unrelated
 * programmes, some of them multi-part, some of them copies with an uploader's label in front.
 */
class ShelfShardTest {

    private fun file(name: String) = MediaItem(
        id = name, sourceId = "s", path = "smb://nas/video/Documentary/PBS/$name", fileName = name,
        title = name.substringBeforeLast('.'), kind = MediaKind.MOVIE, scrapeStatus = ScrapeStatus.UNMATCHED,
    )

    private fun cards(vararg names: String): Map<String, List<String>> =
        LibraryGrouping.group(names.map(::file)).associate { g -> g.key to g.items.map { it.fileName } }

    @Test
    fun aLabelledCopyJoinsThePlainCopiesOfItsProgramme() {
        // Sixteen plain files and one with a Chinese label in front were the same series; the label
        // was taken for the name and the seventeenth file stood as a card of its own.
        val c = cards(
            "Rick.Steves.Europe.01of11.Spain.Toledo.and.Salamanca.720p.Divx6.Ac3.avi",
            "Rick.Steves.Europe.02of11.France.Normandy.720p.Divx6.Ac3.avi",
            "Rick.Steves.Europe.03of11.Belgium.Bruges.and.Brussels.720p.Divx6.Ac3.avi",
            "[PBS.里奇·史蒂夫斯游欧洲].Rick.Steves.Europe.10of11.Spain.Madrid.720p.Divx6.Ac3.avi",
        )
        assertEquals(c.toString(), 1, c.size)
    }

    @Test
    fun theStrandsOfOneBroadcasterAreNotOneProgramme() {
        // Three PBS "Empires" programmes: six characters of the shared name read them as one, and
        // the label in front of each is the name that tells them apart — unless the remainder does.
        val c = cards(
            "[PBS.拿破仑传奇].Empires.Napoleon.1of4.To.Destiny.720p.avi",
            "[PBS.拿破仑传奇].Empires.Napoleon.2of4.Mastering.Luck.720p.avi",
            "[PBS.美第奇家族].Empires.The.Medici.1of4.Birth.of.a.Dynasty.720p.avi",
            "[PBS.维多利亚女王的帝国].Empires.queen.victoria's.empire.1of4.engines.of.change.720p.avi",
            "[PBS.维多利亚女王的帝国].Empires.queen.victoria's.empire.2of4.the.moral.crusade.720p.avi",
        )
        assertEquals(c.toString(), 3, c.size)
        assertEquals(2, c.values.count { it.size == 2 })
    }

    @Test
    fun aNumberedRunNoDatabaseNamedIsStillASeries() {
        val g = LibraryGrouping.group(listOf(
            file("[激流中国].01.喉舌与职责.avi"), file("[激流中国].02.民工.avi"), file("[激流中国].03.北京的中产.avi"),
            file("毛泽东-01.反潮流.avi"), file("毛泽东-02.魔法师的门徒.avi"),
            file("秋叶原物语.avi"),
        ))
        val byTitle = g.associateBy { it.title }
        assertEquals(g.map { it.title }.toString(), 3, g.size)
        assertEquals(true, byTitle.getValue("激流中国").isSeries)
        assertEquals(true, byTitle.getValue("毛泽东").isSeries)
        assertEquals(false, byTitle.getValue("秋叶原物语").isSeries)
    }

    @Test
    fun aNumberedRunsPartsAreLabelledAsEpisodesOnlyWhenTheCardSaysSo() {
        // The episode NUMBER is what the naming has to get right; the words around it are the
        // reader's language and live in strings.xml, so this asserts the parse, not a sentence.
        fun ep(name: String, numberedRun: Boolean = true) =
            com.therealityteam.videoplayer.ui.library.EpisodeNaming.parse(file(name), numberedRun)
        assertEquals(1.0, ep("[激流中国].01.喉舌与职责.avi").episode)
        assertEquals(10.0, ep("Rick.Steves.Europe.10of11.Spain.Madrid.avi").episode)
        assertEquals(3.0, ep("毛泽东-03.革命不是请客吃饭.avi").episode)
        // The prologue has no number: a named feature, not an episode, sorted ahead of the parts.
        assertEquals(EpisodeNaming.Cat.FEATURE, ep("[激流中国].(引子).avi").cat)
        // Off the numbered-run path a bare number is a sequel, never an episode.
        assertEquals(EpisodeNaming.Cat.FEATURE, ep("Terminator.2.Judgment.Day.1991.mkv", numberedRun = false).cat)
    }

    private fun dl(folder: String, name: String) = MediaItem(
        id = "$folder/$name", sourceId = "s", path = "smb://nas/迅雷/下载/$folder/$name", fileName = name,
        title = name.substringBeforeLast('.'), kind = MediaKind.MOVIE, scrapeStatus = ScrapeStatus.UNMATCHED,
    )

    @Test
    fun datedBroadcastsWithEpisodeTitlesAreARun() {
        // Six broadcasts of one programme, each in a dated folder, each named with its 「episode title」
        // and no number: one card, and a series, not a film.
        val g = LibraryGrouping.group(listOf(
            dl("2025-11-10 映像の世紀バタフライエフェクト「銀座 百年の記憶」", "映像の世紀バタフライエフェクト「銀座 百年の記憶」 - [1440-1920@KFMVFR.hevc10_crf 20_p 8][字].mkv"),
            dl("2025-09-29 映像の世紀バタフライエフェクト「ナチを支えた女性たち」", "映像の世紀バタフライエフェクト「ナチを支えた女性たち」 - [1440-1920@KFMVFR.hevc10_crf 20_p 8][字].mkv"),
            dl("2025-09-01 映像の世紀バタフライエフェクト「シリーズ昭和百年② 敗戦国ニッポン 敗れざる者たち」", "映像の世紀バタフライエフェクト「シリーズ昭和百年② 敗戦国ニッポン 敗れざる者たち」 - [1440-1920@KFMVFR.hevc10_crf 20_p 8][字].mkv"),
        ))
        assertEquals(g.map { it.title }.toString(), 1, g.size)
        assertEquals(true, g.single().isSeries)
    }

    @Test
    fun partMarkersInFullwidthNamesAreARun() {
        val g = LibraryGrouping.group(listOf(
            dl("ＮＨＫスペシャル　選　新・ドキュメント太平洋戦争１９４４　絶望の空の下で", "ＮＨＫスペシャル　選　新・ドキュメント太平洋戦争１９４４　絶望の空の下で.mp4"),
            dl("ＮＨＫスペシャル　選　新・ドキュメント太平洋戦争１９４３国家総力戦の真実　後編", "ＮＨＫスペシャル　選　新・ドキュメント太平洋戦争１９４３　国家総力戦の真実　後編.mp4"),
            dl("ＮＨＫスペシャル　選　新・ドキュメント太平洋戦争１９４１開戦（後編）", "ＮＨＫスペシャル　選　新・ドキュメント太平洋戦争「１９４１　開戦（後編）」.mp4"),
            dl("ＮＨＫスペシャル　選　新・ドキュメント太平洋戦争１９４５最終回　忘れられた悲しみ", "ＮＨＫスペシャル　新・ドキュメント太平洋戦争　最終回　忘れられた悲しみ.mp4"),
        ))
        assertEquals(g.map { it.title }.toString(), 1, g.size)
        assertEquals(true, g.single().isSeries)
    }

    @Test
    fun aLoneScrapedEpisodeIsStillASeriesCard() {
        val ep = MediaItem(
            id = "bm", sourceId = "s", path = "smb://nas/video/TV/Black.Mirror.S01/Black.Mirror.S01E03.1080p.WEBRip.mkv",
            fileName = "Black.Mirror.S01E03.1080p.WEBRip.mkv", title = "Black Mirror", showTitle = "Black Mirror",
            kind = MediaKind.EPISODE, season = 1, episode = 3, scrapeStatus = ScrapeStatus.MATCHED, collectionKey = "id:42009",
        )
        val g = LibraryGrouping.group(listOf(ep)).single()
        assertEquals(true, g.isSeries)
        assertEquals(1, g.count)
        // …while a lone film, or a lone unnumbered file, is not.
        assertEquals(false, LibraryGrouping.group(listOf(file("秋叶原物语.avi"))).single().isSeries)
    }

    @Test
    fun aLabelThatIsTheWholeNameStaysTheName() {
        // `[NHK.映像的世纪].第01集` — after the label there is only the instalment.
        val c = cards(
            "[NHK.映像的世纪].第01集.20世纪的开幕.avi",
            "[NHK.映像的世纪].第02集.大量杀戮的完成.avi",
            "[NHK.沸腾都市].第01集.迪拜.avi",
        )
        assertEquals(c.toString(), 2, c.size)
    }
}
