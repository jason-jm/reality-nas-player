package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.FilenameParser
import org.junit.Test
import java.io.File

/** How much of a real library never needs asking about. */
class SkipCountProbe {
    @Test fun count() {
        val src = System.getProperty("aura.tsv") ?: return
        val lines = File(src).readLines().drop(1).filter { it.isNotBlank() }
        var skip = 0
        val byRoot = HashMap<String, Int>()
        val examples = mutableListOf<String>()
        lines.forEach { l ->
            val c = l.split('\t')
            val path = c.getOrNull(2) ?: return@forEach
            val name = c.getOrNull(3) ?: return@forEach
            val title = c.getOrNull(4) ?: ""
            if (FilenameParser.nothingToLookUp(path, name, title)) {
                skip++
                val root = path.split('/').getOrNull(4) ?: "?"
                byRoot[root] = (byRoot[root] ?: 0) + 1
                if (examples.size < 6 && root !in examples.map { it.substringBefore(':') }) {
                    examples += "$root: $name"
                }
            }
        }
        println("SKIP ${skip} of ${lines.size} files never asked about (${skip * 100 / lines.size}%)")
        byRoot.entries.sortedByDescending { it.value }.forEach { println("SKIP   ${it.value} in ${it.key}/") }
        examples.forEach { println("SKIP   e.g. $it") }
    }
}
