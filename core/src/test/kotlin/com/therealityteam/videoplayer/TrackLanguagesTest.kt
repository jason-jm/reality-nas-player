package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.AudioChoice
import com.therealityteam.videoplayer.data.playback.MediaTrack
import com.therealityteam.videoplayer.data.playback.SubtitleChoice
import com.therealityteam.videoplayer.data.playback.TrackMemory
import com.therealityteam.videoplayer.data.playback.TrackPreference
import com.therealityteam.videoplayer.data.playback.TrackPreference.Lang
import com.therealityteam.videoplayer.data.subtitle.SubtitleLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Tracks in every language Aura speaks: read by tag and by name, chosen by preference and by 自动. */
class TrackLanguagesTest {

    private fun t(id: String, name: String, language: String? = null, selected: Boolean = false) =
        MediaTrack(id, name, language = language, name = name, selected = selected)

    @Test fun `tags and names in other languages`() {
        assertEquals(setOf(Lang.GERMAN), TrackPreference.langsOf(t("1", "Deutsch")))
        assertEquals(setOf(Lang.GERMAN), TrackPreference.langsOf(t("1", "Track 2", "ger")))
        assertEquals(setOf(Lang.FRENCH), TrackPreference.langsOf(t("1", "VOSTFR")))
        assertEquals(setOf(Lang.SPANISH), TrackPreference.langsOf(t("1", "Castellano", "spa")))
        assertEquals(setOf(Lang.NORWEGIAN), TrackPreference.langsOf(t("1", "Norsk", "nor")))
        assertEquals(setOf(Lang.RUSSIAN), TrackPreference.langsOf(t("1", "Русский")))
        assertEquals(setOf(Lang.KOREAN), TrackPreference.langsOf(t("1", "한국어", "kor")))
        // A code in a name is a word: "Comentario de director" is not German.
        assertEquals(emptySet<Lang>(), TrackPreference.langsOf(t("1", "Comentario de director")))
    }

    @Test fun `a preference for any language picks its track`() {
        val subs = listOf(t("en", "English", "eng"), t("de", "Deutsch", "ger"), t("fr", "Français", "fre"))
        assertEquals(TrackMemory.Subtitle.Select("de"), TrackPreference.subtitle(SubtitleChoice.GERMAN, subs))
        assertEquals(TrackMemory.Subtitle.Select("fr"), TrackPreference.subtitle(SubtitleChoice.FRENCH, subs))
        val sound = listOf(t("a1", "English", "eng", selected = true), t("a2", "Italiano", "ita"))
        assertEquals("a2", TrackPreference.audio(AudioChoice.ITALIAN, sound))
    }

    @Test fun `自动 gives a German reader German subtitles, but not over a German dub`() {
        val de = Locale.GERMANY
        val subs = listOf(t("en", "English", "eng"), t("de", "Deutsch", "ger"))
        val original = listOf(t("a1", "English", "eng", selected = true), t("a2", "Deutsch", "ger"))
        val dubbed = listOf(t("a1", "English", "eng"), t("a2", "Deutsch", "ger", selected = true))
        assertEquals(TrackMemory.Subtitle.Select("de"), TrackPreference.subtitle(SubtitleChoice.AUTO, subs, de, sound = original))
        assertNull(TrackPreference.subtitle(SubtitleChoice.AUTO, subs, de, sound = dubbed))
        // A Chinese reader's 自动 is as it was: Chinese subtitles whatever the sound.
        val zhSubs = listOf(t("s", "简体中文", "chi"), t("e", "English", "eng"))
        assertEquals(TrackMemory.Subtitle.Select("s"), TrackPreference.subtitle(SubtitleChoice.AUTO, zhSubs, Locale.SIMPLIFIED_CHINESE, sound = dubbed))
    }

    @Test fun `an untagged subtitle's alphabet says its language`() {
        val ru = List(20) { "Привет, как у тебя дела сегодня?" }
        assertEquals(listOf(Lang.RUSSIAN), SubtitleLanguage.of(ru))
        val ko = List(20) { "안녕하세요, 오늘 어떻게 지내요?" }
        assertEquals(listOf(Lang.KOREAN), SubtitleLanguage.of(ko))
        val th = List(20) { "สวัสดี คุณสบายดีไหม" }
        assertEquals(listOf(Lang.THAI), SubtitleLanguage.of(th))
    }
}
