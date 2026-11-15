package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AniListClient
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Dev tool: every name a matched entry answers to, for entries a device dump only has the id of.
 *
 * A library scraped before `aliases` existed carries the display title and the native title and
 * nothing else, so any question of the form "does the file's own name say more than the entry
 * does?" cannot be asked of that dump — the entry's Latin names are simply missing from it. This
 * fetches them.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*AliasProbeTest*' \
 *       -Daura.alias.in=/path/ids.tsv -Daura.alias.out=/path/names.tsv
 *
 * Input is `anilist:<id><TAB><a title to search by>` per line; output is `id<TAB>name|name|…`.
 */
class AliasProbeTest {

    @Test fun names() {
        val inPath = System.getProperty("aura.alias.in") ?: return
        val outPath = System.getProperty("aura.alias.out") ?: return
        val client = AniListClient()
        File(outPath).printWriter().use { w ->
            File(inPath).readLines().filter { it.isNotBlank() }.forEach { line ->
                val (key, title) = line.split('\t').let { it[0] to it.getOrElse(1) { "" } }
                val id = key.substringAfter(':').toIntOrNull() ?: return@forEach
                val hit = runBlocking { client.search(title) }.firstOrNull { it.providerId == id }
                val names = hit?.let {
                    listOfNotNull(it.title, it.romajiTitle, it.englishTitle, it.originalTitle) + it.synonyms
                }.orEmpty().filter { it.isNotBlank() }.distinct()
                w.println("$key\t${names.joinToString("|")}")
                println("ALIAS $key -> ${names.joinToString(" | ")}")
            }
        }
    }
}
