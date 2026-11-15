package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.scrape.FolderReconcile
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.junit.Test
import java.io.File

/**
 * Dev tool for the offline poster-wall evaluation (see `eval/README.md`): runs the REAL
 * [LibraryGrouping] over a dump of a device's library and writes the resulting cards, so the
 * evaluator can score the poster wall without a headset.
 *
 * Skipped unless both system properties are set, so ordinary `testDebugUnitTest` runs ignore it:
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*CardDumpTest*' \
 *       -Daura.library.tsv=/path/library.tsv -Daura.cards.out=/path/cards.tsv
 */
class CardDumpTest {

    @Test
    fun dumpCards() {
        val inPath = System.getProperty("aura.library.tsv") ?: return
        val outPath = System.getProperty("aura.cards.out") ?: return
        val items = reconciled(LibraryDumpReader.read(File(inPath)))
        val groups = LibraryGrouping.group(items)
        File(outPath).printWriter().use { w ->
            w.println("key\ttitle\tyear\tcategory\tisSeries\tcount\thasPoster\tfileCount\tunmatched\tpaths")
            groups.forEach { g ->
                val unmatched = g.items.count { it.scrapeStatus != ScrapeStatus.MATCHED }
                w.println(
                    listOf(
                        g.key, g.title, g.year ?: "", g.category.name, g.isSeries, g.count,
                        g.posterPath != null, g.items.size, unmatched,
                        g.items.joinToString("|") { it.path },
                    ).joinToString("\t"),
                )
            }
        }
        println("wrote ${groups.size} cards from ${items.size} items -> $outPath")
    }

    /**
     * The ITEMS after reconciliation, in the same shape as the input — so a change to
     * [FolderReconcile] can be scored on per-file metadata (whose name a 特典 ended up wearing),
     * not just on which card it landed:
     *
     *   -Daura.library.tsv=… -Daura.items.out=… -Daura.reconcile=1
     */
    @Test
    fun dumpItems() {
        val inPath = System.getProperty("aura.library.tsv") ?: return
        val outPath = System.getProperty("aura.items.out") ?: return
        val items = reconciled(LibraryDumpReader.read(File(inPath)))
        File(outPath).printWriter().use { w ->
            w.println("path\ttitle\toriginalTitle\taliases\tyear\tcollectionKey\ttmdbId\tcontentTag\tkind")
            items.forEach {
                w.println(
                    listOf(
                        it.path, it.title, it.originalTitle.orEmpty(), it.aliases.joinToString("|"),
                        it.year ?: "", it.collectionKey.orEmpty(), it.tmdbId ?: "",
                        it.contentTag?.name.orEmpty(), it.kind.name,
                    ).joinToString("\t"),
                )
            }
        }
        println("wrote ${items.size} items -> $outPath")
    }

    @Test
    fun probeKeys() {
        val inPath = System.getProperty("aura.library.tsv") ?: return
        val needle = System.getProperty("aura.probe") ?: return
        val items = reconciled(LibraryDumpReader.read(File(inPath)))
        val groups = LibraryGrouping.group(items)
        groups.filter { g -> g.items.any { it.path.contains(needle, true) } }.forEach { g ->
            println("PROBE key=${g.key} n=${g.items.size}")
            g.items.take(3).forEach {
                println("   tag=${it.contentTag} ck=${it.collectionKey} st=${it.scrapeStatus} " +
                    "cls=${com.therealityteam.videoplayer.data.scrape.ContentClassifier.classify(it.fileName, it.path)} " +
                    "${it.path.substringAfterLast('/').take(48)}")
            }
        }
    }

    /**
     * `-Daura.reconcile=1` replays folder reconciliation (the scraper's post-pass, see
     * [FolderReconcile]) over the dump, so a rule change there can be scored without a device rescrape.
     */
    private fun reconciled(items: List<MediaItem>): List<MediaItem> {
        if (System.getProperty("aura.reconcile") != "1") return items
        val changed = FolderReconcile.plan(items).associateBy { it.id }
        println("reconcile: ${changed.size} items re-homed")
        return items.map { changed[it.id] ?: it }
    }

}
