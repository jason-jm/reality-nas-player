package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AnimeParse
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.Matcher
import com.therealityteam.videoplayer.data.scrape.MetaResult
import com.therealityteam.videoplayer.data.scrape.ParsedName
import com.therealityteam.videoplayer.data.scrape.TmdbMetadataClient
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Dev tool: which unmatched files of a library snapshot TMDB answers by an ALTERNATIVE title —
 * the engine's first two TMDB questions (the file's title; as TV when that was a film search),
 * then [Matcher.bestByAlternative] over each question's hits. Hits the network.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*TmdbAlternativeProbeTest*' \
 *       -Daura.library.tsv=/path/library.tsv -Daura.tmdbalt.out=/path/out.tsv
 */
class TmdbAlternativeProbeTest {

    @Test fun probe() {
        val inPath = System.getProperty("aura.library.tsv") ?: return
        val outPath = System.getProperty("aura.tmdbalt.out") ?: return
        val client = TmdbMetadataClient(File(System.getProperty("java.io.tmpdir"), "aura-tmdb-probe"))
        val lines = File(inPath).readLines()
        val header = lines.first().split('\t')
        fun col(row: List<String>, name: String) = row.getOrElse(header.indexOf(name)) { "" }
        // One question per distinct parse, as the engine's cache would make it.
        val asked = LinkedHashMap<Triple<String, Boolean, Int?>, MutableList<String>>()
        lines.drop(1).map { it.split('\t') }.forEach { row ->
            if (col(row, "scrapeStatus") == "MATCHED") return@forEach
            val path = col(row, "path")
            // What the engine never asks about (a recording of one's own, game footage), nor does this.
            if (System.getProperty("aura.tmdbalt.all") == null && FilenameParser.nothingToLookUp(path, path.substringAfterLast('/'), col(row, "title"))) return@forEach
            val anime = AnimeParse.parse(path.substringAfterLast('/'))
            // -Daura.tmdbalt.anime=1: the anime path's TMDB fallback instead, which only anime reaches.
            val animePath = anime.isAnimeLikely || FilenameParser.isUnderAnimeRoot(path)
            if (animePath != (System.getProperty("aura.tmdbalt.anime") != null)) return@forEach
            val parsed = FilenameParser.parseFromPath(path)
            val query = if (!animePath && !parsed.isEpisode && FilenameParser.isUnderTvRoot(path)) parsed.copy(season = 1) else parsed
            asked.getOrPut(Triple(query.title, query.isEpisode, query.year)) { mutableListOf() } += path
        }
        // TMDB answers a burst with 429s; wait them out rather than lose the run. Answers are cached on
        // disk, so a rerun only asks what it has not asked.
        fun <T> patient(block: suspend () -> T): T {
            var wait = 2_000L
            while (true) {
                try {
                    return runBlocking { block() }
                } catch (e: com.therealityteam.videoplayer.util.TransientHttpException) {
                    Thread.sleep(wait)
                    wait = (wait * 2).coerceAtMost(60_000L)
                }
            }
        }
        var found = 0
        File(outPath).printWriter().use { w ->
            w.println("title\tepisode\tyear\tfiles\tmatch\tvia\texample")
            asked.forEach { (key, paths) ->
                val (title, episode, year) = key
                val query = ParsedName(title, year = year, season = if (episode) 1 else null)
                val hits = patient { client.search(query) }
                var via = "file"
                var match: MetaResult? = Matcher.best(query, hits)
                var tv: List<MetaResult> = emptyList()
                if (match == null && !episode) {
                    tv = patient { client.search(query.copy(season = 1)) }
                    match = Matcher.best(query, tv)
                    via = "tv"
                }
                if (match != null) return@forEach // the engine finds it today; not this rule's business
                fun alt(q: ParsedName, hs: List<MetaResult>): MetaResult? {
                    val alts = Matcher.alternativeCandidates(q, hs)
                        .associate { it.providerId to patient { client.alternativeTitles(it.providerId, it.kind) } }
                    return Matcher.bestByAlternative(q, hs, alts)
                }
                match = alt(query, hits)?.also { via = "alt" } ?: if (!episode) alt(query, tv)?.also { via = "alt-tv" } else null
                // The anime fallback takes only an animation (or a folder that says live-action).
                if (match != null && System.getProperty("aura.tmdbalt.anime") != null) {
                    val genres = patient { client.details(match!!.providerId, match!!.kind) }?.genres.orEmpty()
                    if (genres.none { it.contains("动画") || it.contains("Animation", ignoreCase = true) } &&
                        !FilenameParser.saysLiveAction(paths.first())
                    ) {
                        via = "alt-not-animation"
                    }
                }
                if (match != null) found++
                w.println(listOf(title, episode, year ?: "", paths.size,
                    match?.let { "${it.title} (${it.year}) ${it.kind} #${it.providerId}" } ?: "", if (match != null) via else "",
                    paths.first()).joinToString("\t"))
            }
        }
        println("TMDBALT ${asked.size} questions, $found answered by an alternative title")
    }
}
