package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.desktop.ui.library.queueTitle
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the player calls each file of a queue: never "Feature" for a film. */
class QueueTitleTest {
    private val s = Strings.forLanguage("en")

    private fun item(file: String, title: String, kind: MediaKind = MediaKind.MOVIE, season: Int? = null, episode: Int? = null) =
        MediaItem(id = file, sourceId = "s", path = "/v/$file", fileName = file, title = title, kind = kind, season = season, episode = episode)

    @Test
    fun `episodes lead with their number, films with their title`() {
        assertEquals("S1 E3 · Frieren", queueTitle(s, item("Frieren.S01E03.mkv", "Frieren", MediaKind.EPISODE, 1, 3)))
        assertEquals("Episode 12 · One Piece", queueTitle(s, item("One Piece - 12.mkv", "One Piece", MediaKind.EPISODE, episode = 12)))
        assertEquals("Dunkirk", queueTitle(s, item("Dunkirk.2017.1080p.mkv", "Dunkirk")))
        assertEquals("Holiday.mov", queueTitle(s, item("Holiday.mov", "")))
    }

    @Test
    fun `an extra keeps its own words`() {
        assertEquals("OVA 02", queueTitle(s, item("Show - OVA 02.mkv", "Show", MediaKind.EPISODE)))
    }
}
