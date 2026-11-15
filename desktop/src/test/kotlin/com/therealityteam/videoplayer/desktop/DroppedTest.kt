package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.desktop.ui.shell.Dropped
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** What files dragged onto the window amount to. */
class DroppedTest {

    @Test
    fun `videos, subtitles and folders are told apart`() {
        val dir = Files.createTempDirectory("aura-drop").toFile()
        fun f(name: String) = File(dir, name).apply { writeText("x") }
        val films = File(dir, "Films").apply { mkdirs() }
        val dropped = Dropped.of(
            listOf(
                f("b.mkv"), f("A.MP4"), f("notes.txt"), f("A.chs.srt"), f("A.ass"),
                // VobSub: the .idx names its .sub, which is not a subtitle of its own.
                f("Dunkirk.idx"), f("Dunkirk.sub"),
                // A lone .sub (MicroDVD) is one.
                f("Old.sub"),
                films,
            ),
        )
        assertEquals(listOf("A.MP4", "b.mkv"), dropped.videos.map { it.name })
        assertEquals(listOf("A.chs.srt", "A.ass", "Dunkirk.idx", "Old.sub"), dropped.subtitles.map { it.name })
        assertEquals(listOf(films), dropped.folders)
        assertEquals(true, Dropped.of(listOf(f("readme.md"))).isEmpty)
    }
}
