package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.desktop.platform.DesktopCore
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pictures of episodes no database has, made from the videos: `-Daura.stills=<folder of
 * episodes>`, `-Daura.stills.data=<data dir>` so a snapshot can draw the card's page after.
 *
 *   ./gradlew -p desktop test --tests '*FrameStillsProbe*' -Daura.stills=… -Daura.stills.data=… -Daura.mpv.opts=ao=null -i
 */
class FrameStillsProbe {

    @Test
    fun episodePictures() {
        val dir = System.getProperty("aura.stills")?.let(::File)
        val data = System.getProperty("aura.stills.data")?.let(::File)
        assumeTrue("set -Daura.stills and -Daura.stills.data", dir != null && data != null)
        DesktopCore.install()
        val graph = AppGraph(data!!)
        val files = dir!!.listFiles()!!.filter { it.extension == "mp4" }.sortedBy { it.name }
        graph.media.upsertAll(files.mapIndexed { i, f ->
            MediaItem(
                id = "ep$i", sourceId = "local:${f.parent}", path = f.absolutePath, fileName = f.name,
                title = dir.name, kind = MediaKind.EPISODE, episode = i + 1, scrapeStatus = ScrapeStatus.UNMATCHED,
            )
        })
        runBlocking {
            for (item in graph.media.all().sortedBy { it.fileName }) {
                val t0 = System.currentTimeMillis()
                val path = graph.frameStills.still(item)
                println("STILLS ${item.fileName}: $path in ${System.currentTimeMillis() - t0} ms")
                assertTrue(path != null && File(path).length() > 0)
            }
            val t0 = System.currentTimeMillis()
            graph.frameStills.still(graph.media.all().first())
            println("STILLS again (kept): ${System.currentTimeMillis() - t0} ms")
            graph.media.flush()
        }
    }
}
