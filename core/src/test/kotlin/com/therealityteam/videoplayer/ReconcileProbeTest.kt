package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.scrape.AnimeMatch
import com.therealityteam.videoplayer.data.scrape.ContentClassifier
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.FolderReconcile
import org.junit.Test
import java.io.File

/**
 * Dev tool: why did reconciliation do THAT to this folder?
 *
 * [FolderReconcile.plan] weighs half a dozen things — which files count as features, which identity
 * dominates, which sibling is a work of its own, who represents the folder — and the plan it returns
 * says only what changed, never why. This prints the inputs for one folder's worth of files out of a
 * library dump: what each file is classified as, what the residue against the representative comes
 * to, and what the plan does with it. Three wrong guesses about one folder paid for writing it.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*ReconcileProbeTest*' \
 *       -Daura.probe.lib=~/aura-eval/library.tsv -Daura.probe.needle='Turn_A_Gundam][Movie'
 */
class ReconcileProbeTest {
    @Test fun probe() {
        val lib = System.getProperty("aura.probe.lib") ?: return
        val needle = System.getProperty("aura.probe.needle") ?: return
        val lines = File(lib).readLines().filter { it.isNotBlank() }
        val head = lines.first().split('\t')
        fun col(r: List<String>, n: String) = head.indexOf(n).let { if (it < 0) "" else r.getOrElse(it) { "" } }
        val items = lines.drop(1).map { it.split('\t') }.filter { col(it, "path").contains(needle) }.map { r ->
            MediaItem(
                id = col(r, "path"), sourceId = "s", path = col(r, "path"),
                fileName = col(r, "path").substringAfterLast('/'),
                title = col(r, "title"), originalTitle = col(r, "originalTitle").ifBlank { null },
                aliases = col(r, "aliases").split('|').filter(String::isNotBlank),
                kind = runCatching { MediaKind.valueOf(col(r, "kind")) }.getOrDefault(MediaKind.UNKNOWN),
                collectionKey = col(r, "collectionKey").ifBlank { null },
                contentTag = col(r, "contentTag").ifBlank { null }?.let { runCatching { ContentKind.valueOf(it) }.getOrNull() },
                tmdbId = col(r, "tmdbId").toIntOrNull(),
                scrapeStatus = runCatching { ScrapeStatus.valueOf(col(r, "scrapeStatus")) }.getOrDefault(ScrapeStatus.PENDING),
            )
        }
        println("RP items=${items.size}")
        items.forEach {
            println("RP  ${it.fileName.take(46)}")
            println("RP     folder=${FolderReconcile.releaseFolder(it.path).substringAfterLast('/').take(40)}")
            println("RP     id=${FolderReconcile.identityOf(it)} tag=${it.contentTag} st=${it.scrapeStatus} " +
                "strong=${ContentClassifier.isStrongExtra(it.fileName)} cls=${ContentClassifier.classify(it.fileName, it.path)}")
            println("RP     ownTitle=${FilenameParser.parse(it.fileName).title.take(44)}")
        }
        val rep = items.firstOrNull { it.originalTitle != null }
        if (rep != null) {
            val names = listOfNotNull(rep.title, rep.originalTitle, rep.showTitle) + rep.aliases
            println("RP rep=${rep.fileName.take(30)} names=$names")
            items.forEach {
                val own = FilenameParser.parse(it.fileName).title
                println("RP    residue(${it.fileName.take(34)}) = ${AnimeMatch.residueOf(own, names)}")
            }
        }
        val plan = FolderReconcile.plan(items)
        println("RP plan changed=${plan.size}")
        plan.forEach { println("RP    -> ${it.fileName.take(40)} id=${FolderReconcile.identityOf(it)} st=${it.scrapeStatus}") }
    }
}
