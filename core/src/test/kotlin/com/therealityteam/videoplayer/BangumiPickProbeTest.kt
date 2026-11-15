package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.BangumiClient
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Dev tool: which Chinese name each AniList work of a library snapshot gets from bgm.tv, under the
 * rule before the exact-name pick and under the rule now, with the network answers shared.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*BangumiPickProbeTest*' \
 *       -Daura.library.tsv=/path/library.tsv -Daura.bangumi.out=/path/bangumi.tsv
 *
 * One row per work: `anilist id, query, own names, name on the snapshot, before, after, changed`.
 * The answers are cached under java.io.tmpdir, so a second run asks bgm.tv nothing.
 */
class BangumiPickProbeTest {

    @Test fun names() {
        val inPath = System.getProperty("aura.library.tsv") ?: return
        val outPath = System.getProperty("aura.bangumi.out") ?: return
        val client = BangumiClient(File(System.getProperty("java.io.tmpdir"), "aura-bgm-probe"))
        val lines = File(inPath).readLines()
        val header = lines.first().split('\t')
        fun col(row: List<String>, name: String) = row.getOrElse(header.indexOf(name)) { "" }
        val works = LinkedHashMap<String, List<String>>()
        lines.drop(1).map { it.split('\t') }.forEach { row ->
            val key = col(row, "collectionKey")
            if (key.startsWith("anilist:")) works.putIfAbsent(key.substringAfter(':').substringBefore('|'), row)
        }
        var changed = 0
        File(outPath).printWriter().use { w ->
            w.println("id\tquery\tnames\tshown\tbefore\tafter\tchanged")
            works.forEach { (id, row) ->
                val q = java.text.Normalizer.normalize(
                    col(row, "originalTitle").ifBlank { col(row, "title") }, java.text.Normalizer.Form.NFKC,
                ).trim()
                val names = col(row, "aliases").split('|').filter { it.isNotBlank() }
                val subjects = runBlocking { client.search(q) }.orEmpty()
                val old = client.firstCorresponding(q, subjects)
                val before = old?.nameCn?.takeIf { it.isNotBlank() && !client.isBroaderWork(q, old.name) }
                val after = client.nameFor(q, client.pick(q, names, subjects))
                val diff = before != after
                if (diff) changed++
                w.println(listOf(id, q, names.joinToString("|"), col(row, "title"), before ?: "", after ?: "",
                    if (diff) "CHANGED" else "").joinToString("\t"))
                if (diff) println("BGM $id  $q  :  ${before ?: "-"}  ->  ${after ?: "-"}")
            }
        }
        println("BGM ${works.size} works, $changed changed")
    }

    /**
     * The Chinese-folder bridge: for every anime-shelf file its bridge query, and the Japanese title
     * bgm.tv's answer bridges to under each rule (`-Daura.bangumi.bridge.out=…`). An upper bound —
     * the bridge is only asked when no AniList query found the file.
     */
    @Test fun bridge() {
        val inPath = System.getProperty("aura.library.tsv") ?: return
        val outPath = System.getProperty("aura.bangumi.bridge.out") ?: return
        val client = BangumiClient(File(System.getProperty("java.io.tmpdir"), "aura-bgm-probe"))
        val lines = File(inPath).readLines()
        val pathCol = lines.first().split('\t').indexOf("path")
        val queries = LinkedHashMap<String, String>()
        lines.drop(1).map { it.split('\t')[pathCol] }.forEach { path ->
            if (!com.therealityteam.videoplayer.data.scrape.FilenameParser.isUnderAnimeRoot(path)) return@forEach
            val q = com.therealityteam.videoplayer.data.scrape.AnimeQueries.cjkBridgeQuery(
                path,
                com.therealityteam.videoplayer.data.scrape.FilenameParser.parseFromPath(path),
                com.therealityteam.videoplayer.data.scrape.AnimeParse.parse(path.substringAfterLast('/')),
            ) ?: return@forEach
            queries.putIfAbsent(java.text.Normalizer.normalize(q, java.text.Normalizer.Form.NFKC).trim(), path)
        }
        var changed = 0
        File(outPath).printWriter().use { w ->
            w.println("query\tbefore\tafter\tchanged\texample")
            queries.forEach { (q, path) ->
                val subjects = runBlocking { client.search(q) }.orEmpty()
                val before = client.firstCorresponding(q, subjects)?.name
                val after = client.pick(q, emptyList(), subjects, official = false)?.nameJp
                val diff = before != after
                if (diff) changed++
                w.println(listOf(q, before ?: "", after ?: "", if (diff) "CHANGED" else "", path).joinToString("\t"))
            }
        }
        println("BGM bridge ${queries.size} queries, $changed changed")
    }
}
