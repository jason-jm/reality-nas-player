package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.junit.Test

/**
 * Dev tool: where the wall's grouping spends its time, on the JVM, over a device dump.
 *
 * The phase marks inside [LibraryGrouping.group] print themselves to logcat on a device; here they
 * go to stdout, so a change can be timed without an install. Absolute numbers are a desktop's, not a
 * headset's — only the shape of the breakdown and a before/after pair mean anything.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*GroupingBenchTest2*' \
 *       -Daura.library.tsv=~/aura-eval/library.tsv -Daura.bench.runs=3
 */
class GroupingBenchTest2 {
    @Test fun bench() {
        val inPath = System.getProperty("aura.library.tsv") ?: return
        val runs = System.getProperty("aura.bench.runs")?.toIntOrNull() ?: 3
        val items = LibraryDumpReader.read(java.io.File(inPath))
        repeat(runs) { i ->
            val t = System.nanoTime()
            val g = LibraryGrouping.group(items)
            println("BENCH run$i ${(System.nanoTime() - t) / 1_000_000}ms -> ${g.size} groups")
        }
    }
}
