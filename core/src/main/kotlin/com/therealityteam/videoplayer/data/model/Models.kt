package com.therealityteam.videoplayer.data.model

/** What a library entry represents. */
enum class MediaKind { MOVIE, SHOW, EPISODE, UNKNOWN }

/** What a file is relative to its work — drives its own card vs the show's 特典 slot. */
enum class ContentKind { MAIN, MOVIE, EXTRA }

/** Stereoscopic layout of a 3D video (how left/right eye are packed). */
enum class StereoMode { MONO, SBS, OU, UNKNOWN }

/** Projection / field-of-view of the video. */
enum class ProjectionMode {
    FLAT, EQ180, EQ360, FISHEYE, EAC, UNKNOWN,
    /** Two fisheye circles side by side, front and back: a 360 camera's own recording. */
    DUAL_FISHEYE,
    /** Google's 3×2 cubemap (spherical metadata layout 0). */
    CUBEMAP,
}

/** Where a library item comes from. */
enum class SourceType { LOCAL, SMB, WEBDAV, DLNA, NFS, JELLYFIN, PLEX, EMBY, FTP, SFTP }

/** Result state of the scraping pipeline for an item. */
enum class ScrapeStatus { PENDING, MATCHED, UNMATCHED, ERROR }

/** A rating provider — Western (TMDB/IMDb/烂番茄/Metacritic) and Eastern (Bangumi/MAL/AniList). */
enum class RatingSource { TMDB, IMDB, ROTTEN_TOMATOES, METACRITIC, DOUBAN, BANGUMI, MAL, ANILIST }

/**
 * One site's score for a title, as the site gives it ("8.2", "91%", "74") — a decimal always with a point: it is
 * read back as a number (the 评分 order, 随便看看) and synced, and each UI formats it for its reader's language.
 */
data class RatingScore(
    val source: RatingSource,
    val value: String,
    /** How many votes stand behind [value], where the site says (TMDB does): a 10.0 from one vote
     *  is not a 10.0, and a "评分" order that lists such films first is an embarrassment. */
    val votes: Int? = null,
) {
    companion object {
        /** A score out of ten as stored: one decimal, a point whatever the device's language ("%.1f" alone wrote "7,5" in German). */
        fun tenths(score: Double): String = String.format(java.util.Locale.ROOT, "%.1f", score)

        /** A value read back: "7,5", written by a build that formatted in the device's language, is "7.5". */
        fun stored(value: String): String = if (DECIMAL_COMMA.matches(value)) value.replace(',', '.') else value

        private val DECIMAL_COMMA = Regex("""\d+,\d+""")
    }
}

/** A configured media source (a NAS share, WebDAV root, DLNA server, local folder…). */
data class MediaSourceConfig(
    val id: String,
    val type: SourceType,
    val name: String,
    val uri: String,
    val username: String? = null,
    val password: String? = null,
    /**
     * A server-issued session token (Jellyfin), refreshed on every scan. Kept OUT of item paths
     * on purpose: a token inside [MediaItem.path] would change the item's id every scan and wipe
     * progress/favorites; playback appends it at open time instead.
     */
    val token: String? = null,
    /**
     * Who [token] signed in as on a Jellyfin / Emby server (its user id), from the last scan: the
     * user-scoped routes playback asks for an item's external subtitles by need it.
     */
    val serverUserId: String? = null,
    val enabled: Boolean = true,
    val addedAt: Long = 0L,
)

/** Per-file playback resume + watched state. */
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val watched: Boolean = false,
    val updatedAt: Long = 0L,
) {
    val fraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val started: Boolean get() = positionMs > 0L
}

/**
 * A credited person attached to a title. Used for both cast (a voice/screen actor — [role] is the
 * character played) and crew/Staff (a director, writer… — [role] is the localized job, e.g. 导演).
 * [profilePath] is a LOCAL cached path to a downloaded headshot (same scheme as poster/backdrop);
 * null → the UI renders an initials avatar.
 */
data class Person(
    val name: String,
    val role: String? = null,
    /** A headshot already on disk. Null on a library scraped after headshots became lazy. */
    val profilePath: String? = null,
    /**
     * Where the headshot lives, fetched the first time somebody actually looks at this person.
     *
     * Headshots were two thirds of everything the scraper downloaded — 3,524 of 5,348 artwork files
     * on a real library — for faces that are only ever shown in the cast row of a detail page. Most
     * are never seen. Carrying the URL instead of the file turns that into a download per face the
     * user opens, and the cast row already knows how to draw an initials circle until one arrives.
     */
    val profileUrl: String? = null,
)

/** Detected (or user-overridden) 3D / projection flags. */
data class VideoFlags(
    val stereo: StereoMode = StereoMode.UNKNOWN,
    val projection: ProjectionMode = ProjectionMode.FLAT,
    val userOverridden: Boolean = false,
    /** A fisheye lens's field of view, degrees, as the viewer set it; 0 for the projection's own. */
    val lensFov: Int = 0,
    /** A stereo frame with the right eye's picture first (`_RL`, a file's own StereoMode 11). */
    val rightFirst: Boolean = false,
    /**
     * Read off the file's own metadata as it played (SphericalMetadata), not guessed from its name:
     * kept, like the viewer's word, when a scan reads the name again.
     */
    val fromFile: Boolean = false,
) {
    /** True when this needs the immersive sphere (Stage) rather than a flat panel. */
    val isImmersive: Boolean
        get() = projection == ProjectionMode.EQ180 ||
            projection == ProjectionMode.EQ360 ||
            projection == ProjectionMode.FISHEYE ||
            projection == ProjectionMode.DUAL_FISHEYE ||
            projection == ProjectionMode.CUBEMAP ||
            projection == ProjectionMode.EAC
}

