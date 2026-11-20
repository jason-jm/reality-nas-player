package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.util.TransientHttpException
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Public show IDs stay in their own namespace. In particular a NeoDB season can name its parent show. */
internal class PublicSeriesCatalog(private val read: suspend (String, String) -> JSONObject?) {
    private suspend fun get(url: String) = read(url, TmdbConfig.language)

    suspend fun match(names: List<String>, year: Int?, season: Int?, documentary: Boolean, allowWikidata: Boolean = true): MetaResult? {
        if (TmdbConfig.offline) return null
        var unavailable: TransientHttpException? = null
        for (name in names) {
            var ambiguousLead: MetaResult? = null
            fun literal(record: MetaResult): Boolean = (listOfNotNull(record.title, record.originalTitle) + record.synonyms)
                .any { literalName(it) == literalName(name) }
            for (source in SOURCES) {
                if (source == "wikidata-tv" && !allowWikidata) continue
                try {
                    val options = search(source, name)
                    val sameName = options.filter { record ->
                        (source != "wikidata-tv" || record.year != null || record.imdbId?.matches(IMDB_ID) == true) &&
                            (!documentary || record.genres.isNotEmpty() && ShelfFit.documentary(record.genres)) &&
                            (listOfNotNull(record.title, record.originalTitle) + record.synonyms).any { sameName(name, it) }
                    }.distinctBy { it.publicMovieKey }
                    val dated = if (year == null) sameName else sameName.filter { it.year != null && abs(it.year - year) <= 1 }
                    if (dated.size > 1) {
                        // Punctuation matters for some remakes ("Change" and "Change!").
                        // A unique literal title is still only a lead until a different catalogue
                        // confirms the same IMDb series ID. Identically spelled remakes stay open.
                        val lead = dated.singleOrNull(::literal)?.takeIf { it.imdbId?.matches(IMDB_ID) == true } ?: return null
                        val previous = ambiguousLead
                        if (previous != null) {
                            if (previous.imdbId == lead.imdbId) return previous
                            return null
                        }
                        ambiguousLead = lead
                        continue
                    }
                    val hit = dated.singleOrNull() ?: sameName.singleOrNull()?.takeIf {
                        it.year == null || season != null && season > 1 && year != null && it.year <= year
                    }
                    if (hit != null) {
                        val previous = ambiguousLead ?: return hit
                        if (hit.imdbId != null && literal(hit)) return previous.takeIf { it.imdbId == hit.imdbId }
                    }
                } catch (e: TransientHttpException) {
                    unavailable = e
                }
            }
            if (ambiguousLead != null) return null
        }
        unavailable?.let { throw it }
        return null
    }

    private suspend fun search(source: String, name: String): List<MetaResult> {
        val query = encode(name)
        return when (source) {
            "tvmaze" -> get("https://api.tvmaze.com/search/shows?q=$query")?.objects("items").orEmpty()
                .mapNotNull { it.optJSONObject("show")?.let(::mazeShow) }
            "neodb-tv" -> neoSearch(query, name)
            "wikidata-tv" -> PublicMovieCatalog.wikiSearch(name, TmdbConfig.language) { path ->
                get(PublicMovieCatalog.Source.WIKI.url(path))
            }.filter { it.kind == MediaKind.SHOW }
            "cinemeta-tv" -> {
                // Stremio's addon protocol puts the escaped search text in a path segment.
                val results = get("https://v3-cinemeta.strem.io/catalog/series/top/search=${query.replace("+", "%20")}.json")
                results?.objects("metas").orEmpty().filter { it.str("type") == "series" &&
                    it.str("name")?.let { title -> sameName(name, title) } == true }
                    .mapNotNull { it.str("id")?.takeIf(IMDB_ID::matches) }.distinct().take(4)
                    .mapNotNull { detail("cinemeta-tv:$it") }
            }
            else -> get("https://every.film/api/v1/search?q=$query")?.optJSONObject("results")?.objects("media").orEmpty()
                .filter { it.str("type") == "tv" }.mapNotNull { PublicMovieCatalog.every(it, TmdbConfig.language) }
                .filter { r -> (listOfNotNull(r.title, r.originalTitle) + r.synonyms).any { sameName(name, it) } }
                .distinctBy { it.publicMovieKey }.take(4).mapNotNull { detail(it.publicMovieKey!!) }
        }
    }

    suspend fun manual(name: String): List<MetaResult> {
        if (TmdbConfig.offline || PublicMovieCatalog.queryNames(name).isEmpty()) return emptyList()
        val choices = mutableListOf<MetaResult>()
        for (source in SOURCES) {
            try { choices += search(source, name.trim()) } catch (_: TransientHttpException) { continue }
            if (choices.size >= 8) break
        }
        return choices.distinctBy { it.publicMovieKey }.take(8)
    }

