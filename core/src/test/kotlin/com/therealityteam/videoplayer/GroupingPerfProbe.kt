package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import com.therealityteam.videoplayer.ui.library.LibrarySort
import com.therealityteam.videoplayer.ui.library.LibSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * How long the wall's grouping takes over a real library — `-Daura.perf.dir=<dir holding aura/library.json>`
 * (a headset's, pulled with `adb exec-out run-as … cat files/aura/library.json`); with `-Daura.jfr=<file>` the
 * test JVM records a profile of it (core/build.gradle.kts).
 */
class GroupingPerfProbe {

    @Test fun group() {
        val dir = System.getProperty("aura.perf.dir")?.let(::File) ?: return
        val repo = JsonMediaRepository(dir)
        val items = runBlocking { repo.loaded.first { it }; repo.items.value }
        println("PERF ${items.size} items")
        if (System.getProperty("aura.perf.warm") != null) {
            val w0 = System.nanoTime()
            runBlocking { com.therealityteam.videoplayer.data.scrape.NameWarmup.warm(items) }
            println("PERF warm-up ${(System.nanoTime() - w0) / 1_000_000} ms on ${Runtime.getRuntime().availableProcessors()} cores")
        }
        var first: List<String>? = null
        repeat(5) { round ->
            val t0 = System.nanoTime()
            val groups = LibraryGrouping.group(items)
            val t1 = System.nanoTime()
            val out = LibraryGrouping.romanized(groups) { null }
            val t2 = System.nanoTime()
            out.forEach { it.shownCount }
            val t3 = System.nanoTime()
            LibrarySort.apply(groups, LibSort.TITLE); LibrarySort.apply(out, LibSort.TITLE)
            val t4 = System.nanoTime()
            // What the wall shows, card by card — the same from a cold start as from the memos.
            val shown = out.map { "${it.key}|${it.title}|${it.count}|${it.shownCount}|${it.isSeries}|${it.items.size}" }
            if (first == null) first = shown else check(shown == first) { "round $round differs from round 0" }
            println("PERF round $round: group ${(t1 - t0) / 1_000_000} ms → ${groups.size} cards, romanized ${(t2 - t1) / 1_000_000} ms, " +
                "shownCount ${(t3 - t2) / 1_000_000} ms, title sorts ${(t4 - t3) / 1_000_000} ms")
        }
    }
}
