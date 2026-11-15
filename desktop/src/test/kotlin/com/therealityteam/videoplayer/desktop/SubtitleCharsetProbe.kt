package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
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
 * A Chinese subtitle in GBK — still the common case — beside a video on a share, read right:
 * `-Daura.subcs=<smb address of the video>` whose sidecar holds [EXPECTED] in GBK, and
 * `-Daura.subcs.source=<smb address of the share>`. mpv's own charset guessing (uchardet) is not
 * built into mediamp's libmpv.
 *
 *   ./gradlew -p desktop test --tests '*SubtitleCharsetProbe*' -Daura.subcs=smb://127.0.0.1:4445/video/Charset/… \
 *       -Daura.subcs.source=smb://127.0.0.1:4445/video -Daura.mpv.opts=ao=null -i
 */
class SubtitleCharsetProbe {

    @Test
    fun gbkSidecarOnAShare() {
        val address = System.getProperty("aura.subcs")
        val sourceUri = System.getProperty("aura.subcs.source")
        assumeTrue("set -Daura.subcs and -Daura.subcs.source", address != null && sourceUri != null)
        DesktopCore.install()
        val graph = AppGraph(Files.createTempDirectory("aura-subcs").toFile())
        // -Daura.subcs.user=… -Daura.subcs.passfile=…: a WebDAV server with a password instead of a guest share.
        val user = System.getProperty("aura.subcs.user")
        val source = if (user != null) {
            MediaSourceConfig("smb:cs", SourceType.WEBDAV, "Test DAV", sourceUri!!, username = user,
                password = java.io.File(System.getProperty("aura.subcs.passfile")!!).readText().trim())
        } else MediaSourceConfig("smb:cs", SourceType.SMB, "Test NAS", sourceUri!!, username = "guest", password = "")
        graph.sources.upsert(source)
        val name = address!!.substringAfterLast('/')
        val item = MediaItem(id = "cs1", sourceId = "smb:cs", path = address, fileName = name, title = name.substringBeforeLast('.'))
        graph.media.upsertAll(listOf(item))
        val player = PlayerController(graph, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        runBlocking {
            withContext(Dispatchers.Main) {
                val mp = player.player
                mp.javaClass.getMethod("createRenderContext\$mediamp_mpv").invoke(mp)
                player.open(item, listOf(item), startMs = 2_000, title = item.title)
                delay(5_000)
                val text = (mp.impl as MPVHandle).getPropertyString("sub-text")
                println("SUBCS tracks: ${player.subtitleTracks.map { it.externalFile ?: it.title }}")
                println("SUBCS sub-text: $text")
                player.dispose()
                assertEquals(EXPECTED, text)
            }
        }
    }

    private companion object { const val EXPECTED = "葬送的芙莉莲：勇者辛美尔死后" }
}