    private suspend fun neoSearch(query: String, name: String): List<MetaResult> {
        val records = get("https://neodb.social/api/catalog/search?query=$query&category=tv")?.objects("data").orEmpty()
        val shows = records.filter { it.str("type") == "TVShow" }.mapNotNull(::neoShow).associateBy { it.publicMovieKey }.toMutableMap()
        // Search often returns several seasons without the parent. Follow a season only when its
        // own title already supplies evidence and confirm identity against the parent's names.
        val parents = records.asSequence().filter { it.str("type") == "TVSeason" }.filter { row ->
            (listOfNotNull(row.str("orig_title"), row.str("title")) + row.objects("localized_title").mapNotNull { it.str("text") })
                .any { sameName(name, it.replace(SEASON_SUFFIX, "").trim()) }
        }.mapNotNull { it.str("parent_uuid")?.takeIf(NEO_ID::matches) }.distinct().take(4)
        for (id in parents) {
            val key = "neodb-tv:$id"
            if (key !in shows) detail(key)?.let { shows[key] = it }
        }
        return shows.values.toList()
    }

    suspend fun detail(key: String): MetaResult? {
        if (TmdbConfig.offline) return null
        val id = key.substringAfter(':')
        return when {
            key.startsWith("tvmaze:") && MAZE_ID.matches(id) ->
                get("https://api.tvmaze.com/shows/$id")?.let(::mazeShow)?.takeIf { it.publicMovieKey == key }
            key.startsWith("neodb-tv:") && NEO_ID.matches(id) ->
                get("https://neodb.social/api/tv/$id")?.let(::neoShow)?.takeIf { it.publicMovieKey == key }
            key.startsWith("wikidata-tv:") && PublicMovieCatalog.wikiId.matches(id) ->
                get(PublicMovieCatalog.Source.WIKI.url(PublicMovieCatalog.wikiPath(listOf(id), TmdbConfig.language)))
                    ?.optJSONObject("entities")?.optJSONObject(id)?.let { PublicMovieCatalog.wiki(it, TmdbConfig.language) }
                    ?.takeIf { it.publicMovieKey == key }
            key.startsWith("everyfilm-tv:") && PublicMovieCatalog.uuid.matches(id) ->
                get("https://every.film/api/v1/media/$id/detail")?.takeIf { it.str("type") == "tv" && it.str("id") == id }
                    ?.let { PublicMovieCatalog.every(it, TmdbConfig.language) }
            key.startsWith("cinemeta-tv:") && IMDB_ID.matches(id) -> {
                val show = get("https://v3-cinemeta.strem.io/meta/series/$id.json")?.optJSONObject("meta") ?: return null
                if (show.str("type") != "series" || show.str("id") != id ||
                    show.str("imdb_id")?.let { it != id } == true) return null
                val title = show.str("name") ?: return null
                val year = sequenceOf("releaseInfo", "year", "released")
                    .mapNotNull { show.str(it)?.take(4)?.toIntOrNull() }.firstOrNull()
                MetaResult(0, MediaKind.SHOW, title, originalTitle = show.str("originalName"),
                    year = year, genres = show.strings("genres"), imdbId = id, publicMovieKey = key)
            }
            else -> null
        }
    }

    suspend fun episodes(key: String): Map<Pair<Int, Int>, EpisodeInfo> {
        val id = key.removePrefix("tvmaze:")
        if (TmdbConfig.offline || !key.startsWith("tvmaze:") || !MAZE_ID.matches(id)) return emptyMap()
        return get("https://api.tvmaze.com/shows/$id/episodes")?.objects("items").orEmpty().mapNotNull { row ->
            val season = row.optInt("season")
            val number = row.optInt("number")
            if (season < 1 || number < 1) return@mapNotNull null
            val name = row.str("name")?.takeUnless(EpisodeNames::isPlaceholder)
            val plot = row.str("summary")?.let(::plainText)
            (season to number) to EpisodeInfo(name, plot, mazeImage(row))
        }.toMap()
    }

    private fun mazeShow(row: JSONObject): MetaResult? {
        val id = row.optInt("id")
        if (id <= 0) return null
        val title = row.str("name") ?: return null
        val genres = (row.strings("genres") + listOfNotNull(row.str("type")?.takeIf { it == "Documentary" })).distinct()
        return MetaResult(0, MediaKind.SHOW, title, year = row.str("premiered")?.take(4)?.toIntOrNull(),
            genres = genres, overview = row.str("summary")?.let(::plainText),
            imdbId = row.optJSONObject("externals")?.str("imdb"), runtime = row.optInt("averageRuntime").takeIf { it > 0 },
            posterUrl = mazeImage(row), publicMovieKey = "tvmaze:$id")
    }

    private fun neoShow(row: JSONObject): MetaResult? {
        if (row.str("type") != "TVShow") return null
        val id = row.str("uuid")?.takeIf(NEO_ID::matches) ?: return null
        if (row.str("url") != "/tv/$id") return null
        val labels = row.objects("localized_title")
        val title = PublicMovieCatalog.localized(labels, TmdbConfig.language, "lang", "text") ?: row.str("title") ?: return null
        return MetaResult(0, MediaKind.SHOW, title, originalTitle = row.str("orig_title"),
            synonyms = (labels.mapNotNull { it.str("text") } + listOfNotNull(row.str("title"))).distinct(),
            year = row.str("release_date")?.take(4)?.toIntOrNull() ?: row.optInt("year").takeIf { it > 0 },
            genres = row.strings("genre"), imdbId = row.str("imdb"), overview = PublicMovieCatalog.overview(
                row.objects("localized_description"), TmdbConfig.language, "lang", "text", row.str("description")),
            posterUrl = row.str("cover_image_url")?.takeIf { it.startsWith("https://neodb.social/") }, publicMovieKey = "neodb-tv:$id")
    }

