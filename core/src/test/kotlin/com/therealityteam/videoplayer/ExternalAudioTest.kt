package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.ExternalAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAudioTest {

    private val video = "Let.the.Bullets.Fly.2010.1080p.BluRay.x264-WiKi.mkv"

    @Test fun `a dub beside the film is its sound, named by the tag its name adds`() {
        val siblings = listOf(
            video,
            "Let.the.Bullets.Fly.2010.1080p.BluRay.x264-WiKi.国语.mka",
            "Let.the.Bullets.Fly.2010.1080p.BluRay.x264-WiKi.粤语.ac3",
            "Let.the.Bullets.Fly.2010.1080p.BluRay.x264-WiKi.chs.ass",
            "Let.the.Bullets.Fly.2010.1080p.BluRay.x264-WiKi.nfo",
        )
        val found = ExternalAudio.match(video, siblings)
        assertEquals(listOf("国语", "粤语"), found.map { it.label })
        assertEquals(listOf("zh", "yue"), found.map { it.language })
    }

    @Test fun `a sound file of the film's own name is taken whole, and named by its file`() {
        val found = ExternalAudio.match("Movie.mkv", listOf("Movie.mkv", "Movie.mka", "movie.DTS"))
        assertEquals(listOf("movie.DTS", "Movie.mka"), found.map { it.fileName })
        assertEquals("Movie.mka", found[1].label)
        assertNull(found[1].language)
    }

    @Test fun `another video's sound and a soundtrack album are not the film's`() {
        val found = ExternalAudio.match(
            "Episode 01.mkv",
            listOf("Episode 01.mkv", "Episode 02.mka", "OST - Main Theme.mp3", "Episode 01.mp4", "Episode 010.mka"),
        )
        assertTrue(found.isEmpty())
    }

    @Test fun `a tag says its language in the spellings releases use`() {
        assertEquals("zh", ExternalAudio.languageOf("国配"))
        assertEquals("zh", ExternalAudio.languageOf("Mandarin.DD5.1"))
        assertEquals("zh-TW", ExternalAudio.languageOf("台配"))
        assertEquals("yue", ExternalAudio.languageOf("Cantonese"))
        assertEquals("en", ExternalAudio.languageOf("eng"))
        assertEquals("en", ExternalAudio.languageOf("英国原声"))
        assertEquals("ko", ExternalAudio.languageOf("韩国语"))
        assertEquals("ja", ExternalAudio.languageOf("日语"))
        assertNull(ExternalAudio.languageOf("commentary"))
        assertNull(ExternalAudio.languageOf("5.1"))
    }
}
