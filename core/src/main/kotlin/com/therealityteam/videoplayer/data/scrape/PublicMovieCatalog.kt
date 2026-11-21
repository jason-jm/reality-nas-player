package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.util.Http
import com.therealityteam.videoplayer.util.TransientHttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Anonymous film lookups. Search ranks suggest candidates; titles and release dates establish identity. */
class PublicMovieCatalog internal constructor(private val read: suspend (String, String) -> JSONObject?) {
    constructor(root: File) : this(publicMovieReader(root))
    internal val series = PublicSeriesCatalog(read)
    internal val music = PublicMusicCatalog(read)
    private val broadcastNames = BroadcastReleaseNames(read)

    internal enum class Source(val prefix: String, val host: String) {
        EVERY("everyfilm", "every.film"), NEO("neodb", "neodb.social"), WIKI("wikidata", "www.wikidata.org"),
        OFDB("ofdb", "ofdb.cc");
        fun url(path: String) = "https://$host$path"
    }

    suspend fun match(parsed: ParsedName, alternatives: List<ParsedName> = emptyList(), allowSeries: Boolean = false): MetaResult? {
        if (TmdbConfig.offline || parsed.isEpisode || !safeTitle(parsed.title)) return null
        var unavailable: TransientHttpException? = null
        for (name in (listOf(parsed) + alternatives).distinct()) {
            var ambiguous = false
            val confirmations = mutableListOf<MetaResult>()
            val leading = mutableListOf<MetaResult>()
            for (source in defaults) {
                try {
                    val result = lookup(source, name)
                    if (result.ambiguous && name.year == null) {
                        ambiguous = true
                        result.lead?.let(leading::add)
                    }
                    val film = result.film ?: continue
                    if (!ambiguous) return completeRecord(film, name, allowSeries)
                    // Two catalogues must supply the same external film identifier to resolve an undated remake.
                    val agrees = film.imdbId?.let { id -> (leading + confirmations).firstOrNull { it.imdbId == id } }
                    if (agrees != null) return completeRecord(if (agrees.posterUrl != null) agrees else film, name, allowSeries)
                    confirmations += film
                } catch (error: TransientHttpException) {
                    unavailable = error
                }
            }
        }
        unavailable?.let { throw it }
        return null
    }

    private suspend fun completeRecord(verified: MetaResult, name: ParsedName, allowSeries: Boolean): MetaResult {
        val id = verified.imdbId ?: return verified
        if (name.year == null && verified.year != null && verified.publicMovieKey?.startsWith("neodb:") == true) {
            // Catalogue release dates may be regional. Only a premiere for the same IMDb record
            // in the preceding year can refine an undated filename's year.
            val evidence = try {
                wikiSearch(verified.originalTitle ?: name.title, TmdbConfig.language) { path ->
                    read(Source.WIKI.url(path), TmdbConfig.language)
                }
            } catch (_: TransientHttpException) { emptyList() }
            if (allowSeries && verified.referencedTmdbMovie == null) {
                val show = evidence.singleOrNull { it.kind == MediaKind.SHOW && it.imdbId == id && it.year == verified.year }
                if (show != null) return show
            }
            val earlier = evidence.firstOrNull { it.kind == MediaKind.MOVIE && it.imdbId == id && it.year == verified.year - 1 }
            return if (earlier != null) verified.copy(year = earlier.year) else verified
        }
        if (verified.posterUrl != null || !(verified.publicMovieKey?.startsWith("wikidata:") == true ||
                verified.publicMovieKey?.startsWith("ofdb:") == true)) return verified
        // A release may only supply the short title. A public identifier from a filmography
        // can then establish which fully titled catalogue record owns the artwork.
        val names = (listOfNotNull(name.title, verified.title, verified.originalTitle) + verified.synonyms)
            .map(::normalized).filter(String::isNotEmpty).toSet()
        for (source in listOf(Source.EVERY, Source.NEO)) {
            try {
                for (card in search(source, name.title).take(3)) {
                    if (card.imdbId != null && card.imdbId != id) continue
                    if (card.year != null && verified.year != null && abs(card.year - verified.year) > 1) continue
                    val titles = listOfNotNull(card.title, card.originalTitle) + card.synonyms
                    if (card.imdbId != id && titles.none { normalized(it.substringBefore(':').substringBefore('：')) in names }) continue
                    val rich = detail(card.publicMovieKey!!) ?: continue
                    if (rich.imdbId == id && rich.posterUrl != null &&
                        (rich.year == null || verified.year == null || abs(rich.year - verified.year) <= 1)) return rich.copy(
                        synonyms = (rich.synonyms + verified.synonyms + verified.title).distinct())
                }
            } catch (_: TransientHttpException) {
                // The identity was already established, even while an artwork source is unavailable.
            }
        }
        return verified
    }

