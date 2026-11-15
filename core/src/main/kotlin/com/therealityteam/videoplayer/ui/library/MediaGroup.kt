package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.scrape.ContentClassifier
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.StringSimilarity
import com.therealityteam.videoplayer.util.Log

/**
 * One poster-wall card representing a whole title rather than a single file: a movie (possibly
 * split into parts/extras), a series (many episodes across seasons), an OVA collection, or a
 * 剧场版. Grouping is by the scraped identity (TMDB id) so a show collapses to one card no matter
 * how its seasons are laid out on disk; OVA and 剧场版 are split into their own cards; OAD/特典/
 * NCOP/PV/CM… stay attached to the show (shown in its 特典 section).
 */
data class MediaGroup(
    val key: String,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    /** Wide TMDB backdrop (local cached path), kept for possible future use. */
    val backdropPath: String?,
    val dominantColor: Int?,
    /** Runtime in minutes (movie length or a show's per-episode length). */
    val runtime: Int?,
    /** Content rating / age classification (e.g. PG-13, TV-MA, R18+). */
    val certification: String?,
    /** Per-site scores (TMDB / IMDb / 烂番茄 / Metacritic / Bangumi / MAL / AniList…). */
    val ratings: List<com.therealityteam.videoplayer.data.model.RatingScore>,
    val isSeries: Boolean,
    /** A show card that should use the season / 特典 selector in its detail view. */
    val seasonal: Boolean,
    /** 电影/电视剧/动画/综艺/纪录/更多 — for the left-sidebar category filters. */
    val category: LibCategory,
    /** Count of feature/episode items (extras excluded) — the "N 集" / "N 个" badge. */
    val count: Int,
    val items: List<MediaItem>,
    /** What the card can be found by — titles, folder, cast, lowercased (see [SearchKeys]); the
     *  romanised forms of the CJK titles are added by [LibraryGrouping.romanized], after first paint. */
    val searchKeys: List<String> = emptyList(),
    /** The title as it sorts A→Z: pinyin / romaji for a CJK title once romanised. */
    val titleKey: String = "",
    /** The card's titles in every language — what [LibraryGrouping.romanized] romanises. */
    val searchTitles: List<String> = emptyList(),
    /** Its people among [searchKeys] — the cast and crew it can be found by (LibraryGrouping.foundOnlyByPeople). */
    val peopleKeys: List<String> = emptyList(),
) {
    val representative: MediaItem get() = items.firstOrNull { it.contentTag != ContentKind.EXTRA } ?: items.first()
    /**
     * More than one thing to watch — episodes, parts — as the card counts them ([shownCount]). A film
     * kept in 720p and in 4K is one film (DetailPlay.alternates), not two things to watch.
     */
    val isCollection: Boolean get() = shownCount > 1

    /**
     * Whether its files are counted as a show's episodes (N 集): a series on a shelf of works. A folder of
     * one's own recordings, of music clips or of a game's footage numbers its files too, and 「20250910苹果发布会
     * · 4 集」 called them episodes of a show; they are files (N 个文件).
     */
    val countsEpisodes: Boolean get() = isSeries && category !in NOT_EPISODES

    /**
     * How much of this card has been watched, 0…1 — the share of its main files played to the end:
     * its episodes or its feature, as the detail page counts them (DetailPlay.mains), not its
     * extras nor a second copy of an episode. Counting every file, a show with a disc of NCOPs and
     * PVs read 17% seen when all 23 episodes were, never earned 已看, and 全部标为已看 — which marks
     * the main files — left its card looking unfinished. A snapshot card has no files, so it reads
     * 0 and the wall shows nothing until the live library arrives.
     */
    val watchedFraction: Float
        get() {
            if (items.isEmpty()) return 0f
            // Most cards nobody has begun: answered without reading a single file name.
            if (items.none { it.progress.started || it.progress.watched }) return 0f
            val counted = countedItems
            // A part-watched episode counts for the part of it that was watched, so a series you
            // are in the middle of reads as begun rather than untouched.
            val done = counted.sumOf { if (it.progress.watched) 1.0 else it.progress.fraction.toDouble() }
            return if (done <= 0.0) 0f else (done / counted.size).toFloat().coerceAtMost(1f)
        }

    /**
     * The files that are this card's episodes or feature (DetailPlay.mains) — what [watchedFraction]
     * and 观影统计 count. Read once per card, on first asking: ask only for a card somebody began.
     */
    internal val countedItems: List<MediaItem> by lazy {
        if (items.size < 2) items else DetailPlay.mains(items, numberedRun = numberedRun, seasonal = seasonal)
    }

    /**
     * Whether this series' files are numbered by their names alone — `01.喉舌与职责`, `Show - 01`,
     * `1 of 4` — and are read that way (EpisodeNaming.parse): a folder of files no database named, or
     * a show a database named whose files the scan read no numbers in (EpisodeNaming.numberedByNames).
     */
    val numberedRun: Boolean by lazy {
        isSeries && (key.startsWith("dir:") || EpisodeNaming.numberedByNames(items))
    }

    /**
     * How many episodes (or files) the card says it holds — its badge, its page's 「N 集」, the wall's
     * snapshot. Mostly [count]. A show some of whose files carry no number, or two of which claim one,
     * is counted as its page lists it (DetailPlay.mains): 进击的巨人 read 62 集 for its 59 — three of
     * its files are music videos named for their songs — and a show held in two releases counted
     * both. Read once per card, on first asking (the view model asks for the wall it shows, off-main).
     */
    val shownCount: Int by lazy {
        if (items.size < 2) return@lazy count
        // A film's card counts what its files' names say is the film — its parts, its versions — and
        // not its sample or trailers: Spider-Verse and its "(Sample)" read as 「2 个文件」, a collection.
        if (!seasonal) return@lazy DetailPlay.mains(items).size
        // Episodes are what a show's badge counts; a folder of files says how many files it holds.
        if (!isSeries) return@lazy count
        val tagged = items.filterNot { it.contentTag == ContentKind.EXTRA }.ifEmpty { items }
        val numbers = tagged.mapNotNull { m -> m.episode?.let { (m.season ?: 1) to it } }
        if (numbers.size == tagged.size && numbers.toSet().size == numbers.size) return@lazy count
        // As DetailPlay.mains counts a show — its page's list without the recaps filed as extras — and
        // a page with no episode list (OVAs, films under one name) keeps the count it had.
        val listed = DetailPlay.listed(items, numberedRun)
        // A card of OVAs alone counts them, as its page lists them under OVA, and not the menus and
        // music clips filed with them: Patlabor's 1988 OVAs read 28 集 for 7.
        if (listed.isEmpty()) {
            val ovas = items.count { EpisodeNaming.parse(it, numberedRun).cat == EpisodeNaming.Cat.OVA }
            return@lazy if (ovas > 0) ovas else count
        }
        listed.count { it.contentTag != ContentKind.EXTRA }.takeIf { it > 0 } ?: listed.size
    }
}

/** Collapses a flat file list into title groups, and filters/sorts groups for the tabs + search. */
object LibraryGrouping {

    /** How much of an unmatched file's title has to agree for it to share a folder card. */
    private const val TITLE_SHARD = 6

    /** How far to climb looking for the release folder that anchors an unidentified file. */
    private const val ANCHOR_CLIMB = 5

    /** A subtree must be a real release (this many files of ONE work) before it may adopt strays. */
    private const val SUBTREE_MIN_FILES = 8

    /** Separates the folder from the title shard in a `dir:` key; stripped before display. */
    private const val SHARD_MARK = "|t="

    /** A disc's own directory structure — nothing inside these is named by a person. */
    private val DISC_DIRS = setOf("bdmv", "stream", "video_ts", "bdrom", "bd_rom", "playlist")

