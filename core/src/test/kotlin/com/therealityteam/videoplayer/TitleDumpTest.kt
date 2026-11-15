package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.FilenameParser
import org.junit.Test
import java.io.File

/**
 * Dev tool: dumps the title the parser hands the metadata DB for every path in a list, so a
 * parsing change can be judged against the WHOLE library offline — the actual re-scrape costs
 * hours of throttled API calls, and a bad query is visible long before the DB answers it.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*TitleDumpTest*' \
 *       -Daura.titles.in=/path/paths.txt -Daura.titles.out=/path/titles.tsv
 */
class TitleDumpTest {

    @Test
    fun dumpTitles() {
        val inPath = System.getProperty("aura.titles.in") ?: return
        val outPath = System.getProperty("aura.titles.out") ?: return
        File(outPath).printWriter().use { w ->
            w.println("title\tseason\tepisode\tyear\tpath")
            File(inPath).readLines().filter { it.isNotBlank() }.forEach { path ->
                val p = FilenameParser.parseFromPath(path)
                w.println("${p.title}\t${p.season ?: ""}\t${p.episode ?: ""}\t${p.year ?: ""}\t$path")
            }
        }
    }
}