    private data class Lookup(val film: MetaResult? = null, val ambiguous: Boolean = false, val lead: MetaResult? = null)

    internal suspend fun match(source: Source, parsed: ParsedName): MetaResult? = lookup(source, parsed).film

    /** A release note supplies a spelling and air date. A catalogue must still identify the programme. */
    internal suspend fun matchBroadcastRelease(file: String, parsed: ParsedName, folder: String): MetaResult? {
        if (parsed.isEpisode || broadcasterName(folder)?.let { it != "nhk" } == true) return null
        val label = broadcastNames.find(file) ?: return null
        if (parsed.year != null && parsed.year != label.year) return null
        fun confirms(record: MetaResult) = record.year == label.year &&
            (listOfNotNull(record.title, record.originalTitle) + record.synonyms).any { normalized(it) == normalized(label.original) }
        val movie = broadcastFilm(ParsedName(label.original, label.year), "NHK", file)
        if (movie != null && confirms(movie)) return movie
        return series.match(listOf(label.original), label.year, null, documentary = true, broadcastFolder = "NHK")?.takeIf(::confirms)
    }

    /** A broadcaster's shelf can mix one-off programmes, fiction and series. */
    internal suspend fun broadcastFilm(name: ParsedName, shelf: String = "", fileName: String = ""): MetaResult? {
        if (TmdbConfig.offline || name.isEpisode || name.title.count(Char::isLetterOrDigit) < 4 ||
            !safeTitle(name.title) || FilenameParser.isJunkTitle(name.title)) return null
        var failure: TransientHttpException? = null
        for (source in listOf(Source.EVERY, Source.NEO)) {
            val found = try { lookup(source, name) } catch (e: TransientHttpException) { failure = e; continue }
            if (found.ambiguous) return null
            val film = found.film ?: continue
            if (film.genres.isNotEmpty()) {
                if (ShelfFit.documentary(film.genres) && !ShelfFit.neverOnDocumentaryShelf(film.genres)) return film
            } else if (hasBroadcastCredit(name.title, film, shelf, fileName)) {
                return film
            }
        }
        failure?.let { throw it }
        return null
    }

    private suspend fun lookup(source: Source, parsed: ParsedName): Lookup {
        for (query in queryNames(parsed.title)) {
            val results = search(source, query)
            fun preview(card: MetaResult) = strength(query, card, parsed.year.takeIf { card.year != null })
            if (parsed.year == null && results.filter { preview(it) > 0 }.distinctBy { it.publicMovieKey }.size > 1) {
                val first = results.firstOrNull()?.takeIf { preview(it) > 0 }
                val lead = if (first != null && (source == Source.EVERY || source == Source.NEO)) detail(first.publicMovieKey!!) else first
                return Lookup(ambiguous = true, lead = lead?.takeIf { strength(query, it, null) > 0 })
            }
            val short = results.filter { parsed.year == null || it.year == null || abs(it.year - parsed.year) <= 1 }
                .sortedByDescending(::preview)
            val seen = mutableListOf<Pair<Int, MetaResult>>()
            for ((index, card) in short.take(3).withIndex()) {
                val preliminary = preview(card)
                // The first card can expose a title absent from the search index in its translations.
                if (index > 0 && preliminary == 0) break
                if (seen.isNotEmpty() && preliminary < seen.maxOf { it.first } && card.year != null) break
                val record = if (source == Source.WIKI || source == Source.OFDB) card else detail(card.publicMovieKey!!) ?: continue
                val score = strength(query, record, parsed.year)
                if (score > 0) seen += score to record
            }
            val best = seen.maxOfOrNull { it.first } ?: continue
            val winners = seen.filter { it.first == best }.map { it.second }.distinctBy { it.publicMovieKey }
            if (winners.size == 1) return Lookup(winners.single())
            if (winners.size > 1) return Lookup(ambiguous = true)
        }
        return Lookup()
    }

