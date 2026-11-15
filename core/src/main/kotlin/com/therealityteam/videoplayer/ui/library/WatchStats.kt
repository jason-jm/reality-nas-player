package com.therealityteam.videoplayer.ui.library

import java.time.Instant
import java.time.ZoneId

/**
 * 观影统计 for 历史's heading: this calendar year, where the viewer is — the films seen through (by
 * card: a film kept in two versions is one), the episodes seen through, and the time it took: a
 * finished file's length, else as far as it got. Counted from the last time each file played, so a
 * film rewatched counts once; the time is said as "about" for that reason.
 */
object WatchStats {

    data class Summary(val films: Int, val episodes: Int, val minutes: Long) {
        val isEmpty: Boolean get() = films == 0 && episodes == 0 && minutes == 0L
    }

    fun ofYear(groups: List<MediaGroup>, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Summary {
        val year = Instant.ofEpochMilli(nowMs).atZone(zone).year
        fun thisYear(atMs: Long) = atMs > 0L && Instant.ofEpochMilli(atMs).atZone(zone).year == year
        var films = 0
        var episodes = 0
        var ms = 0L
        groups.forEach { g ->
            val played = g.items.filter { it.progress.started && thisYear(it.progress.updatedAt) }
            if (played.isEmpty()) return@forEach
            // Seen through, of the card's own episodes or feature: an NCOP or a trailer played to its
            // end is time spent, not an episode or a film watched.
            val mains = g.countedItems.mapTo(HashSet()) { it.id }
            val finished = played.filter { it.progress.watched && it.id in mains }
            if (g.isSeries) episodes += finished.size else if (finished.isNotEmpty()) films++
            played.forEach { item ->
                val p = item.progress
                ms += if (p.watched && p.durationMs > 0L) p.durationMs else p.positionMs
            }
        }
        return Summary(films, episodes, ms / 60_000L)
    }
}
