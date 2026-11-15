package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.scrape.AnimeMatch
import com.therealityteam.videoplayer.data.scrape.MetaResult
import com.therealityteam.videoplayer.data.scrape.StringSimilarity
import org.junit.Test
import java.io.File

/**
 * Dev tool: resolve the library's queries against an OFFLINE anime index, ranked by the REAL
 * [AnimeMatch.pick].
 *
 * The point of the offline index is to answer identification without the network. Whether that is
 * an improvement can only be judged by holding the ranker constant — a naive "first exact name
 * wins" lookup scores far worse than AniList, but that measures the lookup, not the data.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*OfflineIndexProbeTest*' \
 *       -Daura.aodb=~/aura-eval/aodb-index.tsv \
 *       -Daura.queries=~/aura-eval/anime-q3.tsv \
 *       -Daura.aodb.out=~/aura-eval/offline-picked.tsv
 */
class OfflineIndexProbeTest {

    @Test fun resolve() {
        val dbPath = System.getProperty("aura.aodb") ?: return
        val qPath = System.getProperty("aura.queries") ?: return
        val outPath = System.getProperty("aura.aodb.out") ?: return

        // name -> entries. One entry may answer to a dozen names; one name to several entries.
        val byName = HashMap<String, MutableList<MetaResult>>(1 shl 18)
        File(dbPath).useLines { lines ->
            lines.drop(1).forEach { line ->
                val c = line.split('\t')
                if (c.size < 5) return@forEach
                val id = c[0].toIntOrNull() ?: return@forEach
                val names = c[4].split('|').filter { it.isNotBlank() }
                if (names.isEmpty()) return@forEach
                val r = MetaResult(
                    providerId = id,
                    kind = if (c[1] == "MOVIE") MediaKind.MOVIE else MediaKind.SHOW,
                    title = names.first(),
                    romajiTitle = names.getOrNull(1),
                    englishTitle = names.getOrNull(2),
                    synonyms = names.drop(1),
                    year = c[2].toIntOrNull(),
                    // The offline set has no popularity; episode count is the only size signal it
                    // carries, and pick reads popularity last, as a tie-break among equals.
                    popularity = (c[3].toDoubleOrNull() ?: 0.0),
                    format = c[1],
                )
                names.forEach { n ->
                    val k = StringSimilarity.normalizeTitle(n)
                    if (k.length >= 2) byName.getOrPut(k) { mutableListOf() }.add(r)
                }
            }
        }
        println("AODB names=${byName.size}")

        val lines = File(qPath).readLines().filter { it.isNotBlank() }
        val head = lines.first().split('\t')
        fun col(r: List<String>, n: String) = head.indexOf(n).let { if (it < 0) "" else r.getOrElse(it) { "" } }
        var hit = 0
        File(outPath).printWriter().use { w ->
            w.println("path\tid\trank")
            lines.drop(1).map { it.split('\t') }.forEach { r ->
                val queries = col(r, "queries").split(" || ").filter { it.isNotBlank() }
                val season = col(r, "season").toIntOrNull()
                val wantMovie = col(r, "kind") == "MOVIE"
                queries.forEachIndexed { rank, q ->
                    val cands = byName[StringSimilarity.normalizeTitle(q)] ?: return@forEachIndexed
                    val picked = AnimeMatch.pick(q, cands.distinctBy { it.providerId }, season, wantMovie)
                        ?: return@forEachIndexed
                    w.println("${col(r, "path")}\t${picked.providerId}\t$rank")
                    hit++
                    return@forEach
                }
            }
        }
        println("AODB resolved=$hit / ${lines.size - 1}")
    }
}
