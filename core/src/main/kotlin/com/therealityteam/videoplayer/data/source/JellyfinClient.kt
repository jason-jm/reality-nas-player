package com.therealityteam.videoplayer.data.source

import com.therealityteam.videoplayer.util.PRODUCT_NAME
import com.therealityteam.videoplayer.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Jellyfin / Emby REST client — the read side of "adopt the library the user already curated".
 * Jellyfin forked Emby, and the calls this client makes (authenticate by name, the user's
 * items, item images, direct-play streams) are the same on both; the auth header is sent
 * under both names so either server recognises it.
 *
 * A Jellyfin server is not a folder of files to identify: every item it returns arrives with the
 * identity the user's server (and often the user, by hand) already settled — title, year, TMDB/
 * IMDb ids, cast, artwork. So this client's job is transport and parsing only; nothing here goes
 * anywhere near the scrape pipeline's guessing machinery. One Aura library scan against a
 * Jellyfin source makes ZERO external metadata calls.
 *
 * Auth is username/password → token ([authenticate]); every later call carries the token in the
 * MediaBrowser Authorization header. GETs run through [Http], so 429/5xx retry rules apply —
 * mostly irrelevant on a LAN, but a Jellyfin behind a reverse proxy on the internet is common.
 */
class JellyfinClient {

    /** A logged-in connection: where, as whom, and the token proving it. */
    data class Session(val baseUrl: String, val userId: String, val token: String)

    enum class ItemType { MOVIE, EPISODE, OTHER }

    data class Person(val name: String, val role: String?, val type: String?, val id: String?)

    /**
     * One playable item as the server describes it. Identity fields are the server's word and are
     * carried verbatim; nothing is re-parsed from [path]'s filename.
     */
    data class Item(
        val id: String,
        val type: ItemType,
        val name: String,
        val originalTitle: String? = null,
        val year: Int? = null,
        val overview: String? = null,
        val genres: List<String> = emptyList(),
        val communityRating: Double? = null,
        val officialRating: String? = null,
        /** Jellyfin ticks are 100ns; [runtimeMinutes] does the conversion once. */
        val runTimeTicks: Long? = null,
        val tmdbId: Int? = null,
        val imdbId: String? = null,
        /** Server-side file path — kept for display/debug, never re-parsed for identity. */
        val path: String? = null,
        val primaryImageTag: String? = null,
        val backdropImageTag: String? = null,
        // Episode-only: where the episode belongs and the SERIES' own poster, which is what a
        // poster-wall card wants (an episode still would make every show's card a random frame).
        val seriesId: String? = null,
        val seriesName: String? = null,
        val seriesPrimaryImageTag: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val people: List<Person> = emptyList(),
        /**
         * The server's word on a 3D film's packing (Video3DFormat: HalfSideBySide, FullSideBySide,
         * HalfTopAndBottom, FullTopAndBottom, MVC), from its own reading of the file or its name.
         */
        val video3dFormat: String? = null,
    ) {
        val runtimeMinutes: Int? get() = runTimeTicks?.let { (it / TICKS_PER_MINUTE).toInt() }.takeIf { (it ?: 0) > 0 }
    }

