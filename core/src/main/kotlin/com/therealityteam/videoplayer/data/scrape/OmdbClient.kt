package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.util.Http
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * OMDb (omdbapi.com) — the standard free aggregator for Western movie ratings. Given an IMDb id it
 * returns IMDb, Rotten Tomatoes and Metacritic in one call. Needs a free key ([OmdbConfig.apiKey]);
 * empty key = disabled (returns nothing).
 */
class OmdbClient(cacheDir: File? = null) {

    private val fetch = cacheDir?.let { CachedFetch(it) }


    suspend fun ratings(imdbId: String?): List<RatingScore> = withContext(Dispatchers.IO) {
        if (OmdbConfig.apiKey.isBlank() || imdbId.isNullOrBlank()) return@withContext emptyList()
        val url = "https://www.omdbapi.com/?apikey=${OmdbConfig.apiKey}&i=$imdbId&tomatoes=true"
        val body = (fetch?.get(url) ?: Http.get(url)) ?: return@withContext emptyList()
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext emptyList()
        if (!o.optString("Response").equals("True", ignoreCase = true)) return@withContext emptyList()

        val out = mutableListOf<RatingScore>()
        o.field("imdbRating")?.let { out += RatingScore(RatingSource.IMDB, it) }
        o.optJSONArray("Ratings")?.let { arr ->
            (0 until arr.length()).forEach { i ->
                val r = arr.optJSONObject(i) ?: return@forEach
                if (r.optString("Source") == "Rotten Tomatoes") {
                    r.field("Value")?.let { out += RatingScore(RatingSource.ROTTEN_TOMATOES, it) }
                }
            }
        }
        o.field("Metascore")?.let { out += RatingScore(RatingSource.METACRITIC, it) }
        out
    }

    /** Non-blank, non-"N/A" string field, else null. */
    private fun JSONObject.field(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() && it != "N/A" }
}
