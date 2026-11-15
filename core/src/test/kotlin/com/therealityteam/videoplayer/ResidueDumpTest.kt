package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.scrape.AnimeMatch
import com.therealityteam.videoplayer.data.scrape.MetaResult
import org.junit.Test
import java.io.File

/**
 * Dev tool: run the REAL [AnimeMatch.namesAnotherWork] over the offline residue corpus, so the
 * Kotlin the app ships and the Python that scored it are held to the same count.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*ResidueDumpTest*' \
 *       -Daura.residue.queries=~/aura-eval/anime-q.tsv \
 *       -Daura.residue.names=~/aura-eval/alias-names.tsv \
 *       -Daura.residue.library=~/aura-eval/library.tsv \
 *       -Daura.residue.out=~/aura-eval/residue-kt.txt
 */
class ResidueDumpTest {

    @Test fun dumpResidue() {
        val q = System.getProperty("aura.residue.queries") ?: return
        val n = System.getProperty("aura.residue.names") ?: return
        val lib = System.getProperty("aura.residue.library") ?: return
        val out = System.getProperty("aura.residue.out") ?: return

        fun rows(f: String): Pair<List<String>, List<List<String>>> {
            val lines = File(f).readLines().filter { it.isNotBlank() }
            return lines.first().split('\t') to lines.drop(1).map { it.split('\t') }
        }
        val (libHead, libRows) = rows(lib)
        val keyOf = libRows.associate { it[libHead.indexOf("path")] to it.getOrElse(libHead.indexOf("collectionKey")) { "" } }
        val statusOf = libRows.associate { it[libHead.indexOf("path")] to it.getOrElse(libHead.indexOf("scrapeStatus")) { "" } }
        val names = File(n).readLines().filter { it.isNotBlank() }.associate {
            val (k, v) = it.split('\t', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
            k to v.split('|').filter(String::isNotBlank)
        }
        val (qHead, qRows) = rows(q)
        val hits = mutableListOf<String>()
        var scored = 0
        qRows.forEach { r ->
            val path = r.getOrElse(qHead.indexOf("path")) { "" }
            val key = keyOf[path] ?: return@forEach
            if (statusOf[path] != "MATCHED") return@forEach
            val ns = names[key]?.takeIf { it.isNotEmpty() } ?: return@forEach
            scored++
            val own = r.getOrElse(qHead.indexOf("queries")) { "" }.split(" || ").firstOrNull().orEmpty()
            if (own.isBlank()) return@forEach
            val entry = MetaResult(
                providerId = 0, kind = MediaKind.SHOW, title = ns.first(),
                synonyms = ns.drop(1),
            )
            if (AnimeMatch.namesAnotherWork(own, entry)) {
                hits += path
                println("FLAG ${AnimeMatch.residueOf(own, entry)}\t${own.take(52)}\t${ns.first().take(30)}")
            }
        }
        File(out).writeText(hits.joinToString("\n"))
        println("RESIDUE scored=$scored flagged=${hits.size}")
    }
}
