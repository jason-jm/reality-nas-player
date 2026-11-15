package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.desktop.platform.DesktopCore
import com.therealityteam.videoplayer.ui.library.ScanState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A source added while another is still being scanned and matched is scanned after it — not
 * saved and forgotten, as it was.
 */
class SourceQueueTest {

    @Test
    fun `a source added during a scan is scanned when that one ends`() = runBlocking {
        DesktopCore.install()
        val root = Files.createTempDirectory("aura-queue").toFile()
        val graph = AppGraph(File(root, "data"))
        graph.settings.update { it.copy(offlineScrape = true) }
        fun folder(name: String, n: Int) = File(root, name).apply {
            mkdirs()
            repeat(n) { i -> File(this, "$name Clip ${i + 1}.mkv").writeBytes(ByteArray(2048)) }
        }
        val big = folder("Big", 400)
        val small = folder("Small", 3)
        fun source(dir: File) = MediaSourceConfig("local:${dir.path}", SourceType.LOCAL, dir.name, dir.path, addedAt = 1)

        graph.library.addSource(source(big))
        withTimeout(10_000) { graph.library.taskProgress.first { it != null } }
        graph.library.addSource(source(small))
        assertTrue(graph.library.taskProgress.value != null, "the first scan is still running when the second source is added")

        withTimeout(120_000) {
            while (graph.media.all().size < 403 || graph.library.taskProgress.value != null) delay(100)
        }
        val status = graph.library.scanStatus.value
        assertEquals(ScanState.Done::class, status[source(small).id]?.let { it::class }, "the second source was scanned: $status")
        assertEquals(3, graph.media.all().count { it.path.startsWith(small.path) })
    }
}
