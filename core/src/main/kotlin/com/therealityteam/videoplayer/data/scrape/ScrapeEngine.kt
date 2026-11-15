package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.playback.ProjectionDetector
import com.therealityteam.videoplayer.data.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import com.therealityteam.videoplayer.util.Log

/**
 * Scraping pipeline: [scrapeAll] — rules → TMDB / AniList search → match → artwork, per file. Used by scan / 刮削.
 */
class ScrapeEngine(
    private val client: MetadataClient,
    private val artwork: ArtworkCache,
    private val repo: MediaRepository,
    private val anilist: AniListClient,
    private val bangumi: BangumiClient,
    private val omdb: OmdbClient,
    private val jikan: JikanClient,
    private val imdb: ImdbDatasetClient,
    /** Reads the nfo and pictures a scan found beside a file; null reads nothing. */
    private val sidecars: com.therealityteam.videoplayer.data.source.SidecarReader? = null,
) {

    /**
     * All ratings for a TMDB-matched title: the Western sites, plus — when the match is clearly anime
     * (Animation genre + JP/CN/KR origin) — the anime sites too, so a TMDB-matched 剧场版 (闪光的哈萨维,
     * Gundam films…) still shows Bangumi / MAL / AniList instead of being hard-split to TMDB+IMDb only.
     */
    private suspend fun allRatings(details: MetaResult): List<RatingScore> {
        val western = westernRatings(details)
        val eastern = if (isAnimeish(details)) easternRatings(details.title, details.originalTitle) else emptyList()
        return (western + eastern).distinctBy { it.source }
    }

    /** Western movie/show ratings: TMDB (from details) + free IMDb (datasets) + OMDb (RT/Metacritic). */
    private suspend fun westernRatings(details: MetaResult): List<RatingScore> =
        (details.ratings + listOfNotNull(imdb.rating(details.imdbId)) + omdb.ratings(details.imdbId))
            .distinctBy { it.source }

    /**
     * Anime sites (AniList + Bangumi + MAL) for a TMDB-matched anime. We resolve the title on AniList
     * first (its GraphQL matches romaji/english/native/synonyms server-side, so even TMDB's messy
     * "機動警察パトレイバー 2 the Movie" resolves), then reuse THAT entry's clean titles for the other two:
     * Bangumi keys off the Japanese native title, MyAnimeList/Jikan off the romaji — a Japanese string
     * is why MAL kept coming back empty here.
     */
    // Three throttled lookups (AniList 2.5s, Bangumi 1s, Jikan 1.25s) for one WORK's scores — and
    // every file of that work was paying them again. animeWork has been cached by provider id since
    // that cost was found there; this is the same cost on the TMDB side of the fence.
    private val easternCache = java.util.concurrent.ConcurrentHashMap<String, List<RatingScore>>()

    private suspend fun easternRatings(title: String, originalTitle: String?): List<RatingScore> {
        val query = originalTitle?.takeIf { it.isNotBlank() } ?: title
        easternCache[query]?.let { return it }
        val hit = anilist.search(query).firstOrNull() ?: anilist.search(title).firstOrNull()
        val jpTitle = hit?.originalTitle?.takeIf { it.isNotBlank() } ?: query
        val malTitle = hit?.romajiTitle?.takeIf { it.isNotBlank() } ?: hit?.title ?: query
        val out = mutableListOf<RatingScore>()
        hit?.let { out += it.ratings }
        // Asked together: neither needs the other, and one out of reach (bgm.tv and Jikan both, on a
        // headset at home in China) was being waited out before the other was even asked.
        coroutineScope {
            val mal = async { jikan.malRating(malTitle) }
            bangumi.chinese(jpTitle, hit?.let(::ownNames).orEmpty())?.score?.takeIf { it > 0 }
                ?.let { out += RatingScore(RatingSource.BANGUMI, RatingScore.tenths(it)) }
            mal.await()?.let { out += it }
        }
        easternCache[query] = out
        return out
    }

    /**
     * Is a TMDB entry an animation: its genre id 16, in any language — the name was matched as 动画 or Animation, and
     * in Japanese, Russian or Thai (アニメーション, мультфильм, แอนนิเมชั่น) an anime was never one, its ratings and the
     * anime fallback off. The names stay for an entry read before the ids were kept.
     */
    private fun isAnimation(details: MetaResult): Boolean =
        TMDB_ANIMATION in details.genreIds ||
            details.genres.any { it.contains("动画") || it.contains("動畫") || it.contains("Animation", ignoreCase = true) || it == GenreCn.zh("Animation") }

    /** A TMDB match is anime/donghua when it's animation and originates in Japan / China / Korea. */
    private fun isAnimeish(details: MetaResult): Boolean {
        val animation = isAnimation(details)
        return animation && details.originalLanguage in ANIME_LANGS
    }

    /**
     * The work's own entry on TMDB, for its Western ratings and — when bgm.tv could not name it — its
     * Chinese name. Anime live on TMDB / IMDb too, so they are not gated out: TMDB is searched by the
     * AniList title (the movie or the TV endpoint by its form), and a candidate within a year taken.
     * [AnimeTwin.namesWork] says one was the work by its original title ([AnimeMatch.sameWorkByName]),
     * which ratings can do without and a NAME cannot.
     */
    private suspend fun animeTwin(best: MetaResult): AnimeTwin? {
        if (!client.isConfigured || best.title.isBlank()) return null
        val parsed = ParsedName(
            title = best.title,
            year = best.year,
            season = if (best.kind == MediaKind.MOVIE) null else 1, // season != null ⇒ TV search
        )
        val candidates = retry(MAX_RETRIES) { client.search(parsed) } ?: return null
        val dated = candidates.filter { c ->
            val cy = c.year
            val by = best.year
            by == null || cy == null || kotlin.math.abs(cy - by) <= 1
        }
        val named = dated.firstOrNull { AnimeMatch.sameWorkByName(best.originalTitle, it.originalTitle) }
        val match = named ?: dated.firstOrNull() ?: return null
        val details = retry(MAX_RETRIES) { client.details(match.providerId, match.kind) } ?: return null
        return AnimeTwin(details, namesWork = named != null)
    }

    private class AnimeTwin(val details: MetaResult, val namesWork: Boolean) {
        /** TMDB's Chinese name for the work, when the entry is the work and has one. */
        val chineseName: String? get() = if (namesWork) AnimeMatch.chineseName(details.title) else null
        /** …and its Chinese synopsis, under the same condition. */
        val chineseOverview: String? get() =
            details.overview?.takeIf { namesWork && it.any { c -> c in '一'..'鿿' } && !Synopsis.isJapanese(it) }
    }

    private val gate = Semaphore(MAX_CONCURRENCY)

    // ───────────────────────── fast path (rules + TMDB) ─────────────────────────

    suspend fun scrape(item: MediaItem, siblings: Collection<String> = emptyList()): MediaItem {
        val local = localOf(item)
        val identified = local?.let { fromNfo(item) }
            ?: if (local != null && FilenameParser.nothingToLookUp(item.path, item.fileName, item.title)) {
                item.copy(fromNfo = false) // its pictures are still its own; nothing online has its name
            } else {
                // An identity an nfo gave, now asked by name again, is no longer the nfo's.
                scrapeByName(item, siblings).copy(fromNfo = false)
            }
        // What was beside the file when it was identified, so the next pass can tell it changed.
        return withLocalArt(identified, item).copy(localApplied = local?.stamp)
    }

    /** What lies beside the file, as far as this pass may read it (Settings can say not at all). */
    private fun localOf(item: MediaItem): com.therealityteam.videoplayer.data.model.LocalMeta? =
        item.local?.takeIf { TmdbConfig.readLocal && sidecars != null }

    // ───────────────────────── the files beside it (Kodi .nfo, poster, fanart) ─────────────────────────

    /** Neither the nfo nor the pictures speak for the anime path, which files each season and form as
     *  its own work with covers chosen per work; Kodi's tvshow.nfo names a whole TMDB series. */
    private fun animeShelf(item: MediaItem): Boolean =
        FilenameParser.isUnderAnimeRoot(item.path) || AnimeParse.parse(item.fileName).isAnimeLikely

    /**
     * The file's identity from its nfo, when the nfo carries a database id: whoever wrote it settled
     * the question, and asking the name again can only agree with them or be wrong. Null when there is
     * no nfo, it names no entry, or the entry cannot be resolved; the name is then asked as before.
     */
    private suspend fun fromNfo(item: MediaItem): MediaItem? {
        val local = localOf(item) ?: return null
        if (animeShelf(item)) return null
        val own = readNfo(item, local.nfo)
        val show = local.showNfo?.takeIf { it != local.nfo }?.let { readNfo(item, it) }
        val episodeNfo = own?.takeIf { it.kind == KodiNfo.Kind.EPISODE }
        // An episode's own nfo names the EPISODE's entry; the series it belongs to is the show nfo's.
        val identity = own?.takeIf { it.kind != KodiNfo.Kind.EPISODE && it.hasId }
            ?: show?.takeIf { it.hasId }
            ?: return null
        var kind = identity.tmdbKind
        val tmdbId = identity.tmdbId ?: run {
            val found = identity.imdbId?.let { id -> retry(MAX_RETRIES) { client.findByImdb(id) } } ?: return null
            // A bare URL nfo does not say whether it is a film or a show; the database does.
            if (identity.kind == null) kind = found.kind else if (found.kind != kind) return null
            found.providerId
        }
        val details = retry(MAX_RETRIES) { client.details(tmdbId, kind) }
        // Offline, or without a key, what the nfo says is the answer.
        val meta = details ?: identity.toMetaResult(tmdbId)
        val parsed = FilenameParser.parseFromPath(item.path)
        val base = baseOf(item, parsed)
        val series = kind == MediaKind.SHOW
        val season = episodeNfo?.season ?: parsed.season
        val episode = episodeNfo?.episode ?: parsed.episode
        val artKey = "tmdb_$tmdbId"
        val posterPath = artwork.cache(meta.posterUrl, artKey)
        val stillUrl = if (series && details != null && season != null && episode != null) {
            client.episodeStill(tmdbId, season, episode)
        } else null
        val title = meta.title.ifBlank { base.title }
        return base.copy(
            title = title,
            originalTitle = meta.originalTitle,
            aliases = aliasesOf(meta, title),
            year = meta.year ?: base.year,
            overview = meta.overview,
            rating = meta.rating,
            ratings = if (details != null) allRatings(details) else meta.ratings,
            runtime = meta.runtime,
            certification = meta.certification,
            genres = meta.genres,
            cast = persons(meta.cast),
            crew = persons(meta.crew),
            posterPath = posterPath,
            posterGenerated = false,
            backdropPath = artwork.cache(meta.backdropUrl, artKey + "_bd"),
            stillUrl = stillUrl,
            dominantColor = artwork.dominantColor(posterPath),
            tmdbId = tmdbId,
            showTitle = if (series) title else null,
            season = if (series) season else null,
            episode = if (series) episode else null,
            collectionKey = null,
            kind = if (series) MediaKind.EPISODE else MediaKind.MOVIE,
            fromNfo = true,
            scrapeStatus = ScrapeStatus.MATCHED,
            ratingsVersion = RATINGS_VERSION,
        )
    }

    private suspend fun readNfo(item: MediaItem, path: String?): KodiNfo.Nfo? {
        if (path == null) return null
        val bytes = sidecars?.read(item, path, MAX_NFO_BYTES) ?: return null
        return KodiNfo.parse(KodiNfo.text(bytes))
    }

    /**
     * The pictures beside the file over whatever the database had: they are the ones its owner, or
     * their library manager, chose, and reading them needs no network.
     */
    private suspend fun withLocalArt(identified: MediaItem, item: MediaItem): MediaItem {
        val local = localOf(item) ?: return identified
        if ((local.poster == null && local.fanart == null) || animeShelf(item)) return identified
        val poster = local.poster?.let { localImage(item, local, it) }
        val fanart = local.fanart?.let { localImage(item, local, it) }
        if (poster == null && fanart == null) return identified
        return identified.copy(
            posterPath = poster ?: identified.posterPath,
            posterGenerated = if (poster != null) false else identified.posterGenerated,
            backdropPath = fanart ?: identified.backdropPath,
            dominantColor = if (poster != null) artwork.dominantColor(poster) else identified.dominantColor,
        )
    }

    private val artLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /**
     * A picture copied into the artwork cache, keyed by its own path and size: a series' poster is
     * read once for all its episodes, and an edited one (a different size) is read again.
     */
    private suspend fun localImage(item: MediaItem, local: com.therealityteam.videoplayer.data.model.LocalMeta, path: String): String? {
        val key = "local_" + path.hashCode().toUInt().toString(16)
        val tag = local.stamp.split(';').firstOrNull { it.startsWith("$path:") }?.substringAfterLast(':')?.ifEmpty { null } ?: "0"
        return artLocks.getOrPut(key) { kotlinx.coroutines.sync.Mutex() }.withLock {
            artwork.existing(key, tag) ?: run {
                val bytes = sidecars?.read(item, path, MAX_ART_BYTES) ?: return@withLock null
                artwork.store(key, tag) { out -> out.write(bytes); true }
            }
        }
    }

    private suspend fun scrapeByName(item: MediaItem, siblings: Collection<String>): MediaItem {
        val parsed = FilenameParser.parseFromPath(item.path)
        val anime = AnimeParse.parse(item.fileName)
        val base = baseOf(item, parsed)

        // Anime fast path: a fansub-tagged file — OR anything under an Anime/动漫 library root — goes to
        // AniList first (TMDB is the wrong DB for anime). The root signal is what catches messy fansub
        // names whose group isn't in the token list ([FLsnow][CLANNAD][00], [POPGO][Macross Frontier]…):
        // their clean romaji title lives in the FILENAME (which Anitomy reads), so AniList resolves it.
        val animeRoot = FilenameParser.isUnderAnimeRoot(item.path)
        // …but not on a music shelf, whatever a file's brackets say: `[POPGO][Sound_Horizon_6th_Story_
        // Concert_Moira]` is a concert, and a music shelf holds no anime (see the fallback below).
        val musicShelf = FilenameParser.isUnderMusicRoot(item.path)
        if ((anime.isAnimeLikely && !musicShelf) || animeRoot) {
            resolveAnime(base, anime, parsed, item.path, siblings)?.let { return it }
            // AniList is down (see its outage breaker): an identity it gave earlier is better than
            // anything TMDB would hand back now, and swapping it out only to swap it back on the next
            // scan reshuffles the wall twice for nothing. Keep the file as it is; the version stays
            // behind, so the next pass re-asks.
            if (anilist.isDown && item.collectionKey?.startsWith("anilist:") == true) return item
            // AniList missed → try TMDB, but accept ONLY an Animation-genre entry. This never lets an
            // anime file grab a live-action match (摇曳露营 TV → the live-action drama) yet recovers anime
            // that TMDB indexes under a title AniList doesn't (Cowboy Bebop: Knockin' on Heaven's Door).
            // …asked by the file's own title, and then by the Chinese or Japanese names the anime queries
            // read off it and its folder: 「狼的孩子雨和雪 Movie」 and 「H 264」 (云之彼端) are titles TMDB has
            // never heard of, 「狼的孩子雨和雪」 and 「云之彼端,约定的地方」 names it files them under — and on a
            // network where bgm.tv does not answer, nothing else turned a Chinese name into a work.
            tmdbAnimeMatch(base, parsed, item, cjkNames(AnimeQueries.build(item.path, parsed, anime, siblings), parsed.title))
                ?.let { return it }
            // The lookups all failed THIS time (rate limit / outage). Don't destroy a real existing
            // match over it — keep a previously-matched, non-junk-named anime; the next 刮削 retries.
            // (A junk "08"/"Final" fuzzy error has no real match to protect, so it still clears.)
            if (item.scrapeStatus == ScrapeStatus.MATCHED &&
                !isJunkTitle(FilenameParser.parse(item.fileName).title)
            ) {
                return item
            }
            // Genuinely unidentifiable → UNMATCHED with all stale identity cleared, so a game recording
            // (Remember11) collapses into ONE folder card instead of keeping wrong posters/keys.
            return clearedUnmatched(base, animeUnmatchedTitle(anime, item.path, base.title))
        }

        if (!client.isConfigured) return base.copy(scrapeStatus = ScrapeStatus.UNMATCHED)

        // A file under a TV library root is an episode of something even when its own name says
        // nothing about seasons; searching movies first there matched a scene-named John Adams rip
        // to a concert film. season != null is what selects the TV endpoint.
        val query = if (!parsed.isEpisode && FilenameParser.isUnderTvRoot(item.path)) {
            parsed.copy(season = 1)
        } else {
            parsed
        }
        // Whether any question went unanswered — failed, rather than answered with nothing.
        var unanswered = false
        suspend fun search(asked: ParsedName): List<MetaResult> =
            retry(MAX_RETRIES) { client.search(asked) } ?: emptyList<MetaResult>().also { unanswered = true }
        val candidates = search(query)
        // Movie search first; if nothing clears the bar, retry as TV — a miniseries parsed as a movie
        // (Hitler: The Rise of Evil, BBC Jane Eyre, Little Dorrit) has no movie entry but a TV one.
        // search() only auto-falls-back to TV when movie returns ZERO results; this also covers the
        // case where movie returns unrelated junk that the matcher (rightly) rejects.
        var viaProgramme = false
        var viaAlternative = false
        // On a music shelf an answer stands only when the name it was asked by names it (see
        // ShelfFit.namesTheClip) — and one that does not leaves the next question to be asked: the
        // concert's own folder named the disc whose `LIVE DOCUMENT` had found another band's film.
        fun named(asked: String, hit: MetaResult?): MetaResult? =
            hit?.takeIf { !musicShelf || ShelfFit.namesTheClip(asked, listOfNotNull(it.title, it.originalTitle)) }
        var tv: List<MetaResult> = emptyList()
        val match = named(query.title, Matcher.best(query, candidates)) ?: run {
            if (query.isEpisode) return@run null
            tv = search(parsed.copy(season = 1))
            named(parsed.title, Matcher.best(parsed, tv))
        } ?: run {
            // The search found the work but not by the name it files it under — Andor answers
            // 「Star Wars Andor」 as 「Star Wars: Andor」, one of its other names. Asked of the hits
            // already in hand, so it costs nothing where they matched.
            named(query.title, byAlternativeTitle(query, candidates) ?: byAlternativeTitle(parsed, tv))?.also { viaAlternative = true }
        } ?: run {
            // Nothing the FILE says resolves. Its release folder is the other half of the name, and
            // on a localized rip it's the half the DB is indexed under — `TV/Two and a Half Men/01/
            // [YYsoR]好汉两个半第1季01…`, `Anime/Mobile Suit Gundam X.1996 1080P BDRIP/gx_01.mkv`. Only
            // reached after the file's own title found nothing, so it can't override a real match.
            val folder = FilenameParser.showFolderTitle(item.path)
                ?.takeIf { !it.equals(parsed.title, ignoreCase = true) }
                // A music shelf's category folder names a kind of clip, not a work (see inMusicCategoryFolder).
                ?.takeIf { !FilenameParser.inMusicCategoryFolder(item.path) }
                ?: return@run null
            val byFolder = parsed.copy(title = folder)
            named(folder, Matcher.best(byFolder, search(byFolder)))
        } ?: run {
            // Neither the file nor its folder names the work — but the file's instalment mark says
            // what it is a part OF. `[激流中国].12.环保攻防战` asked for "激流中国 12 环保攻防战" and got
            // nothing; "激流中国" is on TMDB, as are 沸腾都市 and Rick Steves' Europe. A run is a
            // show, so TV first. The name is short and the year unknown, so the hit must NAME the
            // programme — 美国魂 must not become 美国招魂 on a character blend.
            val programme = FilenameParser.programmeTitle(item.fileName, item.path.substringBeforeLast('/', "").substringAfterLast('/'))
                ?.takeIf { it.length >= 2 && !it.equals(parsed.title, ignoreCase = true) } ?: return@run null
            // The file's year is the instalment's air year, not the programme's first: Rick.Steves.
            // Europe.17.Provence…2004 asked for a series that began in 2004 and never got one (2000).
            val asShow = parsed.copy(title = programme, season = 1, year = null)
            (Matcher.best(asShow, search(asShow))?.takeIf { Matcher.namesAgree(programme, it) } ?: run {
                val asFilm = parsed.copy(title = programme)
                Matcher.best(asFilm, search(asFilm))?.takeIf { Matcher.namesAgree(programme, it) }
            })?.let { named(programme, it) }?.also { viaProgramme = true }
        }
        if (match == null) {
            // No TMDB match and no fansub signal → it might still be anime, but only worth AniList's
            // (slow, throttled) lookup when the title is CJK — a Western title TMDB missed isn't on
            // AniList, so skipping it keeps big Western-heavy scans from crawling.
            // …but not for a documentary. A shelf of NHK programmes is CJK-titled and none of it is
            // on AniList: 329 documentary files spent 398 AniList calls and 348 of the pass's 912
            // seconds there, 160 of those calls failing under the rate limit. What the user filed as
            // a documentary is the strongest signal available, and it says don't ask.
            // …nor for a music shelf, which holds concerts and lessons and no anime: AniList answered
            // 火键 - 刘牧 (keyboard lessons) with 陆地键仙 and a folder of mixing tutorials with
            // Nanatsu no Taizai, and the folders' other files followed them there.
            if (!anime.isAnimeLikely && hasCjk(parsed.title) && !FilenameParser.isUnderDocRoot(item.path) && !musicShelf) {
                resolveAnime(base, anime, parsed, item.path)?.let { return it }
            }
            // A question that failed is not an answer: a file that had one keeps it, and the version
            // it was stamped with, so the next pass asks again.
            if (unanswered && item.scrapeStatus == ScrapeStatus.MATCHED) return item
            // Nothing found, and nothing an earlier pass found may stay on it either.
            return refused(base)
        }
        val details = retry(MAX_RETRIES) { client.details(match.providerId, match.kind) } ?: match
        // A programme name is short and carries no year: 毛泽东 is NHK's four-part documentary on the
        // 纪录片 shelf and a 49-episode drama on TMDB, and the name alone cannot tell them apart. What
        // the shelf says the file IS can: a documentary shelf takes only a documentary.
        if ((viaProgramme || viaAlternative) && FilenameParser.isUnderDocRoot(item.path) &&
            !ShelfFit.documentary(details.genres)
        ) {
            return refused(base)
        }
        // …and whatever question found it, never an animation, a talk show or a reality show.
        if (FilenameParser.isUnderDocRoot(item.path) && ShelfFit.neverOnDocumentaryShelf(details.genres)) {
            return refused(base)
        }
        // A music shelf takes only music, whichever question found the answer: its folders are
        // categories (Audio, Lesson, Keyboard), and a database answers a category with a film.
        if (musicShelf && !ShelfFit.music(details.genres)) {
            return refused(base)
        }
        // Key the artwork by the WORK, not the file. A show's poster is the same image for every
        // episode, and keyed by item id it was downloaded, written and colour-analysed once per
        // file: 2,818 files outside the anime shelf held 2,611 poster files between 587 works. The
        // anime path has keyed by work since it was fixed there; this is the other half.
        val artKey = "tmdb_${details.providerId}"
        val posterPath = artwork.cache(details.posterUrl, artKey)
        val stillUrl = if (parsed.isEpisode && parsed.season != null && parsed.episode != null) {
            client.episodeStill(details.providerId, parsed.season, parsed.episode)
        } else null
        val ratings = allRatings(details)

        return base.copy(
            title = details.title.ifBlank { base.title },
            originalTitle = details.originalTitle,
            aliases = aliasesOf(details, details.title),
            year = details.year ?: base.year,
            overview = details.overview,
            rating = details.rating,
            ratings = ratings,
            runtime = details.runtime,
            certification = details.certification,
            genres = details.genres,
            cast = persons(details.cast),
            crew = persons(details.crew),
            posterPath = posterPath,
            posterGenerated = false,
            backdropPath = artwork.cache(details.backdropUrl, artKey + "_bd"),
            stillUrl = stillUrl,
            dominantColor = artwork.dominantColor(posterPath),
            tmdbId = details.providerId,
            kind = if (parsed.isEpisode) MediaKind.EPISODE else details.kind,
            // The series is called what the database calls it, as every other path writes it: left as
            // baseOf had it — the file's own title — the card read "The Mandalorian" on a Chinese wall
            // while each episode already knew the show as 曼达洛人 (LibraryRepair puts the old ones right).
            showTitle = if (parsed.isEpisode) details.title.ifBlank { base.title } else null,
            scrapeStatus = ScrapeStatus.MATCHED,
            ratingsVersion = RATINGS_VERSION,
        )
    }

    /** What the last [scrapeAll] learned about its data sources — shown beside the scan result. */
    data class Report(val anilistDown: Boolean, val anilistSkipped: Int, val anilistFailure: String? = null) {
        /** How many titles the outage left for the next pass, or null when there was none. */
        val leftOver: Int?
            get() = if (anilistDown || anilistSkipped > 0) anilistSkipped else null
    }

    @Volatile
    var lastReport: Report? = null
        private set

    suspend fun scrapeAll(
        items: List<MediaItem>,
        /**
         * Whether files already looked up are asked again — those that matched nothing, and the matched
         * ones the corrections in [needsScrape] re-ask. A 刮削 the user asks for does; a source added or
         * listed again does not — its new files are what it brought, and re-asking the whole library
         * (2,604 unmatched files on a real NAS: own recordings, music, programmes) took five minutes
         * of lookups that gave the same answers, re-saving each.
         */
        retryUnmatched: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) = coroutineScope {
        anilist.skipped.set(0)
        com.therealityteam.videoplayer.util.HttpStats.reset()
        ArtworkCache.resetWork()
        ResponseCache.resetDisk()
        val scrapeStartedAt = System.nanoTime()
        // A file nothing could have an entry for must not be wearing one. This runs before the
        // early return below, because a pass with nothing to scrape is exactly when a library
        // scraped under the old rules needs undoing: on a real one, 217 clips off a camera were
        // wearing a 1947 work called "C1284" and 54 guitar covers were wearing a 2021 film called
        // 鹿角. Cheap and idempotent — after the first pass there is nothing left to strip.
        val wronglyNamed = items.filter {
            !it.identityPinned && it.scrapeStatus == ScrapeStatus.MATCHED &&
                FilenameParser.nothingToLookUp(it.path, it.fileName, it.title)
        }
        val stripped = wronglyNamed.map { clearedUnmatched(it, it.fileName.substringBeforeLast('.')) }
        // The library as the corrections below leave it. The pass scrapes THESE: scraping the copies
        // they replaced wrote each correction straight back over itself.
        var library = items
        fun corrected(changed: List<MediaItem>) {
            if (changed.isEmpty()) return
            val byId = changed.associateBy { it.id }
            library = library.map { byId[it.id] ?: it }
        }
        corrected(stripped)
        if (stripped.isNotEmpty()) {
            Log.i("AuraPerf", "stripping ${stripped.size} identities nothing could have given")
            repo.replaceAll(stripped)
            // Written now, not whenever the upserts next go quiet. This pass usually has nothing
            // else to do and returns immediately below, so a one-time correction left in a
            // coalescing window is a one-time correction lost.
            repo.flush()
        }

        // An unmatched file still wearing an identity an earlier pass gave it, from before an unmatched
        // answer took that off (refused): its folder's card showed the entry's poster. Cheap and
        // idempotent too, and it reaches the files this pass will not ask about.
        val stillWearing = library.filter {
            !it.identityPinned && it.scrapeStatus == ScrapeStatus.UNMATCHED && (it.tmdbId != null || it.collectionKey != null)
        }.map { refused(it) }
        corrected(stillWearing)
        if (stillWearing.isNotEmpty()) {
            Log.i("AuraPerf", "taking ${stillWearing.size} refused identities off unmatched files")
            repo.replaceAll(stillWearing)
            repo.flush()
        }

        // The same correction for `kind`. A scanned file's kind is written once and never read
        // again, so the scanner learning to say UNKNOWN reaches files added AFTER it and nobody's
        // existing library — where every file without an episode number is still claiming to be a
        // film on the strength of nothing. Matched items are left alone: their kind came from a
        // database, which is an answer rather than an absence of one.
        val overclaimed = library.filter {
            !it.identityPinned && it.scrapeStatus != ScrapeStatus.MATCHED && it.kind == MediaKind.MOVIE &&
                it.year == null && it.season == null && it.episode == null &&
                !FilenameParser.looksPublished(it.fileName)
        }.map { it.copy(kind = MediaKind.UNKNOWN) }
        corrected(overclaimed)
        if (overclaimed.isNotEmpty()) {
            Log.i("AuraPerf", "unclaiming ${overclaimed.size} films nothing ever called films")
            repo.replaceAll(overclaimed)
            repo.flush()
        }

        // An anime title matched before the details asked AniList for its banner has no picture behind
        // its page. Its banner is asked for — many works to a request, and then from the disk cache, so
        // a work AniList has none for costs a file read a pass — and put on its files. Not a re-scrape:
        // nothing else about the identity is touched.
        val bannerless = library.filter {
            it.scrapeStatus == ScrapeStatus.MATCHED && it.backdropPath == null &&
                it.collectionKey?.startsWith("anilist:") == true
        }.groupBy { it.collectionKey!!.removePrefix("anilist:").toIntOrNull() }
        if (bannerless.isNotEmpty()) {
            val banners = anilist.banners(bannerless.keys.filterNotNull()).map { (id, url) ->
                async(Dispatchers.IO) { gate.withPermit { id to artwork.cache(url, "anilist_${id}_bd") } }
            }.awaitAll().filter { it.second != null }.toMap()
            val bannered = bannerless.flatMap { (id, files) ->
                val path = banners[id] ?: return@flatMap emptyList()
                files.map { it.copy(backdropPath = path) }
            }
            corrected(bannered)
            if (bannered.isNotEmpty()) {
                Log.i("AuraPerf", "banners for ${banners.size} anime works (${bannered.size} files)")
                repo.replaceAll(bannered)
                repo.flush()
            }
        }

        // …and one bgm.tv could not name, matched before TMDB was asked for the name, still shows a
        // Chinese reader its AniList (English) title. Its TMDB entry is looked for from what the files
        // already say — the work's original title, its year and form — so AniList is not asked again;
        // TMDB's answers are cached, so a work it has no name for costs a cache read a pass.
        if (TmdbConfig.isChinese) {
            val unnamed = library.filter {
                it.scrapeStatus == ScrapeStatus.MATCHED && it.collectionKey?.startsWith("anilist:") == true &&
                    AnimeMatch.chineseName(it.showTitle ?: it.title) == null && it.originalTitle != null
            }.groupBy { it.collectionKey!! }
            val named = unnamed.values.map { files ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val rep = files.first()
                        val asked = MetaResult(
                            providerId = rep.tmdbId ?: 0,
                            kind = if (rep.kind == MediaKind.MOVIE) MediaKind.MOVIE else MediaKind.SHOW,
                            title = rep.showTitle ?: rep.title,
                            originalTitle = rep.originalTitle,
                            year = rep.year,
                        )
                        val twin = runCatching { animeTwin(asked) }.getOrNull()
                        val name = twin?.chineseName ?: return@withPermit emptyList()
                        val overview = twin.chineseOverview
                        files.map { f ->
                            f.copy(
                                title = name,
                                showTitle = if (f.showTitle != null) name else null,
                                aliases = (f.aliases + (f.showTitle ?: f.title)).distinct(),
                                overview = overview ?: f.overview,
                            )
                        }
                    }
                }
            }.awaitAll().flatten()
            corrected(named)
            if (named.isNotEmpty()) {
                Log.i("AuraPerf", "Chinese names from TMDB for ${named.mapTo(HashSet()) { it.collectionKey }.size} anime works (${named.size} files)")
                repo.replaceAll(named)
                repo.flush()
            }
        }

        val todo = library.filter { needsScrape(it, retryUnmatched) }
        val total = todo.size
        Log.i("AuraPerf", "scrape pass (${if (retryUnmatched) "requested" else "after a scan"}): $total of ${library.size} to look up " +
            "(${todo.count { it.scrapeStatus == ScrapeStatus.PENDING }} new, ${todo.count { it.scrapeStatus == ScrapeStatus.UNMATCHED }} unmatched, " +
            "${todo.count { it.scrapeStatus == ScrapeStatus.ERROR }} failed, ${todo.count { it.scrapeStatus == ScrapeStatus.MATCHED }} matched)")
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        onProgress(0, total)
        // Nothing to scrape means nothing to reconcile and nothing to draw. The tail below walks
        // the WHOLE library twice and writes it out, which a pass that identified no file has no
        // reason to do: measured on a targeted re-scrape, the empty PENDING batch that runs before
        // the real one spent thirty seconds of a forty-three second pass doing exactly that.
        if (todo.isEmpty()) {
            onProgress(0, 0)
            return@coroutineScope
        }
        val siblings = siblingTitles(library, todo)
        try {
            todo
                .map { item ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val itemStartedAt = System.nanoTime()
                            val updated = runCatching { scrape(item, siblings[releaseFolder(item.path)].orEmpty()) }
                                .getOrElse { item.copy(scrapeStatus = ScrapeStatus.ERROR) }
                            // A file that holds the pass open is said by name: the pass's length is its slowest file's.
                            val itemMs = (System.nanoTime() - itemStartedAt) / 1_000_000
                            if (itemMs > SLOW_ITEM_MS) Log.i("AuraPerf", "slow lookup: ${item.fileName} took ${itemMs}ms (${updated.scrapeStatus})")
                            repo.upsert(updated)
                            onProgress(done.incrementAndGet(), total)
                        }
                    }
                }
                .awaitAll()
        } finally {
            // The share connections the nfo and picture reads opened are this pass's alone.
            sidecars?.release()
        }
        Log.i(
            "AuraPerf",
            "scrape network: ${total} files in ${(System.nanoTime() - scrapeStartedAt) / 1_000_000_000}s\n" +
                com.therealityteam.videoplayer.util.HttpStats.report() + "\n" +
                "  " + ResponseCache.diskReport() + "\n" +
                "  " + ArtworkCache.workReport(),
        )
        // Everything from here walks the whole library rather than this pass's files, so it is
        // timed separately — on a targeted re-scrape it is easily the larger half.
        val tailStartedAt = System.nanoTime()
        // Warming every name across the cores first was tried here and is not worth it: it cost
        // 13.6s to save 5.0s, because it reads each FILE name while these passes mostly want FOLDER
        // names and classifications. Measured, then removed.
        // Folder reconciliation: pull a show's SPs / Extras / NC versions / orphans onto the
        // identity of its main files, so they merge into the show's card instead of orphan cards.
        val touched = todo.mapTo(HashSet()) { it.id }
        reconcileFolders(repo.all(), touched)
        val foldersAt = System.nanoTime()
        // Then adopt any sibling extras PACK (Downfall.Extras-Grym beside Downfall.2004/) onto its movie.
        reconcileExtraPacks(repo.all(), touched)
        val packsAt = System.nanoTime()
        Log.i(
            "AuraPerf",
            "scrape tail: folders=${(foldersAt - tailStartedAt) / 1_000_000}ms " +
                "packs=${(packsAt - foldersAt) / 1_000_000}ms over ${repo.all().size} items",
        )
        repo.flush() // same reason as discovery: the last coalescing window must not be lost
        fun ms(from: Long, to: Long) = (to - from) / 1_000_000
        Log.i(
            "AuraPerf",
            "scrape tail ${ms(tailStartedAt, System.nanoTime())}ms: " +
                "flush=${ms(packsAt, System.nanoTime())}ms",
        )
        onProgress(total, total)
        lastReport = Report(
            anilistDown = anilist.isDown,
            anilistSkipped = anilist.skipped.get(),
            anilistFailure = anilist.lastFailure,
        )
        Unit
    }

    /**
     * Per release folder, the titles its files spell out — context for a file whose own name is only
     * a shorthand (`[HKG&CASO][Seitokai][01]` beside `[KTXP&SD][Seitokai_no_Ichizon][03]`). Only
     * the folders that hold something to scrape are read, so a scan of a settled library pays nothing.
     */
    private fun siblingTitles(all: List<MediaItem>, todo: List<MediaItem>): Map<String, List<String>> {
        val folders = todo.mapTo(HashSet()) { releaseFolder(it.path) }
        return all.asSequence().filter { releaseFolder(it.path) in folders }
            .groupBy({ releaseFolder(it.path) }) {
                AnimeParse.parse(it.fileName).title?.takeIf { t -> t.isNotBlank() } ?: FilenameParser.parse(it.fileName).title
            }
            .mapValues { (_, v) -> v.distinct() }
    }

    /**
     * A poster from the film itself for every local / SMB item nothing identified (or that
     * matched without artwork), so the wall's long tail shows the films instead of grey
     * cards. Bounded: frame grabs decode video, so two at a time.
     */
    /**
     * No usable poster: none was ever recorded, or the file it names is not there any more.
     *
     * The second case is not hypothetical — a library outlives its artwork whenever storage is
     * cleared or a backup is restored onto a device without the cache, and a path alone was taken
     * as proof the picture existed. Those files were never offered a frame again.
     */
    /**
     * Which items to (re)scrape on a 刮削 / scan: unmatched or new ones — PLUS anime that is
     * currently pinned to a bare TMDB id (collectionKey == null). The latter lets a plain 刮削
     * re-route stale anime off wrong (often live-action) TMDB matches onto AniList, without a
     * full 清空媒体库. User-pinned (修正匹配) items are never touched.
     */
    private fun needsScrape(it: MediaItem, retryUnmatched: Boolean = true): Boolean = when {
        it.userMatched -> false
        // What lies beside it changed since it was identified: an nfo or a poster appeared, was
        // edited, or went away. Before the rule below, because a camera clip with an nfo has a name
        // after all.
        (localOf(it)?.stamp ?: "") != (it.localApplied ?: "") -> true
        // Somebody's own recording, or footage of a game. Nothing online has an entry for either —
        // that is not a gap in TMDB's coverage, there is nothing for it to cover — so there is no
        // question to ask. See FilenameParser.nothingToLookUp for what it costs to ask anyway.
        FilenameParser.nothingToLookUp(it.path, it.fileName, it.title) -> false
        // A pass after a scan: what is new or still waiting, what failed to be asked (the network),
        // and a ratings backfill (stamped, so it cannot loop). Not a file that matched nothing, nor the
        // corrections below that re-ask matched files — those are a requested 刮削's, and on a real NAS
        // the ones that cannot heal (an anime nobody lists on AniList) were re-asked on every pass.
        !retryUnmatched -> it.scrapeStatus == ScrapeStatus.PENDING || it.scrapeStatus == ScrapeStatus.ERROR ||
            (it.scrapeStatus == ScrapeStatus.MATCHED && it.ratingsVersion < RATINGS_VERSION)
        it.scrapeStatus != ScrapeStatus.MATCHED -> true
        // A ratings-pipeline upgrade (e.g. anime sites for TMDB-matched 剧场版) → backfill matched
        // items once; they're stamped to the current version below so this can't loop.
        it.ratingsVersion < RATINGS_VERSION -> true
        // An anime-shelf item NOT yet on AniList (unmatched-but-matched-elsewhere, or a stale TMDB/
        // live-action match) is re-routed through the guarded AniList path. Correct AniList hits are
        // left alone so a 刮削 stays fast — only the items that still need fixing are re-fetched.
        FilenameParser.isUnderAnimeRoot(it.path) && it.collectionKey?.startsWith("anilist:") != true -> true
        // A junk-named anime file ("08.avi", "Final.rmvb") that nonetheless matched is a fuzzy error —
        // re-scrape so the junk-query filter clears it (game recordings stop posing as random anime).
        FilenameParser.isUnderAnimeRoot(it.path) && isJunkTitle(FilenameParser.parse(it.fileName).title) -> true
        // …except on a music shelf, which the anime path never takes (see scrapeByName): a concert
        // named the way fansubs name things was re-asked on every pass for an answer that never came.
        else -> it.collectionKey == null && AnimeParse.parse(it.fileName).isAnimeLikely &&
            !FilenameParser.isUnderMusicRoot(it.path)
    }

    /** Folder reconciliation — the rules live in [FolderReconcile] so they can be replayed offline. */
    private fun reconcileFolders(all: List<MediaItem>, touched: Set<String>) {
        val t0 = System.nanoTime()
        val planned = FolderReconcile.plan(all, touched)
        val plannedAt = System.nanoTime()
        repo.replaceAll(planned)
        Log.i(
            "AuraPerf",
            "reconcile folders: planned ${planned.size} in ${(plannedAt - t0) / 1_000_000}ms, " +
                "wrote them in ${(System.nanoTime() - plannedAt) / 1_000_000}ms",
        )
    }

    private val CJK = Regex("""[㐀-鿿぀-ヿ가-힯]""")
    private fun hasCjk(s: String): Boolean = CJK.containsMatchIn(s)

    private fun isJunkTitle(s: String?): Boolean = FilenameParser.isJunkTitle(s)

    /** Best display title for an unmatched anime file: a real Anitomy title, else the franchise folder
     *  name — never a junk token ("01" / "Final"). */
    private fun animeUnmatchedTitle(anime: AnimeParse.Parsed, path: String, fallback: String): String {
        val fansub = FilenameParser.fansubTitle(path.substringAfterLast('/'), FilenameParser.folderName(path))
            ?.takeIf { it.isNotBlank() }
        val anitomy = anime.title?.takeIf { it.isNotBlank() }
        val folder = FilenameParser.animeShowFolder(path)?.let { FilenameParser.parse(it).title }?.takeIf { it.isNotBlank() }
        return fansub?.takeIf { !isJunkTitle(it) }
            ?: anitomy?.takeIf { !isJunkTitle(it) }
            ?: folder?.takeIf { !isJunkTitle(it) }
            ?: anitomy ?: folder ?: fallback
    }

    /**
     * A file a pass found nothing for, or refused the answer for, with whatever an earlier answer left
     * on it taken off. Left on, an unmatched file kept that entry's poster, genres and synopsis — and
     * its folder's card wore them: Music/Lesson's 62 guitar lessons a romance's poster, Drum & Bass's
     * 40 clips a documentary's. What is the file's own stays: the year its name gives, the pictures
     * beside it (`local_…`) and a frame drawn from it.
     */
    private fun refused(base: MediaItem): MediaItem {
        fun own(p: String?) = p?.takeIf { it.substringAfterLast('/').startsWith("local_") }
        val poster = own(base.posterPath) ?: base.posterPath?.takeIf { base.posterGenerated }
        return clearedUnmatched(base, base.title).copy(
            year = FilenameParser.parseFromPath(base.path).year,
            posterPath = poster,
            posterGenerated = poster != null && own(base.posterPath) == null,
            backdropPath = own(base.backdropPath),
            dominantColor = if (poster != null) base.dominantColor else null,
        )
    }

    /** Strip every scraped match identity, keeping only the file + a clean title — a true "unidentified"
     *  state so grouping falls back to the folder and the card shows a placeholder, not stale artwork. */
    private fun clearedUnmatched(base: MediaItem, title: String): MediaItem = base.copy(
        title = title,
        showTitle = if (base.kind == MediaKind.EPISODE) title else null,
        originalTitle = null, year = null, overview = null, rating = null, ratings = emptyList(),
        runtime = null, certification = null, genres = emptyList(), cast = emptyList(), crew = emptyList(),
        posterPath = null, posterGenerated = false,
        backdropPath = null, stillPath = null, stillUrl = null, dominantColor = null,
        tmdbId = null, collectionKey = null, contentTag = null, fromNfo = false,
        scrapeStatus = ScrapeStatus.UNMATCHED,
    )


    /**
     * Second reconciliation pass for an extras PACK that sits as a SIBLING of its movie rather than a
     * subfolder: `Movies/Downfall.Extras-Grym/<extra>/file` beside `Movies/Downfall.2004…/`. Climbing
     * can't reach the movie (different release folder), so instead match the pack to a matched sibling
     * movie by title and adopt its files as 特典. Runs AFTER [reconcileFolders], so subfolder extras
     * (already matched) are excluded and only true sibling packs remain.
     */
    private fun reconcileExtraPacks(all: List<MediaItem>, touched: Set<String>) {
        // Collected and written once: an upsert per file copies the whole library each time.
        val adopted = mutableListOf<MediaItem>()
        val matched = all.filter { it.scrapeStatus == ScrapeStatus.MATCHED && identityOf(it) != null }
        if (matched.isEmpty()) return
        // Matched movies grouped by the parent dir of their release folder, paired with a clean title.
        val moviesByParent = matched.groupBy(
            { releaseFolder(it.path).substringBeforeLast('/', "") },
            { it to FilenameParser.parse(releaseFolder(it.path).substringAfterLast('/')).title },
        )
        all.asSequence()
            .filter { it.scrapeStatus != ScrapeStatus.MATCHED && !it.identityPinned }
            .mapNotNull { item -> extraPackRoot(item.path)?.let { it to item } }
            .groupBy({ it.first }, { it.second })
            // Same reasoning as FolderReconcile.plan: a pack whose files were not looked at this
            // pass will decide what it decided last time, which the library already says.
            .filterValues { files -> files.any { it.id in touched } }
            .forEach { (packRoot, files) ->
                val parent = packRoot.substringBeforeLast('/', "")
                val packTitle = FilenameParser.parse(packBaseName(packRoot.substringAfterLast('/'))).title
                if (packTitle.isBlank()) return@forEach
                val rep = moviesByParent[parent]
                    ?.maxByOrNull { StringSimilarity.titleSimilarity(packTitle, it.second) }
                    ?.takeIf { StringSimilarity.titleSimilarity(packTitle, it.second) >= 0.6 }
                    ?.first ?: return@forEach
                val dominant = identityOf(rep)!!
                files.forEach { item ->
                    adopted += item.copy(
                        showTitle = rep.showTitle ?: rep.title,
                        overview = item.overview ?: rep.overview,
                        genres = rep.genres, cast = rep.cast, crew = rep.crew,
                        rating = rep.rating, ratings = rep.ratings, runtime = rep.runtime,
                        certification = rep.certification, year = item.year ?: rep.year,
                        posterPath = rep.posterPath, backdropPath = rep.backdropPath,
                        dominantColor = rep.dominantColor, tmdbId = rep.tmdbId,
                        collectionKey = rep.collectionKey ?: dominant,
                        kind = MediaKind.EPISODE, contentTag = ContentKind.EXTRA,
                        scrapeStatus = ScrapeStatus.MATCHED, ratingsVersion = rep.ratingsVersion,
                    )
                }
            }
        repo.replaceAll(adopted)
    }


    /** Nearest ancestor folder of a file whose name is a decorated extras pack (`X.Extras-Grym`). */
    private fun extraPackRoot(path: String): String? {
        var dir = path.replace('\\', '/').trimEnd('/').substringBeforeLast('/', "")
        repeat(5) {
            if (!dir.contains('/')) return null
            if (isExtraFolder(dir.substringAfterLast('/'))) return dir
            dir = dir.substringBeforeLast('/')
        }
        return null
    }

    /** The movie name a pack belongs to: the folder name up to its extras marker (`Downfall.Extras` → `Downfall`). */
    private fun packBaseName(folderName: String): String =
        EXTRA_FOLDER_RE.find(folderName)?.let { folderName.substring(0, it.range.first) }
            ?.ifBlank { folderName } ?: folderName

    private fun identityOf(it: MediaItem): String? = FolderReconcile.identityOf(it)

    private fun releaseFolder(path: String): String = FolderReconcile.releaseFolder(path)

    private fun isExtraFolder(name: String): Boolean = FolderReconcile.isExtraFolder(name)

    private fun baseOf(item: MediaItem, parsed: ParsedName): MediaItem {
        val year = parsed.year ?: item.year
        return item.copy(
            title = parsed.title.ifBlank { item.title },
            year = year,
            season = parsed.season,
            episode = parsed.episode,
            showTitle = if (parsed.isEpisode) parsed.title else null,
            // The second copy of the claim the scanner stopped making, and the one that made the
            // correction above pointless: every pass rebuilt an unmatched file as a film, so the
            // scrape that ran the correction undid it on its way past. A match overwrites this
            // with the database's own answer, so it only ever decides what nothing identified.
            kind = when {
                parsed.isEpisode -> MediaKind.EPISODE
                year != null || FilenameParser.looksPublished(item.fileName) -> MediaKind.MOVIE
                else -> MediaKind.UNKNOWN
            },
            // The viewer's word, and what the file said of itself as it played, outrank its name.
            video = if (item.video.userOverridden || item.video.fromFile) item.video else ProjectionDetector.detect(item.fileName),
        )
    }

    /**
     * TMDB fallback for an anime file AniList couldn't resolve — accepts the match ONLY when it carries
     * an Animation genre, so an anime never grabs a live-action lookalike. Recovers titles AniList
     * indexes differently (a film known by its subtitle, an obscure donghua) that TMDB does have.
     */
    private suspend fun tmdbAnimeMatch(base: MediaItem, parsed: ParsedName, item: MediaItem, otherNames: List<String> = emptyList()): MediaItem? {
        if (!client.isConfigured) return null
        val asks = listOfNotNull(parsed.takeIf { it.title.isNotBlank() && !isJunkTitle(it.title) }) +
            otherNames.filterNot(::isJunkTitle).flatMap { name ->
                // 「零之使魔2」 as it is written first (a film's sequel may be called so), then as the show and its
                // season: TMDB files it 零之使魔, a season of which the 2 is.
                listOfNotNull(
                    parsed.copy(title = name),
                    TRAILING_SEASON.matchEntire(name)?.let { m -> parsed.copy(title = m.groupValues[1].trim(), season = parsed.season ?: m.groupValues[2].toInt()) },
                )
            }
        val details = asks.firstNotNullOfOrNull { tmdbAnimeEntry(it, item) } ?: return null
        val artKey = "tmdb_${details.providerId}"
        val posterPath = artwork.cache(details.posterUrl, artKey)
        val stillUrl = if (parsed.isEpisode && parsed.season != null && parsed.episode != null) {
            client.episodeStill(details.providerId, parsed.season, parsed.episode)
        } else null
        return base.copy(
            title = details.title.ifBlank { base.title },
            originalTitle = details.originalTitle,
            aliases = aliasesOf(details, details.title),
            year = details.year ?: base.year,
            overview = details.overview,
            rating = details.rating,
            ratings = allRatings(details),
            runtime = details.runtime,
            certification = details.certification,
            genres = details.genres,
            cast = persons(details.cast),
            crew = persons(details.crew),
            posterPath = posterPath,
            posterGenerated = false,
            backdropPath = artwork.cache(details.backdropUrl, artKey + "_bd"),
            stillUrl = stillUrl,
            dominantColor = artwork.dominantColor(posterPath),
            tmdbId = details.providerId,
            collectionKey = "id:${details.providerId}", // same scheme as auto-grouping → episodes collapse
            kind = if (parsed.isEpisode) MediaKind.EPISODE else details.kind,
            showTitle = if (parsed.isEpisode) details.title else null,
            scrapeStatus = ScrapeStatus.MATCHED,
            ratingsVersion = RATINGS_VERSION,
        )
    }

    /** Everything about an anime WORK that every one of its files shares (see [animeWork]). */
    private data class AnimeWork(
        val displayTitle: String,
        val overview: String?,
        val ratings: List<RatingScore>,
        val posterPath: String?,
        val dominantColor: Int?,
        val backdropPath: String? = null,
    )

    private val animeWorkCache = java.util.concurrent.ConcurrentHashMap<Int, AnimeWork>()

    /**
     * The per-work enrichment — Chinese name/summary from Bangumi, the poster (+ its dominant colour)
     * and the merged ratings — computed ONCE per AniList id.
     *
     * It used to run for every file: a 100-episode show meant 100 Bangumi lookups, 100 poster decodes
     * and, worst of all, 100 uncached TMDB search+details round trips from [animeTwin].
     * That, not the API rate limits, was most of the per-file cost of a full scrape.
     */
    private suspend fun animeWork(best: MetaResult): AnimeWork {
        animeWorkCache[best.providerId]?.let { return it }
        // Enrich with Chinese name + summary from Bangumi (AniList titles are romaji/English).
        val jpTitle = best.originalTitle ?: best.title
        // MAL's score is asked alongside Bangumi rather than after it (see easternRatings).
        val (cn, malScore) = coroutineScope {
            val mal = async { jikan.malRating(best.romajiTitle ?: best.title) }
            bangumi.chinese(jpTitle, ownNames(best)) to mal.await()
        }
        // Poster: prefer Bangumi's large cover (higher-res original scan), fall back to AniList.
        val posterPath = artwork.cache(cn?.posterUrl ?: best.posterUrl, "anilist_${best.providerId}")
        // Ratings: query every source we can, show whatever exists. Anime sites (AniList / Bangumi /
        // MAL) plus the Western sites (TMDB / IMDb …) the title is also on — no anime-vs-movie gating.
        val twin = animeTwin(best)
        val ratings = buildList {
            addAll(best.ratings)
            cn?.score?.takeIf { it > 0 }?.let { add(RatingScore(RatingSource.BANGUMI, RatingScore.tenths(it))) }
            malScore?.let { add(it) }
            twin?.let { addAll(westernRatings(it.details)) }
        }.distinctBy { it.source }
        // Bangumi's Chinese name is the right one to SHOW a Chinese reader and the wrong one for
        // everyone else — a viewer in Munich should see Guilty Crown, not 罪恶王冠. Its poster and
        // its rating are worth having either way, so the lookup still happens.
        val cnNames = TmdbConfig.isChinese
        // …and only when the subject it came from is THIS work rather than the franchise. Bangumi
        // answers 「ラブライブ! School idol project 2期」 with 「ラブライブ!」 and both R.O.D entries
        // with 「R.O.D」, so the second season and the TV series were renamed to names that do not
        // distinguish them from what they continue: two cards, one name between them. AniList's own
        // title always names the entry, so it is the right thing to fall back to.
        val cnName = bangumi.nameFor(jpTitle, cn)
        // Where bgm.tv has no name for it — its search is a keyword one, five deep, and files 43 of a
        // real shelf's 253 works under spellings AniList does not use — TMDB's entry for the work
        // often has one: 机动警察 东京毁灭战, 刀剑神域：序列之争, 浪客剑心. Not over an AniList title
        // that is Chinese already.
        val tmdbName = twin?.chineseName?.takeIf { cnName == null && AnimeMatch.chineseName(best.title) == null }
        // A reader of another language — German, French, Japanese… — reads the work in theirs where TMDB's entry IS
        // the work: its title (the Japanese keep AniList's own, already theirs) and its synopsis, AniList's English otherwise.
        val reader = TmdbConfig.language.substringBefore('-')
        val localTwin = twin?.takeIf { it.namesWork && !cnNames && reader != "en" }?.details
        val work = AnimeWork(
            displayTitle = cnName?.takeIf { cnNames } ?: tmdbName?.takeIf { cnNames }
                ?: localTwin?.title?.takeIf { it.isNotBlank() && reader != "ja" } ?: best.title,
            overview = cn?.summary?.takeIf { cnNames && cnName != null && it.isNotBlank() }
                ?: twin?.chineseOverview?.takeIf { cnNames && tmdbName != null }
                ?: localTwin?.overview?.takeIf { it.isNotBlank() }
                ?: best.overview,
            ratings = ratings,
            posterPath = posterPath,
            dominantColor = artwork.dominantColor(posterPath),
            backdropPath = artwork.cache(best.backdropUrl, "anilist_${best.providerId}_bd"),
        )
        // A concurrent duplicate just recomputes once — cheaper than holding a lock across the network.
        animeWorkCache[best.providerId] = work
        return work
    }

    /** Resolve an anime file against AniList; trusts AniList's top relevance hit. Null if no match. */
    private suspend fun resolveAnime(
        base: MediaItem,
        anime: AnimeParse.Parsed,
        parsed: ParsedName,
        path: String,
        siblings: Collection<String> = emptyList(),
    ): MediaItem? {
        // Query construction lives in AnimeQueries so its ORDER and its junk guards are unit-testable
        // without the network — that is exactly where identification goes wrong.
        val queries = AnimeQueries.build(path, parsed, anime, siblings)
        // AniList returns a popular top hit for ANY string ("UFO之夏 伊里野的天空" → Digimon), so accept
        // a hit only when it actually resembles the query that found it — else a wrong fuzzy match
        // would stick. Take the first query that yields a validated hit.
        // The season the PATH names decides between hits when the query cannot say it — see seasonFit.
        val season = AnimeQueries.seasonOf(path) ?: parsed.season
        // A name that says 劇場版 / the Movie must not settle for the series it spun off from.
        //
        // Both readings of the name get a say. [AnimeParse] only sees the type element Anitomy
        // pulled out of the FILE name, and a film whose marker is glued inside a bracket
        // (`[Macross_Frontier_The_Movie-The_Wings_of_Goodbye]`) or left in the FOLDER yields no
        // type at all: measured over a 5,370-file anime shelf, 26 files — the Macross F films, all
        // three EVA Rebuilds, Gundam 00 the Movie, three G-Reco films, 朝花夕誓 — were read as
        // ordinary episodes and matched with no preference for a film whatsoever.
        val wantMovie = anime.contentKind == ContentKind.MOVIE ||
            ContentClassifier.classify(base.fileName, path) == ContentKind.MOVIE
        // …and the form the PATH names, for the queries that do not spell one themselves — or, when
        // no folder names one, the form the FILE does: `[SumiSora][Munto][TV][01]` is the 2009 series,
        // which AniList answers "Munto" with second, after the one-episode 2003 OVA. Not a clip's:
        // `[AKIRA][TV_Spot]` is a trailer for the film, and its "TV" names no form.
        val pathForm = AnimeQueries.formOfPath(path)
            ?: AnimeQueries.formOfType(anime.type).takeUnless { ContentClassifier.isStrongExtra(base.fileName) }
        // A film's first answer is often its SERIES: the box's Chinese name (`天空之艾斯嘉科尼 TV +
        // Movie`) is a synonym of the TV entry, and the picker can only prefer a film among the hits
        // it is given. So for a 劇場版 keep asking until some query yields a film; the series stays
        // as the fallback in case none does.
        // The best name we have for this file — already stripped of 特典 markers and release words.
        // When a BROADER query is what finally answers, the answer still has to square with it.
        val ownName = queries.firstOrNull()
        // A file numbered like an episode, in a name that names no season: if the box turns out to
        // be a set of numbered FILMS, this is which one. See AnimeMatch.filmOrdinal. Not for an
        // extra — `[PV02]`, `[SP03]` number the trailers and specials, not the films, and read as
        // film numbers they scattered a box's 特典 across its four Borders.
        // …or numbered by the film's own numeral, where it has no episode number: the Zeta Gundam box
        // holds `Mobile_Suit_Z_Gundam_II_-_Lovers`, which no database knows by that name, and the
        // box's name answers with all three films — II and III were filed as the first.
        val filmNumber = if (season == null && ContentClassifier.classify(base.fileName, path) != ContentKind.EXTRA) {
            anime.episode ?: parsed.episode ?: AnimeMatch.romanOrdinal(parsed.title)
        } else null
        var best: MetaResult? = null
        for (q in queries) {
            val hit = AnimeMatch.pick(q, anilist.search(q), season, wantMovie, pathForm, filmNumber) ?: continue
            // A tie-in inside a series' box is in no database under its own name, so the folder's
            // query answers for it with the umbrella work. Refuse that: an unscraped card is honest
            // where a wrong one is silent, and reconciliation still files it beside its siblings.
            if (ownName != null && q != ownName &&
                AnimeMatch.namesAnotherWork(ownName, hit)
            ) continue
            if (!wantMovie || hit.kind == MediaKind.MOVIE) {
                best = hit
                break
            }
            if (best == null) best = hit
        }
        // Chinese-only filename (no romaji anywhere) → AniList can't match it. Bridge through Bangumi,
        // which DOES index Chinese names, to recover the Japanese title AniList resolves (银河英雄传说 →
        // 銀河英雄伝説 → Ginga Eiyuu Densetsu). Key off the most specific CJK folder, AS-IS, so a season
        // (零之使魔2 → ゼロの使い魔～双月の騎士～ → S2 entry) doesn't collapse to S1.
        if (best == null) {
            val cjkQuery = AnimeQueries.cjkBridgeQuery(path, parsed, anime)
            val jp = cjkQuery?.let { bangumi.chinese(it, official = false)?.nameJp }?.takeIf { it.isNotBlank() }
            if (jp != null) {
                best = AnimeMatch.pick(jp, anilist.search(jp), season, wantMovie, filmNumber = filmNumber)
                    ?: AnimeMatch.pick(jp, anilist.search(AnimeParse.baseTitle(jp)), season, wantMovie, filmNumber = filmNumber)
            }
        }
        best ?: return null
        // The search brought back only what choosing needs. Now that one entry has been chosen, ask
        // for the rest of it — description, cast, staff, cover — for that entry alone. Once per
        // work, not once per file: the answer is cached by id in the client. If AniList cannot be
        // reached the light result stands, and the file is still identified correctly.
        best = anilist.detail(best.providerId) ?: best
        val isMovie = best.kind == MediaKind.MOVIE || anime.contentKind == ContentKind.MOVIE
        val work = animeWork(best)
        return base.copy(
            title = work.displayTitle,
            originalTitle = best.originalTitle,
            aliases = aliasesOf(best, work.displayTitle),
            year = best.year ?: base.year,
            overview = work.overview,
            rating = best.rating,
            ratings = work.ratings,
            runtime = best.runtime,
            certification = best.certification,
            genres = best.genres,
            cast = persons(best.cast),
            crew = persons(best.crew),
            posterPath = work.posterPath,
            backdropPath = work.backdropPath,
            stillPath = null,
            dominantColor = work.dominantColor,
            tmdbId = best.providerId, // AniList id; grouping keys off collectionKey, so no TMDB collision
            kind = if (isMovie) MediaKind.MOVIE else MediaKind.EPISODE,
            showTitle = if (isMovie) null else work.displayTitle,
            season = anime.season ?: parsed.season,
            episode = anime.episode ?: parsed.episode,
            collectionKey = "anilist:${best.providerId}",
            contentTag = anime.contentKind,
            scrapeStatus = ScrapeStatus.MATCHED,
            ratingsVersion = RATINGS_VERSION,
        )
    }

    // ───────────────────────── manual fix / misc ─────────────────────────

    /**
     * Films and series both, the better known first. It asked for films and fell back to series
     * only when no film answered — so a name that is both (Shogun: films of 1968 and 2017, series of
     * 1980 and 2024) could never be fixed to its series.
     */
    suspend fun searchManual(query: String): List<MetaResult> {
        if (!client.isConfigured || query.isBlank()) return emptyList()
        val q = query.trim()
        val films = retry(MAX_RETRIES) { client.search(ParsedName(title = q)) } ?: emptyList()
        val series = retry(MAX_RETRIES) { client.search(ParsedName(title = q, season = 1)) } ?: emptyList()
        return (films + series).distinctBy { it.kind to it.providerId }.sortedByDescending { it.popularity }.take(16)
    }

    /** One season of a show TMDB identified, episode by number — for its page (see LibraryViewModel.episodes). */
    suspend fun episodes(tvId: Int, season: Int): Map<Int, EpisodeInfo> =
        runCatching { client.episodes(tvId, season) }.getOrDefault(emptyMap())

    /**
     * Each episode among [items] as the database tells it — its own name, what happens in it — keyed
     * by item id: a show TMDB identified from its season (where the pass left it, in the response
     * cache), an anime from bgm.tv. Nothing is stored on the files, so a library identified before
     * this needs no pass to run again. Empty for anything else.
     */
    suspend fun episodeGuide(items: List<MediaItem>): Map<String, EpisodeInfo> {
        val episodes = items.filter {
            it.scrapeStatus == ScrapeStatus.MATCHED && it.kind == MediaKind.EPISODE && it.episode != null
        }
        if (episodes.isEmpty()) return emptyMap()
        return buildMap {
            episodes.filter { it.collectionKey == null && it.tmdbId != null && it.season != null }
                .groupBy { it.tmdbId!! to it.season!! }.forEach { (season, files) ->
                    val told = episodes(season.first, season.second)
                    files.forEach { file -> told[file.episode!!]?.let { put(file.id, it) } }
                }
            // An anime is one AniList entry a season, and bgm.tv numbers each subject's episodes from 1.
            episodes.filter { it.collectionKey?.startsWith(ANILIST_KEY) == true }
                .groupBy { it.collectionKey!!.removePrefix(ANILIST_KEY).toIntOrNull() }.forEach { (id, files) ->
                    if (id == null) return@forEach
                    val told = animeEpisodes(id)
                    files.forEach { file -> told[file.episode!!]?.let { put(file.id, it) } }
                }
        }
    }

    /**
     * An anime's episodes, by number, from bgm.tv — AniList names none. The subject is the one its
     * Chinese name and cover came from, chosen again from what the pass left cached (AniList's entry
     * and bgm.tv's answer), so a page asks bgm.tv only for the episodes.
     */
    suspend fun animeEpisodes(anilistId: Int): Map<Int, EpisodeInfo> = runCatching {
        val entry = anilist.detail(anilistId) ?: return emptyMap()
        val subject = bangumi.subjectId(entry.originalTitle ?: entry.title, ownNames(entry)) ?: return emptyMap()
        bangumi.episodes(subject)
    }.getOrDefault(emptyMap())

    /** Free-text AniList search, for fixing an anime card by hand (the TMDB one misses most anime). */
    suspend fun searchManualAnime(query: String): List<MetaResult> {
        if (query.isBlank()) return emptyList()
        return anilist.search(query.trim())
    }

    /**
     * Pin [items] — a whole card, usually — to the work [chosen]: the details fetched once, and each
     * file keeping what is its own (season, episode, its still). Pinning only the card's first file
     * split a misidentified series in two: the file that was fixed, and every other episode still
     * filed under the wrong show. [anime]: [chosen] is an AniList entry, filed as the anime path
     * files one — its Chinese name and cover from Bangumi, its ratings, `anilist:` as its card.
     */
    suspend fun applyMatch(items: List<MediaItem>, chosen: MetaResult, anime: Boolean): List<MediaItem> {
        if (items.isEmpty()) return emptyList()
        if (!anime) return items.map { applyMatch(it, chosen) }
        val best = anilist.detail(chosen.providerId) ?: chosen
        val work = animeWork(best)
        val movie = best.kind == MediaKind.MOVIE
        val updated = items.map { item ->
            val a = AnimeParse.parse(item.fileName)
            val parsed = FilenameParser.parseFromPath(item.path)
            item.copy(
                title = work.displayTitle,
                originalTitle = best.originalTitle,
                aliases = aliasesOf(best, work.displayTitle),
                year = best.year ?: item.year,
                overview = work.overview,
                rating = best.rating,
                ratings = work.ratings,
                runtime = best.runtime,
                certification = best.certification,
                genres = best.genres,
                cast = persons(best.cast),
                crew = persons(best.crew),
                posterPath = work.posterPath,
                posterGenerated = false,
                backdropPath = work.backdropPath,
                stillPath = null,
                stillUrl = null,
                dominantColor = work.dominantColor,
                tmdbId = best.providerId,
                kind = if (movie) MediaKind.MOVIE else MediaKind.EPISODE,
                showTitle = if (movie) null else work.displayTitle,
                season = item.season ?: a.season ?: parsed.season,
                episode = item.episode ?: a.episode ?: parsed.episode,
                collectionKey = "anilist:${best.providerId}",
                userMatched = true,
                fromNfo = false,
                scrapeStatus = ScrapeStatus.MATCHED,
                ratingsVersion = RATINGS_VERSION,
            )
        }
        // Overwritten, not merged: upsertAll is discovery's merge, which keeps a file's identity.
        repo.replaceAll(updated)
        return updated
    }

    /**
     * A match another device fixed by hand (sync's [com.therealityteam.videoplayer.data.sync.SyncCorrection]),
     * filed here for this device's own files of it. Only when the entry can be fetched now: one that
     * cannot (offline, an outage) would file the files under an empty name, so the fix waits for the
     * next sync instead. True when the files were filed.
     */
    suspend fun adoptMatch(items: List<MediaItem>, source: String, id: Int, kind: MediaKind): Boolean {
        if (items.isEmpty()) return false
        if (source == "anilist") {
            val entry = anilist.detail(id) ?: return false
            applyMatch(items, entry, anime = true)
            return true
        }
        val details = retry(MAX_RETRIES) { client.details(id, kind) } ?: return false
        applyMatch(items, details, anime = false)
        return true
    }

    suspend fun applyMatch(item: MediaItem, chosen: MetaResult): MediaItem {
        val details = retry(MAX_RETRIES) { client.details(chosen.providerId, chosen.kind) } ?: chosen
        val artKey = "tmdb_${details.providerId}"
        val posterPath = artwork.cache(details.posterUrl ?: chosen.posterUrl, artKey)
        val stillUrl = if (item.kind == MediaKind.EPISODE && item.season != null && item.episode != null) {
            client.episodeStill(details.providerId, item.season, item.episode)
        } else null
        val updated = item.copy(
            title = details.title.ifBlank { chosen.title },
            originalTitle = details.originalTitle,
            aliases = aliasesOf(details, details.title),
            year = details.year ?: item.year,
            overview = details.overview,
            rating = details.rating,
            ratings = allRatings(details),
            runtime = details.runtime,
            certification = details.certification,
            genres = details.genres,
            cast = persons(details.cast),
            crew = persons(details.crew),
            posterPath = posterPath,
            posterGenerated = false,
            backdropPath = artwork.cache(details.backdropUrl ?: chosen.backdropUrl, artKey + "_bd"),
            stillUrl = stillUrl,
            dominantColor = artwork.dominantColor(posterPath),
            tmdbId = details.providerId,
            // A series' episodes carry its name as the card's; left alone, the card kept the name of
            // the show the files had been mistaken for.
            showTitle = if (details.kind == MediaKind.SHOW || chosen.kind == MediaKind.SHOW) details.title.ifBlank { chosen.title } else null,
            // The card follows the TMDB id now; an earlier anime identity (anilist:…) would hold it.
            collectionKey = null,
            kind = if (item.kind == MediaKind.EPISODE) MediaKind.EPISODE else chosen.kind,
            userMatched = true, // pin it: re-scan / re-scrape / AI pass must not overwrite a manual match
            fromNfo = false,
            scrapeStatus = ScrapeStatus.MATCHED,
            ratingsVersion = RATINGS_VERSION,
        )
        repo.upsert(updated)
        return updated
    }

    /**
     * The library's words in the reader's language again, after 元数据语言 (or the app's language it follows) changed:
     * each work matched online is fetched once more in it — TMDB's by its id, an anime by its AniList entry — and its
     * files' title, synopsis, names, genres, cast and crew rewritten; their identity (ids, card, a match fixed by
     * hand), pictures, ratings and progress stay as they are. A file read from its own .nfo keeps the nfo's words.
     * [onProgress] hears works done of works; the answer is how many works were rewritten.
     */
    suspend fun relocalize(items: List<MediaItem>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        if (TmdbConfig.offline) return 0
        animeWorkCache.clear() // its names were the old language's
        val works = items
            .filter { it.scrapeStatus == ScrapeStatus.MATCHED && !it.fromNfo }
            .groupBy { item ->
                item.collectionKey?.takeIf { it.startsWith("anilist:") }
                    ?: item.tmdbId?.let { id -> (if (item.kind == MediaKind.MOVIE) "movie:" else "tv:") + id }
            }
            .filterKeys { it != null }
        val total = works.size
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val rewritten = java.util.concurrent.atomic.AtomicInteger(0)
        onProgress(0, total)
        coroutineScope {
            works.forEach { (key, files) ->
                launch {
                    gate.withPermit {
                        val updated = runCatching { relocalized(key!!, files) }
                            .onFailure { Log.w("AuraScan", "$key: not fetched in ${TmdbConfig.language} ($it)") }.getOrNull()
                        if (updated != null) { repo.replaceAll(updated); rewritten.incrementAndGet() }
                    }
                    onProgress(done.incrementAndGet(), total)
                }
            }
        }
        Log.i("AuraScan", "library in ${TmdbConfig.language}: ${rewritten.get()} of $total works rewritten")
        return rewritten.get()
    }

    /** [files] of one work with its words fetched in the reader's language, or null when the entry did not answer. */
    private suspend fun relocalized(key: String, files: List<MediaItem>): List<MediaItem>? {
        if (key.startsWith("anilist:")) {
            val best = anilist.detail(key.removePrefix("anilist:").toIntOrNull() ?: return null) ?: return null
            val work = animeWork(best)
            return files.map { f ->
                f.copy(
                    title = work.displayTitle,
                    aliases = aliasesOf(best, work.displayTitle),
                    overview = work.overview,
                    genres = best.genres,
                    cast = persons(best.cast),
                    crew = persons(best.crew),
                    showTitle = if (f.showTitle != null) work.displayTitle else null,
                )
            }
        }
        val kind = if (key.startsWith("movie:")) MediaKind.MOVIE else MediaKind.SHOW
        val details = retry(MAX_RETRIES) { client.details(key.substringAfter(':').toInt(), kind) } ?: return null
        if (details.title.isBlank()) return null
        return files.map { f ->
            f.copy(
                title = details.title,
                aliases = aliasesOf(details, details.title),
                overview = details.overview ?: f.overview,
                certification = details.certification ?: f.certification,
                genres = details.genres.ifEmpty { f.genres },
                cast = persons(details.cast).ifEmpty { f.cast },
                crew = persons(details.crew).ifEmpty { f.crew },
                showTitle = if (f.showTitle != null) details.title else null,
            )
        }
    }

    /** Wipe all scraped items + cached artwork (keeps source configs) for a from-zero rebuild. */
    suspend fun clearLibrary() {
        repo.clear()
        artwork.clearAll()
    }

    /**
     * Drop the scraped identity of [items] so the next pass re-identifies them from scratch — the
     * evaluation loop's way of retesting one corner of the library (a show, a shelf) without paying
     * for a full re-scrape of everything else.
     */
    suspend fun forget(items: List<MediaItem>) {
        items.forEach { repo.upsert(clearedUnmatched(it, it.fileName.substringBeforeLast('.')).copy(ratingsVersion = 0)) }
        repo.flush()
    }

    /**
     * Download each credit's headshot into the artwork cache and pair it with the local path.
     * Keyed by a hash of the image URL (not the item) so the same actor's photo is fetched once
     * and shared across every file/episode/title that credits them.
     */
    /**
     * The Latin-script names a card written in Chinese or Japanese has nowhere else.
     *
     * See [MediaItem.aliases]: measured on a real shelf, 30 of 322 anime works and 23 of 245 films
     * carried no Latin letters in any field, so nobody could find them by the name they are known
     * by abroad. Both databases hand these over; the scraper used to read them for matching and
     * then drop them.
     */
    /**
     * The names an AniList entry calls itself besides its native one — what bgm.tv may have filed it
     * under. Not its synonyms: those are other people's names for it, some of them for its relatives.
     */
    private fun ownNames(r: MetaResult): List<String> =
        listOfNotNull(r.title, r.romajiTitle, r.englishTitle).filter { it.isNotBlank() }.distinct()

    /**
     * [Matcher.bestByAlternative] over one search's hits: the few it scores best are asked for their
     * other names, one request each, and only when no hit answered by its own.
     */
    /**
     * TMDB's entry for [asked], as the anime fallback takes one — its details, and only an animated one: the
     * matcher's answer (or season one's), else a hit of the same original title, else each hit called exactly
     * that by another of its names, in turn. 「秒速5厘米」 is the 2025 live-action film's name, and taking the best
     * answer alone, refused as live-action, left the 2007 anime unmatched.
     */
    private suspend fun tmdbAnimeEntry(asked: ParsedName, item: MediaItem): MetaResult? {
        val candidates = retry(MAX_RETRIES) { client.search(asked) } ?: return null
        val first = Matcher.best(asked, candidates)
            ?: (if (asked.isEpisode) null else (retry(MAX_RETRIES) { client.search(asked.copy(season = 1)) }?.let { Matcher.best(asked, it) }))
        first?.let { c -> animatedDetails(c, item)?.let { return it } }
        // The answer is a live-action remake: the anime it remade has the same original title. 「秒速5厘米」 is the
        // 2025 film's name; the 2007 anime TMDB files as 秒速五厘米 — both 秒速5センチメートル.
        val original = first?.originalTitle?.let(StringSimilarity::normalizeTitle)?.takeIf { it.isNotBlank() }
        if (original != null) for (c in candidates) {
            if (c.providerId == first.providerId && c.kind == first.kind) continue
            if (c.originalTitle?.let(StringSimilarity::normalizeTitle) == original) animatedDetails(c, item)?.let { return it }
        }
        // A release named in another region's words: 借物少女艾莉媞 is Taiwan's name for the film TMDB
        // files as 借东西的小人阿莉埃蒂, and one of its alternative titles.
        for (c in byAlternativeTitles(asked, candidates)) {
            if (c.providerId == first?.providerId && c.kind == first.kind) continue
            animatedDetails(c, item)?.let { return it }
        }
        return null
    }

    /** [c]'s details when it is an animation — or the folder says the anime it holds is live-action. */
    private suspend fun animatedDetails(c: MetaResult, item: MediaItem): MetaResult? {
        val details = retry(MAX_RETRIES) { client.details(c.providerId, c.kind) } ?: return null
        val isAnimation = isAnimation(details)
        // …unless the person filed it as live-action. `Anime/Patlabor 機動警察 実写映画/` holds
        // `The.Next.Generation.Patlabor.E08.2014`, which TMDB answers at once; the guard that stops
        // an anime grabbing its live-action lookalike refused the one answer there was, and 14 files
        // sat unidentified under the folder's own name. The folder said 実写; it meant it.
        return details.takeIf { isAnimation || FilenameParser.saysLiveAction(item.path) }
    }

    /** The first few of [queries] in Chinese or Japanese script that [title] is not — what TMDB is asked next. */
    private fun cjkNames(queries: List<String>, title: String): List<String> =
        queries.filter { q -> CJK_SCRIPT.containsMatchIn(q) && !q.equals(title, ignoreCase = true) }.distinct().take(3)

    private suspend fun byAlternativeTitle(q: ParsedName, hits: List<MetaResult>): MetaResult? =
        byAlternativeTitles(q, hits).firstOrNull()

    /** Every hit called exactly [q] by another of its names, in the search's order ([Matcher.allByAlternative]). */
    private suspend fun byAlternativeTitles(q: ParsedName, hits: List<MetaResult>): List<MetaResult> {
        if (hits.isEmpty()) return emptyList()
        val alts = Matcher.alternativeCandidates(q, hits).associate { h ->
            h.providerId to (retry(MAX_RETRIES) { client.alternativeTitles(h.providerId, h.kind) } ?: emptyList())
        }
        return Matcher.allByAlternative(q, hits, alts)
    }

    private fun aliasesOf(r: MetaResult, displayTitle: String): List<String> =
        listOfNotNull(r.romajiTitle, r.englishTitle)
            .filter { it.isNotBlank() && it != displayTitle && it != r.originalTitle }
            .distinct()

    // Note the absence of a download. A headshot is fetched when the cast row first draws it; see
    // Person.profileUrl for the arithmetic that made that worth doing.
    private fun persons(credits: List<Credit>): List<Person> =
        credits.map { Person(it.name, it.role, profileUrl = it.imageUrl) }

    private suspend fun <T> retry(times: Int, block: suspend () -> T): T? {
        var backoff = 400L
        repeat(times + 1) { attempt ->
            // A provider that is rate-limiting us has already been retried with backoff inside
            // Http; asking again here only adds load, and swallowing it would write the item off
            // as "nothing found" and clear an identity it already had. Let it out to scrapeAll,
            // which keeps the item as-is and marks it ERROR for the next pass.
            try {
                block()?.let { return it }
            } catch (e: com.therealityteam.videoplayer.util.TransientHttpException) {
                throw e
            } catch (_: Throwable) {
                // any other failure is worth one more go
            }
            if (attempt < times) {
                delay(backoff)
                backoff *= 2
            }
        }
        return null
    }

    private companion object {
        /** TMDB's genre id for animation, the same in every language. */
        const val TMDB_ANIMATION = 16
        /** A CJK name with its season number after it: 「零之使魔2」. */
        val TRAILING_SEASON = Regex("""(.*[\p{IsHan}\p{IsHiragana}\p{IsKatakana}])\s*([2-9])""")

        /** Han, kana: a name TMDB's Chinese and Japanese titles may answer to. */
        val CJK_SCRIPT = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}]""")

        /** A file's lookup longer than this is logged by name (AuraPerf "slow lookup"). */
        const val SLOW_ITEM_MS = 5_000L

        /**
         * Four was the right number when every AniList query was its own request and the gate was
         * the only thing keeping a burst off a throttled API. Now that queries travel eleven to a
         * request, four files in flight can never fill a batch — the window closes on whatever one
         * or two of them happened to ask. Each client still holds its own rate limiter, so this
         * governs local work and how much company a batched query can find.
         */
        const val MAX_CONCURRENCY = 12


        const val MAX_RETRIES = 2

        /** The card key prefix of an anime the AniList path identified. */
        const val ANILIST_KEY = "anilist:"

        /** Larger than any real nfo (a few KB, a long cast list tens of KB); anything bigger is not one. */
        const val MAX_NFO_BYTES = 1 shl 20

        /** A poster or fanart beside a file; a 4K fanart PNG runs to several MB. */
        const val MAX_ART_BYTES = 16 shl 20
        /**
         * Current scrape generation. Bump when scraping improves so already-matched items re-scrape
         * once and backfill (v1: anime sites for TMDB-matched anime; v3: filename/category/reconcile
         * fixes — movie/TV split follows TMDB as-is; v4: identification overhaul — signal-less and
         * group-name queries rejected, best-of-top-N instead of AniList's first hit, abbreviation
         * folders reduced, sequel seasons keep their own identity, disc-structure folders ignored.
         * A library scraped before v4 carries wrong identities (whole franchises filed under one
         * unrelated show), so it must be re-identified rather than left as it is. v5: the picker
         * ranks how specifically a hit answers the query above raw popularity, so a season query
         * stops collapsing onto its (far more popular) franchise entry; v6: title containment
         * respects word boundaries, so a query can no longer match inside a word; v7: a file whose
         * own title is Chinese keeps it instead of inheriting its folder's — a broadcaster folder
         * (Documentary/NHK) had been renaming all 226 files inside it.
         *
         * v12: a season's worth of identity work that a library already scraped cannot pick up on
         * its own — a film's own spelling beating the name on the box, a numbered sequel no longer
         * answering a query that asked for no number, a CJK folder's season read at last, a form
         * word at the end of a name asked again without it — and, with them, the Latin names every
         * card now keeps so it can be found by the name the world knows it by.
         *
         * v13: a hit is refused when the FILE's own name claims a work the entry has never heard of.
         * A tie-in that ships inside a series' box is in no database under its own name, so the
         * folder's query answered for it with the umbrella work — 33 Sword Art Online game reels,
         * Guilty Crown's 4-koma shorts, Lain's production footage and Cowboy Bebop's film were all
         * filed as episodes of the series they sat beside. Already-matched items carry those wrong
         * identities, so they have to be asked again.
         */
        // 15: a Chinese name is no longer adopted from a Bangumi subject that names the franchise
        // rather than the entry, so every anime title already stored under one is re-asked.
        // 16: and a whole day of matching fixes after it — an edition dropped from the query, a
        // tilde read as punctuation, a glued name unglued, a possessive given its apostrophe back,
        // a group pair refused as a title. Each changes what a file resolves to, and a library
        // scraped before them keeps whatever it settled on until it is asked again.
        // 17: the Bangumi subject that bears one of the entry's own names letter for letter is taken
        // before the first one that merely contains its Japanese title — Advent Children had been
        // named after the short film on the same disc, After Story after the series it continues.
        // And on TMDB, a sequel's number the file never asked for loses to the film it did ask for:
        // a remaster of Patlabor's first film had been filed as Patlabor 2. And a music shelf takes
        // only music: 277 lessons and clips there wore the posters of dramas and comedies. And on
        // AniList, the form a file names ([TV], [OVA]) counts when its folder names none — Munto's
        // nine TV episodes sat on the one-episode 2003 OVA; and a film's own Roman numeral numbers it
        // within a box of films — Zeta Gundam's II and III had been filed as the first — and the
        // name up to that numeral is asked once the subtitle has come back empty (∀ Gundam II).
        // 18: accents are folded off Latin letters when names are compared — `Shogun.S01…` had lost
        // FX's Shōgun (2024) to a 1990 series, the one identity of 1,056 re-asked that moved.
        // 19: a Chinese reader's entry that TMDB's Chinese translation names only in its original
        // language takes its region's alternative title — 安多 for Andor, 基地 for Foundation (ChineseTitle).
        // 20: a title of digits stays (1917, not 一九一七); a documentary shelf never takes an animation or a
        // talk show (明治, 华尔街); a music shelf never asks AniList or a category folder's name (Guitar,
        // ACG) and keeps only what a clip's own name names (not Jamming on an Old Saya for `jamming`);
        // and a file this pass found nothing for keeps nothing an earlier answer left on it.
        const val RATINGS_VERSION = 20
        /** Origins whose animation we also look up on the anime sites (anime / donghua / 애니). */
        val ANIME_LANGS = setOf("ja", "zh", "ko")
        // Extras/container folder vocabulary lives with the reconciliation rules that use it.
        val CONTAINER_DIRS get() = FolderReconcile.CONTAINER_DIRS
        val EXTRA_DIRS get() = FolderReconcile.EXTRA_DIRS
        val EXTRA_FOLDER_RE get() = FolderReconcile.EXTRA_FOLDER_RE
    }
}
