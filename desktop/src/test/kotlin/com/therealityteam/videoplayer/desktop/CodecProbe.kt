package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.desktop.platform.DesktopCore
import com.therealityteam.videoplayer.desktop.player.MediaOpen
import com.therealityteam.videoplayer.desktop.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.mpv.MPVHandle
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * Which codecs the bundled mpv decodes: every file in `-Daura.codecs=<dir>` played for a moment,
 * one after another in one player, reporting whether pictures came out — and what the player
 * told the viewer when they did not.
 *
 *   ./gradlew -p desktop test --tests '*CodecProbe*' -Daura.codecs=/dir/of/clips -Daura.mpv.opts=ao=null -i
 */
class CodecProbe {

    @Test
    fun everyCodec() {
        val dir = System.getProperty("aura.codecs")?.let(::File)
        assumeTrue("set -Daura.codecs", dir != null)
        DesktopCore.install()
        val graph = AppGraph(Files.createTempDirectory("aura-codecs").toFile())
        val player = PlayerController(graph, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        runBlocking {
            withContext(Dispatchers.Main) {
                val mp = player.player
                mp.javaClass.getMethod("createRenderContext\$mediamp_mpv").invoke(mp)
                // What this libavcodec can decode at all, and which containers libavformat reads.
                val handle = mp.impl as MPVHandle
                val n = runCatching { handle.getPropertyInt("decoder-list/count") }.getOrDefault(0)
                val decoders = (0 until n).mapNotNull { i -> runCatching { handle.getPropertyString("decoder-list/$i/codec") }.getOrNull() }.distinct().sorted()
                println("CODEC decoders (${decoders.size}): ${decoders.joinToString(" ")}")
                println("CODEC demuxers: ${runCatching { handle.getPropertyString("demuxer-lavf-list") }.getOrNull()}")
                for (f in dir!!.listFiles()!!.filter { it.isFile && it.extension != "sh" }.sortedBy { it.name }) {
                    val item = MediaOpen.adHocItem(f.absolutePath)
                    player.open(item, listOf(item), startMs = 0, title = f.name)
                    withTimeoutOrNull(15_000) { mp.state.first { it.mediaStatus == MediaStatus.Ready || it.mediaStatus is MediaStatus.Error } }
                    delay(System.getProperty("aura.codecs.wait")?.toLongOrNull() ?: 5_000)
                    val h = mp.impl as MPVHandle
                    fun str(p: String) = runCatching { h.getPropertyString(p) }.getOrNull()
                    println(
                        "CODEC ${f.name}: format=${str("video-format")} codec=${str("video-codec")} size=${str("video-params/w")}x${str("video-params/h")} " +
                            "hw=${str("hwdec-current")} pos=${mp.currentPositionMillis.value} error=${player.error} noPicture=${player.noPicture}",
                    )
                }
                player.dispose()
            }
        }
    }
}