    suspend fun manual(query: String): List<MetaResult> {
        if (TmdbConfig.offline || !safeTitle(query)) return emptyList()
        val cards = mutableListOf<MetaResult>()
        for (source in defaults) {
            try { cards += search(source, query.trim()) } catch (_: TransientHttpException) { continue }
            if (cards.size >= 8) break
        }
        return cards.distinctBy { it.publicMovieKey }.take(16)
    }

    internal suspend fun search(source: Source, query: String): List<MetaResult> {
        if (!safeTitle(query) || TmdbConfig.offline) return emptyList()
        val q = encode(query)
        val lang = TmdbConfig.language
        suspend fun get(path: String) = read(source.url(path), lang)
        return when (source) {
            Source.EVERY -> get("/api/v1/search?q=$q")?.optJSONObject("results")?.objects("media").orEmpty()
                .filter { it.str("type") == "movie" }.take(7).mapNotNull { every(it, lang) }
            Source.NEO -> get("/api/catalog/search?query=$q&category=movie")?.objects("data").orEmpty()
                .filter { it.str("type") == "Movie" }.take(6).mapNotNull { neo(it, lang) }
            Source.WIKI -> wikiSearch(query, lang, ::get).filter { it.kind == MediaKind.MOVIE }
            Source.OFDB -> get("/api/node/movie?filter%5Btitle%5D=$q&page%5Blimit%5D=6")
                ?.objects("data").orEmpty().mapNotNull(::ofdb)
        }
    }

    suspend fun detail(key: String): MetaResult? {
        if (TmdbConfig.offline) return null
        if (PublicSeriesCatalog.credit(key) != null) return series.detail(key)
        if (PublicMusicCatalog.credit(key) != null) return music.detail(key.substringAfter(':'))
        val id = key.substringAfter(':')
        val lang = TmdbConfig.language
        return when {
            key.startsWith("everyfilm:") && uuid.matches(id) ->
                read(Source.EVERY.url("/api/v1/media/$id/detail"), lang)?.takeIf { it.str("type") == "movie" && it.str("id") == id }?.let { every(it, lang) }
            key.startsWith("neodb:") && neoId.matches(id) ->
                read(Source.NEO.url("/api/movie/$id"), lang)?.takeIf { it.str("type") == "Movie" && it.str("url") == "/movie/$id" }?.let { neo(it, lang) }
            key.startsWith("wikidata:") && wikiId.matches(id) ->
                read(Source.WIKI.url(wikiPath(listOf(id), lang)), lang)?.optJSONObject("entities")?.optJSONObject(id)
                    ?.let { wiki(it, lang) }
            key.startsWith("ofdb:") && uuid.matches(id) ->
                read(Source.OFDB.url("/api/node/movie/$id"), lang)?.optJSONObject("data")?.let(::ofdb)
            else -> null
        }
    }

