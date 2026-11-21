package com.therealityteam.videoplayer.util

import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import java.net.URI

/**
 * 离线刮削, meaning what the switch says: nothing goes to the internet's film and anime databases or
 * to the servers their pictures live on. It used to silence TMDB alone (through its key), while
 * AniList, Bangumi, Jikan and IMDb's datasets went on being asked for every anime file, and the
 * title page fetched headshots and stills — under a privacy page saying "no network request at
 * all". A library's own servers (Jellyfin, Plex, DLNA, WebDAV, SMB) are not those databases and
 * keep working.
 */
object Offline {
    private val HOSTS = listOf(
        "themoviedb.org", "tmdb.org", "anilist.co", "bgm.tv", "bangumi.tv", "jikan.moe", "myanimelist.net",
        "omdbapi.com", "imdbws.com", "imdb.com", "media-amazon.com", "fanart.tv", "douban.com", "doubanio.com",
        "tvmaze.com", "every.film", "neodb.social", "wikidata.org", "ofdb.cc", "archive.org", "v3-cinemeta.strem.io",
    )

    /** Whether [url] is one of those databases and the user has asked for none of them. */
    fun refuses(url: String): Boolean {
        if (!TmdbConfig.offline) return false
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        return HOSTS.any { host == it || host.endsWith(".$it") }
    }
}
