package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.desktop.platform.DesktopCore
import com.therealityteam.videoplayer.desktop.player.FramePosters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Frame posters for unidentified files, made headless: `-Daura.frames=a.mp4,b.mkv` (files the
 * scraper would not know), `-Daura.frames.data=<data dir>` so a snapshot can draw the wall after.
 *
 *   ./gradlew -p desktop test --tests '*FramePosterProbe*' -Daura.frames=… -Daura.frames.data=… -i
 */
class FramePosterProbe {

    @Test
    fun makePosters() {
        val files = System.getProperty("aura.frames")?.split(',')?.map(::File)
        val data = System.getProperty("aura.frames.data")?.let(::File)
        assumeTrue("set -Daura.frames and -Daura.frames.data", files != null && data != null)
        DesktopCore.install()
        val graph = AppGraph(data!!)
        graph.media.upsertAll(files!!.mapIndexed { i, f ->
            MediaItem(
                id = "frame$i", sourceId = "local:${f.parent}", path = f.absolutePath, fileName = f.name,
                title = f.nameWithoutExtension, scrapeStatus = ScrapeStatus.UNMATCHED, addedAt = System.currentTimeMillis() - i,
            )
        })
        runBlocking {
            withTimeout(30_000) { graph.library.groups.first { gs -> gs.sumOf { it.items.size } >= files.size } }
            val t0 = System.currentTimeMillis()
            val made = FramePosters(graph).fill()
            println("FRAMES made $made in ${System.currentTimeMillis() - t0} ms")
            graph.media.all().forEach { println("FRAMES ${it.fileName}: poster=${it.posterPath} generated=${it.posterGenerated} colour=${it.dominantColor?.let { c -> "#%06x".format(c and 0xFFFFFF) }}") }
            graph.media.flush()
            assertTrue(made >= 1)
        }
    }
}
