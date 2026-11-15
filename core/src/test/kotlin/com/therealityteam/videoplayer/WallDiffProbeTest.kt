package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaJson
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.json.JSONArray
import org.junit.Test
import java.io.File

/**
 * Dev probe: the cards two library.json files (pulled off a device) group into, and which differ —
 * for a change that should not move the wall. Skipped unless -Daura.wall.a and -Daura.wall.b are set.
 */
class WallDiffProbeTest {

    private fun cards(path: String): Map<String, List<String>> {
        val arr = JSONArray(File(path).readText())
        val items = (1 until arr.length()).map { MediaJson.item(arr.getJSONObject(it)) }
        return LibraryGrouping.group(items).associate { g -> g.key to g.items.map { "${it.fileName} [${g.title}]" } }
    }

    @Test fun diff() {
        val a = cards(System.getProperty("aura.wall.a") ?: return)
        val b = cards(System.getProperty("aura.wall.b") ?: return)
        println("cards: ${a.size} -> ${b.size}")
        System.getProperty("aura.wall.dump")?.let { out -> File(out).writeText(b.entries.sortedBy { it.key }.joinToString("\n") { (k, v) -> k + "\t" + v.sorted().joinToString("|") }) }
        (a.keys - b.keys).forEach { println("- $it ${a.getValue(it).take(3)}") }
        (b.keys - a.keys).forEach { println("+ $it ${b.getValue(it).take(3)}") }
    }
}
