package com.therealityteam.videoplayer.ui.library

/**
 * 因为你看过《…》: titles in this library like one watched lately, not yet begun — a row on 全部
 * that answers "what next?" from what the library already holds (see [SimilarTitles]).
 *
 * The seed is the most recently watched title that has enough like it to fill a row; a title begun
 * already (it is in 继续观看, or was seen) is no suggestion, nor is the seed itself.
 */
object BecauseYouWatched {

    data class Row(val seed: MediaGroup, val cards: List<MediaGroup>)

    /** The row, or null when nothing watched lately has [minCards] untouched titles like it. */
    fun of(groups: List<MediaGroup>, limit: Int = 12, minCards: Int = 4, seedsTried: Int = 5): Row? {
        val seeds = groups.asSequence()
            .filter { g -> g.items.any { it.progress.updatedAt > 0L && it.progress.started } }
            .sortedByDescending { HistoryBuckets.lastWatched(it) }
            .take(seedsTried)
        for (seed in seeds) {
            val cards = SimilarTitles.of(seed, groups, limit = limit * 2)
                .filter { card -> card.items.none { it.progress.started || it.progress.watched } }
                .take(limit)
            if (cards.size >= minCards) return Row(seed, cards)
        }
        return null
    }
}
