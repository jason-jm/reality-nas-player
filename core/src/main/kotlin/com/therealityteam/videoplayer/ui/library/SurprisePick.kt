package com.therealityteam.videoplayer.ui.library

import kotlin.math.pow
import kotlin.random.Random

/**
 * 随便看看: a title from the wall in view that nobody has begun, chosen at random — a better-rated one
 * more often — for a library too big to choose from. Only cards a database named (a file name is no
 * invitation), and not the last few it offered, while there are others.
 */
object SurprisePick {

    /** Whether [g] is a candidate: named, and not a minute of it seen. */
    fun fresh(g: MediaGroup): Boolean =
        LibrarySort.identified(g) && g.items.none { it.progress.started || it.progress.watched }

    fun of(pool: List<MediaGroup>, recent: Collection<String> = emptyList(), random: Random = Random.Default): MediaGroup? {
        val fresh = pool.filter(::fresh)
        val candidates = fresh.filterNot { it.key in recent }.ifEmpty { fresh }
        if (candidates.isEmpty()) return null
        val weights = candidates.map(::weight)
        var r = random.nextDouble() * weights.sum()
        for ((i, w) in weights.withIndex()) {
            r -= w
            if (r <= 0.0) return candidates[i]
        }
        return candidates.last()
    }

    /** An unrated title counts as a 6; each point above doubles its chance, each below halves it. */
    internal fun weight(g: MediaGroup): Double {
        val rating = LibrarySort.ratingValue(g.ratings).takeIf { it > 0.0 } ?: 6.0
        return 2.0.pow((rating - 6.0).coerceIn(-3.0, 4.0))
    }
}