    companion object {
        internal val defaults = listOf(Source.EVERY, Source.NEO, Source.WIKI, Source.OFDB)
        private val broadcasters = setOf("nhk", "bbc", "pbs", "itv", "hbo", "cctv", "discovery", "natgeo", "arte")
        internal fun broadcasterName(folder: String): String? = folder.trim().lowercase(Locale.ROOT).takeIf { it in broadcasters }
        private val documentaryLabel = Regex("""(?i)\bdocumentar(?:y|ies)\b|纪录片|紀錄片|記錄片|ドキュメンタリー""")

        private fun hasBroadcastCredit(title: String, film: MetaResult, shelf: String, fileName: String): Boolean {
            val broadcaster = broadcasterName(shelf) ?: return false
            if (title.count(Char::isLetterOrDigit) < 5) return false
            val exact = (listOfNotNull(film.title, film.originalTitle) + film.synonyms).any { normalized(it) == normalized(title) }
            if (!exact) return false
            // Some small broadcasts have no genre. The labelled release and a catalogue
            // production credit can corroborate one another independently of the title.
            if (documentaryLabel.containsMatchIn(fileName) && film.crew.any { it.name.trim().equals(broadcaster, true) }) return true
            // Only accept a short self-description such as "NHK纪录片". A synopsis may
            // mention another station's documentary in passing, even when this is a drama.
            val line = film.overview?.trim() ?: return false
            if (!line.startsWith(broadcaster, ignoreCase = true)) return false
            return documentaryLabel.matches(line.substring(broadcaster.length).trim(' ', '-', ':', '：', '。', '.'))
        }
        private val han = Regex("[\\p{IsHan}]")
        private val bilingual = Regex("""^([\p{IsHan}\p{N}·：:，、\s]+?)\s+([a-zA-Z][a-zA-Z\p{N}\p{P}\s]+)$""")
        private val tmdbMovie = Regex("""^https://(?:www\.)?themoviedb\.org/movie/(\d+)(?:[-/?].*)?$""")
        internal val uuid = Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
        private val neoId = Regex("[A-Za-z0-9]{10,32}")
        private val filmTypes = setOf("Q11424", "Q24869", "Q29168811", "Q506240", "Q93204", "Q226730")
        private val seriesTypes = setOf("Q5398426", "Q581714", "Q1259759", "Q116515088")
        internal val wikiId = Regex("Q[1-9][0-9]*")
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun safeTitle(query: String) = query.isNotBlank() && query.length <= 160 &&
            query.none { it == '/' || it == '\\' || it == '@' || it.isISOControl() }
        internal fun queryNames(title: String): List<String> {
            if (!safeTitle(title)) return emptyList()
            val clean = java.text.Normalizer.normalize(title.trim(), java.text.Normalizer.Form.NFC)
            val split = bilingual.matchEntire(clean.replace(Regex("""(?<=[\p{IsHan}])[-–—]+(?=[a-zA-Z])"""), " "))
            return if (split == null) listOf(clean) else listOf(split.groupValues[2].trim(), split.groupValues[1].trim(), clean).distinct()
        }

        fun credit(key: String?): Pair<String, String>? {
            val id = key?.substringAfter(':') ?: return null
            PublicSeriesCatalog.credit(key)?.let { return it }
            PublicMusicCatalog.credit(key)?.let { return it }
            return when {
                key.startsWith("bangumi:") && (id.toIntOrNull() ?: 0) > 0 -> "Bangumi" to "https://bgm.tv/subject/$id"
                key.startsWith("everyfilm:") && uuid.matches(id) -> "every.film" to "https://every.film/"
                key.startsWith("neodb:") && neoId.matches(id) -> "NeoDB" to "https://neodb.social/movie/$id"
                key.startsWith("wikidata:") && wikiId.matches(id) -> "Wikidata" to "https://www.wikidata.org/wiki/$id"
                key.startsWith("ofdb:") && uuid.matches(id) -> "Open Film Database" to "https://ofdb.cc/api/node/movie/$id"
                else -> null
            }
        }

        internal fun eligible(path: String, filename: String, title: String, parsed: ParsedName): Boolean {
            if (parsed.isEpisode || FilenameParser.isUnderTvRoot(path) || FilenameParser.isUnderMusicRoot(path) ||
                FilenameParser.isUnderAnimeRoot(path) || AnimeParse.parse(filename).isAnimeLikely ||
                FilenameParser.nothingToLookUp(path, filename, title)) return false
            if (!FilenameParser.isUnderDocRoot(path)) return true
            val folder = FilenameParser.folderName(path) ?: return false
            // A feature in its own release folder is searchable. A channel's archive or a numbered
            // instalment does not supply a reliable film identity, even when a catalogue finds one.
            return !FilenameParser.isShelfFolder(folder) && FilenameParser.programmeTitle(filename, folder) == null
        }

        private val articles = setOf("a", "an", "the")
        private val leadingYears = Regex("""^(\d{4})[. _(-]+((?:19|20)\d{2})(?:[^0-9]|$)""")
        private val numericTitle = Regex("""^\[(\d{4})\]""")
        private val siteTag = Regex("""(?i)^[a-z0-9-]+\.(?:com|org|net)$""")

        /** A numeric film title can precede its release year, and the release folder can carry a cleaner title. */
        internal fun fileNames(path: String, parsed: ParsedName): List<ParsedName> {
            if (parsed.isEpisode) return listOf(parsed)
            val stem = path.substringAfterLast('/').substringBeforeLast('.')
            val parent = path.substringBeforeLast('/', "").substringAfterLast('/')
            val numbered = leadingYears.find(stem)
            if (numbered != null) return listOf(ParsedName(numbered.groupValues[1], numbered.groupValues[2].toInt()))
            val bracketed = numericTitle.find(stem)?.groupValues?.get(1)
            if (bracketed != null && parent == bracketed) return listOf(ParsedName(bracketed))
            val folder = FilenameParser.parse(parent)
            val folderUsable = (folder.year != null || FilenameParser.looksPublished(parent)) &&
                safeTitle(folder.title) && !FilenameParser.isJunkTitle(folder.title)
            val additional = if (folderUsable) listOf(folder.copy(year = folder.year ?: parsed.year)) else emptyList()
            return (if (siteTag.matches(stem)) additional else listOf(parsed) + additional).distinct()
        }

        private fun normalized(name: String) = StringSimilarity.normalizeTitle(name).replace(" ", "")
        private fun strength(query: String, record: MetaResult, year: Int?): Int {
            if (year != null && record.year != null && abs(year - record.year) > 1) return 0
            val q = normalized(query)
            if (q.isEmpty()) return 0
            val names = (listOfNotNull(record.title, record.originalTitle) + record.synonyms).distinct()
            val exact = names.any { normalized(it) == q }
            // A release may use only the subtitle of a franchise. Require its own year and a word boundary.
            val suffix = year != null && year == record.year && q.length >= 7 && names.any { name ->
                name.split(Regex("[:：–—]")).drop(1).any { normalized(it) == q }
            }
            val words = StringSimilarity.normalizeTitle(query).split(' ')
            fun significant(s: String) = StringSimilarity.normalizeTitle(s).split(' ').filterNot { it in articles }
            val articlesOnly = year != null && year == record.year && words.size >= 3 && significant(query).size >= 2 &&
                names.any { significant(it) == significant(query) }
            if (!exact && !suffix && !articlesOnly) return 0
            val date = when {
                year == null -> 0
                record.year == null -> return 0
                year == record.year -> 2
                else -> 1
            }
            return (if (exact) 4 else 1) + date
        }

        internal fun every(j: JSONObject, lang: String): MetaResult? {
            val id = j.str("id")?.takeIf(uuid::matches) ?: return null
            val show = j.str("type") == "tv"
            val titles = j.objects("titles")
            val translations = j.objects("translations")
            val aliases = (titles.mapNotNull { it.str("title") } + translations.mapNotNull { it.str("name") } +
                listOfNotNull(j.str("display_title"), j.str("original_title"))).distinct()
            val title = localized(translations, lang, "language_code", "name") ?: localized(titles, lang, "language_code", "title") ?:
                j.str("display_title") ?: j.str("original_title") ?: return null
            val credits = j.objects("credits")
            fun creditsOf(type: String) = credits.filter { it.str("credit_type") == type }.take(20).mapNotNull { c ->
                c.str("name")?.let { Credit(it, c.str("character") ?: c.str("job")) }
            }
            fun image(field: String) = j.str(field)?.takeIf { it.startsWith("/api/v1/img/media/$id/") }?.let(Source.EVERY::url)
            return MetaResult(0, if (show) MediaKind.SHOW else MediaKind.MOVIE, title,
                originalTitle = j.str("original_title"), synonyms = aliases,
                year = j.str("original_release_date")?.take(4)?.toIntOrNull(),
                overview = overview(translations, lang, "language_code", "summary", j.str("overview")),
                imdbId = j.objects("mappings").firstOrNull { it.str("source_platform") == "imdb" }?.str("external_id"),
                runtime = j.optInt("runtime").takeIf { it > 0 }, genres = j.strings("genres"), cast = creditsOf("cast"),
                crew = creditsOf("crew"), posterUrl = image("poster_stored_url"), backdropUrl = image("backdrop_stored_url"),
                publicMovieKey = if (show) "everyfilm-tv:$id" else "everyfilm:$id", referencedTmdbMovie = j.objects("mappings")
                    .firstOrNull { it.str("source_platform") == "tmdb" && it.str("resource_type") == "movie" }?.str("external_id")?.toIntOrNull())
        }

        private fun neo(j: JSONObject, lang: String): MetaResult? {
            val id = j.str("url")?.removePrefix("/movie/")?.takeIf(neoId::matches) ?: return null
            val titles = j.objects("localized_title")
            val title = localized(titles, lang, "lang", "text") ?: j.str("title") ?: return null
            return MetaResult(0, MediaKind.MOVIE, title, originalTitle = j.str("orig_title"),
                synonyms = (titles.mapNotNull { it.str("text") } + listOfNotNull(j.str("title"), j.str("display_title"))).distinct(),
                year = j.optInt("year").takeIf { it > 0 } ?: j.str("release_date")?.take(4)?.toIntOrNull(),
                overview = overview(j.objects("localized_description"), lang, "lang", "text", j.str("description")),
                imdbId = j.str("imdb"), runtime = (j.optInt("length") / 60).takeIf { it > 0 },
                genres = j.strings("genre"), cast = j.strings("actor").take(20).map(::Credit),
                crew = j.strings("director").take(8).map { Credit(it, "Director") },
                posterUrl = j.str("cover_image_url")?.takeIf { it.startsWith("https://neodb.social/") },
                publicMovieKey = "neodb:$id", referencedTmdbMovie = j.objects("external_resources").firstNotNullOfOrNull {
                    tmdbMovie.find(it.str("url").orEmpty())?.groupValues?.get(1)?.toIntOrNull() })
        }

        internal fun wikiPath(ids: List<String>, lang: String): String {
            val languages = (listOf("zh", "zh-hans", "zh-cn", "en", "ja") + lang.substringBefore('-')).distinct().joinToString("|")
            return "/w/api.php?action=wbgetentities&format=json&ids=" + encode(ids.joinToString("|")) +
                "&props=" + encode("claims|labels|aliases|descriptions") + "&languages=" + encode(languages)
        }

        internal suspend fun wikiSearch(query: String, lang: String, get: suspend (String) -> JSONObject?): List<MetaResult> {
            if (!safeTitle(query) || TmdbConfig.offline) return emptyList()
            val searchLanguage = if (han.containsMatchIn(query)) "zh" else "en"
            val ids = get("/w/api.php?action=wbsearchentities&format=json&search=${encode(query)}&language=$searchLanguage&uselang=" +
                encode(lang.substringBefore('-')) + "&type=item&limit=6")?.objects("search").orEmpty()
                .mapNotNull { it.str("id")?.takeIf(wikiId::matches) }
            if (ids.isEmpty()) return emptyList()
            val entities = get(wikiPath(ids, lang))?.optJSONObject("entities") ?: return emptyList()
            return ids.mapNotNull { id -> entities.optJSONObject(id)?.let { wiki(it, lang) } }
        }

        internal fun wiki(j: JSONObject, lang: String): MetaResult? {
            val id = j.str("id")?.takeIf(wikiId::matches) ?: return null
            val claims = j.optJSONObject("claims") ?: return null
            fun values(prop: String) = claims.objects(prop).filter { it.str("rank") != "deprecated" }.mapNotNull {
                it.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.opt("value")
            }
            val types = values("P31").filterIsInstance<JSONObject>().mapNotNull { it.str("id") }
            val kind = when {
                types.any { it in seriesTypes } -> MediaKind.SHOW
                types.any { it in filmTypes } -> MediaKind.MOVIE
                else -> return null
            }
            val labels = j.optJSONObject("labels") ?: return null
            val allLabels = labels.keys().asSequence().mapNotNull { labels.optJSONObject(it) }.toList()
            val title = localized(allLabels, lang, "language", "value") ?: allLabels.firstOrNull()?.str("value") ?: return null
            val aliases = j.optJSONObject("aliases")
            val names = allLabels.mapNotNull { it.str("value") } + aliases?.keys()?.asSequence().orEmpty()
                .flatMap { aliases!!.objects(it).asSequence() }.mapNotNull { it.str("value") }.toList()
            fun firstYear(prop: String) = values(prop).filterIsInstance<JSONObject>().mapNotNull {
                it.str("time")?.removePrefix("+")?.take(4)?.toIntOrNull()
            }.minOrNull()
            val year = firstYear("P577") ?: if (kind == MediaKind.SHOW) firstYear("P580") else null
            val documentary = "Q116515088" in types || "Q93204" in types ||
                values("P136").filterIsInstance<JSONObject>().any { it.str("id") == "Q93204" }
            return MetaResult(0, kind, title, originalTitle = values("P1476").filterIsInstance<JSONObject>().firstOrNull()?.str("text"),
                synonyms = names.distinct(), year = year, imdbId = values("P345").filterIsInstance<String>().firstOrNull(),
                genres = if (documentary) listOf("Documentary") else emptyList(),
                publicMovieKey = if (kind == MediaKind.SHOW) "wikidata-tv:$id" else "wikidata:$id")
        }

        private fun ofdb(j: JSONObject): MetaResult? {
            val id = j.str("id")?.takeIf(uuid::matches) ?: return null
            val a = j.optJSONObject("attributes") ?: return null
            return MetaResult(0, MediaKind.MOVIE, a.str("title") ?: return null, originalTitle = a.str("field_original_title"),
                synonyms = a.strings("field_alternative_title"), year = a.str("field_release_date")?.take(4)?.toIntOrNull(),
                imdbId = a.str("field_imdb"), publicMovieKey = "ofdb:$id")
        }

        internal fun overview(entries: List<JSONObject>, lang: String, tag: String, field: String, fallback: String?): String? {
            val readable = entries.filter { Synopsis.forReader(it.str(field), lang) != null }
            return Synopsis.forReader(localized(readable, lang, tag, field) ?: fallback, lang)
        }

        internal fun localized(entries: List<JSONObject>, lang: String, tag: String, field: String): String? {
            val preferred = lang.lowercase(Locale.ROOT)
            val base = preferred.substringBefore('-')
            fun weight(code: String) = when {
                code == preferred -> 0
                base == "zh" && preferred == "zh-cn" && code == "zh-hans" -> 1
                code == base -> 2
                code.substringBefore('-') == base -> 3
                code.substringBefore('-') == "en" -> 4
                else -> 5
            }
            return entries.mapNotNull { j -> j.str(field)?.let { weight(j.str(tag).orEmpty().lowercase(Locale.ROOT)) to it } }
                .filter { it.first < 5 }.minByOrNull { it.first }?.second
        }
    }
}

