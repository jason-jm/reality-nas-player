package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.ContentClassifier
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.NameWarmup
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * What discovery pays to read the names it finds, on one thread against all of them.
 *
 * buildItem parses the name of every file the walk returns, and did so one at a time on the thread
 * doing the walking. Pass a file of real paths with -Daura.paths=<file>; without one this is a
 * no-op, because synthetic names do not exercise the same branches.
 */
class ScanParseBenchTest {

    @Test
    fun readingEveryNameOnce() {
        val src = System.getProperty("aura.paths") ?: return
        val paths = File(src).readLines().filter { it.isNotBlank() }
        val names = paths.map { it.substringAfterLast('/') to it }
        println("SCANPARSE ${paths.size} paths")

        fun cold() { FilenameParser.forgetParses(); ContentClassifier.forgetClassifications() }

        var serial = 0L
        var warmed = 0L
        repeat(3) { block ->
            // Alternate, so neither arm gets all the JIT.
            val arms = if (block % 2 == 0) listOf(false, true) else listOf(true, false)
            arms.forEach { warm ->
                cold()
                val ms = measureTimeMillis {
                    if (warm) runBlocking { NameWarmup.warmNames(names) }
                    paths.forEach { FilenameParser.parseFromPath(it) }
                }
                if (warm) warmed += ms else serial += ms
            }
        }
        println("SCANPARSE serial=${serial}ms warmed=${warmed}ms saving=${"%.1f".format(100.0 * (serial - warmed) / serial)}%")
    }
}