    /**
     * A file called `00001.m2ts` under a disc's BDMV/STREAM has no title of its own — whatever it
     * matched was read off the folders above it, so it belongs to whichever release those name,
     * never to an entry of its own. SAO II ships four such discs with its CD singles, and all four
     * matched season ONE together, which is exactly what the lone-fuzzy-hit test cannot catch.
     */
    private fun discStructure(item: MediaItem): Boolean =
        FilenameParser.isJunkTitle(FilenameParser.parse(item.fileName).title) &&
            item.path.split('/').any { it.trim().lowercase() in DISC_DIRS }

    /** Container folders that don't name the title — climb past them when grouping by folder. */
    private val JUNK_FOLDERS = setOf(
        "stream", "bdmv", "video_ts", "subs", "subtitles",
        "sample", "samples", "trailer", "trailers", "featurette", "featurettes",
    )

    fun group(items: List<MediaItem>): List<MediaGroup> {
        // Where the wall's cold start goes. Grouping is the largest single cost on a headset and it
        // does not get cheaper the second time — the caches that make parsing and classifying cheap
        // do not cover the work here — so the breakdown is worth carrying rather than re-deriving
        // by hand each time the question comes up.
        // nanoTime, not SystemClock: the same instrumentation then runs in a JVM bench, where
        // SystemClock is a stub that returns 0 and every phase reads as free.
        var tPrev = System.nanoTime() / 1_000_000
        val t0 = tPrev
        val phases = StringBuilder()
        fun mark(name: String) {
            val now = System.nanoTime() / 1_000_000
            phases.append(name).append('=').append(now - tPrev).append("ms ")
            tPrev = now
        }
        // Identify "junk-dump" franchise folders — a game screen-recording (Remember11) etc. where
        // almost every file is unidentifiable: unmatched, OR a junk-named file ("08","Final") whose
        // match is a lone fuzzy hit (its collectionKey is shared by no sibling). Inside such a folder
        // even those stray matches fold into the single unidentified card instead of standing alone.
        val keyCountByRoot = HashMap<String, HashMap<String, Int>>()
        items.forEach { i ->
            val root = FilenameParser.animeShowRoot(i.path) ?: return@forEach
            i.collectionKey?.let { keyCountByRoot.getOrPut(root) { HashMap() }.merge(it, 1, Int::plus) }
        }
        fun softUnidentified(i: MediaItem): Boolean {
            if (i.scrapeStatus != ScrapeStatus.MATCHED) return true
            val junkName = FilenameParser.isJunkTitle(FilenameParser.parse(i.fileName).title)
            val root = FilenameParser.animeShowRoot(i.path) ?: return false
            val singleton = (i.collectionKey?.let { keyCountByRoot[root]?.get(it) } ?: 0) <= 1
            return singleton && junkName
        }
        mark("roots")
        // A folder whose main feature IS identified anchors everything else in it: a release folder
        // holds the film plus a swarm of differently-named featurettes ("A Roll of Film", "Art
        // Department", "Dubai Car Crash") that no name pattern marks as extras. Without this they
        // each became their own card — 20 files, 16 cards for one film.
        val anchorByFolder = HashMap<String, String>()
        items.groupBy { it.path.substringBeforeLast('/') }.forEach { (folder, group) ->
            // A SHELF never anchors. `Documentary/PBS` holds 29 unrelated programmes, and since
            // their file names carry the broadcaster too — `[PBS.维多利亚女王的帝国].E01` — the
            // release-folder test below reads the shelf as their release and hands every one of
            // them the identity of whichever programme happened to match.
            if (FilenameParser.isShelfFolder(folder.substringAfterLast('/'))) return@forEach
            // the SAME identity keyOf uses: anime carries a collectionKey, a TMDB-matched film
            // only a tmdbId — looking at collectionKey alone missed every film on the Movies shelf.
            val matched = group.filter { it.scrapeStatus == ScrapeStatus.MATCHED && identityKeyOf(it) != null }
            if (matched.isEmpty()) return@forEach
            val counts = matched.groupingBy { identityKeyOf(it)!! }.eachCount()
            val (dominant, n) = counts.maxByOrNull { it.value }!!
            if (n * 2 <= matched.size) return@forEach // a box of several works, not one release
            // …and only when the FOLDER is that work's release folder. A folder whose own name is
            // just a short label (`Documentary/NHK`) is a shelf: the identified series inside it
            // must not swallow the unrelated one-off programmes filed beside it.
            // ANY file of the dominant work may be the one the folder is named after — the first
            // one often isn't (a release folder's matched files include featurettes like
            // "Composer-Grym.mkv", whose name says nothing about the film).
            val forms = folderForms(folder.substringAfterLast('/'))
            val named = forms.isNotEmpty() && matched.any {
                identityKeyOf(it) == dominant && namesTheWork(forms, it.fileName, workNames(it))
            }
            if (named) anchorByFolder[folder] = dominant
        }

        mark("anchors")
        // Subtree tallies: how many files live under each ancestor folder, and which identities
        // they carry. The flat anchor above sees one folder at a time, and a box's dominant work
        // regularly lives one level DOWN from the stray it should adopt (`翼神传说 BD-BOX/` holds
        // the movie file beside a `Rahxephon/` subfolder with the 93 identified episodes) — or
        // one level UP (a taiga drama's dated special-feature subfolders under the release).
        val subtreeTotal = HashMap<String, Int>()
        val subtreeCounts = HashMap<String, HashMap<String, Int>>()
        val subtreeSample = HashMap<String, MediaItem>()
        items.forEach { it ->
            var dir = it.path.replace('\\', '/').substringBeforeLast('/', "")
            var hops = 0
            val key = if (it.scrapeStatus == ScrapeStatus.MATCHED) identityKeyOf(it) else null
            while (hops < ANCHOR_CLIMB && dir.contains('/')) {
                subtreeTotal.merge(dir, 1, Int::plus)
                if (key != null) {
                    subtreeCounts.getOrPut(dir) { HashMap() }.merge(key, 1, Int::plus)
                    subtreeSample.putIfAbsent("$dir|$key", it)
                }
                if (dir.substringAfterLast('/').trim().lowercase() !in DISC_DIRS) hops++
                dir = dir.substringBeforeLast('/')
            }
        }

        /**
         * The release folder's verdict for an unidentified descendant: the subtree's dominant
         * identity, but only where the subtree is essentially ONE work — a shelf never anchors, a
         * mixed box (宮崎駿全集) has no 85% majority, and the folder has to NAME the dominant work
         * the way [isReleaseFolderOf] demands, so a shelf-like collection folder cannot swallow
         * strays merely because one big series lives in it.
         */
        fun subtreeAnchorOf(path: String): Pair<String, MediaItem>? {
            var dir = path.replace('\\', '/').substringBeforeLast('/', "")
            var hops = 0
            while (hops < ANCHOR_CLIMB && dir.contains('/')) {
                val name = dir.substringAfterLast('/')
                if (!FilenameParser.isShelfFolder(name)) {
                    val counts = subtreeCounts[dir]
                    val total = subtreeTotal[dir] ?: 0
                    val top = counts?.maxByOrNull { it.value }
                    if (top != null && top.value >= SUBTREE_MIN_FILES) {
                        val matched = counts.values.sum()
                        val sample = subtreeSample["$dir|" + top.key]
                        if (top.value * 100 >= matched * 85 && matched * 100 >= total * 60 &&
                            sample != null && isReleaseFolderOf(name, sample.fileName, workNames(sample))
                        ) {
                            return top.key to sample
                        }
                    }
                }
                if (dir.substringAfterLast('/').trim().lowercase() !in DISC_DIRS) hops++
                dir = dir.substringBeforeLast('/')
            }
            return null
        }

        mark("subtree")
        val seriesByFolder = seriesFolders(items.map { it.path })

        val junkRoots = items.groupBy { FilenameParser.animeShowRoot(it.path) }
            .filterKeys { it != null }
            .filterValues { g -> g.count(::softUnidentified).let { n -> n >= 3 && n >= g.size * 0.6 } }
            .keys.filterNotNull().toHashSet()

        // Ten files named 世界市场.财富攻防战.第N回 sit loose on a shelf; nine could not be identified
        // and the tenth matched a Chinese nature documentary, on the strength of its episode
        // subtitle 「made in china」. A lone hit among siblings that agree on their title is an
        // outlier, not a discovery — the same test the junk-dump rule makes, applied where a SHELF
        // is what holds the files together rather than a folder.
        mark("series")
        val strayHits = HashSet<String>()
        items.filter { FilenameParser.isShelfFolder(seriesFolder(it.path).substringAfterLast('/')) }
            .groupBy { seriesFolder(it.path) + titleShard(it) }
            .forEach { (_, g) ->
                if (g.size < 3) return@forEach
                val unnamed = g.count { it.scrapeStatus != ScrapeStatus.MATCHED || identityKeyOf(it) == null }
                if (unnamed * 2 <= g.size) return@forEach // the group is identified; trust it
                g.groupBy { identityKeyOf(it) }.forEach { (id, files) ->
                    if (id != null && files.size == 1) strayHits += files[0].path
                }
            }

        // A shelf is where unrelated programmes sit side by side, so one identity spread across
        // two or more programme names there is not one work: 29 files on `Documentary/PBS` all
        // matched The Medici, and Rick Steves' Europe, Napoleon and 维多利亚女王的帝国 rode onto its
        // card. Sound for the same reason the test above is: a shard now names the programme.
        items.filter { FilenameParser.isShelfFolder(it.path.substringBeforeLast('/').substringAfterLast('/')) }
            .groupBy { identityKeyOf(it) }
            .forEach { (id, g) ->
                if (id == null) return@forEach
                if (g.map { titleShard(it) }.distinct().size >= 2) g.forEach { strayHits += it.path }
            }

        // …and the mirror of it: five files named 明治.序章 / 明治.第1集 … 第4集 sit together, four
        // identified as one programme and the fifth not. The fifth is that programme too.
        mark("stray")
        val adopted = HashMap<String, String>()
        items.filter { FilenameParser.isShelfFolder(seriesFolder(it.path).substringAfterLast('/')) }
            .groupBy { seriesFolder(it.path) + titleShard(it) }
            .forEach { (_, g) ->
                if (g.size < 3) return@forEach
                val named = g.filter { it.scrapeStatus == ScrapeStatus.MATCHED }.mapNotNull { identityKeyOf(it) }
                val (dominant, n) = named.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return@forEach
                if (n * 2 <= g.size) return@forEach // no one identity speaks for this group
                g.filter { it.scrapeStatus != ScrapeStatus.MATCHED || identityKeyOf(it) == null }
                    .forEach { adopted[it.path] = dominant }
            }

        mark("adopt")
        val mainMovies = mainMovies(items)

        mark("movies")
        val byKey = oneFilmOnTwoDatabases(
            items.groupBy { keyOf(it, junkRoots, anchorByFolder, seriesByFolder, mainMovies, strayHits, adopted, ::softUnidentified, ::subtreeAnchorOf) },
        )
        mark("key")
        return byKey.map { (key, groupItems) ->
            // Keyed once per item, not once per comparison: sortKey re-reads the file name, and a
            // comparator that calls it is asked n log n times for n answers.
            val sorted = groupItems
                .map { Triple(it, EpisodeNaming.sortKey(it), it.fileName.lowercase()) }
                .sortedWith(compareBy({ it.first.season ?: 0 }, { it.second }, { it.third }))
                .map { it.first }
            // The feature(s) define the card; 特典/花絮 (extras) must not — else a movie's folded extras
            // make it look like a multi-episode series and land in 电视剧 instead of 电影.
            val mains = sorted.filterNot { it.contentTag == ContentKind.EXTRA }.ifEmpty { sorted }
            // …and the card is named and postered by a file that IS this work. A bonus disc carries
            // trailers for OTHER shows — 「Fate/strange Fake TVCM」 rides along on a G-Reco disc —
            // and one of those, tagged MAIN by the scraper, was titling the film's card and lending
            // it its poster. Whatever a stray file scraped to, it does not speak for this card.
            // Preferring a feature over an extra comes SECOND: 劇場版 GのレコンギスタⅤ was itself
            // tagged an extra by the scraper, which left the disc's stray TVCM as the only "main"
            // in the card and let it do the naming. Carrying this card's identity comes first.
            val ours = sorted.filter { identityKeyOf(it) == key.removeSuffix("|movie") }
            val own = ours.filterNot { underExtrasFolder(it.path) || it.contentTag == ContentKind.EXTRA }
                // A file FILED under 特典DISC/ never names the card; a feature the scraper merely
                // mistagged still may — 劇場版 GのレコンギスタⅤ was itself tagged an extra, and the
                // 特典 disc beside it then named the film 「勇者王GaoGaiGar」.
                .ifEmpty { ours.filterNot { underExtrasFolder(it.path) } }
                .ifEmpty { ours }
                .ifEmpty { mains }
            val rep = own.firstOrNull { it.posterPath != null } ?: own.first()
            // A folder-grouped (dir:) card holds only unidentifiable files — render it as a clean
            // placeholder (no poster / color / ratings), never with stale artwork from a rejected match.
            val unidentified = key.removeSuffix("|movie").startsWith("dir:")
            // …except a poster made from one of its own files (a frame, posterGenerated): that is no
            // one's rejected match, it is what the video looks like.
            val frame = if (unidentified) (own + sorted).firstOrNull { it.posterGenerated && it.posterPath != null } else null
            // No database numbered these files, so their NAMES say whether this is a run: 激流中国's
            // thirteen `01.喉舌与职责` parts and Rick Steves' `01of11` read as "13 个" films otherwise.
            // …and Japanese broadcasts name their instalments instead of numbering them: 映像の世紀
            // バタフライエフェクト「銀座 百年の記憶」, 「ナチを支えた女性たち」 — a 「title」 after the
            // programme's name is the episode.
            val numbered = unidentified && mains.size > 1 &&
                mains.count { EPISODE_MARK.containsMatchIn(it.fileName) || QUOTED_EPISODE.containsMatchIn(it.fileName) } >= 2
            val episodey = mains.any { it.episode != null || it.season != null } ||
                rep.kind == MediaKind.EPISODE || rep.kind == MediaKind.SHOW || numbered
            val mainCard = !key.endsWith("|movie")
            val title = titleOf(rep, key, groupItems)
            val searchTitles = searchTitles(title, mains)
            MediaGroup(
                key = key,
                title = title,
                year = if (unidentified) null else rep.year,
                posterPath = if (unidentified) frame?.posterPath else rep.posterPath ?: sorted.firstOrNull { it.posterPath != null }?.posterPath,
                backdropPath = if (unidentified) null else sorted.firstOrNull { it.backdropPath != null }?.backdropPath,
                dominantColor = if (unidentified) frame?.dominantColor else rep.dominantColor,
                runtime = mains.firstOrNull { it.runtime != null }?.runtime,
                certification = mains.firstOrNull { it.certification != null }?.certification,
                ratings = if (unidentified) emptyList() else mains.firstOrNull { it.ratings.isNotEmpty() }?.ratings ?: emptyList(),
                // One file that is a NUMBERED episode (Black.Mirror.S01E03 alone) is still a series'
                // card: its badge says so and its detail page lists it as 第 3 集, not 正片. The kind
                // alone is not enough — the scraper files anime films as episodes too often.
                isSeries = episodey && (mains.size > 1 || (rep.kind == MediaKind.EPISODE && (rep.episode != null || rep.season != null))),
                seasonal = mainCard && episodey,
                category = MediaCategory.of(rep),
                count = mains.size - episodeCopies(mains),
                items = sorted,
                searchKeys = SearchKeys.plain(searchTitles + searchOthers(rep, sorted)),
                peopleKeys = SearchKeys.plain(peopleOf(rep)),
                titleKey = title.lowercase().trim(),
                searchTitles = searchTitles,
            )
        }.sortedByDescending { g -> g.items.maxOf { it.addedAt } }
            .let(::tellApartSameNames)
            .also {
                mark("cards")
                if (items.isNotEmpty()) {
                    Log.i(
                        "AuraPerf",
                        "group ${items.size}→${it.size} ${System.nanoTime() / 1_000_000 - t0}ms: $phases",
                    )
                }
            }
    }

