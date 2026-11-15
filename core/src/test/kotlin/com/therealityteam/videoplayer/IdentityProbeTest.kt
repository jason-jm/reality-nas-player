package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AniListClient
import com.therealityteam.videoplayer.data.scrape.BangumiClient
import com.therealityteam.videoplayer.data.scrape.AnimeMatch
import com.therealityteam.videoplayer.data.scrape.AnimeParse
import com.therealityteam.videoplayer.data.scrape.AnimeQueries
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Dev tool for the poster-wall evaluation: replays the identification decision (query order +
 * accept guard) against the LIVE AniList API for a list of representative files, in both the old
 * (no signal-less guard) and new modes, so identity accuracy can be measured — and A/B'd — without
 * re-scraping the whole library on a headset.
 *
 * Hits the network. Skipped unless both properties are set:
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*IdentityProbeTest*' \
 *       -Daura.probe.paths=/path/probe_paths.txt -Daura.probe.out=/path/probe_results.tsv
 */
class IdentityProbeTest {

    private val anilist = AniListClient()
    private val bangumi by lazy { BangumiClient(File(System.getProperty("java.io.tmpdir"), "aura-probe")) }

    @Test
    fun probeIdentity() {
        val inPath = System.getProperty("aura.probe.paths") ?: return
        val outPath = System.getProperty("aura.probe.out") ?: return
        val paths = File(inPath).readLines().filter { it.isNotBlank() }
        File(outPath).printWriter().use { w ->
            w.println("path\tmode\tquery\tanilistId\ttitle\tromaji\tqueries")
            paths.forEachIndexed { i, path ->
                val fileName = path.substringAfterLast('/')
                val parsed = FilenameParser.parse(fileName)
                val anime = AnimeParse.parse(fileName)
                val old = AnimeQueries.candidates(path, parsed, anime)
                val new = AnimeQueries.build(path, parsed, anime)
                // The shipped strategy only, unless an A/B against the pre-guard behaviour is asked
                // for: each extra mode triples the API calls, and reading a legacy mode's row as if
                // it were the product's answer is how the Patlabor films got diagnosed twice.
                val modes = if (System.getProperty("aura.probe.ab") == "1") {
                    listOf("old" to old, "new" to new, "pick" to new)
                } else {
                    listOf("pick" to new)
                }
                modes.forEach { (mode, qs) ->
                    val hit = runBlocking {
                        (if (mode == "pick") bestOfTop(qs, path, parsed, anime) else firstAccepted(qs))
                            ?: viaBangumiBridge(path, parsed, anime, mode == "pick")
                    }
                    w.println(
                        listOf(
                            path, mode, hit?.first ?: "", hit?.second?.providerId ?: "",
                            hit?.second?.title ?: "", hit?.second?.romajiTitle ?: "",
                            qs.joinToString(" | "),
                        ).joinToString("\t"),
                    )
                }
                w.flush()
                if (i % 10 == 0) println("PROBE progress ${i + 1}/${paths.size}")
            }
        }
        println("PROBE done -> $outPath")
    }

    /**
     * The Chinese→Japanese fallback the engine uses when AniList can't resolve the query: Bangumi
     * indexes Chinese names, so its Japanese title is what AniList actually matches.
     */
    private suspend fun viaBangumiBridge(
        path: String,
        parsed: com.therealityteam.videoplayer.data.scrape.ParsedName,
        anime: com.therealityteam.videoplayer.data.scrape.AnimeParse.Parsed,
        pickMode: Boolean,
    ): Pair<String, com.therealityteam.videoplayer.data.scrape.MetaResult>? {
        val cjk = AnimeQueries.cjkBridgeQuery(path, parsed, anime) ?: return null
        val jp = bangumi.chinese(cjk)?.nameJp?.takeIf { it.isNotBlank() } ?: return null
        val base = com.therealityteam.videoplayer.data.scrape.AnimeParse.baseTitle(jp)
        val hit = if (pickMode) {
            // Same arguments the engine gives it: the bridge is where a CJK season lands, and
            // without the season the probe answered a different question than resolveAnime does.
            val season = AnimeQueries.seasonOf(path) ?: parsed.season
            val wantMovie = anime.contentKind == com.therealityteam.videoplayer.data.model.ContentKind.MOVIE
            AnimeMatch.pick(jp, anilist.search(jp), season, wantMovie)
                ?: AnimeMatch.pick(jp, anilist.search(base), season, wantMovie)
        } else {
            (anilist.search(jp).firstOrNull() ?: anilist.search(base).firstOrNull())
                ?.takeIf { AnimeMatch.corresponds(jp, it) }
        }
        return hit?.let { "bangumi:$cjk→$jp" to it }
    }

    /**
     * The shipped strategy, argument for argument: [ScrapeEngine.resolveAnime]'s loop.
     *
     * The season the path names, whether the name marks a film, and the form the path carries all
     * change what [AnimeMatch.pick] accepts, so a probe that leaves them out answers a question the
     * engine never asks — it read the Patlabor films onto the wrong entries for exactly that reason.
     * A film keeps asking until some query yields a film, with the first hit kept as the fallback.
     */
    private suspend fun bestOfTop(
        queries: List<String>,
        path: String,
        parsed: com.therealityteam.videoplayer.data.scrape.ParsedName,
        anime: com.therealityteam.videoplayer.data.scrape.AnimeParse.Parsed,
    ): Pair<String, com.therealityteam.videoplayer.data.scrape.MetaResult>? {
        val season = AnimeQueries.seasonOf(path) ?: parsed.season
        val wantMovie = anime.contentKind == com.therealityteam.videoplayer.data.model.ContentKind.MOVIE
        val pathForm = AnimeQueries.formOfPath(path)
        var best: Pair<String, com.therealityteam.videoplayer.data.scrape.MetaResult>? = null
        for (q in queries) {
            val hit = AnimeMatch.pick(q, anilist.search(q), season, wantMovie, pathForm) ?: continue
            if (!wantMovie || hit.kind == com.therealityteam.videoplayer.data.model.MediaKind.MOVIE) {
                return q to hit
            }
            if (best == null) best = q to hit
        }
        return best
    }

    private suspend fun firstAccepted(queries: List<String>) =
        queries.firstNotNullOfOrNull { q ->
            anilist.search(q).firstOrNull()?.takeIf { AnimeMatch.corresponds(q, it) }?.let { q to it }
        }
}
