package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaJson
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.json.JSONArray
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Times LibraryGrouping.group over a real library dump (pass -Daura.library=/path/to/json).
 * Runs as a no-op without the property, so ordinary test runs are unaffected.
 */
class GroupingBenchTest {
    @Test
    fun measure() {
        val path = System.getProperty("aura.library") ?: return
        val arr = JSONArray(File(path).readText())
        // A dump may open with the credits header (see CreditsTable); the items follow it.
        var credits: com.therealityteam.videoplayer.data.model.CreditsTable? = null
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (com.therealityteam.videoplayer.data.model.CreditsTable.isHeader(o)) {
                credits = com.therealityteam.videoplayer.data.model.CreditsTable.read(o)
                null
            } else {
                MediaJson.item(o, credits)
            }
        }
        repeat(3) { round ->
            val ms = measureTimeMillis { LibraryGrouping.group(items) }
            println("GROUPBENCH round=$round n=${items.size} ms=$ms")
        }
    }
}
