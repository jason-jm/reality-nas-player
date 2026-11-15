package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.desktop.platform.DesktopCore
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The whole engine on this machine: scan a real folder and scrape what is in it, as the desktop
 * app would. Off unless asked for — it reads the network and the user's own films:
 *
 *   ./gradlew -p desktop test --tests '*EngineProbeTest*' -Daura.probe.folder=$HOME/Movies -i
 */
class EngineProbeTest {

    @Test
    fun scanAndScrapeAFolder() {
        val folder = System.getProperty("aura.probe.folder")
        assumeTrue("set -Daura.probe.folder to run", folder != null)
        DesktopCore.install()
        val dir = Files.createTempDirectory("aura-desktop-probe").toFile()
        val graph = AppGraph(dir)
        val config = MediaSourceConfig(
            id = "local:$folder", type = SourceType.LOCAL, name = File(folder!!).name, uri = folder,
            addedAt = System.currentTimeMillis(),
        )
        graph.sources.upsert(config)
        val found = runBlocking { graph.scanner.scan(config) { done, total -> if (done == total) println("PROBE scraped $done/$total") } }
        println("PROBE found $found files")
        val cards = LibraryGrouping.group(graph.media.all())
        cards.forEach { c ->
            val first = c.items.first()
            println("PROBE card '${c.title}' ${c.year ?: ""} tmdb=${first.tmdbId} status=${first.scrapeStatus} poster=${first.posterPath != null} files=${c.items.size}")
        }
    }
}
