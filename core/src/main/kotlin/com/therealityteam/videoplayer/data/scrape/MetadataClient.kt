package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.data.model.MediaKind

/**
 * A credited person from a provider, with a REMOTE [imageUrl] (downloaded + cached to a local path
 * by the scrape engine into [com.therealityteam.videoplayer.data.model.Person]). [role] is the
 * character (cast) or the localized job (crew).
 */
data class Credit(
    val name: String,
    val role: String? = null,
    val imageUrl: String? = null,
)

/** One metadata candidate returned by a provider (TMDB / TVDB / 豆瓣…). */
data class MetaResult(
    val providerId: Int,
    val kind: MediaKind,
    val title: String,
    val originalTitle: String? = null,
    /** AniList romaji (→ english) — the Latin-script title MyAnimeList/Jikan search matches reliably. */
    val romajiTitle: String? = null,
    /** AniList English title — kept distinct from romaji so an English query can still be validated
     *  against an entry whose display title is a Chinese synonym (Ghost in the Shell → 攻壳机动队). */
    val englishTitle: String? = null,
    /** Alternative titles (AniList synonyms) — fansub abbreviations (Oinarisama, Otoboku) live here,
     *  so the match guard can confirm a hit that none of the canonical titles would. */
    val synonyms: List<String> = emptyList(),
    val year: Int? = null,
    val overview: String? = null,
    val rating: Double? = null,
    /** Per-site scores carried from this provider (the engine merges in OMDb / Jikan / Bangumi too). */
    val ratings: List<com.therealityteam.videoplayer.data.model.RatingScore> = emptyList(),
    /** IMDb id (e.g. "tt1234567") when known — lets the engine fetch IMDb/烂番茄/Metacritic via OMDb. */
    val imdbId: String? = null,
    /** Runtime in minutes (movie length or a show's per-episode length). */
    val runtime: Int? = null,
    /** Content rating / age classification (e.g. PG-13, TV-MA, R18+). */
    val certification: String? = null,
    /** ISO-639-1 original language (TMDB `original_language`, e.g. "ja") — flags anime/donghua. */
    val originalLanguage: String? = null,
    val genres: List<String> = emptyList(),
    /** TMDB's genre ids beside [genres]' names: what a genre IS in every language (16 is animation). */
    val genreIds: List<Int> = emptyList(),
    val cast: List<Credit> = emptyList(),
    /** Director / writers / 主创 (Staff). */
    val crew: List<Credit> = emptyList(),
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    /** Provider relevance/popularity (TMDB `popularity`); disambiguates near-duplicate titles. */
    val popularity: Double = 0.0,
    /** The provider's form for the entry (AniList: TV / TV_SHORT / MOVIE / SPECIAL / OVA / ONA / MUSIC),
     *  so a query that names a form (`… OVA`) can prefer the entry of that form. */
    val format: String? = null,
)

/** A TV show's season and its episode count (for mapping absolute episode numbers to seasons). */
data class SeasonInfo(val season: Int, val episodeCount: Int)

/**
 * One episode as the database tells it: its own name — not the "第 3 集" a database fills in for an
 * episode it has no name for — what happens in it, and its still.
 */
data class EpisodeInfo(val name: String?, val overview: String?, val still: String?)

/** Step ② of the pipeline: pluggable metadata source. */
interface MetadataClient {
    val name: String
    val isConfigured: Boolean
    suspend fun search(parsed: ParsedName): List<MetaResult>
    suspend fun details(providerId: Int, kind: MediaKind): MetaResult?
    /** The other names the database knows the entry by (translations, franchise-prefixed forms). */
    suspend fun alternativeTitles(providerId: Int, kind: MediaKind): List<String> = emptyList()
    /** Per-season episode counts for a TV show (season ≥ 1), used to map absolute → SxxExx. */
    suspend fun seasons(tvId: Int): List<SeasonInfo> = emptyList()
    /** Remote still/thumbnail URL for one episode of a show, or null if unavailable. */
    suspend fun episodeStill(tvId: Int, season: Int, episode: Int): String? = episodes(tvId, season)[episode]?.still
    /** One season of a show, episode by number; empty if unavailable. */
    suspend fun episodes(tvId: Int, season: Int): Map<Int, EpisodeInfo> = emptyMap()
    /** The entry an IMDb id (`tt…`) names — a film or a show — as a bare result to fetch [details] for. */
    suspend fun findByImdb(imdbId: String): MetaResult? = null
}

