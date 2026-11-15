package com.therealityteam.videoplayer.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Hand-rolled (de)serialization via [org.json] — keeps the build free of codegen
 * plugins (Room/kotlinx-serialization) while still persisting the library to disk.
 */
object MediaJson {

    fun item(o: JSONObject, credits: CreditsTable? = null): MediaItem = MediaItem(
        id = o.getString("id"),
        sourceId = o.optString("sourceId"),
        path = o.optString("path"),
        fileName = o.optString("fileName"),
        kind = enumOf(o.optString("kind"), MediaKind.UNKNOWN),
        title = o.optString("title"),
        originalTitle = o.optStringOrNull("originalTitle"),
        aliases = stringList(o.optJSONArray("aliases")),
        year = o.optIntOrNull("year"),
        overview = o.optStringOrNull("overview") ?: o.optIntOrNull("ov")?.let { credits?.text(it) },
        rating = if (o.has("rating") && !o.isNull("rating")) o.getDouble("rating") else null,
        ratings = ratingList(o.optJSONArray("ratings")),
        runtime = o.optIntOrNull("runtime"),
        certification = o.optStringOrNull("certification"),
        genres = stringList(o.optJSONArray("genres")),
        // Its own copy wins; only an item that has none looks the shared list up, so a file
        // half-written by two different builds cannot lose anybody. See [CreditsTable].
        cast = personList(o.optJSONArray("cast")).ifEmpty { credits?.credits(o.optStringOrNull("creditsKey"))?.first.orEmpty() },
        crew = personList(o.optJSONArray("crew")).ifEmpty { credits?.credits(o.optStringOrNull("creditsKey"))?.second.orEmpty() },
        posterPath = o.optStringOrNull("posterPath"),
        posterGenerated = o.optBoolean("posterGenerated", false),
        stillUrl = o.optStringOrNull("stillUrl"),
        backdropPath = o.optStringOrNull("backdropPath"),
        stillPath = o.optStringOrNull("stillPath"),
        dominantColor = o.optIntOrNull("dominantColor"),
        tmdbId = o.optIntOrNull("tmdbId"),
        showTitle = o.optStringOrNull("showTitle"),
        season = o.optIntOrNull("season"),
        episode = o.optIntOrNull("episode"),
        collectionKey = o.optStringOrNull("collectionKey"),
        contentTag = o.optStringOrNull("contentTag")?.let { runCatching { ContentKind.valueOf(it) }.getOrNull() },
        video = VideoFlags(
            stereo = enumOf(o.optString("stereo"), StereoMode.UNKNOWN),
            projection = enumOf(o.optString("projection"), ProjectionMode.FLAT),
            userOverridden = o.optBoolean("videoOverridden", false),
            lensFov = o.optInt("lensFov", 0),
            rightFirst = o.optBoolean("rightFirst", false),
            fromFile = o.optBoolean("videoFromFile", false),
        ),
        progress = PlaybackProgress(
            positionMs = o.optLong("positionMs", 0L),
            durationMs = o.optLong("durationMs", 0L),
            watched = o.optBoolean("watched", false),
            updatedAt = o.optLong("progressUpdatedAt", 0L),
        ),
        favorite = o.optBoolean("favorite", false),
        audioTrackId = o.optStringOrNull("audioTrackId"),
        subtitleTrackId = o.optStringOrNull("subtitleTrackId"),
        userMatched = o.optBoolean("userMatched", false),
        local = o.optJSONObject("local")?.let { l ->
            LocalMeta(
                nfo = l.optStringOrNull("nfo"),
                showNfo = l.optStringOrNull("showNfo"),
                poster = l.optStringOrNull("poster"),
                fanart = l.optStringOrNull("fanart"),
                stamp = l.optString("stamp"),
            )
        },
        localApplied = o.optStringOrNull("localApplied"),
        fromNfo = o.optBoolean("fromNfo", false),
        scrapeStatus = enumOf(o.optString("scrapeStatus"), ScrapeStatus.PENDING),
        ratingsVersion = o.optInt("ratingsVersion", 0),
        addedAt = o.optLong("addedAt", 0L),
    )

