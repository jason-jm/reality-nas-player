package com.therealityteam.videoplayer.data.repository

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.scrape.FilenameParser

/**
 * Puts right, as a library loads, what an older pass wrote wrong and no newer pass will rewrite — a
 * file already identified is not asked about again. Pure, so it is unit-tested.
 */
object LibraryRepair {

    /**
     * Episodes the main TMDB path identified before it named their series after the database: the
     * episode knows the show as the database does (曼达洛人), the series kept its files' title (The
     * Mandalorian), and the card shows the series' — 20 shows in English on a Chinese wall. An
     * episode is touched only when its series name IS its own file's parsed title, which is what
     * that path left; an extra adopted into a series carries the series' name on purpose, a Jellyfin
     * or Plex episode its server's, an anime its work's, a manual match the one it was given.
     *
     * @return the items, the same list when nothing needed it, and how many were put right
     */
    fun seriesTitles(items: List<MediaItem>): Pair<List<MediaItem>, Int> {
        var fixed = 0
        val out = items.map { item ->
            if (namedAfterItsFile(item)) {
                fixed++
                item.copy(showTitle = item.title)
            } else {
                item
            }
        }
        return (if (fixed == 0) items else out) to fixed
    }

    internal fun namedAfterItsFile(item: MediaItem): Boolean {
        val show = item.showTitle ?: return false
        if (item.kind != MediaKind.EPISODE || item.scrapeStatus != ScrapeStatus.MATCHED || item.tmdbId == null) return false
        if (item.title.isBlank() || item.title == show || item.userMatched || item.fromNfo) return false
        // anilist: is an anime's work, plex: and jellyfin: a server's own series.
        val key = item.collectionKey
        if (key != null && !key.startsWith("id:")) return false
        return show == FilenameParser.parseFromPath(item.path).title
    }
}