    /**
     * The same cards with the romanised forms of their CJK titles added to their keys, and their
     * A→Z key in pinyin / romaji. Kept apart from [group] because the transliteration is the slow
     * part and no card needs it to be painted: the view model runs it after the wall is up.
     */
    /**
     * [groups] sorted A→Z as they will be once romanised: each card whose name has been romanised
     * before ([known]: name → A→Z key, from the snapshot and every pass since) takes that key now.
     * The key is a function of the name alone, so it is the one the next pass arrives at — and the
     * wall, in title order, no longer reshuffles twice on a cold start: CJK titles out to the end
     * when the plain cards land, back to their pinyin places a second later.
     */
    fun withKnownTitleKeys(groups: List<MediaGroup>, known: Map<String, String>): List<MediaGroup> =
        if (known.isEmpty()) groups else groups.map { g -> known[g.title]?.let { if (it == g.titleKey) g else g.copy(titleKey = it) } ?: g }

    fun romanized(groups: List<MediaGroup>, romanize: (String) -> String?): List<MediaGroup> =
        groups.map { g ->
            g.copy(
                searchKeys = SearchKeys.withRomanized(g.searchKeys, g.searchTitles, romanize),
                titleKey = SearchKeys.titleKey(g.title, romanize),
            )
        }

    /** A card's titles in every language: what it is called, called originally, and called as a show. */
    private fun searchTitles(title: String, mains: List<MediaItem>): List<String> = buildList {
        add(title)
        mains.take(6).forEach { m ->
            m.originalTitle?.let(::add)
            m.showTitle?.let(::add)
            addAll(m.aliases)
            add(m.title)
        }
    }.filter { it.isNotBlank() }.distinct()

