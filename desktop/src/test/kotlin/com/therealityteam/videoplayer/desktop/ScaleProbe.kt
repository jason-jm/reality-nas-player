package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.desktop.platform.DesktopCore
import com.therealityteam.videoplayer.ui.library.LibSection
import com.therealityteam.videoplayer.ui.library.LibSort
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * The desktop app over a real library's size: a data folder holding the headset's library.json
 * (the evaluation snapshot — 9,597 files), timed from start to a live wall, a searchable one, and
 * a search.
 *
 *   ./gradlew -p desktop test --tests '*ScaleProbe*' -Daura.scale.data=/path/with/aura/library.json -i
 */
class ScaleProbe {

    @Test
    fun realLibrary() {
        val dir = System.getProperty("aura.scale.data")?.let(::File)
        assumeTrue("set -Daura.scale.data", dir != null)
        DesktopCore.install()
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        val graph = AppGraph(dir!!)
        runBlocking {
            withTimeout(120_000) { graph.media.loaded.first { it } }
            println("SCALE library read: ${graph.media.all().size} files at ${ms()} ms")
            val groups = withTimeout(120_000) { graph.library.groups.first { it.isNotEmpty() } }
            println("SCALE grouped: ${groups.size} cards at ${ms()} ms")
            withTimeout(120_000) { graph.library.wallLive.first { it } }
            println("SCALE wall live at ${ms()} ms")
            val searchable = withTimeout(180_000) { graph.library.searchableGroups.first { it.size == groups.size } }
            println("SCALE searchable (romanised): ${searchable.size} at ${ms()} ms")
            for (q in listOf("frieren", "zsdfll", "葬送", "gundam", "dune")) {
                val s0 = System.nanoTime()
                val hits = LibraryGrouping.filter(searchable, LibSection.ALL, q, LibSort.RECENT, false)
                println("SCALE search '$q': ${hits.size} cards in ${(System.nanoTime() - s0) / 1000} µs — ${hits.take(3).map { it.title }}")
            }
            for (section in LibSection.entries) {
                val s0 = System.nanoTime()
                val n = LibraryGrouping.filter(searchable, section, "", LibSort.RATING, false).size
                println("SCALE shelf $section: $n cards, sorted by rating in ${(System.nanoTime() - s0) / 1000} µs")
            }
        }
    }
}
