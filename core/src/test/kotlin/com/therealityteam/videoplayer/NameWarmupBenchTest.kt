package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.CreditsTable
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaJson
import com.therealityteam.videoplayer.data.scrape.ContentClassifier
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.ui.library.EpisodeNaming
import org.json.JSONArray
import org.junit.Test
import java.io.File

/**
 * Dev tool: what NameWarmup's reading of every name costs, step by step, single-threaded on the JVM,
 * over a device's library.json — the first two seconds of a regroup on the headset.
 *
 *   ./gradlew :core:test --tests '*NameWarmupBenchTest*' -Daura.library.json=~/aura-eval/<run>/library.json
 */
class NameWarmupBenchTest {
    @Test fun bench() {
        val path = System.getProperty("aura.library.json") ?: return
        var credits: CreditsTable? = null
        val arr = JSONArray(File(path).readText())
        val items: List<MediaItem> = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (CreditsTable.isHeader(o)) { credits = CreditsTable.read(o); null } else MediaJson.item(o, credits)
        }
        fun step(name: String, work: (MediaItem) -> Unit) {
            val t = System.nanoTime()
            items.forEach(work)
            println("WARMUP $name: ${(System.nanoTime() - t) / 1_000_000} ms for ${items.size} items")
        }
        repeat(2) { round ->
            println("WARMUP round $round (caches ${if (round == 0) "cold" else "warm"})")
            step("FilenameParser.parse") { FilenameParser.parse(it.fileName) }
            step("FilenameParser.parseFromPath") { FilenameParser.parseFromPath(it.path) }
            step("ContentClassifier.classify") { ContentClassifier.classify(it.fileName, it.path) }
            step("EpisodeNaming.parse") { EpisodeNaming.parse(it) }
            step("isShelfFolder") { item -> item.path.split('/').dropLast(1).forEach { if (it.isNotEmpty()) FilenameParser.isShelfFolder(it) } }
        }
    }
}
