package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AnimeParse
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import org.junit.Test
import java.io.File

/**
 * Dev tool: which path the engine takes for each file — AniList first (anime-likely or under an
 * anime root) or TMDB — so a change to what counts as anime can be diffed over a whole library.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*AnimeLikelyDumpTest*' \
 *       -Daura.animelikely.in=/path/paths.txt -Daura.animelikely.out=/path/out.tsv
 */
class AnimeLikelyDumpTest {
    @Test fun dump() {
        val inPath = System.getProperty("aura.animelikely.in") ?: return
        val outPath = System.getProperty("aura.animelikely.out") ?: return
        File(outPath).printWriter().use { w ->
            File(inPath).readLines().filter { it.isNotBlank() }.forEach { path ->
                val likely = AnimeParse.parse(path.substringAfterLast('/')).isAnimeLikely
                w.println("${if (likely || FilenameParser.isUnderAnimeRoot(path)) "anilist" else "tmdb"}\t$likely\t$path")
            }
        }
    }
}