/**
 * The files beside a video that describe it, laid out the way Kodi, Jellyfin and tinyMediaManager
 * write them: its own `.nfo`, its series' `tvshow.nfo`, a poster and a fanart. Paths are written
 * like [MediaItem.path], so whatever reads the video can read them.
 */
data class LocalMeta(
    /** The video's own nfo — `<name>.nfo`, or `movie.nfo` in a folder that holds one film. */
    val nfo: String? = null,
    /** The series' `tvshow.nfo`, in the video's folder or the one above it (a `Season 1` folder). */
    val showNfo: String? = null,
    val poster: String? = null,
    val fanart: String? = null,
    /** The chosen files' names and sizes: a rescan that finds a different stamp scrapes again. */
    val stamp: String = "",
)

/**
 * A single library entry: one playable video file plus its scraped metadata, artwork,
 * dominant color (drives Vibrant ambiance), 3D flags and playback progress.
 */
data class MediaItem(
    val id: String,
    val sourceId: String,
    val path: String,
    val fileName: String,
    val kind: MediaKind = MediaKind.UNKNOWN,
    // scraped metadata
    val title: String,
    val originalTitle: String? = null,
    /**
     * The other names this work answers to — the database's romaji and English titles.
     *
     * A card named in Chinese has no Latin-script name anywhere else: [title] is the Chinese one,
     * [originalTitle] the Japanese. Measured on a real shelf, 30 of 322 anime works carried no Latin
     * letters in any field or in the folder they were filed under, so 混沌武士 could not be found by
     * typing `samurai champloo` and 狼与香辛料 not by `spice and wolf` — the names most people know
     * them by, and the only ones easy to type on a headset. Romanising the Chinese gives pinyin,
     * which is not those names; only the database has them.
     */
    val aliases: List<String> = emptyList(),
    val year: Int? = null,
    val overview: String? = null,
    val rating: Double? = null,
    /** Per-site scores (TMDB / IMDb / 烂番茄 / Metacritic / Bangumi / MAL / AniList…). */
    val ratings: List<RatingScore> = emptyList(),
    /** Runtime in minutes — a movie's length, or a show's typical per-episode length. */
    val runtime: Int? = null,
    /** Content rating / age classification (e.g. PG-13, TV-MA, R18+). */
    val certification: String? = null,
    val genres: List<String> = emptyList(),
    /** Top-billed cast — [Person.role] is the character. */
    val cast: List<Person> = emptyList(),
    /** Director / writers / 主创 (Staff) — [Person.role] is the localized job. */
    val crew: List<Person> = emptyList(),
    val posterPath: String? = null,
    /** True when [posterPath] is a frame the app drew itself, not artwork a database supplied. */
    val posterGenerated: Boolean = false,
    val backdropPath: String? = null,
    /** Episode still / thumbnail (local cached path); null → list shows a key-info card instead. */
    val stillPath: String? = null,
    /**
     * Where the episode still lives, fetched the first time a card actually wants to draw it.
     *
     * One still was downloaded for every episode in the library — 742 of them — and the only place
     * any of them is ever shown is the continue-watching row, which holds about ten cards and
     * already falls back to the title's backdrop. So the picture is fetched for the episode you are
     * part-way through, not for the seven hundred you are not.
     */
    val stillUrl: String? = null,
    val dominantColor: Int? = null,
    val tmdbId: Int? = null,
    // tv hierarchy
    val showTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    // AI-identified grouping (the on-device LLM's decision; null = fall back to rules)
    /** Stable "work" key from the AI pass — all files of one title/movie share it (drives grouping). */
    val collectionKey: String? = null,
    /** Whether this file is the main feature, a movie (剧场版), or an extra (特典/OVA/OP/ED…). */
    val contentTag: ContentKind? = null,
    // playback
    val video: VideoFlags = VideoFlags(),
    val progress: PlaybackProgress = PlaybackProgress(),
    val favorite: Boolean = false,
    /** The audio track ("group:track" id) this file was last watched with; re-selected on open. */
    val audioTrackId: String? = null,
    /** The subtitle track last chosen for this file, or "off" when the user turned them off. */
    val subtitleTrackId: String? = null,
    /** User pinned this match via 修正匹配 — auto-scrape / re-scan must not overwrite it. */
    val userMatched: Boolean = false,
    /** What the scan found beside the file — its nfo and artwork — or null when there is nothing. */
    val local: LocalMeta? = null,
    /** [LocalMeta.stamp] as it was when the file was last identified; a different one asks again. */
    val localApplied: String? = null,
    /**
     * The identity came from the file's own nfo. Like [userMatched], the folder rules leave it
     * alone — somebody settled the question — but unlike it a changed nfo is read again.
     */
    val fromNfo: Boolean = false,
    val scrapeStatus: ScrapeStatus = ScrapeStatus.PENDING,
    /**
     * Which generation of the ratings pipeline produced [ratings]. A matched item whose version is
     * behind [ScrapeEngine]'s current one is re-scraped once on the next 刮削 so libraries built
     * before a ratings improvement (e.g. anime sites for TMDB-matched 剧场版) backfill the new sources.
     */
    val ratingsVersion: Int = 0,
    val addedAt: Long = 0L,
) {
    /** Whether the identity was settled outside the matcher — by hand, or by the file's nfo. */
    val identityPinned: Boolean get() = userMatched || fromNfo

    companion object {
        /** Stable id derived from source + path so re-scans don't duplicate entries. */
        fun idFor(sourceId: String, path: String): String =
            Integer.toHexString((sourceId + "::" + path).hashCode()) + "_" +
                Integer.toHexString(path.hashCode())
    }
}
