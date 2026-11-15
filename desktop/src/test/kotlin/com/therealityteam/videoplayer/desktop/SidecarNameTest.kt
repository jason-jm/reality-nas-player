package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.desktop.player.sharedSidecarStem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What sidecar subtitle files share — dimmed in the list, so what tells them apart stands out. */
class SidecarNameTest {
    @Test
    fun `the shared stem ends at a dot`() {
        val film = "[GRP] Frieren - 01 [1080p][HEVC]"
        assertEquals("$film.", sharedSidecarStem(listOf("$film.chs.ass", "$film.cht.ass", "$film.jpn.srt")))
        assertEquals("X.", sharedSidecarStem(listOf("X.chs.srt", "X.chs&eng.srt")), "the common prefix X.chs is cut back to its last dot")
    }

    @Test
    fun `nothing to compare, nothing dimmed`() {
        assertNull(sharedSidecarStem(listOf("Film.srt")))
        assertNull(sharedSidecarStem(listOf("a.srt", "b.srt")), "no shared stem")
    }
}
