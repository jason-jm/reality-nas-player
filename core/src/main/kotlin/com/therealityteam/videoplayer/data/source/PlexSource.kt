package com.therealityteam.videoplayer.data.source

import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.playback.ProjectionDetector
import com.therealityteam.videoplayer.data.scrape.ArtworkCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Turns a Plex server's library into Aura library entries that arrive ALREADY MATCHED — the
 * same contract as [JellyfinSource]: identity, prose, cast and artwork are the server's word,
 * scrapeStatus is MATCHED from birth, ratingsVersion is pinned so no ratings-pipeline bump
 * drags a server-owned item through the guessing machinery, and every item carries a
 * collectionKey ("plex:{showKey|ratingKey}") that cards a show's episodes together.
 */
class PlexSource(
    private val client: PlexClient,
    private val artwork: ArtworkCache,
) {

    /** The scan result: the items, plus the token playback will sign stream URLs with. */
    data class Result(val items: List<MediaItem>, val token: String)

    /**
     * Verify the token, read every movie and episode, and cache artwork locally. Auth/host
     * errors propagate so the sources UI can say what went wrong. The token is what the user
     * typed into the source's password field.
     */
    suspend fun listMatched(config: MediaSourceConfig): Result {
        val token = config.password?.trim().orEmpty().ifBlank { config.token.orEmpty() }
        if (token.isBlank()) throw PlexAuthException("no X-Plex-Token configured")
        val session = client.connect(config.uri, token)
        val now = System.currentTimeMillis()
        val items = client.allItems(session)
            .filter { it.partKey != null }
            .mapIndexed { i, item -> toMediaItem(config, session, item, now, i) }
        return Result(enrichArtwork(session, items), session.token)
    }

    private suspend fun enrichArtwork(
        session: PlexClient.Session,
        items: List<Pair<MediaItem, PlexClient.Item>>,
    ): List<MediaItem> = coroutineScope {
        val gate = Semaphore(ART_CONCURRENCY)
        items.map { (media, px) ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    // A show's card wears the SHOW poster; an episode's own image is its still.
                    val posterPath = if (px.type == PlexClient.ItemType.EPISODE) px.showThumb ?: px.thumb else px.thumb
                    val poster = PlexClient.imageUrl(session, posterPath)?.let { artwork.cache(it, media.id) }
                    val backdrop = PlexClient.imageUrl(session, px.art)?.let { artwork.cache(it, media.id + "_bd") }
                    val still = if (px.type == PlexClient.ItemType.EPISODE) {
                        PlexClient.imageUrl(session, px.thumb)?.let { artwork.cache(it, "still_" + media.id) }
                    } else null
                    media.copy(
                        posterPath = poster,
                        backdropPath = backdrop,
                        stillPath = still,
                        dominantColor = artwork.dominantColor(poster),
                    )
                }
            }
        }.awaitAll()
    }

    companion object {
        private const val ART_CONCURRENCY = 6

        /** The server item → a library entry, paired with its origin for the artwork pass. Pure. */
        fun toMediaItem(
            config: MediaSourceConfig,
            session: PlexClient.Session,
            item: PlexClient.Item,
            now: Long,
            index: Int,
        ): Pair<MediaItem, PlexClient.Item> {
            // Token-free ON PURPOSE: a token in the path would rotate the item's id every scan
            // and wipe progress/favorites. Playback signs the URL with the source's token at open.
            val path = PlexClient.streamPath(session, item.partKey ?: "/library/metadata/${item.ratingKey}")
            val fileName = item.file?.substringAfterLast('/')?.ifBlank { null } ?: item.title
            val isEpisode = item.type == PlexClient.ItemType.EPISODE
            val media = MediaItem(
                id = MediaItem.idFor(config.id, path),
                sourceId = config.id,
                path = path,
                fileName = fileName,
                kind = if (isEpisode) MediaKind.EPISODE else MediaKind.MOVIE,
                title = if (isEpisode) (item.showTitle ?: item.title) else item.title,
                originalTitle = item.originalTitle,
                year = item.year,
                overview = item.summary,
                rating = item.rating,
                runtime = item.runtimeMinutes,
                certification = item.contentRating,
                genres = item.genres,
                cast = item.cast.take(12).map { Person(it.name, it.role, null) },
                crew = item.directors.take(4).map { Person(it, director(), null) } +
                    item.writers.take(4).map { Person(it, writer(), null) },
                tmdbId = item.tmdbId,
                showTitle = if (isEpisode) item.showTitle else null,
                season = item.season,
                episode = item.episode,
                collectionKey = "plex:" + (item.showKey ?: item.ratingKey),
                video = ProjectionDetector.detect(fileName, null, null),
                scrapeStatus = ScrapeStatus.MATCHED,
                ratingsVersion = Int.MAX_VALUE,
                addedAt = now - index,
            )
            return media to item
        }
    }
}

/**
 * Jellyfin and Plex hand back English crew roles; TMDB hands back the reader's own. Say it in
 * Chinese only to a reader whose metadata is Chinese — "Director" is the right answer for everyone
 * else, and a better one than Chinese for a German. (Same rule as GenreCn.)
 */
private fun director(): String = if (TmdbConfig.isChinese) "导演" else "Director"

private fun writer(): String = if (TmdbConfig.isChinese) "编剧" else "Writer"