    /** The rest a card answers to: the folder it was filed under and its people. */
    /**
     * How many of [mains] are second copies of an episode another file already is — a show held as a
     * BDRip and a TVRip of every episode counted 46 集 for 23. The show page files those copies under
     * 特典 (DetailSections), and the count leaves out the same files. Only read for when two files
     * claim one episode number, so the library's thousands of plain cards never pay for the reading.
     */
    private fun episodeCopies(mains: List<MediaItem>): Int {
        if (mains.size < 2) return 0
        val numbered = mains.mapNotNull { m -> m.episode?.let { (m.season ?: 1) to it } }
        if (numbered.size == numbered.toSet().size) return 0
        return DetailSections.copies(mains).size
    }

    private fun searchOthers(rep: MediaItem, all: List<MediaItem>): List<String> = buildList {
        // The release folder's own name — what the user typed when they filed it.
        all.firstOrNull()?.path?.replace('\\', '/')?.trimEnd('/')?.substringBeforeLast('/', "")?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && !FilenameParser.isShelfFolder(it) }
            ?.let { add(FilenameParser.parse(it).title) }
        addAll(peopleOf(rep))
    }.filter { it.isNotBlank() }.distinct()

    /** The people a card can be found by: the first eight of its cast and four of its crew. */
    private fun peopleOf(rep: MediaItem): List<String> =
        rep.cast.take(8).map { it.name } + rep.crew.take(4).map { it.name }

    fun filter(
        groups: List<MediaGroup>,
        section: LibSection,
        query: String,
        sort: LibSort = LibSort.RECENT,
        showAdult: Boolean = true,
    ): List<MediaGroup> {
        // Hiding the shelf was never hiding the content: the tab came off the sidebar and every
        // card on it stayed in 全部, in search, in 收藏 and in 最近 — which is the one place the
        // setting had to hold. It is dropped from the pool rather than from a branch so that a
        // section added later cannot forget to.
        val pool = if (showAdult || section == LibSection.ADULT) groups
        else groups.filterNot { it.category == LibCategory.ADULT }
        var result = when (section) {
            LibSection.ALL -> pool
            LibSection.FAVORITES -> pool.filter { g -> g.items.any { it.favorite } }
            LibSection.HISTORY -> pool.filter { g -> g.items.any { it.progress.started } }
            else -> pool.filter { it.category == section.category }
        }
        val q = SearchKeys.Query(query)
        if (q.text.isNotEmpty()) {
            result = result.filter { g ->
                q.matches(g.searchKeys) || g.title.contains(q.text, ignoreCase = true) ||
                    g.items.any { it.fileName.contains(q.text, ignoreCase = true) }
            }
        }
        // 历史 is ordered by when it was watched unless the user asked for something else.
        val effective = if (section == LibSection.HISTORY && sort == LibSort.RECENT) LibSort.WATCHED else sort
        val sorted = LibrarySort.apply(result, effective)
        if (q.text.isEmpty()) return sorted
        // A search answers first with the cards whose own name begins with what was typed, in the order asked
        // for, then the rest: 「shingeki」 begins 进击的巨人's (Shingeki no Kyojin), and is only somewhere inside
        // 攻壳机动队 新剧场版's (…Shin Gekijouban), which had come first. Its people's names do not count here.
        val (begun, rest) = sorted.partition { g ->
            g.title.startsWith(q.text, ignoreCase = true) || q.begins(g.searchKeys, g.peopleKeys.toHashSet())
        }
        return begun + rest
    }

    /**
     * Whether [g] answers [query] only through the people in it — 火星救援 to 「高达」, for its writer
     * 德鲁·高达 — and through no name of its own, none of its files' names and not its folder's. A
     * search's 作品 come first and these after them, apart: 人物 already shows the face, and a work
     * whose name holds the query is what was asked for.
     */
    fun foundOnlyByPeople(g: MediaGroup, query: String): Boolean = foundOnlyByPeople(g, SearchKeys.Query(query))

    /** [foundOnlyByPeople] for a query made ready once for all the cards it is asked of. */
    fun foundOnlyByPeople(g: MediaGroup, query: SearchKeys.Query): Boolean {
        // In simplified Han on both sides, as the search itself compares (SearchKeys.matches).
        val q = query.folded
        if (q.isEmpty() || g.items.isEmpty()) return false
        if (g.title.contains(query.text, ignoreCase = true) || HanFold.fold(SearchKeys.normalize(g.title)).contains(q) ||
            g.items.any { it.fileName.contains(query.text, ignoreCase = true) }
        ) {
            return false
        }
        // The people the card was made searchable by, not its first file's: a film kept as a TMDB copy
        // and an AniList one credits its crew twice over, and only one of them went into its keys.
        val people = g.peopleKeys.mapTo(HashSet(), HanFold::fold)
        if (people.none { it.contains(q) }) return false
        // A key a name of the work shares with a person (a documentary named for its subject) is
        // still the work's own name.
        val titles = SearchKeys.plain(g.searchTitles).mapTo(HashSet(), HanFold::fold)
        return g.searchKeys.none { raw -> HanFold.fold(raw).let { k -> (k !in people || k in titles) && k.contains(q) } }
    }

    /**
     * Same scraped identity groups together; OVA and a show-scraped 剧场版 are split off with a
     * suffix so they form their own cards. Unmatched files group by their (de-junked, de-seasoned)
     * series folder so a show's season subfolders still collapse to one card.
     */
    private fun keyOf(
        item: MediaItem,
        junkRoots: Set<String>,
        anchorByFolder: Map<String, String>,
        seriesByFolder: Map<String, String>,
        mainMovies: Map<String, Boolean>,
        strayHits: Set<String>,
        adopted: Map<String, String>,
        softUnidentified: (MediaItem) -> Boolean,
        subtreeAnchor: (String) -> Pair<String, MediaItem>? = { null },
    ): String {
        // An UNMATCHED file's collectionKey / tmdbId is a STALE leftover from a rejected match (the
        // guard unmatched it but the old identity lingers) — never group by it, or 11 un-IDable files
        // each scatter onto a different wrong anime card. A junk-named lone fuzzy match inside a
        // junk-dump folder is likewise demoted. Everything else keeps its scraped identity so real
        // shows still collapse to one card; the rest group by their franchise folder.
        val root = FilenameParser.animeShowRoot(item.path)
        val demote = item.scrapeStatus != ScrapeStatus.MATCHED || item.path in strayHits ||
            (root != null && root in junkRoots && softUnidentified(item))
        // A file filed UNDER an extras path belongs to the work whose release folder holds it, even
        // when it scraped to a database entry of its own: `进击的巨人/Extra/Special/Chuugakkou/…`
        // matched the Junior High spin-off and left the show's card, though the filing says it is
        // that show's 特典. Only applies when the enclosing folder really is one work's release.
        // The FILING is what says "this is that work's 特典" — a folder called Extra/Special/特典.
        // A file whose NAME merely says OVA, sitting beside the main files with a database entry of
        // its own, is a work: `读或死R.O.D/R.O.D －READ OR DIE－ OVA 第01話` is the 2001 OVA
        // (anilist:208), not an extra of the 2003 TV series it shares a folder with.
        // A file called `00001.m2ts` has no title of its own, so the release it sits under overrules
        // whatever it matched — but only where that release names one. `OPPENHEIMER…/BDMV/STREAM/
        // 00294.m2ts` IS the film's only file, and its own match is all there is to go on.
        if (discStructure(item)) {
            anchorOf(item.path, anchorByFolder)?.let { return movieAware(it, item, mainMovies) }
        }
        if (ContentClassifier.classify(item.fileName, item.path) == ContentKind.EXTRA &&
            (underExtrasFolder(item.path) || identityKeyOf(item) == null)
        ) {
            anchorOf(item.path, anchorByFolder)?.let { return movieAware(it, item, mainMovies) }
        }
        if (!demote) {
            item.collectionKey?.let { return movieAware(it, item, mainMovies) } // AI/auto pass authority
            item.tmdbId?.let { return movieAware("id:$it", item, mainMovies) }
        }
        adopted[item.path]?.let { return movieAware(it, item, mainMovies) }
        // An unidentified file sitting beside — or under — an identified main feature is that
        // work's extra. A release nests them one level down (`…-Grym/…Extras-Grym/clip.mkv`), so
        // climb a few levels; a box of several works has no anchor, so nothing is inherited there.
        anchorOf(item.path, anchorByFolder)?.let { return movieAware(it, item, mainMovies) }
        // …and one whose SIBLING folders are the rest of the same series joins them, no matter that
        // each episode was downloaded into a folder of its own.
        seriesByFolder[item.path.substringBeforeLast('/', "")]?.let { return movieAware("dir:$it", item, mainMovies) }
        // …and an unidentified file anywhere under a release whose SUBTREE is one work belongs to
        // that work — the box's stray movie file, a taiga drama's dated special subfolders. Keyed
        // by a DOMINANT sample, not by the stray, or a stray whose name says Movie lands one
        // |movie suffix away from the card it was adopted into.
        subtreeAnchor(item.path)?.let { (k, sample) -> return movieAware(k, sample, mainMovies) }
        // An unidentified file belongs to its WORK folder. Only when that folder is a shelf —
        // `Documentary/NHK` holding 170 unrelated programmes, the 下载 root — does the file's own
        // title have to decide, which is what the shard is for. Keying on the shelf plus a shard
        // regardless split `新海诚全集/秒速5CM 1080P BDRIP/` into five cards, one per disc label.
        // …except inside a junk-dump root, where the subfolders (`Remember11/中文影像版/悟篇`,
        // `…/心篇`) are chapters of one unidentifiable pile and belong on its single card.
        val work = seriesFolder(item.path)
        if (root !in junkRoots && !FilenameParser.isShelfFolder(work.substringAfterLast('/'))) {
            return movieAware("dir:$work", item, mainMovies)
        }
        return movieAware("dir:${root ?: work}${titleShard(item, (root ?: work).substringAfterLast('/'))}", item, mainMovies)
    }

    /**
     * Extra key material for files grouped by FOLDER, so a folder that is a shelf rather than a
     * title doesn't collapse into one card.
     *
     * `Documentary/NHK` holds ~120 unrelated one-off programmes beside a few real series; keying
     * purely on the folder put all 226 on a single poster. Files whose names start the same way
     * (a series' episodes) still share a key, while unrelated titles get their own. A junk name
     * ("01", "Final" — a game recording) carries no title at all, so those still collapse to the
     * one placeholder card the folder deserves.
     */
    /** Is any folder on the way down an extras folder — `Extra`, `Special`, `特典`, `SPs`? */
    private fun underExtrasFolder(path: String): Boolean =
        path.replace('\\', '/').split('/').dropLast(1).any {
            it.lowercase() in JUNK_FOLDERS || EXTRAS_FOLDER.matches(it.trim())
        }

    // Matched against the whole segment, so the CJK forms need their compounds: a folder named
    // 特典映像 is not the token 特典.
    private val EXTRAS_FOLDER = Regex(
        """(?i)(sps?|specials?|extras?|bonus|menu|ncop|nced|pv|cm|scans?|cds?|""" +
            """(?:映像|本[篇編编])?特典(?:映像|cd|disc)?|花絮|おまけ|音声特典|総集編|总集编|總集編|""" +
            // A disc ships its extras under names of its own: `Special Disc`, `Eizou Tokuten`.
            """specials?[ _-]?disc|eizou[ _-]?tokuten|bonus[ _-]?disc)""",
    )

    /** The nearest enclosing folder (up to [ANCHOR_CLIMB] levels) that is one work's release. */
    private fun anchorOf(path: String, anchorByFolder: Map<String, String>): String? {
        var dir = path.substringBeforeLast('/', "")
        var climbed = 0
        while (climbed < ANCHOR_CLIMB) {
            if (dir.isBlank() || !dir.contains('/')) return null
            anchorByFolder[dir]?.let { return it }
            // A disc's own tree is one disc, not three levels of filing: counting `BD_ROM/BDMV/
            // STREAM` as three used up the climb before it ever reached the release folder.
            if (dir.substringAfterLast('/').trim().lowercase() !in DISC_DIRS) climbed++
            dir = dir.substringBeforeLast('/')
        }
        return null
    }

    /** The scraped identity a card keys off: an AniList collectionKey, else a TMDB id. */
    private fun identityKeyOf(item: MediaItem): String? = FilmIdentity.key(item)

    private val EPISODE_MARK get() = FilenameParser.EPISODE_MARK
    private val QUOTED_EPISODE get() = FilenameParser.QUOTED_EPISODE


    /**
     * `[PBS.拿破仑传奇].Empires.Napoleon.1of4` — on a SHELF, a lone CJK head bracket is the
     * programme's name; what follows it is the strand and the instalment, which is why Napoleon,
     * The Medici and 维多利亚女王的帝国 all sharded as 「empire」 and shared one card. TWO label
     * brackets are not a name (`[NHK][纪录片]`, `[道兰][NHK纪录片]`), a Latin one is a release group,
     * and a site tag is neither.
     */
    /** The bracket's name and where the file name continues after it. */
    private fun shelfBracket(item: MediaItem): Pair<String, Int>? =
        FilenameParser.shelfBracket(item.fileName, item.path.substringBeforeLast('/', "").substringAfterLast('/'))

    private fun titleShard(item: MediaItem, folder: String? = null): String {
        val shard: String = shelfBracket(item)?.let { (bracketName, end) ->
            // The bracket is the uploader's label; what follows it is the name other copies of the
            // programme share. `[PBS.里奇·史蒂夫斯游欧洲].Rick.Steves.Europe.10of11` sat beside sixteen
            // plain `Rick.Steves.Europe.NNof11` files and keyed on the label, a card of its own.
            // When the remainder names an instalment, its programme part is the key; a remainder
            // that is only the instalment (`[NHK.映像的世纪].第01集`) leaves the label as the name.
            programmeOf(item.fileName.substring(end).trimStart('.', ' ', '_', '-', '　'))
                ?: StringSimilarity.normalizeTitle(bracketName).filterNot(Char::isWhitespace).take(TITLE_SHARD)
        } ?: programmeOf(item.fileName) ?: run {
            val title = FilenameParser.parse(item.fileName).title
            if (FilenameParser.isJunkTitle(title)) return ""
            StringSimilarity.normalizeTitle(title).filterNot { it.isWhitespace() }.take(TITLE_SHARD)
        }
        // A file named for its folder (`[KFC][Remember11][EX]` in `Remember11/`) says nothing its
        // junk-named siblings (`01.avi`) don't: the shard split one unidentifiable pile in two.
        val folderNorm = folder?.let { StringSimilarity.normalizeTitle(it).filterNot(Char::isWhitespace) }
        if (shard.isBlank() || (folderNorm != null && folderNorm.startsWith(shard))) return ""
        return "$SHARD_MARK$shard"
    }

    /** The programme's name as a KEY: the parser's title, normalised, spaces dropped. */
    private fun programmeOf(name: String): String? =
        FilenameParser.programmeOf(name)?.let { StringSimilarity.normalizeTitle(it).filterNot(Char::isWhitespace) }?.ifBlank { null }
    /**
     * A 劇場版 always gets its own key namespace, so a film never lands on its series' card.
     *
     * This has to apply to the [MediaItem.collectionKey] path too: the scraper writes an AniList id
     * for every anime file, and a film sitting in its own `…剧场版…` folder is regularly matched to
     * the TV entry (Macross Frontier, ∀高达, GUNBUSTER 合体劇場版 all did) — keying straight off that
     * shared id put the film on the series card. Suffixing is safe for a standalone film as well:
     * all of its files (feature + 特典 under the same 剧场版 folder) get the same suffix and still
     * collapse to one card.
     */
    /**
     * Is [folderName] the release folder OF the work that [anchorFileName] belongs to — rather than
     * a shelf that merely contains it? The folder has to name the work about as fully as the file
     * does; a short label ("NHK") sitting above "NHK映像的世纪（01）" is a shelf, while
     * "Mission.Impossible-Ghost.Protocol.2011.Bluray…" above "Mission.Impossible-Ghost.Protocol…"
     * is a release.
     */
    /**
     * Two folder-side readings: parsed (release words stripped) AND the raw name normalized.
     * parse() drops a CJK title glued to a Latin tail — 翼神传说 BD-BOX - TV + Movie + OVA parses to
     * just the tail — and the raw form is the only one that still contains the scraped 翼神传说, so
     * a box named in one language over files named in another anchors.
     *
     * Taken once per FOLDER. Asking it inside a loop over the folder's files recomputed the same
     * two readings for every file in it, which was most of the anchoring pass on a big library.
     */
    private fun folderForms(folderName: String): List<String> = listOf(
        StringSimilarity.normalizeTitle(FilenameParser.parse(folderName).title),
        StringSimilarity.normalizeTitle(folderName),
    ).filter { it.isNotBlank() }.distinct()

    private fun isReleaseFolderOf(folderName: String, anchorFileName: String, workNames: List<String>): Boolean =
        namesTheWork(folderForms(folderName), anchorFileName, workNames)

    /**
     * What a file's work is called: the title it shows, and the names it was known by before — a
     * Chinese name put on an anime from TMDB keeps its English AniList title among the aliases, and
     * `Mobile Suit Gundam 0083 The Afterglow of ZEON + ET/` names the work by that one, not by
     * 机动战士高达0083：吉翁的残光. Its two 特典 folders had lost their way to its card.
     */
    private fun workNames(item: MediaItem): List<String> = listOf(item.title) + item.aliases

    private fun namesTheWork(folderForms: List<String>, anchorFileName: String, workNames: List<String>): Boolean {
        if (folderForms.isEmpty()) return false
        // Compare against the file's own name AND the title it scraped to: a Chinese folder over
        // romaji file names ("进击的巨人 (BDRip)" / "Shingeki no Kyojin - 01") only corresponds via
        // the scraped title, while a broadcaster label ("NHK" over "NHK映像的世纪") corresponds to
        // neither — it is far too short to be naming the work.
        return (listOf(anchorFileName.let { FilenameParser.parse(it).title }) + workNames)
            .map { StringSimilarity.normalizeTitle(it) }
            .filter { it.isNotBlank() }
            .any { a ->
                folderForms.any { f ->
                    f.length >= a.length * 0.6 &&
                        (f.contains(a) || a.contains(f) || StringSimilarity.titleSimilarity(f, a) >= 0.8)
                }
            }
    }

    private fun movieAware(base: String, item: MediaItem, mainMovies: Map<String, Boolean>): String {
        // The suffix exists to part a 劇場版 from the series it shares an identity with. A folder key
        // has no identity to part, and letting each file answer for itself split one card down the
        // middle: nine unidentifiable parts of 世界市场·财富攻防战 said no and the tenth, which had
        // matched something, said yes.
        if (base.startsWith("dir:")) return base
        // MAIN + EXTRA (incl. OVA) share the show card; only a 劇場版 gets its own namespace.
        // An unmatched extra carries no identity of its own; the card it was anchored to names one.
        val id = identityKeyOf(item) ?: base.takeUnless { it.startsWith("dir:") }
        val isMovie = id?.let { mainMovies["$it@${releaseFolder(item.path)}"] ?: mainMovies["$it@"] }
            ?: isMovieFile(item)
        return if (isMovie) "$base|movie" else base
    }

    /**
     * 劇場版-ness belongs to the WORK-as-filed, not to each file of it. A film's Menu/PV/CM/SP files
     * are not themselves films, and a release's featurettes ("A Roll of Film") carry no marker at
     * all — read one at a time they tore 「攻殻機動隊 The New Movie」, 「劇場版 魔法科高校の劣等生」 and
     * a dozen films' extras off their own cards. So the main features of one release decide for
     * everything filed under it, which makes the answer the same for every file of that release.
     *
     * The scope is the RELEASE folder, not the series: the whole point of the `|movie` suffix is
     * that 劇場版 Macross Frontier leaves the TV card it shares an AniList id with, and those live
     * in folders of their own. A work with no vote here (a bonus disc filed apart from the feature
     * it belongs to) asks what that work is everywhere else.
     */
    private fun mainMovies(items: List<MediaItem>): Map<String, Boolean> {
        val votes = HashMap<String, IntArray>() // -> [movies, total]
        items.forEach { i ->
            // The scrape-time tag counts as well as the name: `Memento.Extras1.Anatomy.of.a.Scene`
            // carries no marker a regex can see, yet the scraper knew it for an extra — and left to
            // vote in the `Memento.Extras-Grym/` folder it sits in, it out-voted the feature itself.
            // The tag also settles the reverse: a file matched to a FILM is a feature whatever its
            // name suggests, and `The.Menu.2016.1080p.BluRay` reads as a menu file to any regex.
            if (i.contentTag == ContentKind.EXTRA ||
                (ContentClassifier.classify(i.fileName, i.path) == ContentKind.EXTRA)
            ) {
                return@forEach
            }
            val id = identityKeyOf(i) ?: return@forEach
            val movie = isMovieFile(i)
            listOf("$id@${releaseFolder(i.path)}", "$id@").forEach { k -> // "id@" is the library tally
                val v = votes.getOrPut(k) { IntArray(2) }
                if (movie) v[0]++
                v[1]++
            }
        }
        return votes.mapValues { (_, v) -> v[0] * 2 > v[1] }
    }

    /**
     * The scraper's own verdict outranks the filename. Two encodes of one film get named
     * differently — 「攻殻機動隊 The New Movie」 says 新剧场版 in the Mp4Ba release and nothing at all
     * in the VCB-S one — yet both scraped to the same AniList entry, and that entry knows it is a
     * film. Reading only the name put the two encodes of one film on two cards.
     */
    private fun isMovieFile(item: MediaItem): Boolean =
        item.kind == MediaKind.MOVIE || ContentClassifier.isMovie(item.fileName, item.path)

    /**
     * One film, two databases. A film filed twice — a 2160p release in 下载 and a BDRip on the anime
     * shelf — is identified twice over: TMDB answers off the anime shelf and AniList on it. 机动战士高达
     * 闪光的哈萨维 was id:685274 and anilist:105595, 借りぐらしのアリエッティ TMDB 51739 and AniList
     * 7711, and the wall showed each of them twice — one name, one year, two cards.
     *
     * Two such cards are one when they agree on the film's own name letter for letter (the original
     * title TMDB gives and the native title AniList gives are the same Japanese) and on its year, a
     * year apart at most: a festival premiere and a release are both "the year". Films only — a
     * TMDB series is every season of a show and an AniList entry one of them, numbered apart. Only
     * across the two databases: two entries of one database are two works by its own account,
     * whatever they are called. And only an animation by TMDB's account, as everything on AniList
     * is: a live-action film sharing an anime film's name is another film. A name two films of one
     * database share, in one year, says nothing about which is meant, so it joins neither.
     *
     * The card with more files keeps its identity — the release with its 特典 — and the other's
     * files join it as another copy of the film; on a tie, TMDB's, which knows the film's cast.
     */
    private fun oneFilmOnTwoDatabases(byKey: Map<String, List<MediaItem>>): Map<String, List<MediaItem>> {
        class Film(val key: String, val tmdb: Boolean, val name: String, val year: Int, val size: Int)
        val films = byKey.mapNotNull { (key, files) ->
            if (!key.endsWith("|movie")) return@mapNotNull null
            val id = key.removeSuffix("|movie")
            val tmdb = when {
                FilmIdentity.isTmdb(id) -> true
                FilmIdentity.isAniList(id) -> false
                else -> return@mapNotNull null
            }
            val own = files.filter { identityKeyOf(it) == id }
            if (tmdb && own.none(MediaCategory::isAnimation)) return@mapNotNull null
            val name = own.firstNotNullOfOrNull(FilmIdentity::name) ?: return@mapNotNull null
            val year = own.firstNotNullOfOrNull { it.year } ?: return@mapNotNull null
            Film(key, tmdb, name, year, files.size)
        }
        if (films.none { it.tmdb } || films.all { it.tmdb }) return byKey
        val pairs = films.filter { !it.tmdb }.mapNotNull { a ->
            films.filter { it.tmdb && it.name == a.name && FilmIdentity.sameYear(it.year, a.year) }
                .singleOrNull()?.let { a to it }
        }
        // …and a film either side answers with once only: one TMDB film that two AniList entries
        // both claim is a name the two databases cut differently, not a pair.
        val once = pairs.groupingBy { it.second.key }.eachCount()
        val into = HashMap<String, String>()
        pairs.filter { once[it.second.key] == 1 }.forEach { (anilist, tmdb) ->
            val (keep, drop) = if (anilist.size > tmdb.size) anilist to tmdb else tmdb to anilist
            into[drop.key] = keep.key
        }
        if (into.isEmpty()) return byKey
        val merged = LinkedHashMap<String, MutableList<MediaItem>>()
        byKey.forEach { (key, files) -> merged.getOrPut(into[key] ?: key) { ArrayList() }.addAll(files) }
        return merged
    }

    /**
     * Two cards with one name between them.
     *
     * 逮捕令's 1994 OVA and its 1996 TV series sit side by side on a real wall under the same name
     * and the same cover, because both works ARE called 逮捕しちゃうぞ — AniList tells them apart by
     * a format field this app does not keep. 攻壳机动队 has the 1995 anime and the 2017 live-action
     * film; 简爱 has a film and a serial. The card already prints its year underneath and that was
     * not enough: what a person reads is the name.
     *
     * So a name several cards share takes their years. ONLY when the years differ — two cards with
     * one name and one year are not a pair to label, they are a scraping fault, and stamping a year
     * on both would hide it rather than fix it. Cards with no year at all (a folder of somebody's
     * own clips, named after the folder) are left alone for the same reason.
     *
     * Display only: [searchKeys] still hold the name as the databases give it, so searching for
     * 逮捕令 finds both.
     */
    private fun tellApartSameNames(groups: List<MediaGroup>): List<MediaGroup> {
        val byName = groups.groupBy { it.title.lowercase().trim() }
        if (byName.values.none { it.size > 1 }) return groups
        return groups.map { g ->
            val peers = byName[g.title.lowercase().trim()].orEmpty()
            val years = peers.mapNotNull { it.year }
            if (peers.size < 2 || years.size != peers.size || years.distinct().size != peers.size) {
                g
            } else {
                g.copy(title = "${g.title} (${g.year})")
            }
        }
    }

    /** The folder a release lives in — `…/Bonus`, `…/Extras`, `…/BDMV` are inside one, not one. */    /** The folder a release lives in — `…/Bonus`, `…/Extras`, `…/BDMV` are inside one, not one. */
    private fun releaseFolder(path: String): String {
        var f = path.replace('\\', '/').substringBeforeLast('/', "")
        while (f.isNotEmpty()) {
            val seg = f.substringAfterLast('/').trim()
            if (seg.lowercase() in JUNK_FOLDERS || EXTRAS_FOLDER.matches(seg)) {
                f = f.substringBeforeLast('/', "")
            } else {
                break
            }
        }
        return f
    }

    /** Where one part of a title ends and the next begins. */
    private val TITLE_BREAK = Regex("""[\s\u3000·:：\-－~～,，.、_/|]""")

    /** What a shared title prefix dangles once the episode is cut off it: ` 0`, ` ：`, `-第`. */
    private val EPISODE_DANGLE = Regex("""[\s\u3000]*第?[0-9０-９]{0,3}[\s\u3000·:：\-－~～,，.、]*$""")

    private fun titleOf(rep: MediaItem, key: String, group: List<MediaItem>): String {
        // A folder-grouped card (unidentified files) is named after its franchise folder, cleaned of
        // release tags — NOT after a stale per-file title ("01", "Final") left over from a bad match.
        if (key.startsWith("dir:")) {
            // A per-episode series keys as `<parent folder>|<shared prefix>` — the prefix is the
            // programme's name and the folder is only where it was downloaded, so a card was
            // reading 「下载|ＮＨＫスペシャル 選 新・ドキュメント太平洋戦争」. No path segment holds a '|'.
            val name = key.removePrefix("dir:").removeSuffix("|movie")
                .substringBefore(SHARD_MARK).substringAfterLast('/').substringAfterLast('|')
            // …and a SHELF is not a name either. `Documentary/NHK` holds 170 unrelated programmes,
            // each keyed by a title shard of its own — and every one of their cards read "NHK".
            // Where the folder shelves works instead of naming one, the file has to do the naming.
            if (FilenameParser.isShelfFolder(name)) {
                // …and what those files SHARE is the programme; what differs is the instalment.
                // Named from one file, a ten-part series read 「美国魂系列 ：太平洋新波澜」 — episode
                // one, standing in for all of it.
                // What MOST of them share, not all: one part of 世界市场·财富攻防战 is subtitled
                // 「made in china」 and shares no opening with its nine siblings, which collapsed the
                // prefix to nothing and left the card named after that one file.
                val titles = group.map { FilenameParser.parse(it.fileName).title }.sorted()
                val half = titles.size / 2
                val raw = (0..titles.size - 1 - half)
                    .map { titles[it].commonPrefixWith(titles[it + half]) }
                    .maxByOrNull { it.length }.orEmpty()
                // …and a prefix stops wherever the instalments diverge, which is not always a word
                // boundary: two 横滨中华街 programmes agreed only as far as 「横滨中华街 ：从」. Back
                // off to the last break — but only where they really did part mid-word, or
                // 「纪录片-日米安保50年」, whose two files part at the 前編/後編 after it, loses its name.
                val midWord = titles.filter { it.startsWith(raw) }.all { t ->
                    t.getOrNull(raw.length)?.let { !TITLE_BREAK.matches(it.toString()) } == true
                }
                val atBreak = raw.dropLastWhile { !TITLE_BREAK.matches(it.toString()) }
                val shared = (if (midWord && atBreak.length >= 4) atBreak else raw)
                    .replace(EPISODE_DANGLE, "")
                return shared.ifBlank { FilenameParser.parse(rep.fileName).title }
                    .ifBlank { rep.title.ifBlank { name } }
            }
            // As the folder writes it, both scripts: nothing identified it, so its own name is the best
            // there is, and the database's preferred half of it named two folders alike.
            return FilenameParser.writtenTitle(name).ifBlank { name.ifBlank { rep.title } }
        }
        val baseTitle = rep.showTitle ?: rep.title
        return if (key.endsWith("|movie")) rep.title else baseTitle
    }

    /** Series folder for unmatched files: climbs out of BDMV/STREAM and Season/Specials buckets. */
    /** Shortest folder-name prefix that still names a work rather than a franchise ("The Matrix"). */
    private const val SERIES_PREFIX = 14

    /** A download folder often opens with the air date: `2025-09-01 映像の世紀…`. */
    private val LEADING_DATE = Regex("""^\s*(?:\d{4}[-.]\d{1,2}[-.]\d{1,2}|\d{6,8})[\s_-]*""")

    /**
     * What a broadcast programme puts after its name: the air year (`１９４２　大日本帝国の分岐点`), an
     * episode number, or the episode's own title in quotes (`「銀座 百年の記憶」`).
     *
     * A film series puts a subtitle there instead — `Ⅰ 行け！コア・ファイター`, `Hathaway's Flash`,
     * `SEED C.E.73 -STARGAZER` — which is why this test, not the shared prefix, is what decides.
     */
    private val EPISODE_TAIL = Regex(
        """^(?:[「『][^」』]+[」』]|(?:19|20|１９|２０)[0-9０-９]{2}|""" +
            """第\s*[0-9０-９一二三四五六七八九十]{1,3}\s*[回話话集]|最終回|最终回)""",
    )

    /**
     * Sibling folders that are episodes of ONE series, mapped to a shared key.
     *
     * A per-episode download leaves one folder per episode — `ＮＨＫスペシャル　選　新・ドキュメント
     * 太平洋戦争１９４１開戦（前編）/`, `…１９４２大日本帝国の分岐点　前編/` — and the poster wall drew nine
     * cards for one series. What a person reads there is the long shared prefix, with an episode
     * designation after it.
     *
     * Three conditions keep a FRANCHISE out, and all three are needed: the shared prefix runs
     * [SERIES_PREFIX] characters (sequels diverge early — "The Matrix" vs "The Matrix Reloaded"
     * share 11); each folder holds a file or two (`Mobile Suit Gundam X.1996 1080P BDRIP/` holds
     * 39 and is a whole show); and what follows the prefix reads as an episode rather than a
     * subtitle. Consulted only for files with no identity of their own — an identified file keys
     * off the work the database named, which is strictly better information.
     */
    private fun seriesFolders(paths: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        val filesIn = paths.groupingBy { it.substringBeforeLast('/', "") }.eachCount()
        paths.map { it.substringBeforeLast('/', "") }.filter { it.contains('/') }.distinct()
            .filter { filesIn.getOrDefault(it, 0) <= 2 }
            .groupBy { it.substringBeforeLast('/') }
            .forEach { (parent, folders) ->
                if (folders.size < 2) return@forEach
                val name = folders.associateWith {
                    it.substringAfterLast('/').replace(LEADING_DATE, "").trim()
                }
                // every PAIR, not just neighbours in sort order: one folder that doesn't qualify
                // (a straggler named after no episode) would otherwise cut the series in two
                val key = HashMap<String, String>()
                for (i in folders.indices) for (j in i + 1 until folders.size) {
                    val a = folders[i]
                    val b = folders[j]
                    // back off whatever the two names happen to share of the episode designation
                    // itself — its leading digits ("…太平洋戦争１９４") or its opening quote — so that
                    // what remains is read as a designation and not as a subtitle
                    val shared = name.getValue(a).commonPrefixWith(name.getValue(b))
                        .trimEnd { it.isDigit() || it in "０１２３４５６７８９ 　.-_「『（(【[" }
                    if (shared.length < SERIES_PREFIX) continue
                    val tails = listOf(a, b).map { name.getValue(it).removePrefix(shared).trimStart(' ', '\u3000') }
                    if (!tails.all(EPISODE_TAIL::containsMatchIn)) continue
                    val k = key[a] ?: key[b] ?: "$parent|$shared"
                    key[a] = k
                    key[b] = k
                }
                // a straggler sharing the whole established name is the same programme, whatever it
                // calls that episode (`映像の世紀バタフライエフェクト　ベトナム　勝利の代償`)
                val established = key.entries.groupBy({ it.value }, { name.getValue(it.key) })
                folders.filterNot(key::containsKey).forEach { f ->
                    established.entries.firstOrNull { (k, names) ->
                        names.size >= 2 && name.getValue(f).startsWith(k.substringAfter('|'))
                    }?.let { key[f] = it.key }
                }
                out.putAll(key)
            }
        return out
    }

    private fun seriesFolder(path: String): String {
        val norm = path.replace('\\', '/').trimEnd('/')
        var dir = norm.substringBeforeLast('/', "")
        repeat(4) {
            val name = dir.substringAfterLast('/')
            if ((name.lowercase() in JUNK_FOLDERS || FilenameParser.isSeasonFolder(name)) && dir.contains('/')) {
                dir = dir.substringBeforeLast('/')
            }
        }
        return dir.ifBlank { norm }
    }
}

/** The shelves whose numbered files are not episodes of anything ([MediaGroup.countsEpisodes]). */
private val NOT_EPISODES = setOf(LibCategory.PERSONAL, LibCategory.MUSIC, LibCategory.GAME, LibCategory.OTHER)
