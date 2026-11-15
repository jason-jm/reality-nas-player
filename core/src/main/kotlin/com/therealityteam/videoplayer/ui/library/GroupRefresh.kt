package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.PlaybackProgress

/**
 * The cards again without grouping again, when all that changed is what grouping never reads — a
 * position saved every ten seconds of playing, a favourite, a picked track. Grouping a library of
 * 9,647 files took 1.7 s, and romanising its cards' titles 1.4 s more: every ten seconds, the whole
 * time a film played. Pure, so it is tested.
 */
object GroupRefresh {

    /** Whether [next] would group as [prev] did: the same files in the same order, changed only in watch state and picks. */
    fun groupsAlike(prev: List<MediaItem>, next: List<MediaItem>): Boolean {
        if (prev.size != next.size) return false
        for (i in prev.indices) {
            val a = prev[i]
            val b = next[i]
            if (a === b) continue
            if (a.id != b.id || grouped(a) != grouped(b)) return false
        }
        return true
    }

    /** [groups] with each card's files taken from [next] by id — their watch state, favourites and picks as they are now. */
    fun refreshed(groups: List<MediaGroup>, next: List<MediaItem>): List<MediaGroup> {
        val byId = next.associateBy { it.id }
        return groups.map { g -> g.copy(items = g.items.map { byId[it.id] ?: it }) }
    }

    /** Whether [a] and [b] are the same cards — keys, titles, files — whatever state their files are in. */
    fun sameCards(a: List<MediaGroup>, b: List<MediaGroup>): Boolean =
        a.size == b.size && a.indices.all { i ->
            val x = a[i]
            val y = b[i]
            x.key == y.key && x.title == y.title && x.items.size == y.items.size &&
                x.items.indices.all { j -> x.items[j].id == y.items[j].id }
        }

    /**
     * [fresh]'s cards with the search and A→Z keys already worked out for [romanised] — the same
     * cards ([sameCards]), so romanising them again would only repeat itself. Everything else is
     * [fresh]'s: the cards a cold start revives come from the wall's snapshot, which keeps no
     * backdrop, and carrying THEM over — files swapped in, shells kept — left 继续观看's large card
     * and every detail page without their pictures for as long as the app stayed open.
     */
    fun carryKeys(romanised: List<MediaGroup>, fresh: List<MediaGroup>): List<MediaGroup> =
        fresh.mapIndexed { i, c -> c.copy(searchKeys = romanised[i].searchKeys, titleKey = romanised[i].titleKey) }

    /** An item as grouping reads it. */
    private fun grouped(m: MediaItem): MediaItem =
        m.copy(progress = PlaybackProgress(), favorite = false, audioTrackId = null, subtitleTrackId = null)
}
