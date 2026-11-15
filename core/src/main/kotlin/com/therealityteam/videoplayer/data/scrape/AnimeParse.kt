package com.therealityteam.videoplayer.data.scrape

import com.dgtlrepublic.anitomyj.AnitomyJ
import com.dgtlrepublic.anitomyj.Element.ElementCategory
import com.therealityteam.videoplayer.data.model.ContentKind

/**
 * Anime-aware filename parsing via Anitomy (the de-facto fansub-name parser). Extracts a clean
 * title + episode/season/type/release-group from `[Group] Title - 01v2 (BD 1080p) [hash].mkv`,
 * decides whether a file even looks like anime (so we route it to an anime DB, not TMDB), and
 * classifies OP/ED/OVA/剧场版 vs main.
 */
object AnimeParse {

    data class Parsed(
        val title: String?,
        val episode: Int?,
        val season: Int?,
        val year: Int?,
        val type: String?,
        val releaseGroup: String?,
        val isAnimeLikely: Boolean,
        val contentKind: ContentKind,
    )

    // Fansub-only tokens / known anime groups — a RELIABLE "this is anime" signal. Deliberately does
    // NOT include a generic bracketed release group ([YTS.MX], [RARBG]) — those tag live-action scene
    // releases too, and false-flagging them as anime routes them to AniList (which has no live-action),
    // misses, and leaves them unmatched. Only these distinctive markers count.
    // …and a raw-release group, whatever its name: `[Shiniori-Raws]`, `[DBD-Raws]`, `[TD-RAWS]`,
    // `[VCB-S&philosophy-raws]`. Every such file on a 9.6k-file library was anime; the one outside
    // the Anime shelf, a Patlabor film remaster in the downloads, went to TMDB and was filed as the
    // wrong film. Only the hyphenated group form — a bare `[RAW]` also tags a Japanese TV drama.
    private val ANIME_TOKEN = Regex(
        "(?i)ma10p|hi10p|ncop|nced|\\b(?:ova|oad)\\b|総集編|总集编|\\[(vcb-studio|nekomoe|beansub|" +
            "airota|dmg|sakurato|lolihouse|ani|lilith-raws|jibaketa|kissaten|orion|sweetsub)|-raws\\]",
    )

    private val EXTRA_TYPES = setOf(
        "OP", "OPENING", "ED", "ENDING", "NCOP", "NCED", "OPED", "PV", "CM", "MENU",
        "SP", "SPECIAL", "OAD", "OAV", "OVA", "ONA", "PREVIEW", "TRAILER",
    )
    private val MOVIE_TYPES = setOf("MOVIE", "GEKIJOUBAN")

    private val NUM = Regex("\\d+")

    fun parse(fileName: String): Parsed {
        val els = runCatching { AnitomyJ.parse(fileName) }.getOrNull().orEmpty()
        fun first(cat: ElementCategory): String? =
            els.firstOrNull { it.category == cat }?.value?.trim()?.ifEmpty { null }

        // A scene release names its group after everything else (FilenameParser.trailingGroup), where
        // Anitomy looks for one only in front. Left on the title, "The Borrowers Arrietty - THORA-UTW"
        // was the name the file had to answer to, and the film itself (Karigurashi no Arrietty) was
        // refused for not carrying it.
        val trailing = FilenameParser.trailingGroup(fileName.substringBeforeLast('.'))
        val title = first(ElementCategory.kElementAnimeTitle)?.let { t ->
            if (trailing != null && t.endsWith(trailing, ignoreCase = true)) {
                t.dropLast(trailing.length).trimEnd().removeSuffix("-").trimEnd().ifEmpty { t }
            } else t
        }
        val type = first(ElementCategory.kElementAnimeType)
        val group = first(ElementCategory.kElementReleaseGroup) ?: trailing
        val episode = first(ElementCategory.kElementEpisodeNumber)?.let { intOf(it) }
        val season = first(ElementCategory.kElementAnimeSeason)?.let { intOf(it) }
        val year = first(ElementCategory.kElementAnimeYear)?.let { intOf(it) }

        val typeU = type?.uppercase()
        val kind = when {
            typeU in MOVIE_TYPES -> ContentKind.MOVIE
            typeU != null && typeU in EXTRA_TYPES -> ContentKind.EXTRA
            else -> ContentKind.MAIN
        }
        // Only a reliable anime marker flags a file as anime — NOT a bare release group / "Movie"
        // type, which also appear on live-action (e.g. "F1 The Movie", "...-[YTS.MX]").
        val animeLikely = ANIME_TOKEN.containsMatchIn(fileName)

        return Parsed(title, episode, season, year, type, group, animeLikely, kind)
    }

    // Strips a trailing Japanese-style ~subtitle~ and season/sequel suffixes for the DB search,
    // so all seasons/cours of a franchise resolve to one (S1/base) entry — which carries the good
    // Chinese name & poster — instead of fragmenting into per-season cards with romaji names.
    private val SUBTITLE = Regex("\\s*[~～][^~～]*[~～]?\\s*$")
    private val SEASON_SUFFIX = Regex(
        "(?i)\\s*(the\\s+final\\s+season|final\\s+season|\\d+(?:st|nd|rd|th)\\s+season|" +
            "season\\s*\\d+|part\\s*\\d+|cour\\s*\\d+|s\\d{1,2}|第\\s*[0-9一二三四五六七八九十]+\\s*[季期])\\s*$",
    )

    fun baseTitle(title: String): String {
        val noSub = SUBTITLE.replace(title.trim(), "").trim().ifBlank { return title.trim() }
        var s = noSub
        var prev: String
        do {
            prev = s
            s = SEASON_SUFFIX.replace(s, "").trim()
        } while (s != prev && s.isNotBlank())
        return s.ifBlank { noSub }
    }

    private fun intOf(s: String): Int? = NUM.find(s)?.value?.toIntOrNull()
}
