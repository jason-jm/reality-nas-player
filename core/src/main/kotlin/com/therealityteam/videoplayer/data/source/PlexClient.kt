package com.therealityteam.videoplayer.data.source

import com.therealityteam.videoplayer.util.PRODUCT_NAME
import com.therealityteam.videoplayer.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Plex Media Server REST client — the read side of "adopt the library the user already
 * curated", Plex flavour. Like [JellyfinClient] it is transport and parsing only: every item
 * arrives with the identity the server settled (title, year, TMDB/IMDb guids, cast, artwork)
 * and nothing here touches the scrape pipeline.
 *
 * Auth is a token: Plex signs its API with an `X-Plex-Token` the user copies from the server
 * (or from any signed-in Plex client's XML). It is sent as a header on every call; images and
 * the direct-play stream carry it as a query parameter because the player and the image
 * cache fetch those URLs on their own.
 */
class PlexClient {

    /** A verified connection: where, and the token the server accepted. */
    data class Session(val baseUrl: String, val token: String)

    enum class ItemType { MOVIE, EPISODE }

    /** A library section as `/library/sections` lists it; only movie and show sections matter. */
    data class Section(val key: String, val type: String, val title: String)

    data class Credit(val name: String, val role: String?)

    /**
     * One playable item as the server describes it. Identity fields are the server's word and
     * are carried verbatim; nothing is re-parsed from [file]'s name.
     */
    data class Item(
        val ratingKey: String,
        val type: ItemType,
        val title: String,
        val originalTitle: String? = null,
        val year: Int? = null,
        val summary: String? = null,
        val genres: List<String> = emptyList(),
        /** The audience score when there is one, else the critic score — both out of 10. */
        val rating: Double? = null,
        val contentRating: String? = null,
        val durationMs: Long? = null,
        val tmdbId: Int? = null,
        val imdbId: String? = null,
        /** Server-side file path — display/debug only. */
        val file: String? = null,
        /** The direct-play part, e.g. `/library/parts/123/1700000000/file.mkv`. */
        val partKey: String? = null,
        val thumb: String? = null,
        val art: String? = null,
        // Episode-only: the show it belongs to and the show's poster, which a card wants.
        val showKey: String? = null,
        val showTitle: String? = null,
        val showThumb: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val cast: List<Credit> = emptyList(),
        val directors: List<String> = emptyList(),
        val writers: List<String> = emptyList(),
    ) {
        val runtimeMinutes: Int? get() = durationMs?.let { (it / 60_000L).toInt() }.takeIf { (it ?: 0) > 0 }
    }

    /**
     * Verify the server and token with the lightest call there is. Throws [PlexAuthException]
     * when the token is refused (the one failure the UI can fix by asking the user again).
     */
    suspend fun connect(serverUrl: String, token: String): Session = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(serverUrl)
        val conn = (URL("$base/identity").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            headers(token).forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            when (val code = conn.responseCode) {
                in 200..299 -> Session(base, token)
                401, 403 -> throw PlexAuthException("HTTP 401/403")
                else -> throw SourceException(SourceFault.SERVER, "Plex HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Every movie of every movie section and every episode of every show section, paged. */
    suspend fun allItems(session: Session): List<Item> = withContext(Dispatchers.IO) {
        val body = Http.getOrThrow("${session.baseUrl}/library/sections", headers(session.token))
            ?: throw SourceException(SourceFault.SERVER, "Plex sections HTTP 4xx")
        val out = mutableListOf<Item>()
        parseSections(body).forEach { section ->
            val type = when (section.type) {
                "movie" -> 1
                "show" -> 4          // 4 = episodes, listed flat across the section's shows
                else -> return@forEach
            }
            var start = 0
            while (true) {
                val url = itemsUrl(session, section.key, type, start, PAGE_SIZE)
                val page = Http.getOrThrow(url, headers(session.token))
                    ?: throw SourceException(SourceFault.SERVER, "Plex items HTTP 4xx")
                val (items, total) = parseItemsPage(page)
                out += items
                start += PAGE_SIZE
                if (items.isEmpty() || start >= total) break
            }
        }
        out
    }

    companion object {
        private const val PAGE_SIZE = 500

        /** The identification Plex expects on every request, plus the token and a JSON ask. */
        fun headers(token: String): Map<String, String> = mapOf(
            "X-Plex-Token" to token,
            "Accept" to "application/json",
            "X-Plex-Client-Identifier" to "aura-pico",
            "X-Plex-Product" to PRODUCT_NAME,
            "X-Plex-Version" to "1.0",
            "X-Plex-Platform" to "Android",
        )

        /**
         * What the user types becomes a base URL: scheme added when missing, Plex's own port
         * (32400) added when none is given, trailing slashes trimmed.
         */
        fun normalizeBaseUrl(input: String): String {
            var s = input.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            val hostPart = s.substringAfter("://").substringBefore('/')
            if (!hostPart.contains(':') && !s.startsWith("https://")) {
                s = s.replaceFirst(hostPart, "$hostPart:32400")
            }
            return s
        }

        fun itemsUrl(session: Session, sectionKey: String, type: Int, start: Int, size: Int): String =
            "${session.baseUrl}/library/sections/$sectionKey/all?type=$type&includeGuids=1" +
                "&X-Plex-Container-Start=$start&X-Plex-Container-Size=$size"

        /** A server image path (`/library/metadata/1/thumb/2`) as a fetchable, signed URL. */
        fun imageUrl(session: Session, path: String?): String? = path?.takeIf { it.isNotBlank() }?.let {
            session.baseUrl + it + (if (it.contains('?')) "&" else "?") + "X-Plex-Token=" + encode(session.token)
        }

        /** The token-free direct-play URL a library entry keeps; playback signs it at open. */
        fun streamPath(session: Session, partKey: String): String = session.baseUrl + partKey

        /** [streamPath] signed for the player. */
        fun signed(path: String, token: String): String =
            path + (if (path.contains('?')) "&" else "?") + "X-Plex-Token=" + encode(token)

        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

        fun parseSections(body: String): List<Section> {
            val arr = JSONObject(body).optJSONObject("MediaContainer")?.optJSONArray("Directory") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val d = arr.optJSONObject(i) ?: return@mapNotNull null
                val key = d.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Section(key, d.optString("type"), d.optString("title"))
            }
        }

        /** One page of a section → the items plus the section's total count. */
        fun parseItemsPage(body: String): Pair<List<Item>, Int> {
            val mc = JSONObject(body).optJSONObject("MediaContainer") ?: return emptyList<Item>() to 0
            val total = mc.optInt("totalSize", mc.optInt("size", 0))
            val arr = mc.optJSONArray("Metadata") ?: return emptyList<Item>() to total
            val items = (0 until arr.length()).mapNotNull { parseItem(arr.optJSONObject(it) ?: return@mapNotNull null) }
            return items to total
        }

        fun parseItem(o: JSONObject): Item? {
            val key = o.optString("ratingKey").takeIf { it.isNotBlank() } ?: return null
            val title = o.optString("title").takeIf { it.isNotBlank() } ?: return null
            val type = when (o.optString("type")) {
                "movie" -> ItemType.MOVIE
                "episode" -> ItemType.EPISODE
                else -> return null
            }
            val part = o.optJSONArray("Media")?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0)
            val guids = o.optJSONArray("Guid")?.let { tags(it, "id") } ?: emptyList()
            val roles = o.optJSONArray("Role")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val r = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = r.optString("tag").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Credit(name, r.optString("role").ifBlank { null })
                }
            } ?: emptyList()
            return Item(
                ratingKey = key,
                type = type,
                title = title,
                originalTitle = o.optString("originalTitle").ifBlank { null },
                year = o.optInt("year").takeIf { it in 1870..2100 },
                summary = o.optString("summary").ifBlank { null },
                genres = o.optJSONArray("Genre")?.let { tags(it, "tag") } ?: emptyList(),
                rating = o.optDouble("audienceRating").takeIf { !it.isNaN() && it > 0 }
                    ?: o.optDouble("rating").takeIf { !it.isNaN() && it > 0 },
                contentRating = o.optString("contentRating").ifBlank { null },
                durationMs = o.optLong("duration").takeIf { it > 0 },
                tmdbId = guids.firstOrNull { it.startsWith("tmdb://") }?.removePrefix("tmdb://")?.toIntOrNull(),
                imdbId = guids.firstOrNull { it.startsWith("imdb://") }?.removePrefix("imdb://"),
                file = part?.optString("file")?.ifBlank { null },
                partKey = part?.optString("key")?.ifBlank { null },
                thumb = o.optString("thumb").ifBlank { null },
                art = o.optString("art").ifBlank { null },
                showKey = o.optString("grandparentRatingKey").ifBlank { null },
                showTitle = o.optString("grandparentTitle").ifBlank { null },
                showThumb = o.optString("grandparentThumb").ifBlank { null },
                season = if (o.has("parentIndex")) o.optInt("parentIndex") else null,
                episode = if (o.has("index") && type == ItemType.EPISODE) o.optInt("index") else null,
                cast = roles,
                directors = o.optJSONArray("Director")?.let { tags(it, "tag") } ?: emptyList(),
                writers = o.optJSONArray("Writer")?.let { tags(it, "tag") } ?: emptyList(),
            )
        }

        private fun tags(arr: JSONArray, field: String): List<String> =
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString(field)?.ifBlank { null } }
    }
}

/** A refused token — the one connection failure the UI fixes by asking the user again. */
class PlexAuthException(message: String) : Exception(message)
