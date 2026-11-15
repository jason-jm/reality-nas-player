package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.desktop.platform.DesktopCore
import com.therealityteam.videoplayer.desktop.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.openani.mediamp.MediaStatus
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * A WebDAV folder with a password, the way a user adds one (an Alist or a NAS's WebDAV): walked,
 * identified, and a film played straight from the server with the password in the header — and
 * the subtitles beside it found and played the same way. Needs such a server:
 *
 *   ./gradlew -p desktop test --tests '*SmbEndToEndProbe*' -Daura.webdav.e2e=http://127.0.0.1:8089/ -Daura.webdav.passfile=/path/pass.txt \
 *       -Daura.mpv.opts=ao=null -i
 */
class WebDavEndToEndProbe {

    @Test
    fun addScanPlay() {
        val target = System.getProperty("aura.webdav.e2e")
        assumeTrue("set -Daura.webdav.e2e=http://host:port/ and -Daura.webdav.passfile", target != null)
        val password = java.io.File(System.getProperty("aura.webdav.passfile")!!).readText().trim()
        DesktopCore.install()
        org.openani.mediamp.mpv.MPVHandle.setLogHandler { m -> if (m.level <= org.openani.mediamp.mpv.MPVLog.INFO) println("MPV $m") }
        val graph = AppGraph(Files.createTempDirectory("aura-smb-e2e").toFile())
        val config = MediaSourceConfig(
            id = "webdav:e2e", type = SourceType.WEBDAV, name = "Test WebDAV", uri = target!!,
            username = "tester", password = password, addedAt = System.currentTimeMillis(),
        )
        graph.sources.upsert(config)
        val items = runBlocking {
            graph.media.loaded.first { it }
            val t0 = System.currentTimeMillis()
            val found = graph.scanner.discover(config)
            val t1 = System.currentTimeMillis()
            graph.scrape.scrapeAll(graph.media.all()) { _, _ -> }
            println("DAVE2E discovered $found in ${t1 - t0} ms, identified in ${System.currentTimeMillis() - t1} ms")
            graph.media.all()
        }
        items.forEach { println("DAVE2E item ${it.path} -> ${it.title} (${it.year}) ${it.scrapeStatus} tmdb=${it.tmdbId} video=${it.video}") }
        assertTrue(items.isNotEmpty(), "the film found")
        val film = items.first { it.fileName.endsWith(".mkv") }
        val player = PlayerController(graph, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        runBlocking {
            withContext(Dispatchers.Main) {
                val mp = player.player
                mp.javaClass.getMethod("createRenderContext\$mediamp_mpv").invoke(mp)
                val t0 = System.currentTimeMillis()
                player.open(film, listOf(film), startMs = 30_000, title = film.title)
                withTimeout(40_000) { mp.state.first { it.mediaStatus == MediaStatus.Ready || it.mediaStatus is MediaStatus.Error } }
                println("DAVE2E opened over WebDAV in ${System.currentTimeMillis() - t0} ms: ${mp.state.value.mediaStatus} ${player.error ?: ""}")
                delay(3_000)
                println("DAVE2E playing at ${mp.currentPositionMillis.value} ms, playing=${mp.state.value.isPlaying}")
                player.subtitleTracks.forEach { println("DAVE2E subtitle $it") }
                player.mediaInfo().forEach { println("DAVE2E info ${it.first}: ${it.second}") }
                player.skip(60_000)
                delay(2_500)
                println("DAVE2E after a 1-minute jump: ${mp.currentPositionMillis.value} ms")
                val pos = mp.currentPositionMillis.value
                player.dispose()
                assertTrue(pos > 30_000, "played past the start")
            }
        }
    }
}
