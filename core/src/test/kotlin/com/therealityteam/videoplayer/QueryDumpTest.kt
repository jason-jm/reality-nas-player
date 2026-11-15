package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AnimeParse
import com.therealityteam.videoplayer.data.scrape.AnimeQueries
import com.therealityteam.videoplayer.data.scrape.ContentClassifier
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import org.junit.Test
import java.io.File

/**
 * Dev tool (see `eval/README.md`): for every path in a list, the ORDERED search queries the
 * anime path would try, plus the season / content kind read from the path — so a query-builder
 * change can be judged against the whole library offline, before any API call.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*QueryDumpTest*' \
 *       -Daura.queries.in=/path/paths.txt -Daura.queries.out=/path/queries.tsv
 */
class QueryDumpTest {

    @Test
    fun dumpQueries() {
        val inPath = System.getProperty("aura.queries.in") ?: return
        val outPath = System.getProperty("aura.queries.out") ?: return
        File(outPath).printWriter().use { w ->
            w.println("queries\tseason\tepisode\tkind\tclass\tanimeTitle\tparsedTitle\tpath\tcandidates\tfolderTitles")
            File(inPath).readLines().filter { it.isNotBlank() }.forEach { path ->
                val fileName = path.substringAfterLast('/')
                val parsed = FilenameParser.parseFromPath(path)
                val anime = AnimeParse.parse(fileName)
                val queries = runCatching { AnimeQueries.build(path, parsed, anime) }.getOrElse { listOf("!! ${it.message}") }
                val season = AnimeQueries.seasonOf(path) ?: parsed.season
                // The unfiltered candidate list and the folder titles, for chasing an ordering question.
                val cands = runCatching { AnimeQueries.candidates(path, parsed, anime) }.getOrElse { emptyList() }
                val folders = path.replace('\\', '/').split('/').dropLast(1).takeLast(2)
                    .map { "$it => " + (FilenameParser.parse(it).title) + " / shelf=" + FilenameParser.isShelfFolder(it) + " / fansub=" + FilenameParser.fansubTitle(it) }
                w.println(
                    listOf(
                        queries.joinToString(" || "), season ?: "",
                        anime.episode ?: parsed.episode ?: "", anime.contentKind,
                        ContentClassifier.classify(fileName, path), anime.title ?: "", parsed.title, path,
                        cands.joinToString(" || "), folders.joinToString(" || "),
                    ).joinToString("\t"),
                )
            }
        }
    }
}
