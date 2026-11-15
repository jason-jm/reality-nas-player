package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind

/**
 * Continue watching — the row at the top of the wall that picks up where the user left off.
 *
 * One entry per title, the file last touched in it, most recent first; a file watched to the
 * end is done, not in progress. Tapping plays that file at once (the card below it is still
 * there for the title itself). Pure selection here; drawn by [ContinueRow].
 */
object ContinueWatching {
    /** How far in a file counts as finished even without the watched mark. */
    private const val DONE_FRACTION = 0.95f

    /** What the card resumes — or, for a series whose last episode was finished, what it starts next. */
    data class Entry(val group: MediaGroup, val item: MediaItem, val nextUp: Boolean = false, val at: Long = item.progress.updatedAt)

    /**
     * The row, most recent first. [hidden] holds the cards taken out of it (从「继续观看」中移除) with
     * the moment that was current then: a card stays out until it is watched again after it.
     */
    fun of(groups: List<MediaGroup>, limit: Int = 12, hidden: Map<String, Long> = emptyMap()): List<Entry> =
        groups.asSequence()
            .mapNotNull { g ->
                g.items.filter { inProgress(it) }.maxByOrNull { it.progress.updatedAt }?.let { Entry(g, it) }
                    ?: nextUp(g)
            }
            .filterNot { e -> hidden[e.group.key]?.let { e.at <= it } == true }
            .sortedByDescending { it.at }
            .take(limit)
            .toList()

    /**
     * A series you finished an episode of stays in the row with the episode that follows — the way
     * the detail page's button already reads it — until nothing is left to watch.
     */
    private fun nextUp(g: MediaGroup): Entry? {
        if (!g.isSeries) return null
        val lastWatched = g.items.filter { it.progress.watched }.maxByOrNull { it.progress.updatedAt } ?: return null
        val numberedRun = g.numberedRun
        val ordered = g.items.sortedWith(compareBy({ EpisodeNaming.sortKey(it, numberedRun) }, { it.fileName.lowercase() }))
        val choice = DetailPlay.choose(ordered, numberedRun, g.seasonal) ?: return null
        // Only "the next one after what you finished" belongs here — not a series never started
        // and not one seen to the end. Asked of the kind, so it holds in every language.
        if (choice.kind != DetailPlay.Kind.NEXT) return null
        return Entry(g, choice.item, nextUp = true, at = lastWatched.progress.updatedAt)
    }

    fun inProgress(item: MediaItem): Boolean {
        val p = item.progress
        return p.started && !p.watched && (p.durationMs <= 0L || p.fraction < DONE_FRACTION)
    }

    /**
     * What the card's second line says, before it is put into words: the episode it is about (a
     * film's single feature names nothing), and how much of it is left. Kept apart from the
     * wording so the choice is testable and the words are the reader's.
     */
    data class Sub(val nextUp: Boolean, val episode: MediaItem?, val minutesLeft: Int?, val percent: Int?)

    fun sub(entry: Entry): Sub {
        val p = entry.item.progress
        val numberedRun = entry.group.numberedRun
        val named = entry.item.kind == MediaKind.EPISODE || entry.group.isSeries
        val episode = entry.item
            .takeIf { named && EpisodeNaming.parse(it, numberedRun).cat != EpisodeNaming.Cat.FEATURE }
        if (entry.nextUp) return Sub(nextUp = true, episode = episode, minutesLeft = null, percent = null)
        val left = if (p.durationMs > 0L) ((p.durationMs - p.positionMs) / 60_000L).coerceAtLeast(1).toInt() else null
        return Sub(false, episode, left, if (left == null) (p.fraction * 100).toInt() else null)
    }
}
