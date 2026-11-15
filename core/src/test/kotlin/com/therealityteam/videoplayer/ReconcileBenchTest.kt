package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.CreditsTable
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaJson
import com.therealityteam.videoplayer.data.scrape.ContentClassifier
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.FolderReconcile
import org.json.JSONArray
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * What folder reconciliation costs, on a real library.
 *
 * Measured on the device it was 22.3 seconds over 9,597 items — every scrape, however few files
 * that scrape actually looked at. This is the same work on a JVM, split from the writes that follow
 * it, so the cost can be attributed before anything is changed.
 */
class ReconcileBenchTest {

    @Test
    fun planningTheWholeLibrary() {
        val path = System.getProperty("aura.library") ?: return
        val arr = JSONArray(File(path).readText())
        var credits: CreditsTable? = null
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (CreditsTable.isHeader(o)) { credits = CreditsTable.read(o); null } else MediaJson.item(o, credits)
        }
        println("RECONBENCH ${items.size} items")

        // Same answer, scoped or not — on a real library, not a fixture.
        val full = FolderReconcile.plan(items).associateBy { it.id }
        val everything = FolderReconcile.plan(items, items.mapTo(HashSet()) { it.id }).associateBy { it.id }
        check(full == everything) { "scoping to every id changed the answer" }
        listOf("/Anime/", "/Documentary/", "/Movies/").forEach { shelf ->
            val touched = items.filter { shelf in it.path }.mapTo(HashSet()) { it.id }
            val scoped = FolderReconcile.plan(items, touched).associateBy { it.id }
            scoped.forEach { (id, item) ->
                check(full[id] == item) { "scoped $shelf decided differently for $id" }
            }
            // And nothing outside the touched folders may be rewritten.
            var ms = 0L
            repeat(3) {
                FilenameParser.forgetParses(); ContentClassifier.forgetClassifications()
                ms += kotlin.system.measureTimeMillis { FolderReconcile.plan(items, touched) }
            }
            println(
                "RECONBENCH scoped to $shelf: ${touched.size} touched ids -> planned ${scoped.size} " +
                    "(full planned ${full.size}), all agreeing, ${ms / 3}ms",
            )
        }
        repeat(3) { round ->
            FilenameParser.forgetParses()
            ContentClassifier.forgetClassifications()
            var planned = 0
            val cold = measureTimeMillis { planned = FolderReconcile.plan(items).size }
            val warm = measureTimeMillis { FolderReconcile.plan(items) }
            println("RECONBENCH round=$round cold=${cold}ms warm=${warm}ms planned=$planned of ${items.size}")
        }
    }
}
