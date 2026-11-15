package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.desktop.platform.DesktopCore
import com.therealityteam.videoplayer.desktop.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.openani.mediamp.mpv.MPVHandle
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The audio track chosen for a file last time, chosen again when the file is slow to open — a
 * share over a weak link. `-Daura.trackmem=<address of a file with two audio tracks>`; a server
 * that waits before every answer makes the slowness (scratchpad slow_server.py).
 *
 *   ./gradlew -p desktop test --tests '*TrackMemoryProbe*' -Daura.trackmem=http://127.0.0.1:8095/two-audio.mkv -Daura.mpv.opts=ao=null -i
 */
class TrackMemoryProbe {

    @Test
    fun rememberedAudioOnASlowSource() {
        val address = System.getProperty("aura.trackmem")
        assumeTrue("set -Daura.trackmem", address != null)
        DesktopCore.install()
        val graph = AppGraph(Files.createTempDirectory("aura-trackmem").toFile())
        // Last time, the second audio track (mpv's id 2) was chosen for this file.
        val item = MediaItem(id = "slow1", sourceId = "s", path = address!!, fileName = "two-audio.mkv", title = "Two Audio", audioTrackId = "2")
        graph.media.upsertAll(listOf(item))
        val player = PlayerController(graph, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        runBlocking {
            withContext(Dispatchers.Main) {
                val mp = player.player
                mp.javaClass.getMethod("createRenderContext\$mediamp_mpv").invoke(mp)
                val t0 = System.currentTimeMillis()
                player.open(item, listOf(item), startMs = 0, title = "Two Audio")
                delay(System.getProperty("aura.trackmem.wait")?.toLongOrNull() ?: 12_000)
                val aid = (mp.impl as MPVHandle).getPropertyString("aid")
                println("TRACKMEM after ${System.currentTimeMillis() - t0} ms: aid=$aid, tracks=${player.audioTracks.map { "${it.id}:${it.language}${if (it.selected) "*" else ""}" }}")
                player.dispose()
                assertEquals("2", aid, "the remembered audio track")
            }
        }
    }
}
