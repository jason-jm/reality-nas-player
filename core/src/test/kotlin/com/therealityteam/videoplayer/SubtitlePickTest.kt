package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.subtitle.SubtitlePick
import com.therealityteam.videoplayer.data.subtitle.SubtitlePick.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class SubtitlePickTest {

    private val zhCN = Locale.forLanguageTag("zh-CN")
    private val zhTW = Locale.forLanguageTag("zh-TW")
    private val en = Locale.ENGLISH

    // Dunkirk as it sits on disk: two embedded English SRTs (one SDH), a fansub's bilingual ASS
    // with no language tag, and an English VobSub in Subs/.
    private val dunkirk = listOf(
        Candidate("1", "eng", null, null, "subrip"),
        Candidate("2", "eng", "SDH", null, "subrip", hearingImpaired = false),
        Candidate("3", null, null, "Dunkirk.2017.1080p.BluRay.x264-SPARKS.chs&eng-R3.ass", "ass"),
        Candidate("4", "en", null, "Dunkirk.2017.1080p.BluRay.x264-SPARKS.idx", "dvd_subtitle"),
    )

    @Test fun `a Chinese reader gets the bilingual fansub file over the labelled English tracks`() {
        assertEquals("3", SubtitlePick.best(dunkirk, zhCN)?.id)
    }

    @Test fun `an English reader gets English text before English pictures`() {
        assertEquals("1", SubtitlePick.best(dunkirk, en)?.id)
    }

    @Test fun `Traditional readers prefer Traditional, Simplified readers Simplified`() {
        val both = listOf(
            Candidate("s", null, "简体中文", null, "ass"),
            Candidate("t", null, "繁體中文", null, "ass"),
        )
        assertEquals("s", SubtitlePick.best(both, zhCN)?.id)
        assertEquals("t", SubtitlePick.best(both, zhTW)?.id)
    }

    @Test fun `a forced track loses to a full one, and no match means no choice`() {
        val tracks = listOf(
            Candidate("f", "chi", "Forced", null, "subrip", forced = true),
            Candidate("full", "chi", null, null, "subrip"),
        )
        assertEquals("full", SubtitlePick.best(tracks, zhCN)?.id)
        assertNull(SubtitlePick.best(listOf(Candidate("fr", "fre", null, null, "subrip")), zhCN))
    }

    @Test fun `the film's own name says nothing about its subtitles`() {
        val video = "Parasite.2019.KOREAN.1080p.mkv"
        val subs = listOf(
            Candidate("k", null, null, "Parasite.2019.KOREAN.1080p.srt", "subrip"),
            Candidate("c", null, null, "Parasite.2019.KOREAN.1080p.chs.srt", "subrip"),
        )
        assertEquals("c", SubtitlePick.best(subs, zhCN, video)?.id)
        // Nothing but the film's name: no language to go on, so no choice for a Korean reader either.
        assertNull(SubtitlePick.best(listOf(subs[0]), Locale.KOREAN, video))
    }

    @Test fun `a hero is not English`() {
        assertNull(SubtitlePick.best(listOf(Candidate("x", null, null, "英雄.2002.srt", "subrip")), en))
    }

    @Test fun `words inside a name are not language codes`() {
        // "Scene" contains "sc", "Tender" contains "en": neither is a language.
        val t = Candidate("x", null, null, "Opening.Scene.Tender.srt", "subrip")
        assertNull(SubtitlePick.best(listOf(t), zhCN))
    }
}
