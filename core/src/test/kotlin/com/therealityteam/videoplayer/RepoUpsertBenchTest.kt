package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.system.measureTimeMillis

/**
 * What the library's bookkeeping costs a scrape.
 *
 * A scrape upserts every file it finishes, one at a time, and reads items back by id while it works.
 * Both of those are what this times, at the size of a real shelf — the shape of the cost matters more
 * than the absolute milliseconds, so it runs at several sizes and the growth is printed.
 *
 * Runs only with -Daura.bench=1: it is a measurement, not an assertion about this machine.
 */
class RepoUpsertBenchTest {


    private fun item(n: Int) = MediaItem(
        id = "id$n", sourceId = "s", path = "smb://nas/video/Anime/Show $n/Show $n - 01.mkv",
        fileName = "Show $n - 01.mkv", title = "Show $n", kind = MediaKind.EPISODE,
    )

    @Test
    fun upsertingEveryItemOnce() {
        if (System.getProperty("aura.bench") != "1") { println("skipped (pass -Daura.bench=1)"); return }
        listOf(1_000, 3_000, 9_597).forEach { n ->
            val dir = Files.createTempDirectory("aura-bench").toFile()
            val repo = JsonMediaRepository(dir)
            val items = (0 until n).map(::item)
            repo.upsertAll(items)
            repo.flush()

            // The scrape's own pattern: read the item back, then write the finished one.
            val upsertMs = measureTimeMillis { items.forEach { repo.upsert((repo.get(it.id) ?: it).copy(title = "done")) } }
            val getMs = measureTimeMillis { items.forEach { repo.get(it.id) } }
            println("UPSERTBENCH n=$n  upsert+get=${upsertMs}ms  get alone=${getMs}ms  (${"%.2f".format(upsertMs.toDouble() / n)}ms per item)")
            dir.deleteRecursively()
        }
    }
}
