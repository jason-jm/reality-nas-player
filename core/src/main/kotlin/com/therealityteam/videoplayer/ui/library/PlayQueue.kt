package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.MediaItem

/**
 * What plays after [item] — the list the detail page shows it in, in that order. A card's files
 * were the queue, as the library held them: after 86's episode 9 came its "EP09 B-Part Non-Credit
 * Ver.", after episode 23 a hundred extras, and 上一个 from episode 1 went to a music video.
 *
 * A show's episode is followed by the next one across its seasons; an OVA by the OVAs, an extra by
 * the extras. A film is followed by its own other parts (CD2) — not its trailer, nor its other
 * versions — and a trailer by the other extras.
 */
object PlayQueue {

    fun of(group: MediaGroup, item: MediaItem): List<MediaItem> {
        val numberedRun = group.numberedRun
        val ordered = group.items.sortedWith(DetailSections.order(numberedRun))
        val queue = if (group.seasonal) {
            val sections = DetailSections.of(ordered, numberedRun)
            val mine = sections.firstOrNull { s -> s.items.any { it.id == item.id } }
            when {
                mine == null -> emptyList()
                mine.episodic -> sections.filter { it.episodic }.flatMap { it.items }
                else -> mine.items
            }
        } else {
            val mains = DetailPlay.mains(ordered, numberedRun)
            val mainIds = mains.mapTo(HashSet()) { it.id }
            val versions = DetailPlay.alternates(ordered, numberedRun)
            val standsFor = versions[item.id]
            when {
                item.id in mainIds -> mains
                // Another version of a film: in that film's place — not ahead of its extras.
                standsFor != null -> mains.map { if (it.id == standsFor) item else it }
                else -> ordered.filterNot { it.id in mainIds || it.id in versions }
            }
        }
        // Whatever the list, the file asked for is in it — else nothing would know where it stands.
        return queue.takeIf { q -> q.any { it.id == item.id } } ?: listOf(item)
    }

    private val DetailSections.Section.episodic: Boolean
        get() = kind == DetailSections.Kind.SEASON || kind == DetailSections.Kind.EPISODES
}
