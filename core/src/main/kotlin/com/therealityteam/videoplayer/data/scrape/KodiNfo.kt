package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource

/**
 * A Kodi-style `.nfo`: what another library manager — Kodi, Jellyfin, tinyMediaManager, the player
 * on a TV box — already wrote down about a file. Its database ids are the strongest identity a file
 * can carry: somebody (or their tool) settled the question, and matching the name again can only
 * agree with them or be wrong.
 *
 * Read leniently, the way Kodi reads them, because the files in the wild are not all XML: a
 * "combined" nfo is XML with a database URL after it, a "URL" nfo is only the URL, plots carry bare
 * ampersands, and some writers forget the closing tag. Nothing here resolves entities beyond the
 * five XML ones and numeric references, so a hostile file cannot make the parser fetch anything.
 */
object KodiNfo {

    enum class Kind { MOVIE, SHOW, EPISODE }

    data class Actor(val name: String, val role: String?, val thumb: String?)

    data class Nfo(
        /** What the root element says the file is; null for a URL-only nfo. */
        val kind: Kind?,
        val title: String? = null,
        val originalTitle: String? = null,
        /** An episode's series, as `<showtitle>` names it. */
        val showTitle: String? = null,
        val year: Int? = null,
        val plot: String? = null,
        /** Minutes. */
        val runtime: Int? = null,
        val mpaa: String? = null,
        val genres: List<String> = emptyList(),
        val ratings: List<RatingScore> = emptyList(),
        /** The headline score Kodi shows — the default rating, else the first. */
        val rating: Double? = null,
        val tmdbId: Int? = null,
        /** True when the TMDB id came from a `themoviedb.org/tv/…` URL — a show, whatever the root says. */
        val tmdbIsShow: Boolean = false,
        val imdbId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val actors: List<Actor> = emptyList(),
        val directors: List<String> = emptyList(),
        val writers: List<String> = emptyList(),
        val posterUrl: String? = null,
        val fanartUrl: String? = null,
    ) {
        /** Whether it names a database entry at all. */
        val hasId: Boolean get() = tmdbId != null || imdbId != null

        /** The kind of TMDB entry [tmdbId] names: a show for a tvshow/episode nfo or a /tv/ URL. */
        val tmdbKind: MediaKind
            get() = if (tmdbIsShow || kind == Kind.SHOW || kind == Kind.EPISODE) MediaKind.SHOW else MediaKind.MOVIE

        /** The nfo as a metadata answer, for when the database cannot be asked (offline, no key). */
        fun toMetaResult(providerId: Int): MetaResult = MetaResult(
            providerId = providerId,
            kind = tmdbKind,
            title = (if (kind == Kind.EPISODE) showTitle else null) ?: title ?: originalTitle.orEmpty(),
            originalTitle = originalTitle,
            year = year,
            overview = plot,
            rating = rating,
            ratings = ratings,
            imdbId = imdbId,
            runtime = runtime,
            certification = mpaa,
            genres = genres,
            cast = actors.map { Credit(it.name, it.role, it.thumb) },
            crew = directors.map { Credit(it, "导演") } + writers.map { Credit(it, "编剧") },
            posterUrl = posterUrl,
            backdropUrl = fanartUrl,
        )
    }

    private val ROOTS = listOf("movie" to Kind.MOVIE, "tvshow" to Kind.SHOW, "episodedetails" to Kind.EPISODE)

    /** Blocks whose children reuse top-level names (`<name>`, `<title>`, `<thumb>`, `<value>`…). */
    private val NESTED = listOf("actor", "set", "fileinfo", "ratings", "resume", "art", "fanart", "namedseason", "producer")

    private val TMDB_URL = Regex("""themoviedb\.org/(movie|tv)/(\d+)""", RegexOption.IGNORE_CASE)
    private val IMDB_URL = Regex("""imdb\.com/title/(tt\d{5,10})""", RegexOption.IGNORE_CASE)
    private val IMDB_ID = Regex("""^tt\d{5,10}$""")