    companion object {
        private val SOURCES = listOf("tvmaze", "neodb-tv", "everyfilm-tv", "cinemeta-tv", "wikidata-tv")
        private val IMDB_ID = Regex("tt[0-9]{5,12}")
        private val MAZE_ID = Regex("[1-9][0-9]{0,9}")
        private val NEO_ID = Regex("[a-zA-Z0-9]{10,32}")
        private val MINI_SERIES = Regex("""(?i)\bmini[ ._-]*series\b|迷你[剧劇]|ミニシリーズ""")
        private val OF_TOTAL = Regex("""(?i)(?<![A-Za-z0-9])(\d{1,2})\s*of\s*(\d{1,2})(?![0-9])""")
        private val AFTER_DATE = Regex("""[\[（(](?:19|20)\d{2}[\]）)][ ._-]+(\d{1,2})$""")
        private val PART = Regex("""(?i)\b(?:part|pt)[ ._-]*(\d{1,2})\b""")
        private val DISC = Regex("""(?i)\b(?:cd|dvd|disc|disk)[ ._-]*\d""")
        private val SEASON_SUFFIX = Regex("""(?i)\s*[:,：–-]?\s*(?:season\s+\d+|第[一二三四五六七八九十零\d]+季)$""")
        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun sameName(a: String, b: String) = StringSimilarity.normalizeTitle(a).replace(" ", "") ==
            StringSimilarity.normalizeTitle(b).replace(" ", "")
        private val SPACE = Regex("""\s+""")
        private fun literalName(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC)
            .trim().lowercase(Locale.ROOT).replace(SPACE, " ")
        private fun plainText(html: String) = Synopsis.unescape(html.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
        private fun mazeImage(row: JSONObject) = row.optJSONObject("image")?.let { it.str("original") ?: it.str("medium") }
            ?.takeIf { it.startsWith("https://static.tvmaze.com/") }

        fun credit(key: String): Pair<String, String>? {
            val id = key.substringAfter(':')
            return when {
                key.startsWith("tvmaze:") && MAZE_ID.matches(id) -> "TVmaze" to "https://www.tvmaze.com/shows/$id"
                key.startsWith("neodb-tv:") && NEO_ID.matches(id) -> "NeoDB" to "https://neodb.social/tv/$id"
                key.startsWith("everyfilm-tv:") && PublicMovieCatalog.uuid.matches(id) -> "every.film" to "https://every.film/"
                key.startsWith("cinemeta-tv:") && IMDB_ID.matches(id) ->
                    "Stremio Cinemeta" to "https://web.stremio.com/#/detail/series/$id"
                key.startsWith("wikidata-tv:") && PublicMovieCatalog.wikiId.matches(id) -> "Wikidata" to "https://www.wikidata.org/wiki/$id"
                else -> null
            }
        }

        fun instalment(file: String): Int? {
            val stem = file.substringBeforeLast('.')
            if (DISC.containsMatchIn(stem)) return null
            if (isMiniseries(file)) PART.find(stem)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
            OF_TOTAL.find(stem)?.let { m ->
                val part = m.groupValues[1].toInt()
                val total = m.groupValues[2].toInt()
                if (total >= 3 && part in 1..total) return part
            }
            return AFTER_DATE.find(stem)?.groupValues?.get(1)?.toInt()?.takeIf { it > 0 }
        }

        fun isMiniseries(file: String) = MINI_SERIES.containsMatchIn(file)

        fun queries(path: String, file: String, parsed: ParsedName): List<String> {
            val tv = FilenameParser.isUnderTvRoot(path)
            val doc = FilenameParser.isUnderDocRoot(path)
            val mixed = FilenameParser.isUnderMovieRoot(path) &&
                (parsed.year != null || instalment(file) != null || isMiniseries(file))
            if ((!tv && !doc && !parsed.isEpisode && !mixed) || FilenameParser.isUnderAnimeRoot(path) ||
                FilenameParser.isUnderMusicRoot(path) || FilenameParser.nothingToLookUp(path, file, parsed.title)) return emptyList()
            val parent = FilenameParser.folderName(path).orEmpty()
            val programme = FilenameParser.programmeTitle(file, parent)
            val show = FilenameParser.showFolderTitle(path)?.takeUnless { FilenameParser.isShelfFolder(parent) && doc }
            return listOfNotNull(programme, parsed.title, show).flatMap(PublicMovieCatalog::queryNames).filter {
                it.length >= 2 && it.any(Char::isLetter) && !FilenameParser.isShelfFolder(it) && !FilenameParser.isJunkTitle(it)
            }.distinct().take(3)
        }
    }
}