internal fun JSONObject.str(field: String) = optString(field).takeUnless { isNull(field) || it.isBlank() }
internal fun JSONObject.objects(field: String): List<JSONObject> = optJSONArray(field)?.let { a ->
    (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}.orEmpty()
internal fun JSONObject.strings(field: String): List<String> = optJSONArray(field)?.let { a ->
    (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.takeIf(String::isNotBlank) }
}.orEmpty()

private fun publicMovieReader(root: File): suspend (String, String) -> JSONObject? {
    val caches = ConcurrentHashMap<String, CachedFetch>()
    val requestLocks = Array(64) { Mutex() }
    val limits = PublicMovieCatalog.Source.entries.associate { it.host to RateLimiter(if (it == PublicMovieCatalog.Source.NEO) 2300 else 1800) } +
        mapOf("api.tvmaze.com" to RateLimiter(650), "archive.org" to RateLimiter(1500),
            "v3-cinemeta.strem.io" to RateLimiter(1500), "public-api.wordpress.com" to RateLimiter(1500))
    return { url, language ->
        withContext(Dispatchers.IO) {
            val cache = caches.getOrPut(language) { CachedFetch(File(root, "public-movies/$language"), memoryEntries = 12) }
            val body = cache.cached(url) ?: requestLocks[(url.hashCode() and Int.MAX_VALUE) % requestLocks.size].withLock {
                cache.cached(url) ?: run {
                    if (Http.cooling(url)) throw TransientHttpException(url)
                    limits.getValue(java.net.URI(url).host).withPermit {
                        cache.get(url, mapOf("Accept" to "application/json", "Accept-Language" to language,
                            "User-Agent" to "RealityNASPlayer/1.0 (+https://github.com/jason-jm/reality-nas-player)"), throwOnTransient = true)
                    }
                }
            }
            try {
                body?.takeUnless { it == CachedFetch.MISS }?.let { text ->
                    if (text.trimStart().startsWith("[")) JSONObject().put("items", org.json.JSONArray(text)) else JSONObject(text)
                }
            } catch (_: org.json.JSONException) {
                // Captive portals and faulty gateways sometimes answer HTTP 200 with HTML.
                // Let the next catalogue run, and let a later scan retry this one.
                cache.forget(url)
                throw TransientHttpException(url)
            }
        }
    }
}
