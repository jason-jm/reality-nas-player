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
 * Dev tool: what AniList's pick makes of a file under the query list a QueryDumpTest run saved
 * BEFORE a change to [AnimeQueries] and under the list the code builds now — the engine's loop,
 * the rest of its inputs as the engine derives them. Input is a QueryDumpTest output.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*AnimeQueryListProbeTest*' \
 *       -Daura.querylist.old=/path/old_queries.tsv -Daura.querylist.out=/path/out.tsv
 */
class AnimeQueryListProbeTest {

    @Test fun probe() {
        val oldPath = System.getProperty("aura.querylist.old") ?: return
        val outPath = System.getProperty("aura.querylist.out") ?: return
        val lines = File(oldPath).readLines()
        val header = lines.first().split('\t')
        val client = AniListClient()
        fun loop(queries: List<String>, season: Int?, wantMovie: Boolean, form: String?, film: Int?): MetaResult? {
            val ownName = queries.firstOrNull()
            var best: MetaResult? = null
            for (q in queries) {
                val hit = AnimeMatch.pick(q, runBlocking { client.search(q) }, season, wantMovie, form, film) ?: continue
                if (ownName != null && q != ownName && AnimeMatch.namesAnotherWork(ownName, hit)) continue
                if (!wantMovie || hit.kind == MediaKind.MOVIE) { best = hit; break }
                if (best == null) best = hit
            }
            return best
        }
        var changed = 0
        File(outPath).printWriter().use { w ->
            w.println("before\tafter\tchanged\tpath")
            lines.drop(1).map { it.split('\t') }.forEach { row ->
                val path = row[header.indexOf("path")]
                val old = row[header.indexOf("queries")].split(" || ").filter { it.isNotBlank() }
                val fileName = path.substringAfterLast('/')
                val anime = AnimeParse.parse(fileName)
                val parsed = FilenameParser.parseFromPath(path)
                val now = AnimeQueries.build(path, parsed, anime)
                if (now == old) return@forEach
                val season = AnimeQueries.seasonOf(path) ?: parsed.season
                val wantMovie = anime.contentKind == ContentKind.MOVIE || ContentClassifier.classify(fileName, path) == ContentKind.MOVIE
                val form = AnimeQueries.formOfPath(path)
                    ?: AnimeQueries.formOfType(anime.type).takeUnless { ContentClassifier.isStrongExtra(fileName) }
                val film = if (season == null && ContentClassifier.classify(fileName, path) != ContentKind.EXTRA) {
                    anime.episode ?: parsed.episode ?: AnimeMatch.romanOrdinal(parsed.title)
                } else null
                val before = loop(old, season, wantMovie, form, film)
                val after = loop(now, season, wantMovie, form, film)
                val diff = before?.providerId != after?.providerId
                if (diff) changed++
                fun f(m: MetaResult?) = m?.let { "${it.providerId} ${it.title} (${it.kind} ${it.year})" } ?: ""
                w.println(listOf(f(before), f(after), if (diff) "CHANGED" else "", path).joinToString("\t"))
            }
        }
        println("QUERYLIST $changed changed")
    }
}
