package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.AudioChoice
import com.therealityteam.videoplayer.data.playback.MediaTrack
import com.therealityteam.videoplayer.data.playback.SubtitleChoice
import com.therealityteam.videoplayer.data.playback.TrackMemory
import com.therealityteam.videoplayer.data.playback.TrackPreference
import com.therealityteam.videoplayer.data.playback.TrackPreference.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 首选字幕 / 首选音轨: tracks are read for the languages they hold, whatever their names look like. */
class TrackPreferenceTest {

    private fun file(id: String, name: String, language: String? = null) =
        MediaTrack(id, name, language = language, name = name, external = true)
    private fun stream(id: String, name: String, language: String?) = MediaTrack(id, name, language = language, name = name)

    private val sidecars = listOf(
        file("s0", "[Grp] Show - 01.chs&eng.ass", "chs&eng"),
        file("s1", "[Grp] Show - 01.cht.ass", "cht"),
        file("s2", "[Grp] Show - 01.chs.ass", "chs"),
        file("s3", "[Grp] Show - 01.eng.srt", "eng"),
    )

    @Test fun `a file's name says which languages it holds`() {
        assertEquals(setOf(Lang.SIMPLIFIED, Lang.ENGLISH), TrackPreference.langsOf(sidecars[0]))
        assertEquals(setOf(Lang.TRADITIONAL), TrackPreference.langsOf(sidecars[1]))
        assertEquals(setOf(Lang.SIMPLIFIED), TrackPreference.langsOf(stream("2:0", "简体中文", "chi")))
        assertEquals(setOf(Lang.TRADITIONAL), TrackPreference.langsOf(stream("2:1", "Chinese (Traditional)", "zho")))
        assertEquals(setOf(Lang.CHINESE), TrackPreference.langsOf(stream("2:2", "中文", "chi")))
        assertEquals(setOf(Lang.JAPANESE), TrackPreference.langsOf(stream("1:0", "日本語", "jpn")))
        assertEquals(emptySet<Lang>(), TrackPreference.langsOf(file("x", "movie.srt")))
    }

    /** The Mandalorian's disc, as its tracks read: 27 of them, three Chinese all tagged chi. */
    private val disc = listOf(
        stream("3:0", "English (Forced)", "eng"),
        stream("3:1", "English (SDH)", "eng"),
        stream("3:2", "Chinese (Cantonese)", "chi"),
        stream("3:3", "Chinese (Simplified)", "chi"),
        stream("3:4", "Chinese (Traditional)", "chi"),
        stream("3:5", "Czech", "cze"),
    )

    @Test fun `automatic subtitles follow a Chinese system's script, and anyone else's language`() {
        assertEquals(TrackMemory.Subtitle.Select("3:3"), TrackPreference.subtitle(SubtitleChoice.AUTO, disc, java.util.Locale.SIMPLIFIED_CHINESE))
        assertEquals(TrackMemory.Subtitle.Select("3:4"), TrackPreference.subtitle(SubtitleChoice.AUTO, disc, java.util.Locale.TRADITIONAL_CHINESE))
        assertEquals(TrackMemory.Subtitle.Select("3:4"), TrackPreference.subtitle(SubtitleChoice.AUTO, disc, java.util.Locale.forLanguageTag("zh-Hant-HK")))
        // An English reader: the English subtitles — unless the film is heard in English already.
        assertEquals(TrackMemory.Subtitle.Select("3:1"), TrackPreference.subtitle(SubtitleChoice.AUTO, disc, java.util.Locale.US))
        val english = listOf(MediaTrack("a", "English", language = "eng", selected = true))
        assertNull(TrackPreference.subtitle(SubtitleChoice.AUTO, disc, java.util.Locale.US, sound = english))
        // Nothing Chinese at all: the engine's choice stands.
        assertNull(TrackPreference.subtitle(SubtitleChoice.AUTO, disc.take(2), java.util.Locale.SIMPLIFIED_CHINESE))
    }

    @Test fun `within a language, the whole film before a forced track, the written standard before Cantonese`() {
        assertEquals(TrackMemory.Subtitle.Select("3:1"), TrackPreference.subtitle(SubtitleChoice.ENGLISH, disc))
        // Only untagged Chinese besides the Cantonese: the written standard.
        val untagged = listOf(stream("4:0", "粤语", "chi"), stream("4:1", "中文", "chi"))
        assertEquals(TrackMemory.Subtitle.Select("4:1"), TrackPreference.subtitle(SubtitleChoice.SIMPLIFIED, untagged))
    }

    @Test fun `Chinese sound is the Mandarin before a Cantonese dub, but in Hong Kong the Cantonese`() {
        val sound = listOf(stream("1:0", "Cantonese", "chi"), stream("1:1", "Mandarin", "chi"), stream("1:2", "English", "eng"))
        assertEquals("1:1", TrackPreference.audio(AudioChoice.CHINESE, sound, java.util.Locale.SIMPLIFIED_CHINESE))
        assertEquals("1:0", TrackPreference.audio(AudioChoice.CHINESE, sound, java.util.Locale.forLanguageTag("zh-HK")))
    }

