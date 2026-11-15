package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.source.connectTo
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
 * A share, the way a user adds one: the source saved, the share walked, the files identified, and
 * one played through the loopback proxy with its subtitles found beside it on the share. Needs an
 * SMB server that may be written by a guest:
 *
 *   ./gradlew -p desktop test --tests '*SmbEndToEndProbe*' -Daura.smb.e2e=127.0.0.1:4445/video \
 *       -Daura.mpv.opts=ao=null -i
 */
class SmbEndToEndProbe {

    @Test
    fun addScanPlay() {
        val target = System.getProperty("aura.smb.e2e")
        assumeTrue("set -Daura.smb.e2e=host[:port]/share", target != null)
        DesktopCore.install()
        org.openani.mediamp.mpv.MPVHandle.setLogHandler { m -> if (m.level <= org.openani.mediamp.mpv.MPVLog.INFO) println("MPV $m") }
        val graph = AppGraph(Files.createTempDirectory("aura-smb-e2e").toFile())
        val config = MediaSourceConfig(
            id = "smb:e2e", type = SourceType.SMB, name = "Test NAS", uri = "smb://$target",
            username = "guest", password = "", addedAt = System.currentTimeMillis(),
        )
        graph.sources.upsert(config)
        val items = runBlocking {
            graph.media.loaded.first { it }
            val t0 = System.currentTimeMillis()
            val found = graph.scanner.discover(config)
            val t1 = System.currentTimeMillis()
            graph.scrape.scrapeAll(graph.media.all()) { _, _ -> }
            println("SMBE2E discovered $found in ${t1 - t0} ms, identified in ${System.currentTimeMillis() - t1} ms")
            graph.media.all()
        }
        items.forEach { println("SMBE2E item ${it.path} -> ${it.title} (${it.year}) ${it.scrapeStatus} tmdb=${it.tmdbId} video=${it.video}") }
        assertTrue(items.size >= 2, "both files found")
        val film = items.first { it.fileName.endsWith(".mkv") }
        // The proxy's own open, done here to see why it fails if it does.
        runCatching {
            val c = com.therealityteam.videoplayer.data.playback.streamingSmbClient()
            val (host, share) = target!!.split('/')
            val conn = c.connectTo(host)
            val d = conn.authenticate(com.hierynomus.smbj.auth.AuthenticationContext("guest", CharArray(0), null)).connectShare(share) as com.hierynomus.smbj.share.DiskShare
            val rel = film.path.substringAfter("/$share/").replace('/', '\\')
            val f = d.openFile(rel, setOf(com.hierynomus.msdtyp.AccessMask.GENERIC_READ), null,
                setOf(com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_READ), com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN, null)
            println("SMBE2E direct open ok: ${f.fileInformation.standardInformation.endOfFile} bytes")
            f.close(); c.close()
        }.onFailure { println("SMBE2E direct open failed: $it") }

        val player = PlayerController(graph, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        runBlocking {
            withContext(Dispatchers.Main) {
                val mp = player.player
                mp.javaClass.getMethod("createRenderContext\$mediamp_mpv").invoke(mp)
                val t0 = System.currentTimeMillis()
                player.open(film, listOf(film), startMs = 30_000, title = film.title)
                withTimeout(40_000) { mp.state.first { it.mediaStatus == MediaStatus.Ready || it.mediaStatus is MediaStatus.Error } }
                println("SMBE2E opened over SMB in ${System.currentTimeMillis() - t0} ms: ${mp.state.value.mediaStatus} ${player.error ?: ""}")
                delay(3_000)
                println("SMBE2E playing at ${mp.currentPositionMillis.value} ms, playing=${mp.state.value.isPlaying}")
                player.subtitleTracks.forEach { println("SMBE2E subtitle $it") }
                player.mediaInfo().forEach { println("SMBE2E info ${it.first}: ${it.second}") }
                player.skip(60_000)
                delay(2_500)
                println("SMBE2E after a 1-minute jump: ${mp.currentPositionMillis.value} ms")
                val pos = mp.currentPositionMillis.value
                player.dispose()
                assertTrue(pos > 30_000, "played past the start")
            }
        }
    }
}
