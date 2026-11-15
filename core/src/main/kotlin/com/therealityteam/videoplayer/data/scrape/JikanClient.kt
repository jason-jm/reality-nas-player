package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.util.PRODUCT_AGENT
import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Jikan (api.jikan.moe) — the free, key-less MyAnimeList API. Looks up an anime by title and returns
 * its MAL score (0–10). Jikan is a volunteer service (~3 req/s, 60 req/min per IP), so calls are
 * throttled to ~1/s and results (incl. misses) are cached to disk to keep us well-behaved and to
 * avoid re-querying on re-scans — which is what used to drop the odd MAL score to a 429.
 */
class JikanClient(filesDir: java.io.File) {

    private val cache = ScrapeCache(filesDir, "jikan", TTL_MS)

    suspend fun malRating(title: String): RatingScore? = withContext(Dispatchers.IO) {
        val q = title.trim()
        if (q.isEmpty()) return@withContext null
        val key = q.lowercase()
        cache.get(key)?.let { return@withContext it.asRating() }

        val url = "https://api.jikan.moe/v4/anime?q=${URLEncoder.encode(q, "UTF-8")}&limit=1"
        // null body = transient (network / 429) → don't poison the cache; a re-scan retries.
        val body = limiter.withPermit { Http.get(url, mapOf("User-Agent" to USER_AGENT)) }
            ?: return@withContext null
        val score = runCatching {
            JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optDouble("score")
        }.getOrNull()?.takeIf { it > 0 }
        val value = score?.let { RatingScore.tenths(it) } ?: "" // "" = looked up, no score (negative cache)
        cache.put(key, value)
        value.asRating()
    }

    private fun String.asRating(): RatingScore? =
        if (isBlank()) null else RatingScore(RatingSource.MAL, this)

    private companion object {
        const val USER_AGENT = "$PRODUCT_AGENT (PICO Spatial video player)"
        const val TTL_MS = 30L * 24 * 3600 * 1000 // 30 days
        // ~1.1s spacing ⇒ ≤55/min, comfortably inside Jikan's 60/min + 3/s limits.
        // Jikan's cap is 3/s AND 60/min; 1100ms (~54/min) left 10% headroom that a single retry
        // or clock skew could eat. 48/min keeps a whole scan comfortably inside both budgets.
        val limiter = RateLimiter(1_250)
    }
}
