package com.therealityteam.videoplayer.data.source

import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.scrape.ArtworkCache
import com.therealityteam.videoplayer.data.playback.ProjectionDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Turns a Jellyfin server's library into Aura library entries that arrive ALREADY MATCHED.
 *
 * These items never meet the scrape pipeline: identity, prose, cast and artwork are the server's
 * word (usually the user's own curation), so scrapeStatus is MATCHED from birth and
 * [MediaItem.ratingsVersion] is pinned to MAX_VALUE — no future ratings-pipeline bump should ever
 * drag a server-owned item through the guessing machinery. Refresh comes from re-scanning the
 * source, which re-reads the server.
 *
 * Every item gets a collectionKey ("jellyfin:{seriesId|itemId}"), for two reasons: it is what
 * groups a show's episodes onto one card, and it is what keeps needsScrape's anime-looking-
 * filename fallback from ever re-routing a server-owned file through AniList.
 */
class JellyfinSource(
    private val client: JellyfinClient,
    private val artwork: ArtworkCache,
) {

    /** The scan result: the items, plus the fresh token playback will need and whom it signed in as. */
    data class Result(val items: List<MediaItem>, val token: String, val userId: String)

    /**
     * Authenticate, read every movie/episode, and cache artwork locally. Auth/host errors
     * propagate so the sources UI can say what went wrong.
     */
    suspend fun listMatched(config: MediaSourceConfig): Result {
        val session = client.authenticate(config.uri, config.username.orEmpty(), config.password.orEmpty())
        val now = System.currentTimeMillis()
        val items = client.allItems(session)
            .filter { it.type != JellyfinClient.ItemType.OTHER }
            .mapIndexed { i, item -> toMediaItem(config, session, item, now, i) }
        return Result(enrichArtwork(session, items), session.token, session.userId)
    }

    /** Download poster/backdrop/still for each item (bounded), extract the dominant color. */
    private suspend fun enrichArtwork(
        session: JellyfinClient.Session,
        items: List<Pair<MediaItem, JellyfinClient.Item>>,
    ): List<MediaItem> = coroutineScope {
        val gate = Semaphore(ART_CONCURRENCY)
        items.map { (media, jf) ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    // A show's card wears the SERIES poster; an episode's own image is its still.
                    val posterUrl = when (jf.type) {
                        JellyfinClient.ItemType.EPISODE ->
                            JellyfinClient.seriesImageUrl(session, jf) ?: JellyfinClient.primaryImageUrl(session, jf)
                        else -> JellyfinClient.primaryImageUrl(session, jf)
                    }
                    val stillUrl = if (jf.type == JellyfinClient.ItemType.EPISODE) {
                        JellyfinClient.primaryImageUrl(session, jf)
                    } else null
                    val poster = posterUrl?.let { artwork.cache(it, media.id) }
                    val backdrop = JellyfinClient.backdropImageUrl(session, jf)
                        ?.let { artwork.cache(it, media.id + "_bd") }
                    val still = stillUrl?.let { artwork.cache(it, "still_" + media.id) }
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

        /**
         * The picture's flags: by the file's name, and — where the name says no packing — the server's
         * Video3DFormat for a 3D film (side by side, top and bottom; MVC's two coded views are one
         * picture a frame, shown as such).
         */
        internal fun flagsOf(fileName: String, video3dFormat: String?): com.therealityteam.videoplayer.data.model.VideoFlags {
            val named = ProjectionDetector.detect(fileName, null, null)
            if (named.stereo == com.therealityteam.videoplayer.data.model.StereoMode.SBS || named.stereo == com.therealityteam.videoplayer.data.model.StereoMode.OU) return named
            val packing = when (video3dFormat?.lowercase()) {
                "halfsidebyside", "fullsidebyside" -> com.therealityteam.videoplayer.data.model.StereoMode.SBS
                "halftopandbottom", "fulltopandbottom" -> com.therealityteam.videoplayer.data.model.StereoMode.OU
                else -> return named
            }
            // The server's reading of the file: as sure as the file's own word (its 3D tag shows).
            return named.copy(stereo = packing, fromFile = true)
        }

        /**
         * The server item → a library entry, paired with its origin for the artwork pass.
         * Pure — pinned by unit tests against the demo server's real responses.
         */
        fun toMediaItem(
            config: MediaSourceConfig,
            session: JellyfinClient.Session,
            item: JellyfinClient.Item,
            now: Long,
            index: Int,
        ): Pair<MediaItem, JellyfinClient.Item> {
            // Token-free ON PURPOSE: a token in the path would rotate the item's id every scan
            // and wipe progress/favorites. Playback appends the source's stored token at open.
            val path = "${session.baseUrl}/Videos/${item.id}/stream?static=true"
            val fileName = item.path?.substringAfterLast('/')?.ifBlank { null } ?: item.name
            val isEpisode = item.type == JellyfinClient.ItemType.EPISODE
            val cast = item.people.filter { it.type == "Actor" }.take(12)
                .map { Person(it.name, it.role, null) }
            val crew = item.people.filter { it.type == "Director" || it.type == "Writer" }.take(8)
                .map { Person(it.name, if (it.type == "Director") director() else writer(), null) }
            val media = MediaItem(
                id = MediaItem.idFor(config.id, path),
                sourceId = config.id,
                path = path,
                fileName = fileName,
                kind = if (isEpisode) MediaKind.EPISODE else MediaKind.MOVIE,
                title = if (isEpisode) (item.seriesName ?: item.name) else item.name,
                originalTitle = item.originalTitle,
                year = item.year,
                overview = item.overview,
                rating = item.communityRating,
                runtime = item.runtimeMinutes,
                certification = item.officialRating,
                genres = item.genres,
                cast = cast,
                crew = crew,
                tmdbId = item.tmdbId,
                showTitle = if (isEpisode) item.seriesName else null,
                season = item.season,
                episode = item.episode,
                collectionKey = config.type.name.lowercase() + ":" + (item.seriesId ?: item.id),
                video = flagsOf(fileName, item.video3dFormat),
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