/** Runtime config (API key set from Settings; empty = offline, scraping degrades gracefully). */
object TmdbConfig {
    /** Optional publisher resource. A clean checkout uses the viewer’s key from Settings. */
    val BUILT_IN_KEY: String by lazy {
        TmdbConfig::class.java.getResourceAsStream("/reality-nas-player/tmdb-api-key.txt")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText().trim() }.orEmpty()
    }

    /** The user's own key from Settings ("" = none). */
    @Volatile
    var apiKey: String = ""
    /**
     * The language metadata is fetched and shown in. The device's, until the user picks another.
     *
     * It used to be `zh-CN` for everyone, which is what a product written by and for one reader
     * looks like: a viewer in Munich got Chinese titles, Chinese summaries and Chinese genre labels
     * for their own films. TMDB takes an IETF tag and falls back to the language's default region,
     * so `de`, `de-DE` and `pt-BR` all work.
     */
    @Volatile
    var language: String = requestTag(java.util.Locale.getDefault().toLanguageTag())
        set(value) { field = requestTag(value); languageFlow.value = field }

    /** [language] as it changes — the library rewrites its words in a new one (LibraryViewModel). */
    val languageFlow = kotlinx.coroutines.flow.MutableStateFlow(language)

    /**
     * A tag as TMDB takes it: language-REGION with no script or extensions — the headset's own tag can be
     * `zh-Hans-CN` or `de-DE-u-mu-celsius`, which went out as they were. Chinese by its region (zh-Hant alone is
     * zh-TW), and Norwegian as TMDB files it: `no` (asked for `nb-NO` it answers no overviews at all).
     */
    fun requestTag(tag: String): String {
        val l = java.util.Locale.forLanguageTag(tag.trim().replace('_', '-'))
        val lang = l.language.ifEmpty { return "en-US" }
        return when (lang) {
            "zh" -> "zh-" + l.country.ifEmpty { if (l.script.equals("Hant", true)) "TW" else "CN" }
            "nb", "nn", "no" -> "no-" + l.country.ifEmpty { "NO" }
            else -> if (l.country.isNotEmpty()) "$lang-${l.country}" else lang
        }
    }

    /** The country whose certifications (PG-13, FSK 12…) a reader of [language] expects: its region, else its language's. */
    val region: String get() = java.util.Locale.forLanguageTag(language).country.ifEmpty { HOME_REGION[java.util.Locale.forLanguageTag(language).language] ?: "US" }

    private val HOME_REGION = mapOf(
        "en" to "US", "ja" to "JP", "ko" to "KR", "de" to "DE", "fr" to "FR", "es" to "ES", "it" to "IT", "pt" to "BR",
        "ru" to "RU", "nl" to "NL", "pl" to "PL", "sv" to "SE", "da" to "DK", "no" to "NO", "fi" to "FI", "cs" to "CZ",
        "el" to "GR", "ro" to "RO", "tr" to "TR", "th" to "TH", "ms" to "MY", "zh" to "CN",
    )

    /**
     * Whether the reader reads Chinese — which decides where a work's NAME comes from, not just how
     * it is spelled. Bangumi's Chinese title is the right display name for a Chinese library and
     * the wrong one for every other.
     */
    val isChinese: Boolean get() = language.startsWith("zh", ignoreCase = true)

    /**
     * 离线刮削. A separate flag rather than blanking [apiKey], because offline must silence the
     * BUILT-IN key too — blanking only the user's key would quietly fall back to the built-in
     * and keep making network calls the user asked us not to make.
     */
    @Volatile
    var offline: Boolean = false

    /**
     * 本地 NFO 与海报: whether the nfo and pictures a scan found beside a file are read. Turning it
     * off makes the next pass identify those files by name again, as if nothing were there.
     */
    @Volatile
    var readLocal: Boolean = true

    /** The key requests actually use: user's own, else the built-in, else "" (= unconfigured). */
    val effectiveKey: String
        get() = if (offline) "" else apiKey.trim().ifBlank { BUILT_IN_KEY }

    val hasBuiltIn: Boolean get() = BUILT_IN_KEY.isNotBlank()
}

/** OMDb API key (free, omdbapi.com) — enables IMDb / 烂番茄 / Metacritic ratings. Empty = disabled. */
object OmdbConfig {
    @Volatile
    var apiKey: String = ""
}
