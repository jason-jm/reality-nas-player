package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.MediaItem

/**
 * How a show's page divides its files: one list per season (or one "Episodes" list), then OVA,
 * then extras — all re-derived from the file names by [EpisodeNaming], because the persisted
 * season/episode/tag are unreliable for fansub releases.
 *
 * Within a season each episode is listed once: duplicate files for one (season, episode) — a
 * creditless "(NC Ver.)", or a whole second release of the show — keep one primary in the list and
 * move the rest to extras, so the episode list never shows phantom duplicates. Which file is the
 * episode does not depend on the order the files came in ([PRIMARY]): the page took the first it
 * was handed, the play button the first by name, and a show held as both a BDRip and a TVRip
 * opened on 特典, its 接下来 a TVRip copy filed there. The words for each section are each app's own.
 */
object DetailSections {

    enum class Kind { EPISODES, SEASON, OVA, EXTRAS }

    data class Section(val kind: Kind, val season: Int?, val items: List<MediaItem>)

    fun order(numberedRun: Boolean) =
        compareBy<MediaItem>({ EpisodeNaming.sortKey(it, numberedRun) }, { it.fileName.lowercase() })

    fun of(all: List<MediaItem>, numberedRun: Boolean = false): List<Section> {
        val tagged = all.map { it to EpisodeNaming.parse(it, numberedRun) }
        val episodes = tagged.filter { it.second.cat == EpisodeNaming.Cat.EPISODE }
        val ova = tagged.filter { it.second.cat == EpisodeNaming.Cat.OVA }.map { it.first }
        // EXTRA + unrecognised (a FEATURE inside a show is a named special) both belong in extras.
        val extras = tagged.filter { it.second.cat == EpisodeNaming.Cat.EXTRA || it.second.cat == EpisodeNaming.Cat.FEATURE }
            .map { it.first }.toMutableList()

        val (primaryEps, copies) = split(episodes)
        extras += copies

        val sections = mutableListOf<Section>()
        val bySeason = primaryEps.groupBy { EpisodeNaming.parse(it, numberedRun).season ?: 1 }.toSortedMap()
        when {
            bySeason.size <= 1 -> if (primaryEps.isNotEmpty()) {
                sections += Section(Kind.EPISODES, null, primaryEps.sortedWith(order(numberedRun)))
            }
            else -> bySeason.forEach { (s, eps) -> sections += Section(Kind.SEASON, s, eps.sortedWith(order(numberedRun))) }
        }
        if (ova.isNotEmpty()) sections += Section(Kind.OVA, null, ova.sortedWith(order(numberedRun)))
        if (extras.isNotEmpty()) sections += Section(Kind.EXTRAS, null, extras.sortedWith(order(numberedRun)))
        return sections
    }

    /** The ids of the episode files [of] files under extras as copies of an episode already listed. */
    fun copies(all: List<MediaItem>, numberedRun: Boolean = false): Set<String> {
        val episodes = all.map { it to EpisodeNaming.parse(it, numberedRun) }.filter { it.second.cat == EpisodeNaming.Cat.EPISODE }
        return split(episodes).second.mapTo(HashSet()) { it.id }
    }

    /** One file per (season, episode) — the [PRIMARY] one — and the copies besides it. */
    private fun split(episodes: List<Pair<MediaItem, EpisodeNaming.Info>>): Pair<List<MediaItem>, List<MediaItem>> {
        val primary = mutableListOf<MediaItem>()
        val copies = mutableListOf<MediaItem>()
        episodes.groupBy { (it.second.season ?: 1) to it.second.episode }.forEach { (_, group) ->
            val ordered = group.sortedWith(PRIMARY)
            primary += ordered.first().first
            copies += ordered.drop(1).map { it.first }
        }
        return primary to copies
    }

    /**
     * Which of one episode's files is the episode: the cut itself before a creditless or other
     * "Ver."; then the one somebody has been watching (so the list keeps the copy they play); then
     * the better source — a Blu-ray before a web release before a TV capture; then the name.
     */
    private val PRIMARY = compareBy<Pair<MediaItem, EpisodeNaming.Info>>(
        { if (it.second.version.isNullOrBlank()) 0 else 1 },
        { if (it.first.progress.started || it.first.progress.watched) 0 else 1 },
        { sourceRank(it.first.path) },
        { it.first.fileName.lowercase() },
    )

    private val TOKENS = Regex("[^a-z0-9]+")
    private val BLU_RAY = setOf("bd", "bdrip", "bluray", "bdmv", "bdremux", "bdbox", "bdiso", "vcb")
    private val WEB = setOf("web", "webdl", "webrip")
    private val TV = setOf("tv", "tvrip", "hdtv", "hdtvrip", "dtv", "dvb")

    /** 0 for a Blu-ray release, 1 web, 3 a TV capture, 2 for anything that does not say — from the file's path. */
    internal fun sourceRank(path: String): Int {
        val tokens = path.lowercase().split(TOKENS).toSet()
        return when {
            tokens.any { it in BLU_RAY } || ("blu" in tokens && "ray" in tokens) -> 0
            tokens.any { it in WEB } -> 1
            tokens.any { it in TV } -> 3
            else -> 2
        }
    }
}