    fun item(m: MediaItem, creditsKey: String? = null, overviewRef: Int? = null): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("sourceId", m.sourceId)
        put("path", m.path)
        put("fileName", m.fileName)
        put("kind", m.kind.name)
        put("title", m.title)
        putOpt("originalTitle", m.originalTitle)
        if (m.aliases.isNotEmpty()) put("aliases", JSONArray(m.aliases))
        putOpt("year", m.year)
        // A shared overview by its index in the header's table, when the caller has pooled them.
        if (overviewRef != null) put("ov", overviewRef) else putOpt("overview", m.overview)
        putOpt("rating", m.rating)
        put("ratings", ratingArray(m.ratings))
        putOpt("runtime", m.runtime)
        putOpt("certification", m.certification)
        put("genres", JSONArray(m.genres))
        // A key instead of the lists themselves, when the caller has pooled them.
        if (creditsKey != null) put("creditsKey", creditsKey) else {
            put("cast", personArray(m.cast))
            put("crew", personArray(m.crew))
        }
        putOpt("posterPath", m.posterPath)
        if (m.posterGenerated) put("posterGenerated", true)
        putOpt("stillUrl", m.stillUrl)
        putOpt("backdropPath", m.backdropPath)
        putOpt("stillPath", m.stillPath)
        putOpt("dominantColor", m.dominantColor)
        putOpt("tmdbId", m.tmdbId)
        putOpt("showTitle", m.showTitle)
        putOpt("season", m.season)
        putOpt("episode", m.episode)
        putOpt("collectionKey", m.collectionKey)
        putOpt("contentTag", m.contentTag?.name)
        put("stereo", m.video.stereo.name)
        put("projection", m.video.projection.name)
        put("videoOverridden", m.video.userOverridden)
        if (m.video.lensFov > 0) put("lensFov", m.video.lensFov)
        if (m.video.rightFirst) put("rightFirst", true)
        if (m.video.fromFile) put("videoFromFile", true)
        put("positionMs", m.progress.positionMs)
        put("durationMs", m.progress.durationMs)
        put("watched", m.progress.watched)
        put("progressUpdatedAt", m.progress.updatedAt)
        put("favorite", m.favorite)
        put("userMatched", m.userMatched)
        // Written only where there is something: most libraries have no nfo files at all, and the
        // file is read in full on every launch.
        m.local?.let { l ->
            put(
                "local",
                JSONObject().apply {
                    putOpt("nfo", l.nfo)
                    putOpt("showNfo", l.showNfo)
                    putOpt("poster", l.poster)
                    putOpt("fanart", l.fanart)
                    put("stamp", l.stamp)
                },
            )
        }
        putOpt("localApplied", m.localApplied)
        if (m.fromNfo) put("fromNfo", true)
        put("scrapeStatus", m.scrapeStatus.name)
        put("ratingsVersion", m.ratingsVersion)
        putOpt("audioTrackId", m.audioTrackId)
        putOpt("subtitleTrackId", m.subtitleTrackId)
        put("addedAt", m.addedAt)
    }

    fun source(o: JSONObject): MediaSourceConfig = MediaSourceConfig(
        id = o.getString("id"),
        type = enumOf(o.optString("type"), SourceType.LOCAL),
        name = o.optString("name"),
        uri = o.optString("uri"),
        username = o.optStringOrNull("username"),
        password = o.optStringOrNull("password"),
        token = o.optStringOrNull("token"),
        serverUserId = o.optStringOrNull("serverUserId"),
        enabled = o.optBoolean("enabled", true),
        addedAt = o.optLong("addedAt", 0L),
    )

    fun source(s: MediaSourceConfig): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("type", s.type.name)
        put("name", s.name)
        put("uri", s.uri)
        putOpt("username", s.username)
        putOpt("password", s.password)
        s.token?.let { put("token", it) }
        putOpt("serverUserId", s.serverUserId)
        put("enabled", s.enabled)
        put("addedAt", s.addedAt)
    }

    fun <T> array(arr: JSONArray?, map: (JSONObject) -> T): List<T> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { map(arr.getJSONObject(it)) }
    }

    fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun ratingList(arr: JSONArray?): List<RatingScore> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val src = runCatching { RatingSource.valueOf(o.optString("source")) }.getOrNull() ?: return@mapNotNull null
            o.optStringOrNull("value")?.let { RatingScore(src, RatingScore.stored(it), votes = o.optInt("votes").takeIf { v -> v > 0 }) }
        }
    }

    private fun ratingArray(list: List<RatingScore>): JSONArray =
        JSONArray().apply {
            list.forEach { r ->
                put(JSONObject().put("source", r.source.name).put("value", r.value).also { o -> r.votes?.let { o.put("votes", it) } })
            }
        }

    /**
     * Reads a [Person] list. Back-compatible with libraries persisted before rich credits existed,
     * where `cast` was a plain array of name strings — those decode to name-only [Person]s.
     */
    fun personList(arr: JSONArray?): List<Person> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            when (val v = arr.opt(i)) {
                is JSONObject -> v.optStringOrNull("name")?.let {
                    Person(it, v.optStringOrNull("role"), v.optStringOrNull("profilePath"), v.optStringOrNull("profileUrl"))
                }
                is String -> v.ifEmpty { null }?.let { Person(it) }
                else -> null
            }
        }
    }

    internal fun personArray(people: List<Person>): JSONArray = JSONArray().apply {
        people.forEach { p ->
            put(
                JSONObject().apply {
                    put("name", p.name)
                    putOpt("role", p.role)
                    putOpt("profilePath", p.profilePath)
                    putOpt("profileUrl", p.profileUrl)
                },
            )
        }
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        if (name.isNullOrEmpty()) default
        else runCatching { enumValueOf<E>(name) }.getOrDefault(default)
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).ifEmpty { null }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)
