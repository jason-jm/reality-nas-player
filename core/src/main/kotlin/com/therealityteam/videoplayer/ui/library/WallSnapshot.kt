package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.therealityteam.videoplayer.util.Log
import com.therealityteam.videoplayer.util.elapsedMillis

/**
 * The poster wall as it last stood, kept on disk so the next launch can paint it at once.
 *
 * A cold start reads the library (32 MB of JSON for 9.6k files) and groups it before the wall
 * has anything to show — seconds on a headset, most of a minute on the emulator — and until then
 * the user looks at "还没有内容". The snapshot is what the cards need to be drawn, filtered by
 * section and sorted by any order: title, year, poster, colour, count, category, sort keys, the
 * favourite / watched marks. It is never opened into: each card stands on one stub item, and the
 * shell keeps taps off until the live library has replaced it. Written after every settled
 * regroup; an empty live library writes an empty snapshot, so 清空媒体库 clears it too.
 */
object WallSnapshot {
    private const val PATH = "aura/wall.json"
    const val SOURCE = "wall-snapshot"

    fun file(filesDir: File): File = File(filesDir, PATH)

    fun load(filesDir: File): List<MediaGroup> {
        val f = file(filesDir)
        if (!f.exists()) return emptyList()
        val t0 = elapsedMillis()
        val text = runCatching { f.readText() }.getOrNull() ?: return emptyList()
        val read = elapsedMillis()
        val out = runCatching { decode(text) }.getOrDefault(emptyList())
        Log.i(
            "AuraPerf",
            "wall snapshot read: ${out.size} cards, ${f.length() / 1024}KB, " +
                "read=${read - t0}ms decode=${elapsedMillis() - read}ms",
        )
        return out
    }

    fun save(filesDir: File, groups: List<MediaGroup>) {
        val f = file(filesDir)
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(encode(groups))
            if (!tmp.renameTo(f)) {
                f.writeText(encode(groups))
                tmp.delete()
            }
        }
    }

    /** `SOURCE|value|votes`, the compact form ratings are written in. */
    private fun ratingOf(s: String): RatingScore? {
        if (s.isBlank()) return null
        val parts = s.split('|')
        if (parts.size < 2) return null
        val src = runCatching { RatingSource.valueOf(parts[0]) }.getOrNull() ?: return null
        return RatingScore(src, RatingScore.stored(parts[1]), votes = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 })
    }

    /** Is this card a stand-in from the snapshot rather than a live one? */
    fun isStub(group: MediaGroup): Boolean = group.items.firstOrNull()?.sourceId == SOURCE

    fun encode(groups: List<MediaGroup>): String {
        val arr = JSONArray()
        groups.forEach { g ->
            val o = JSONObject()
                .put("key", g.key)
                .put("title", g.title)
                .put("isSeries", g.isSeries)
                .put("seasonal", g.seasonal)
                .put("category", g.category.name)
                // What the card's badge says (MediaGroup.shownCount), so the next cold start says the same.
                .put("count", g.shownCount)
                .put("addedAt", g.items.maxOf { it.addedAt })
                .put("watchedAt", g.items.maxOf { it.progress.updatedAt })
                .put("started", g.items.any { it.progress.started })
                .put("favorite", g.items.any { it.favorite })
                .put("titleKey", g.titleKey)
                // Whether a database named this card. Without it every stub claimed to be named,
                // and A→Z — which puts the works first and the files nobody could name after them —
                // saw one flat alphabet: a library opened on `00 Footage`, `01-Pick`, `03-BROLL`
                // until the real library arrived and the order changed under the reader.
                .put("identified", LibrarySort.identified(g))
                .put("keys", JSONArray(g.searchKeys))
                // A rating as one string rather than an object: a 869-card snapshot carried about a
                // thousand of them as three thousand strings across a thousand nested objects, and
                // `TMDB|8.5|1234` says the same thing in one.
                .put("ratings", JSONArray(g.ratings.map { "${it.source.name}|${it.value}|${it.votes ?: 0}" }))
            g.year?.let { o.put("year", it) }
            g.posterPath?.let { o.put("poster", it) }
            g.dominantColor?.let { o.put("color", it) }
            g.runtime?.let { o.put("runtime", it) }
            g.certification?.let { o.put("certification", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    fun decode(json: String): List<MediaGroup> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = o.optString("title")
            val isSeries = o.optBoolean("isSeries")
            val category = runCatching { LibCategory.valueOf(o.optString("category")) }.getOrDefault(LibCategory.OTHER)
            val ratings = o.optJSONArray("ratings")?.let { ra ->
                (0 until ra.length()).mapNotNull { j -> ratingOf(ra.optString(j)) }
            } ?: emptyList()
            val keys = o.optJSONArray("keys")?.let { ka -> (0 until ka.length()).map { ka.optString(it) } } ?: emptyList()
            val started = o.optBoolean("started")
            val stub = MediaItem(
                id = "snapshot:$key",
                sourceId = SOURCE,
                path = "",
                fileName = title,
                title = title,
                kind = if (isSeries) MediaKind.EPISODE else MediaKind.MOVIE,
                year = if (o.has("year")) o.optInt("year") else null,
                posterPath = o.optString("poster").takeIf { it.isNotBlank() },
                dominantColor = if (o.has("color")) o.optInt("color") else null,
                ratings = ratings,
                favorite = o.optBoolean("favorite"),
                progress = PlaybackProgress(positionMs = if (started) 1L else 0L, durationMs = 0L, updatedAt = o.optLong("watchedAt")),
                // Older snapshots carry no such field; they were all written as named, so that
                // is what they keep meaning.
                scrapeStatus = if (o.optBoolean("identified", true)) ScrapeStatus.MATCHED else ScrapeStatus.UNMATCHED,
                addedAt = o.optLong("addedAt"),
            )
            MediaGroup(
                key = key,
                title = title,
                year = stub.year,
                posterPath = stub.posterPath,
                backdropPath = null,
                dominantColor = stub.dominantColor,
                runtime = if (o.has("runtime")) o.optInt("runtime") else null,
                certification = o.optString("certification").takeIf { it.isNotBlank() },
                ratings = ratings,
                isSeries = isSeries,
                seasonal = o.optBoolean("seasonal"),
                category = category,
                count = o.optInt("count", 1),
                items = listOf(stub),
                searchKeys = keys,
                titleKey = o.optString("titleKey"),
            )
        }
    }
}