    /**
     * An nfo's bytes as text: by its byte-order mark, else the encoding its XML prolog declares, else
     * UTF-8, else the code page its words read best in (GBK, Big5, Shift_JIS… — SubtitleParser's guess):
     * the older Chinese tools write their system's code page and say nothing, a Taiwan one's in Big5.
     */
    fun text(bytes: ByteArray): String {
        fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF)
        when {
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            at(0) == 0xFF && at(1) == 0xFE -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            at(0) == 0xFE && at(1) == 0xFF -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        Regex("""encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""").find(head)?.groupValues?.get(1)?.let { name ->
            runCatching { java.nio.charset.Charset.forName(name) }.getOrNull()
                ?.takeIf { it != Charsets.UTF_8 }?.let { return String(bytes, it) }
        }
        return runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, com.therealityteam.videoplayer.data.subtitle.SubtitleParser.detectCharset(bytes)) }
    }

    /** Parse an nfo's text; null when it says nothing usable. */
    fun parse(raw: String): Nfo? {
        val text = raw.removePrefix("﻿")
        val root = ROOTS.mapNotNull { (tag, kind) ->
            Regex("""<$tag(\s[^>]*)?>""", RegexOption.IGNORE_CASE).find(text)?.let { Triple(tag, kind, it) }
        }.minByOrNull { it.third.range.first }
        val urlTmdb = TMDB_URL.find(text)
        val urlImdb = IMDB_URL.find(text)?.groupValues?.get(1)
        if (root == null) {
            // A URL nfo: the whole file is a pointer at the entry.
            if (urlTmdb == null && urlImdb == null) return null
            return Nfo(
                kind = null,
                tmdbId = urlTmdb?.groupValues?.get(2)?.toIntOrNull(),
                tmdbIsShow = urlTmdb?.groupValues?.get(1).equals("tv", ignoreCase = true),
                imdbId = urlImdb,
            )
        }
        val (tag, kind, open) = root
        val start = open.range.last + 1
        // The first block only: a multi-episode file's nfo holds one <episodedetails> per episode.
        val end = Regex("""</$tag\s*>""", RegexOption.IGNORE_CASE).find(text, start)?.range?.first ?: text.length
        val body = text.substring(start, end)
        // Top-level fields are read with the nested blocks taken out, so an actor's <name> or a
        // set's <title> is never mistaken for the film's own.
        var top = body
        for (n in NESTED) top = blocks(top, n).fold(top) { acc, b -> acc.replace(b, " ") }

        val uniqueIds = Regex("""<uniqueid(\s[^>]*)?>(.*?)</uniqueid\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(top).map { m -> attr(m.groupValues[1], "type")?.lowercase() to clean(m.groupValues[2]) }.toList()
        val tmdbId = uniqueIds.firstOrNull { it.first == "tmdb" }?.second?.toIntOrNull()
            ?: values(top, "tmdbid").firstOrNull()?.toIntOrNull()
            ?: urlTmdb?.groupValues?.get(2)?.toIntOrNull()
        val imdbId = (uniqueIds.firstOrNull { it.first == "imdb" }?.second
            ?: values(top, "imdbid").firstOrNull()
            ?: values(top, "imdb_id").firstOrNull()
            ?: values(top, "id").firstOrNull())?.takeIf { IMDB_ID.matches(it) }
            ?: urlImdb

        val (ratings, headline) = ratingsOf(body, top)
        return Nfo(
            kind = kind,
            title = values(top, "title").firstOrNull(),
            originalTitle = values(top, "originaltitle").firstOrNull(),
            showTitle = values(top, "showtitle").firstOrNull(),
            year = values(top, "year").firstNotNullOfOrNull { yearOf(it) }
                ?: listOf("premiered", "aired", "releasedate").firstNotNullOfOrNull { t -> values(top, t).firstNotNullOfOrNull { yearOf(it) } },
            plot = values(top, "plot").firstOrNull() ?: values(top, "outline").firstOrNull(),
            runtime = values(top, "runtime").firstNotNullOfOrNull { Regex("""\d{1,4}""").find(it)?.value?.toIntOrNull() }?.takeIf { it > 0 },
            mpaa = values(top, "mpaa").firstOrNull()?.let(::certificationOf),
            genres = values(top, "genre").flatMap { it.split(" / ", "/") }.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            ratings = ratings,
            rating = headline,
            tmdbId = tmdbId,
            tmdbIsShow = urlTmdb?.groupValues?.get(1).equals("tv", ignoreCase = true) && tmdbId == urlTmdb?.groupValues?.get(2)?.toIntOrNull(),
            imdbId = imdbId,
            season = values(top, "season").firstOrNull()?.toIntOrNull()?.takeIf { it >= 0 },
            episode = values(top, "episode").firstOrNull()?.toIntOrNull()?.takeIf { it >= 0 },
            actors = blocks(body, "actor").mapNotNull { b ->
                val name = values(b, "name").firstOrNull() ?: return@mapNotNull null
                Actor(name, values(b, "role").firstOrNull(), values(b, "thumb").firstOrNull()?.takeIf(::isWebUrl))
            },
            directors = values(top, "director").distinct(),
            writers = (values(top, "credits") + values(top, "writer")).distinct(),
            posterUrl = posterOf(body, top),
            fanartUrl = fanartOf(body),
        )
    }

    // ── pieces ──

    /** Every `<tag …>…</tag>` block, outermost first. */
    private fun blocks(s: String, tag: String): List<String> =
        Regex("""<$tag(\s[^>]*)?>.*?</$tag\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(s).map { it.value }.toList()

    /** The text of every `<tag>` element directly in [s], cleaned; empty ones dropped. */
    private fun values(s: String, tag: String): List<String> =
        Regex("""<$tag(\s[^>]*)?>(.*?)</$tag\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(s).map { clean(it.groupValues[2]) }.filter { it.isNotEmpty() }.toList()

    private fun attr(attrs: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)

    private fun clean(v: String): String {
        val cdata = Regex("""<!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL)
        val unwrapped = cdata.replace(v) { it.groupValues[1] }
        return decode(unwrapped).trim()
    }

    private fun decode(s: String): String {
        if ('&' !in s) return s
        return Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);""").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> {
                    val code = if (e.startsWith("#x")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
                    code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
                }
            }
        }
    }

    private fun yearOf(s: String): Int? =
        Regex("""(?<!\d)(1[89]\d\d|20\d\d)(?!\d)""").find(s)?.value?.toIntOrNull()

    /** `Rated PG-13` / `US:PG-13` / `PG-13` → `PG-13`. */
    private fun certificationOf(s: String): String? =
        s.substringAfterLast(':').removePrefix("Rated ").trim().takeIf { it.isNotEmpty() && it.length <= 16 }

    private fun isWebUrl(s: String): Boolean = s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    private fun ratingsOf(body: String, top: String): Pair<List<RatingScore>, Double?> {
        val out = mutableListOf<RatingScore>()
        var headline: Double? = null
        var first: Double? = null
        for (b in blocks(body, "ratings").flatMap { blocks(it, "rating") }) {
            val open = Regex("""<rating(\s[^>]*)?>""", RegexOption.IGNORE_CASE).find(b)?.groupValues?.get(1).orEmpty()
            val value = values(b, "value").firstOrNull()?.toDoubleOrNull() ?: continue
            val max = attr(open, "max")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 10.0
            val ten = value * 10.0 / max
            if (first == null) first = ten
            if (attr(open, "default").equals("true", ignoreCase = true)) headline = ten
            val source = sourceOf(attr(open, "name")) ?: continue
            out += RatingScore(source, display(source, ten), values(b, "votes").firstOrNull()?.replace(",", "")?.toIntOrNull())
        }
        if (out.isEmpty() && first == null) {
            // The older single score: <rating>7.4</rating><votes>1,234</votes>.
            val legacy = values(top, "rating").firstOrNull()?.toDoubleOrNull()
            if (legacy != null) first = legacy
        }
        return out.distinctBy { it.source } to (headline ?: first)?.takeIf { it > 0 }
    }

    private fun sourceOf(name: String?): RatingSource? = when (name?.lowercase()?.replace(" ", "")) {
        "imdb" -> RatingSource.IMDB
        "tmdb", "themoviedb" -> RatingSource.TMDB
        "douban" -> RatingSource.DOUBAN
        "metacritic" -> RatingSource.METACRITIC
        "rottentomatoes", "tomatometerallcritics", "tomatometer" -> RatingSource.ROTTEN_TOMATOES
        "anilist" -> RatingSource.ANILIST
        "mal", "myanimelist" -> RatingSource.MAL
        "bangumi", "bgm" -> RatingSource.BANGUMI
        else -> null
    }

    /** Shown the way each site shows its own score: a percentage, a hundred, or one decimal of ten. */
    private fun display(source: RatingSource, ten: Double): String = when (source) {
        RatingSource.ROTTEN_TOMATOES, RatingSource.ANILIST -> "${Math.round(ten * 10)}%"
        RatingSource.METACRITIC -> "${Math.round(ten * 10)}"
        else -> String.format(java.util.Locale.ROOT, "%.1f", ten)
    }

    private fun posterOf(body: String, top: String): String? {
        val thumbs = Regex("""<thumb(\s[^>]*)?>(.*?)</thumb\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(top).map { attr(it.groupValues[1], "aspect")?.lowercase() to clean(it.groupValues[2]) }
            .filter { isWebUrl(it.second) }.toList()
        return thumbs.firstOrNull { it.first == "poster" }?.second
            ?: blocks(body, "art").firstNotNullOfOrNull { values(it, "poster").firstOrNull() }?.takeIf(::isWebUrl)
            ?: thumbs.firstOrNull { it.first == null }?.second
    }

    private fun fanartOf(body: String): String? =
        blocks(body, "fanart").firstNotNullOfOrNull { values(it, "thumb").firstOrNull() }?.takeIf(::isWebUrl)
            ?: blocks(body, "art").firstNotNullOfOrNull { values(it, "fanart").firstOrNull() }?.takeIf(::isWebUrl)
}