    @Test fun `subtitles take the chosen script first and alone before bilingual, then its neighbours`() {
        assertEquals(TrackMemory.Subtitle.Select("s2"), TrackPreference.subtitle(SubtitleChoice.SIMPLIFIED, sidecars))
        assertEquals(TrackMemory.Subtitle.Select("s1"), TrackPreference.subtitle(SubtitleChoice.TRADITIONAL, sidecars))
        assertEquals(TrackMemory.Subtitle.Select("s3"), TrackPreference.subtitle(SubtitleChoice.ENGLISH, sidecars))
        // No traditional file: the simplified one before nothing.
        assertEquals(TrackMemory.Subtitle.Select("s2"), TrackPreference.subtitle(SubtitleChoice.TRADITIONAL, listOf(sidecars[2], sidecars[3])))
        // Nothing Japanese: the engine's choice stands.
        assertNull(TrackPreference.subtitle(SubtitleChoice.JAPANESE, sidecars))
        assertNull(TrackPreference.subtitle(SubtitleChoice.AUTO, sidecars, java.util.Locale.US, sound = listOf(MediaTrack("a", "English", language = "eng", selected = true))))
        assertEquals(TrackMemory.Subtitle.Off, TrackPreference.subtitle(SubtitleChoice.OFF, sidecars))
        assertNull(TrackPreference.subtitle(SubtitleChoice.OFF, emptyList()))
    }

    @Test fun `sound takes the chosen language, any Chinese for 中文, else the file's default`() {
        val sound = listOf(stream("1:0", "国语", "chi"), stream("1:1", "日语", "jpn"), stream("1:2", "English", "eng"))
        assertEquals("1:1", TrackPreference.audio(AudioChoice.JAPANESE, sound))
        assertEquals("1:0", TrackPreference.audio(AudioChoice.CHINESE, sound))
        assertEquals("1:2", TrackPreference.audio(AudioChoice.ENGLISH, sound))
        assertNull(TrackPreference.audio(AudioChoice.DEFAULT, sound))
        assertNull(TrackPreference.audio(AudioChoice.JAPANESE, listOf(sound[0])))
    }

    @Test fun `one untagged subtitle is read in the language the video's name gives its subtitles`() {
        val one = listOf(stream("3:0", "轨道 1", null))
        val zh = java.util.Locale.SIMPLIFIED_CHINESE
        assertEquals(TrackMemory.Subtitle.Select("3:0"),
            TrackPreference.subtitle(SubtitleChoice.AUTO, one, zh, "[WLGO][Murder_Princess][DVDRIP][BIG5&GB][x264_aac][OVA][01].mkv"))
        assertEquals(TrackMemory.Subtitle.Select("3:0"), TrackPreference.subtitle(SubtitleChoice.AUTO, one, zh, "Show.01.CHS.mkv"))
        // No word in the name, or a name that speaks of another language: left to the engine.
        assertNull(TrackPreference.subtitle(SubtitleChoice.AUTO, one, zh, "Show.01.1080p.mkv"))
        assertNull(TrackPreference.subtitle(SubtitleChoice.AUTO, one, zh, "Show.01.ENG.mkv"))
        // Two untagged: the name cannot say which.
        assertNull(TrackPreference.subtitle(SubtitleChoice.AUTO, one + stream("3:1", "轨道 2", null), zh, "Show.01.CHS.mkv"))
    }

    @Test fun `a sidecar is read by what it adds to the video's name`() {
        val video = "[WLGO][Murder_Princess][DVDRIP][BIG5&GB][x264_aac][OVA][01][F33BD822].mkv"
        val stem = video.substringBeforeLast('.')
        val subs = listOf(stream("s0", "$stem.big5.ass", null), stream("s1", "$stem.gb.ass", null))
        val zh = java.util.Locale.SIMPLIFIED_CHINESE
        assertEquals(TrackMemory.Subtitle.Select("s1"), TrackPreference.subtitle(SubtitleChoice.AUTO, subs, zh, video))
        assertEquals(TrackMemory.Subtitle.Select("s0"), TrackPreference.subtitle(SubtitleChoice.AUTO, subs, java.util.Locale.TRADITIONAL_CHINESE, video))
    }

    @Test fun `默认 opens a Korean or Japanese film on its own language, not the dub the file leads with`() {
        // 공작 (The Spy Gone North), MULTi: French first and flagged, Korean second.
        val multi = listOf(
            MediaTrack("a0", "法语", language = "fr", selected = true),
            MediaTrack("a1", "韩语", language = "ko"),
        )
        assertEquals("a1", TrackPreference.originalSound(multi, "공작"))
        val anime = listOf(MediaTrack("a0", "English", language = "eng", selected = true), MediaTrack("a1", "日本語", language = "jpn"))
        assertEquals("a1", TrackPreference.originalSound(anime, "君の名は。"))
        // Already on it, one track only, nothing in the language, or a title that does not say: the file's own.
        assertNull(TrackPreference.originalSound(multi.map { it.copy(selected = it.id == "a1") }, "공작"))
        assertNull(TrackPreference.originalSound(multi.take(1), "공작"))
        assertNull(TrackPreference.originalSound(listOf(MediaTrack("a0", "法语", language = "fr", selected = true), MediaTrack("a1", "英语", language = "en")), "공작"))
        assertNull(TrackPreference.originalSound(multi, "Le Samouraï"))
        assertNull(TrackPreference.originalSound(multi, "攻殻機動隊"))
        assertNull(TrackPreference.originalSound(multi, null))
    }
}
