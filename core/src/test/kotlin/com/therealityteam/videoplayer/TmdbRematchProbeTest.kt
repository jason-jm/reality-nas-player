package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AnimeParse
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.Matcher
import com.therealityteam.videoplayer.data.scrape.MetaResult
import com.therealityteam.videoplayer.data.scrape.ParsedName
import com.therealityteam.videoplayer.data.scrape.TmdbMetadataClient
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Dev tool: every file of a snapshot that the general TMDB path matched, asked again with the
 * matcher as it is now — so a change to [Matcher]'s scoring shows every identity it would move
 * before a device rescrape. Only the file's own question and its TV retry, as the engine asks them
 * first; answers are cached under java.io.tmpdir.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*TmdbRematchProbeTest*' \
 *       -Daura.library.tsv=/path/library.tsv -Daura.rematch.out=/path/out.tsv
 */
class TmdbRematchProbeTest {

    @Test fun probe() {
        val inPath = System.getProperty("aura.library.tsv") ?: return
        val outPath = System.getProperty("aura.rematch.out") ?: return
        val client = TmdbMetadataClient(File(System.getProperty("java.io.tmpdir"), "aura-tmdb-probe"))
        val lines = File(inPath).readLines()
        val header = lines.first().split('\t')
        fun col(row: List<String>, name: String) = row.getOrElse(header.indexOf(name)) { "" }
        fun <T> patient(block: suspend () -> T): T {
            var wait = 2_000L
            while (true) {
                try {
                    return runBlocking { block() }
                } catch (e: com.therealityteam.videoplayer.util.TransientHttpException) {
                    Thread.sleep(wait)
                    wait = (wait * 2).coerceAtMost(60_000L)
                }
            }
        }
        // question -> (the ids the snapshot holds for its files, file count, an example)
        val asked = LinkedHashMap<Triple<String, Boolean, Int?>, Triple<MutableSet<String>, IntArray, String>>()
        lines.drop(1).map { it.split('\t') }.forEach { row ->
            if (col(row, "scrapeStatus") != "MATCHED" || col(row, "tmdbId").isBlank()) return@forEach
            if (col(row, "collectionKey").startsWith("anilist:")) return@forEach
            val path = col(row, "path")
            if (AnimeParse.parse(path.substringAfterLast('/')).isAnimeLikely || FilenameParser.isUnderAnimeRoot(path)) return@forEach
            val parsed = FilenameParser.parseFromPath(path)
            val query = if (!parsed.isEpisode && FilenameParser.isUnderTvRoot(path)) parsed.copy(season = 1) else parsed
            val e = asked.getOrPut(Triple(query.title, query.isEpisode, query.year)) { Triple(mutableSetOf(), IntArray(1), path) }
            e.first += col(row, "tmdbId"); e.second[0]++
        }
        var moved = 0
        File(outPath).printWriter().use { w ->
            w.println("title\tepisode\tyear\tfiles\tsnapshot\tnow\tchanged\texample")
            asked.forEach { (key, v) ->
                val (title, episode, year) = key
                val query = ParsedName(title, year = year, season = if (episode) 1 else null)
                var best: MetaResult? = Matcher.best(query, patient { client.search(query) })
                if (best == null && !episode) best = Matcher.best(query, patient { client.search(query.copy(season = 1)) })
                // A question the snapshot answered by a later step (folder, programme) is not this one's.
                val now = best?.providerId?.toString() ?: ""
                val changed = now.isNotEmpty() && now !in v.first
                if (changed) moved++
                w.println(listOf(title, episode, year ?: "", v.second[0], v.first.joinToString(","),
                    best?.let { "$now ${it.title} (${it.year})" } ?: "", if (changed) "CHANGED" else "", v.third).joinToString("\t"))
            }
        }
        println("REMATCH ${asked.size} questions, $moved moved")
    }
}
