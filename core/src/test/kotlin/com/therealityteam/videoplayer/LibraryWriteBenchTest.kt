package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.CreditsTable
import com.therealityteam.videoplayer.data.model.MediaJson
import org.json.JSONArray
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Where the time goes when the library is written.
 *
 * A scrape rewrites the whole file whenever its upserts go quiet — on device, 16.9 MB in about
 * three seconds, several times a pass. Whether that is worth attacking depends on whether it is the
 * bytes going to disk or the JSON being built, so this separates them.
 */
class LibraryWriteBenchTest {

    @Test
    fun buildingAgainstWriting() {
        val path = System.getProperty("aura.library") ?: return
        val arr = JSONArray(File(path).readText())
        var credits: CreditsTable? = null
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (CreditsTable.isHeader(o)) { credits = CreditsTable.read(o); null } else MediaJson.item(o, credits)
        }
        val out = File.createTempFile("aura-write", ".json")
        repeat(3) { round ->
            val (table, keyOf) = CreditsTable.of(items)
            var text = ""
            val build = measureTimeMillis {
                text = buildString(32 shl 20) {
                    append("[")
                    if (table.size > 0) append(table.header().toString())
                    items.forEachIndexed { i, it ->
                        if (i > 0 || table.size > 0) append(",")
                        append(MediaJson.item(it, keyOf[it.id]).toString())
                    }
                    append("]")
                }
            }
            val write = measureTimeMillis { out.writeText(text) }
            println("WRITEBENCH round=$round build=${build}ms write=${write}ms bytes=${text.length}")
        }
        out.delete()
    }
}