    /**
     * Log in. Throws [JellyfinAuthException] on wrong credentials (the one failure the UI can fix
     * by asking the user) and [java.io.IOException]-ish nulls become a generic failure.
     */
    suspend fun authenticate(serverUrl: String, username: String, password: String): Session =
        withContext(Dispatchers.IO) {
            val base = normalizeBaseUrl(serverUrl)
            val body = JSONObject().put("Username", username).put("Pw", password).toString()
            val conn = (URL("$base/Users/AuthenticateByName").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", authHeader(token = null))
                setRequestProperty("X-Emby-Authorization", authHeader(token = null))
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                when (val code = conn.responseCode) {
                    in 200..299 -> parseAuth(base, conn.inputStream.bufferedReader().use { it.readText() })
                    401, 403 -> throw JellyfinAuthException("HTTP 401/403")
                    else -> throw SourceException(SourceFault.SERVER, "Jellyfin HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }

    /** Every playable item (movies + episodes), paged until the server says that's all of them. */
    suspend fun allItems(session: Session): List<Item> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Item>()
        var start = 0
        while (true) {
            val url = itemsUrl(session, start, PAGE_SIZE)
            val body = Http.getOrThrow(url, authHeaders(session.token))
                ?: throw SourceException(SourceFault.SERVER, "Jellyfin items HTTP 4xx")
            val (page, total) = parseItemsPage(body)
            out += page
            start += PAGE_SIZE
            if (out.size >= total || page.isEmpty()) break
        }
        out
    }

    /**
     * A subtitle the server keeps in a file of its own beside an item's video — what Jellyfin and Emby list
     * as an external stream — at the [url] the server serves it from, in its own [format].
     */
    data class ExternalSubtitle(
        val url: String,
        /** The file's name on the server, when it says. */
        val fileName: String?,
        val format: String,
        /** The server's language code ("chi", "eng"), when it has one. */
        val language: String?,
    )

    /**
     * [itemId]'s external text subtitles (.srt / .ass / .vtt beside the video), signed with the session's
     * [token]: the direct-play stream is the video file alone, so these never come with it. Embedded tracks
     * do, and are not listed. Empty when the server says nothing of any.
     */
    suspend fun externalSubtitles(baseUrl: String, userId: String, token: String, itemId: String): List<ExternalSubtitle> =
        withContext(Dispatchers.IO) {
            val body = Http.get("$baseUrl/Users/$userId/Items/$itemId", authHeaders(token)) ?: return@withContext emptyList()
            parseExternalSubtitles(baseUrl, itemId, token, body)
        }

    companion object {
        private const val PAGE_SIZE = 500
        private const val TICKS_PER_MINUTE = 600_000_000L

        /** Fields beyond the defaults that the mapping needs — identity, prose, people, path. */
        private const val FIELDS = "Overview,Genres,ProviderIds,People,OfficialRating,OriginalTitle,Path"

        /**
         * The MediaBrowser scheme's identification header. Sent on every request; the token slot
         * is filled after [authenticate]. DeviceId is stable-per-install in spirit — "aura-pico"
         * is fine because Jellyfin uses it to group sessions, not to authorize.
         */
        fun authHeader(token: String?): String = buildString {
            append("""MediaBrowser Client="$PRODUCT_NAME", Device="PICO", DeviceId="aura-pico", Version="1.0"""")
            if (!token.isNullOrBlank()) append(""", Token="$token"""")
        }

        /** The MediaBrowser header under both the name Jellyfin reads and the one Emby reads. */
        fun authHeaders(token: String?): Map<String, String> =
            mapOf("Authorization" to authHeader(token), "X-Emby-Authorization" to authHeader(token))

        /**
         * What the user types becomes a base URL: scheme added when missing (a LAN Jellyfin is
         * plain http far more often than not), trailing slashes trimmed so path-joins are sane.
         * A subpath install (reverse proxy at /jellyfin) survives because only '/' is trimmed.
         */
        fun normalizeBaseUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
            else "http://$trimmed"
        }

        /** The user-scoped items route — the one form Jellyfin and Emby both serve. */
        fun itemsUrl(session: Session, startIndex: Int, limit: Int): String =
            "${session.baseUrl}/Users/${session.userId}/Items?recursive=true" +
                "&includeItemTypes=Movie,Episode&fields=$FIELDS&enableImages=true" +
                "&startIndex=$startIndex&limit=$limit"

        /** The item's own poster (movie poster / episode still), when the server has one. */
        fun primaryImageUrl(session: Session, item: Item): String? = item.primaryImageTag?.let {
            "${session.baseUrl}/Items/${item.id}/Images/Primary?tag=$it&quality=90"
        }

        /** The SERIES poster for an episode — what the show's card on the wall should wear. */
        fun seriesImageUrl(session: Session, item: Item): String? =
            if (item.seriesId != null && item.seriesPrimaryImageTag != null) {
                "${session.baseUrl}/Items/${item.seriesId}/Images/Primary?tag=${item.seriesPrimaryImageTag}&quality=90"
            } else null

        fun backdropImageUrl(session: Session, item: Item): String? = item.backdropImageTag?.let {
            "${session.baseUrl}/Items/${item.id}/Images/Backdrop/0?tag=$it&quality=90"
        }

        /** Direct-play stream URL — the container passes through untouched; the player demuxes. */
        fun streamUrl(session: Session, itemId: String): String =
            "${session.baseUrl}/Videos/$itemId/stream?static=true&api_key=" +
                URLEncoder.encode(session.token, "UTF-8")

        /** The item a direct-play [streamUrl] (signed or not) plays, or null for any other address. */
        fun itemIdOf(url: String): String? = Regex("/Videos/([^/?]+)/stream").find(url)?.groupValues?.get(1)

        /** The text format a subtitle [codec] is asked for in, as both servers name them; null for a picture one. */
        private fun textFormat(codec: String?): String? = when (codec?.lowercase()) {
            "srt", "subrip" -> "srt"
            "ass" -> "ass"
            "ssa" -> "ssa"
            "vtt", "webvtt" -> "vtt"
            else -> null
        }

        /**
         * An item's external text subtitles from its full description ([body], `/Users/{id}/Items/{id}`):
         * the streams of its first media source marked external, each at its route
         * `/Videos/{item}/{source}/Subtitles/{index}/Stream.{format}` — the same on Jellyfin and Emby.
         */
        fun parseExternalSubtitles(baseUrl: String, itemId: String, token: String, body: String): List<ExternalSubtitle> {
            val o = JSONObject(body)
            fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }
            val source = o.optJSONArray("MediaSources")?.optJSONObject(0)
            val streams = source?.optJSONArray("MediaStreams") ?: o.optJSONArray("MediaStreams") ?: return emptyList()
            val sourceId = source?.text("Id") ?: itemId
            return (0 until streams.length()).mapNotNull { i ->
                val s = streams.optJSONObject(i) ?: return@mapNotNull null
                if (s.text("Type") != "Subtitle" || !s.optBoolean("IsExternal") || s.isNull("Index")) return@mapNotNull null
                val format = textFormat(s.text("Codec")) ?: return@mapNotNull null
                ExternalSubtitle(
                    url = "$baseUrl/Videos/$itemId/$sourceId/Subtitles/${s.optInt("Index")}/Stream.$format?api_key=" +
                        URLEncoder.encode(token, "UTF-8"),
                    // A Windows server's path is written with backslashes.
                    fileName = s.text("Path")?.substringAfterLast('/')?.substringAfterLast('\\')?.ifBlank { null },
                    format = format,
                    language = s.text("Language"),
                )
            }
        }

        fun parseAuth(baseUrl: String, body: String): Session {
            val o = JSONObject(body)
            val token = o.optString("AccessToken").ifBlank { throw SourceException(SourceFault.SERVER, "no AccessToken in response") }
            val userId = o.optJSONObject("User")?.optString("Id").orEmpty()
                .ifBlank { throw SourceException(SourceFault.SERVER, "no User.Id in response") }
            return Session(baseUrl, userId, token)
        }

        /** One /Items page → the items we can play plus the server's total count. */
        fun parseItemsPage(body: String): Pair<List<Item>, Int> {
            val o = JSONObject(body)
            val total = o.optInt("TotalRecordCount", 0)
            val arr = o.optJSONArray("Items") ?: return emptyList<Item>() to total
            val items = (0 until arr.length()).mapNotNull { parseItem(arr.optJSONObject(it) ?: return@mapNotNull null) }
            return items to total
        }

        fun parseItem(o: JSONObject): Item? {
            val id = o.optString("Id").takeIf { it.isNotBlank() } ?: return null
            val name = o.optString("Name").takeIf { it.isNotBlank() } ?: return null
            val type = when (o.optString("Type")) {
                "Movie" -> ItemType.MOVIE
                "Episode" -> ItemType.EPISODE
                else -> ItemType.OTHER
            }
            val providers = o.optJSONObject("ProviderIds")
            val images = o.optJSONObject("ImageTags")
            val backdrops = o.optJSONArray("BackdropImageTags")
            val people = o.optJSONArray("People")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val p = arr.optJSONObject(i) ?: return@mapNotNull null
                    val pn = p.optString("Name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Person(pn, p.optString("Role").ifBlank { null }, p.optString("Type").ifBlank { null }, p.optString("Id").ifBlank { null })
                }
            } ?: emptyList()
            return Item(
                id = id,
                type = type,
                name = name,
                originalTitle = o.optString("OriginalTitle").ifBlank { null },
                year = o.optInt("ProductionYear").takeIf { it in 1870..2100 },
                overview = o.optString("Overview").ifBlank { null },
                genres = o.optJSONArray("Genres")?.let { g -> (0 until g.length()).map { g.optString(it) } } ?: emptyList(),
                communityRating = o.optDouble("CommunityRating").takeIf { !it.isNaN() && it > 0 },
                officialRating = o.optString("OfficialRating").ifBlank { null },
                runTimeTicks = o.optLong("RunTimeTicks").takeIf { it > 0 },
                tmdbId = providers?.optString("Tmdb")?.toIntOrNull(),
                imdbId = providers?.optString("Imdb")?.ifBlank { null },
                path = o.optString("Path").ifBlank { null },
                video3dFormat = o.optString("Video3DFormat").ifBlank { null },
                primaryImageTag = images?.optString("Primary")?.ifBlank { null },
                backdropImageTag = backdrops?.optString(0)?.ifBlank { null },
                seriesId = o.optString("SeriesId").ifBlank { null },
                seriesName = o.optString("SeriesName").ifBlank { null },
                seriesPrimaryImageTag = o.optString("SeriesPrimaryImageTag").ifBlank { null },
                season = if (o.has("ParentIndexNumber")) o.optInt("ParentIndexNumber") else null,
                episode = if (o.has("IndexNumber")) o.optInt("IndexNumber") else null,
                people = people,
            )
        }
    }
}

/** Wrong credentials — the one connection failure the UI fixes by asking the user again. */
class JellyfinAuthException(message: String) : Exception(message)
