package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.ShelfFit
import org.junit.Test
import java.io.File

/**
 * Dev tool: every match on a library dump's music shelf, beside the names a pass could have asked
 * with and whether [ShelfFit.namesTheClip] lets the match stand. Skipped unless the dump is given:
 *
 *   ./gradlew :core:test --tests '*MusicShelfProbe*' -Daura.music.tsv=/path/library.tsv  (eval/extract.py makes the tsv)
 */
class MusicShelfProbe {

    @Test
    fun probe() {
        val path = System.getProperty("aura.music.tsv") ?: return
        val items = LibraryDumpReader.read(File(path)).filter {
            FilenameParser.isUnderMusicRoot(it.path) && it.scrapeStatus == ScrapeStatus.MATCHED
        }
        var kept = 0
        var lost = 0
        items.filter { it.contentTag != ContentKind.EXTRA || it.collectionKey == null }.forEach { item ->
            val parent = item.path.substringBeforeLast('/').substringAfterLast('/')
            val asked = listOfNotNull(
                FilenameParser.parseFromPath(item.path).title,
                FilenameParser.showFolderTitle(item.path),
                FilenameParser.programmeTitle(item.fileName, parent),
            ).filter { it.isNotBlank() }.distinct()
            val names = (listOfNotNull(item.title, item.originalTitle, item.showTitle) + item.aliases).distinct()
            val ok = asked.any { ShelfFit.namesTheClip(it, names) }
            if (ok) kept++ else lost++
            println("${if (ok) "KEEP" else "LOSE"}\t${item.path.substringAfter("/Music/")}\tasked=$asked\tnames=${names.take(3)}")
        }
        println("kept $kept, lost $lost")
    }
}
