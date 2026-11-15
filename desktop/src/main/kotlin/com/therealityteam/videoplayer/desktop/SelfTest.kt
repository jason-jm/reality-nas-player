package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.scrape.TmdbMetadataClient
import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.desktop.player.MediaOpen
import com.therealityteam.videoplayer.desktop.player.PlayerController
import com.therealityteam.videoplayer.ui.library.Romanizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openani.mediamp.MediaStatus
import java.nio.file.Files

/**
 * `Aura --self-test [video]`: the parts a packaged build can lose without anyone noticing — the
 * trimmed Java runtime's TLS (every metadata site is HTTPS), its locale data, ICU, and libmpv with
 * its codecs — exercised without a window, so it runs on a build machine or a locked screen.
 * Prints one line per check and exits non-zero if any failed.
 */
object SelfTest {

    fun run(args: List<String>): Int {
        var failures = 0
        fun check(name: String, block: () -> String) {
            val t0 = System.nanoTime()
            val result = runCatching(block)
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (result.isSuccess) println("SELFTEST ok   $name: ${result.getOrNull()} ($ms ms)")
            else { failures++; println("SELFTEST FAIL $name: ${result.exceptionOrNull()}") }
        }
        check("strings") { Strings.forLanguage("zh-CN")["nav_all"] + " / " + Strings.forLanguage("en")["nav_all"] }
        check("locale data") { java.util.Locale.JAPANESE.getDisplayLanguage(java.util.Locale.SIMPLIFIED_CHINESE) }
        check("icu") { Romanizer.romanize("刀剑神域") ?: error("no romanisation") }
        // AI matching's llama.cpp: the native library for this machine, kept by the jar trim.
        // WebDAV's PROPFIND goes through java.net.http, a module jlink must have kept.
        check("dock progress") { if (com.therealityteam.videoplayer.desktop.platform.DockProgress.supported) "supported" else "not on this system" }
        check("http client") { java.net.http.HttpClient.newHttpClient().version().name }
        check("tls + tmdb") {
            val parsed = com.therealityteam.videoplayer.data.scrape.FilenameParser.parse("Dunkirk.2017.1080p.BluRay.x264.mkv")
            val hits = runBlocking { TmdbMetadataClient(null).search(parsed) }
            "${hits.size} results, first ${hits.firstOrNull()?.title}"
        }
        val video = args.firstOrNull()
        check("mpv") {
            val graph = AppGraph(Files.createTempDirectory("aura-selftest").toFile())
            // Decoded, not heard: a check run on a build machine or at night makes no sound.
            graph.settings.update { it.copy(muted = true) }
            val player = PlayerController(graph, CoroutineScope(SupervisorJob() + Dispatchers.Main))
            runBlocking {
                withContext(Dispatchers.Main) {
                    val mp = player.player
                    mp.javaClass.getMethod("createRenderContext\$mediamp_mpv").invoke(mp)
                    // The FFmpeg it carries: ours has some 470 decoders and FLV, mediamp's 40 and none.
                    val h = mp.impl as org.openani.mediamp.mpv.MPVHandle
                    val decoders = runCatching { h.getPropertyInt("decoder-list/count") }.getOrDefault(0)
                    val flv = runCatching { h.getPropertyString("demuxer-lavf-list") }.getOrNull()?.split(',')?.contains("flv") == true
                    if (decoders < 300 || !flv) error("a cut-down FFmpeg: $decoders decoders, FLV ${if (flv) "read" else "not read"}")
                    if (video == null) return@withContext "libmpv loaded; FFmpeg with $decoders decoders"
                    val item = MediaOpen.adHocItem(video)
                    player.open(item, listOf(item), startMs = 60_000, title = "self-test")
                    withTimeoutOrNull(30_000) { mp.state.first { it.mediaStatus == MediaStatus.Ready || it.mediaStatus is MediaStatus.Error } }
                    delay(2_000)
                    val pos = mp.currentPositionMillis.value
                    val info = player.mediaInfo().joinToString("; ") { "${it.first}=${it.second}" }
                    player.dispose()
                    if (pos <= 60_000) error("not playing (position $pos, ${player.error})")
                    "playing at $pos ms; $info"
                }
            }
        }
        println("SELFTEST ${if (failures == 0) "passed" else "FAILED ($failures)"}")
        return if (failures == 0) 0 else 1
    }
}
