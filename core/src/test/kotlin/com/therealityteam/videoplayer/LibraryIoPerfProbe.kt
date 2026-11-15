package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** How long a real library takes to read and to write — `-Daura.perf.dir=<dir holding aura/library.json>`. */
class LibraryIoPerfProbe {

    @Test fun readAndWrite() {
        val src = System.getProperty("aura.perf.dir")?.let(::File) ?: return
        repeat(3) { round ->
            val dir = kotlin.io.path.createTempDirectory("io").toFile()
            File(dir, "aura").mkdirs()
            File(src, "aura/library.json").copyTo(File(dir, "aura/library.json"))
            val t0 = System.nanoTime()
            val repo = JsonMediaRepository(dir)
            val items = runBlocking { repo.loaded.first { it }; repo.all() }
            val t1 = System.nanoTime()
            repo.upsert(items.first().copy(favorite = !items.first().favorite))
            repo.flush()
            val t2 = System.nanoTime()
            System.gc(); Thread.sleep(200); System.gc()
            val rt = Runtime.getRuntime()
            println("IOPERF heap after load: ${(rt.totalMemory() - rt.freeMemory()) / (1 shl 20)} MB")
            println("IOPERF round $round: read ${items.size} items in ${(t1 - t0) / 1_000_000} ms, write ${(t2 - t1) / 1_000_000} ms " +
                "(${File(dir, "aura/library.json").length() / 1024} KB)")
            // What was written reads back as what was read: every item, field for field.
            val again = JsonMediaRepository(dir).let { r -> runBlocking { r.loaded.first { it }; r.all() } }
            val wrote = repo.all()
            check(again == wrote) { "read back differs: ${again.zip(wrote).count { (a, b) -> a != b }} items" }
            println("IOPERF round $round: read back ${again.size} items identical")
            dir.deleteRecursively()
        }
    }
}
