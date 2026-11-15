package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.scrape.AniListClient
import com.therealityteam.videoplayer.data.scrape.AnimeMatch
import com.therealityteam.videoplayer.data.scrape.AnimeParse
import com.therealityteam.videoplayer.data.scrape.AnimeQueries
import com.therealityteam.videoplayer.data.scrape.ContentClassifier
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.MetaResult
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Dev tool: the anime files that take a film number from a Roman numeral in their own name, and
 * what AniList's pick makes of each with and without it — the engine's query loop, in order.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*AnimeFilmNumberProbeTest*' \
 *       -Daura.library.tsv=/path/library.tsv -Daura.filmnumber.out=/path/out.tsv
 */
class AnimeFilmNumberProbeTest {

    @Test fun probe() {
        val inPath = System.getProperty("aura.library.tsv") ?: return
        val outPath = System.getProperty("aura.filmnumber.out") ?: return
        val lines = File(inPath).readLines()
        val header = lines.first().split('\t')
        fun col(row: List<String>, name: String) = row.getOrElse(header.indexOf(name)) { "" }
        data class Ask(val queries: List<String>, val season: Int?, val wantMovie: Boolean, val form: String?, val number: Int)
        val asks = LinkedHashMap<Ask, MutableList<List<String>>>()
        lines.drop(1).map { it.split('\t') }.forEach { row ->
            val path = col(row, "path")
            val fileName = path.substringAfterLast('/')
            val anime = AnimeParse.parse(fileName)
            if (!(anime.isAnimeLikely || FilenameParser.isUnderAnimeRoot(path))) return@forEach
            val parsed = FilenameParser.parseFromPath(path)
            val season = AnimeQueries.seasonOf(path) ?: parsed.season
            if (season != null || ContentClassifier.classify(fileName, path) == ContentKind.EXTRA) return@forEach
            if ((anime.episode ?: parsed.episode) != null) return@forEach
            val number = AnimeMatch.romanOrdinal(parsed.title) ?: return@forEach
            val wantMovie = anime.contentKind == ContentKind.MOVIE || ContentClassifier.classify(fileName, path) == ContentKind.MOVIE
            val form = AnimeQueries.formOfPath(path)
                ?: AnimeQueries.formOfType(anime.type).takeUnless { ContentClassifier.isStrongExtra(fileName) }
            asks.getOrPut(Ask(AnimeQueries.build(path, parsed, anime), season, wantMovie, form, number)) { mutableListOf() } += row
        }
        val client = AniListClient()
        fun loop(a: Ask, number: Int?): MetaResult? {
            val ownName = a.queries.firstOrNull()
            var best: MetaResult? = null
            for (q in a.queries) {
                val hit = AnimeMatch.pick(q, runBlocking { client.search(q) }, a.season, a.wantMovie, a.form, number) ?: continue
                if (ownName != null && q != ownName && AnimeMatch.namesAnotherWork(ownName, hit)) continue
                if (!a.wantMovie || hit.kind == MediaKind.MOVIE) { best = hit; break }
                if (best == null) best = hit
            }
            return best
        }
        var changed = 0
        File(outPath).printWriter().use { w ->
            w.println("number\tfiles\tsnapshot\tbefore\tafter\tchanged\tqueries\texample")
            asks.forEach { (a, rows) ->
                val before = loop(a, null)
                val after = loop(a, a.number)
                val diff = before?.providerId != after?.providerId
                if (diff) changed++
                fun f(m: MetaResult?) = m?.let { "${it.providerId} ${it.title} (${it.kind} ${it.year})" } ?: ""
                w.println(listOf(a.number, rows.size, rows.map { col(it, "collectionKey") }.toSet().joinToString(","),
                    f(before), f(after), if (diff) "CHANGED" else "", a.queries.joinToString(" || "), col(rows.first(), "path")).joinToString("\t"))
            }
        }
        println("FILMNUMBER ${asks.size} asks, $changed changed")
    }
}
