package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AniListClient
import com.therealityteam.videoplayer.data.scrape.AnimeMatch
import com.therealityteam.videoplayer.data.scrape.AnimeParse
import com.therealityteam.videoplayer.data.scrape.AnimeQueries
import com.therealityteam.videoplayer.data.scrape.ContentClassifier
import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.MetaResult
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Dev tool: the anime files whose FILE names a form (Anitomy's type: TV, OVA, ONA) while no folder
 * does, and what AniList's pick makes of each with and without that form — the engine's query loop,
 * in order, first accepted hit. Hits AniList (throttled); run it in the background.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*AnimeFormProbeTest*' \
 *       -Daura.library.tsv=/path/library.tsv -Daura.animeform.out=/path/out.tsv [-Daura.animeform.dry=1]
 */
class AnimeFormProbeTest {

    @Test fun probe() {
        val inPath = System.getProperty("aura.library.tsv") ?: return
        val outPath = System.getProperty("aura.animeform.out") ?: return
        val dry = System.getProperty("aura.animeform.dry") != null
        val lines = File(inPath).readLines()
        val header = lines.first().split('\t')
        fun col(row: List<String>, name: String) = row.getOrElse(header.indexOf(name)) { "" }
        data class Ask(val queries: List<String>, val season: Int?, val wantMovie: Boolean, val fileForm: String, val filmNumber: Int?)
        val asks = LinkedHashMap<Ask, MutableList<List<String>>>()
        lines.drop(1).map { it.split('\t') }.forEach { row ->
            val path = col(row, "path")
            val fileName = path.substringAfterLast('/')
            val anime = AnimeParse.parse(fileName)
            if (!(anime.isAnimeLikely || FilenameParser.isUnderAnimeRoot(path))) return@forEach
            if (AnimeQueries.formOfPath(path) != null) return@forEach
            val fileForm = AnimeQueries.formOfType(anime.type)?.takeUnless { ContentClassifier.isStrongExtra(fileName) } ?: return@forEach
            val parsed = FilenameParser.parseFromPath(path)
            val season = AnimeQueries.seasonOf(path) ?: parsed.season
            val wantMovie = anime.contentKind == ContentKind.MOVIE || ContentClassifier.classify(fileName, path) == ContentKind.MOVIE
            val filmNumber = if (season == null && ContentClassifier.classify(fileName, path) != ContentKind.EXTRA) anime.episode ?: parsed.episode else null
            val queries = AnimeQueries.build(path, parsed, anime)
            asks.getOrPut(Ask(queries, season, wantMovie, fileForm, filmNumber)) { mutableListOf() } += row
        }
        val client = AniListClient()
        fun loop(a: Ask, form: String?): MetaResult? {
            val ownName = a.queries.firstOrNull()
            var best: MetaResult? = null
            for (q in a.queries) {
                val hit = AnimeMatch.pick(q, runBlocking { client.search(q) }, a.season, a.wantMovie, form, a.filmNumber) ?: continue
                if (ownName != null && q != ownName && AnimeMatch.namesAnotherWork(ownName, hit)) continue
                if (!a.wantMovie || hit.kind == MediaKind.MOVIE) { best = hit; break }
                if (best == null) best = hit
            }
            return best
        }
        var changed = 0
        File(outPath).printWriter().use { w ->
            w.println("form\tfiles\tsnapshot\tbefore\tafter\tchanged\tqueries\texample")
            asks.forEach { (a, rows) ->
                val snap = rows.map { col(it, "collectionKey") }.toSet().joinToString(",")
                if (dry) { w.println(listOf(a.fileForm, rows.size, snap, "", "", "", a.queries.joinToString(" || "), col(rows.first(), "path")).joinToString("\t")); return@forEach }
                val before = loop(a, null)
                val after = loop(a, a.fileForm)
                val diff = before?.providerId != after?.providerId
                if (diff) changed++
                fun f(m: MetaResult?) = m?.let { "${it.providerId} ${it.title} (${it.kind} ${it.year})" } ?: ""
                w.println(listOf(a.fileForm, rows.size, snap, f(before), f(after), if (diff) "CHANGED" else "",
                    a.queries.joinToString(" || "), col(rows.first(), "path")).joinToString("\t"))
            }
        }
        println("ANIMEFORM ${asks.size} asks, $changed changed")
    }
}
