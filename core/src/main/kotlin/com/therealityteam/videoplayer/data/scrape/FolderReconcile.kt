package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus

/**
 * Folder reconciliation: once every file has been identified on its own, the folders decide.
 *
 * Within each release folder (the dir holding the main video files; /SPs /Extras /BDMV etc.
 * climbed into it), find the DOMINANT identity from the main feature, then adopt every straggler
 * (orphan / extra / a file matched to a different id) onto it — so a show's specials, NC OP/ED, PV,
 * "Special Report" 花絮 etc. merge INTO the show card instead of forming their own. Correctly-matched
 * main files, works of their own (several numbered files strong) and user-pinned items are untouched.
 * A second pass lets a big show adopt a tiny entry from one of its subfolders.
 *
 * A pure function over the library, so the poster-wall evaluation can replay it offline against a
 * device dump (`CardDumpTest -Daura.reconcile=1`); [ScrapeEngine] applies [plan] after a scrape.
 */
object FolderReconcile {

    /** A file's database identity as the card key sees it. */
    fun identityOf(it: MediaItem): String? = it.collectionKey ?: it.tmdbId?.let { id -> "id:$id" }

    /** How many files a show needs before it may adopt a tiny work from one of its subfolders. */
    private const val SHOW_MIN_FILES = 10

    /**
     * The items whose identity changes. Unchanged items are not returned.
     *
     * [touched] is the ids a pass actually looked at. Both passes below partition the library by a
     * folder, and each folder's answer is decided by that folder's own files and nothing else — so a
     * folder none of whose files were touched will decide exactly what it decided last time, which
     * is already what the library says. Skipping those is not an approximation.
     *
     * Null means every folder, which is what a full pass and the offline evaluation want. Worth
     * knowing where this is NOT exact: if a scan DELETED a file, the folder it left is not in
     * [touched] and keeps the identity it had. That identity only changes when the deleted file was
     * the one deciding it, and it is put right by the next pass that scrapes anything in that folder.
     */
    fun plan(all: List<MediaItem>, touched: Set<String>? = null): List<MediaItem> {
        val out = LinkedHashMap<String, MediaItem>()
        releasePass(all, out, touched)
        showPass(all.map { out[it.id] ?: it }, out, touched)
        return out.values.toList()
    }

    private fun isStrong(item: MediaItem): Boolean = ContentClassifier.isStrongExtra(item.fileName)

    /**
     * Is this file a FEATURE of its folder — read from its NAME, not from [MediaItem.contentTag]?
     *
     * The tag is what reconciliation itself writes when it adopts a straggler, so reading it back
     * makes the decision circular: a feature adopted once as an extra is an extra for ever, and no
     * later pass can tell that the folder ever had a feature at all.
     */
    private fun namesAFeature(item: MediaItem, folderKey: String): Boolean =
        !extrasSubfolder(item.path, folderKey) && !isStrong(item) &&
            ContentClassifier.classify(item.fileName, item.path) != ContentKind.EXTRA

    private fun releasePass(all: List<MediaItem>, out: MutableMap<String, MediaItem>, touched: Set<String>?) {
        all.groupBy { releaseFolder(it.path) }.forEach { (folderKey, group) ->
            if (group.size < 2) return@forEach
            if (touched != null && group.none { it.id in touched }) return@forEach
            // A SHELF is not a release folder. `Documentary/PBS/` holds 29 unrelated programmes;
            // one of them matched (The Medici) and the whole shelf inherited its identity, poster
            // and all — seven works on one card. Grouping already refuses to anchor on a shelf; the
            // scraper's reconciliation did not.
            if (FilenameParser.isShelfFolder(folderKey.substringAfterLast('/'))) return@forEach
            // …and nor is a music shelf's category folder (`Music/Audio/`, `Music/Lesson/`): eleven
            // mixing tutorials there had matched one entry, and all 83 clips beside them took it.
            if (FilenameParser.inMusicCategoryFolder("$folderKey/-")) return@forEach
            // A file reached through an EXTRAS subfolder (`/Extras`, `/Sample`…) is never a feature —
            // even if it matched to the movie by its folder name. Container dirs (BDMV/STREAM) hold the
            // main feature, so they don't count.
            fun isExtraPath(it: MediaItem) = extrasSubfolder(it.path, folderKey)
            val matched = group.filter { it.scrapeStatus == ScrapeStatus.MATCHED && identityOf(it) != null }
            if (matched.isEmpty()) return@forEach
            // The show is defined by its feature files.
            val features = group.filter { namesAFeature(it, folderKey) }
            val pool = matched.filter { namesAFeature(it, folderKey) }
                .ifEmpty {
                    // Only extras matched. A folder holding nothing BUT extras may be identified by
                    // them — that is all it has. A folder whose FEATURE simply failed to match may
                    // not: `劇場版 GのレコンギスタⅤ 死線を越えて` drew a blank from every database, its
                    // bonus disc hit 勇者王ガオガイガー 特典Disc, and the film was then filed as an
                    // extra OF that bonus disc — wrong card, wrong name, wrong year, and the feature
                    // itself tagged EXTRA. Left alone, the folder names its own card.
                    if (features.isNotEmpty()) return@forEach
                    matched
                }
            val counts = pool.groupingBy { identityOf(it)!! }.eachCount()
            val (dominant, domCount) = counts.maxByOrNull { it.value }!!
            // Need a clear majority among the main files — don't collapse a folder that holds two
            // co-equal works (rare for a release folder, but be conservative).
            if (counts.size > 1 && domCount * 2 <= pool.size) return@forEach
            // On a music shelf a folder is an artist's, a brand's or somebody's own as often as a
            // concert's, and one clip matching something says nothing of the rest: `Guitar/Marcel
            // Coenen/jamming.rm` matched a film called Jamming on an Old Saya, and 33 of his clips went
            // with it. There a folder is one work only when most of its files say so themselves.
            if (FilenameParser.isUnderMusicRoot(folderKey) && domCount * 2 <= features.size) return@forEach
            // The representative must be a FEATURE file. Taken from `matched` it could be one of the
            // stragglers already wearing a rejected name, which then reads as the folder's own name
            // and both the scrub and the year below take their answer from the damage.
            // …and one that actually carries a database identity. A file ADOPTED by an earlier pass
            // has the right id and a name taken back from its own file name, so as the folder's
            // representative it answers every question with itself: `Movie_II Moon Butterfly` read as
            // the folder's own name, and the repair below then found nothing to repair.
            val rep = pool.firstOrNull { identityOf(it) == dominant && it.originalTitle != null }
                ?: pool.firstOrNull { identityOf(it) == dominant }
                ?: matched.first { identityOf(it) == dominant }
            val ownWorks = ownWorks(matched, dominant)

            group.forEach { item ->
                if (item.identityPinned) return@forEach
                val settled = !isExtraPath(item) && item.scrapeStatus == ScrapeStatus.MATCHED
                // A feature file already matched to the dominant stays as-is; an extras-subfolder file
                // (even if it matched to the same id via its folder name) is re-tagged as an extra below.
                // …and so does one that the database gave a work of its own.
                if (settled && (identityOf(item) == dominant || identityOf(item) in ownWorks)) {
                    if (identityOf(item) == dominant) scrubbed(item, rep)?.let { out[item.id] = it }
                    return@forEach
                }
                out[item.id] = adopt(item, rep, dominant)
            }
        }
    }

    /**
     * Which of the OTHER identities in a release folder are works in their own right.
     *
     * A distinct WORK filed inside a series' box (R.O.D's OVA beside its TV series, 逮捕令's OVA,
     * Solid State Society beside S.A.C.) brings several files of its own; a fuzzy mis-match on a
     * featurette is usually alone. Only the latter should be folded onto the show.
     *
     * Two refinements to "several":
     *  - A file whose NAME says it is a non-work — a clean opening, a TV spot, a trailer, a disc
     *    menu (see [ContentClassifier.isStrongExtra]) — never counts, however many of them there
     *    are. 銀英's four TV Spots / NCOP had matched a TMDB entry between them and stood as a
     *    second card beside the twelve episodes.
     *  - Two UNNUMBERED clips are not a work either: SHIROBAKO's two credit variants of its
     *    in-story Exodus! each matched AniList's entry for it and made a two-file card; SAO's lone
     *    `[TV SP]` did the same. Two numbered episodes, or a film, still are.
     */
    private fun ownWorks(matched: List<MediaItem>, dominant: String): Set<String> {
        val domTitles = titlesOf(matched.filter { identityOf(it) == dominant })
        return matched.filter { !isStrong(it) }.groupBy { identityOf(it)!! }
            .filter { (id, files) ->
                id != dominant &&
                    (isFilm(files) || announcesItself(files, domTitles) || (files.size >= 2 && !isTiny(files)))
            }
            .keys
    }

    /** Every name a set of files was given — including the Latin ones, which is often all a card has. */
    private fun titlesOf(files: List<MediaItem>): List<String> =
        files.flatMap { listOfNotNull(it.title, it.originalTitle, it.showTitle) + it.aliases }
            .map { StringSimilarity.normalizeTitle(it) }.filter { it.length >= 2 }.distinct()

    /**
     * A work of its own that ships inside another's folder announces itself twice.
     *
     * "Several files" reads a lone match as what it usually is, a stray featurette — but
     * `guilty crown - lost christmas ova`, alone in an OVA/ subfolder of the TV release, matched
     * Guilty Crown: Lost Christmas because that is what it says it is. The count was only ever a
     * proxy for evidence the name already carries.
     *
     * Both halves are needed, and each keeps a case the count used to get right:
     *  - The FILE NAME must carry one of the matched work's names. SHIROBAKO's `[TV SP]` matched an
     *    entry whose name appears nowhere in the file's, which is what a fuzzy hit looks like.
     *  - One of those names must carry the folder's own work. SHIROBAKO's two credit variants of its
     *    in-story `Exodus!` do name what they matched — but `Exodus!` is not a SHIROBAKO, and a
     *    sibling filed in a release folder always says whose sibling it is.
     *
     * Both halves read [MediaItem.aliases] as well as the titles: a shelf named in Chinese has its
     * Latin names nowhere else, and the file names are Latin.
     */
    private fun announcesItself(files: List<MediaItem>, domTitles: List<String>): Boolean =
        files.any { item ->
            val fromName = StringSimilarity.normalizeTitle(FilenameParser.parse(item.fileName).title)
            if (fromName.length < 4) return@any false
            val own = titlesOf(listOf(item)).filter { it.length >= 4 }
            own.any { fromName.contains(it) } &&
                own.any { t -> domTitles.any { d -> t != d && t.contains(d) } }
        }

    /**
     * A film that ships inside a series' box is a work of its own, alone or not — 劇場版 魔法科高校
     * の劣等生 sat in the TV folder as a single file, matched its own AniList entry, and was folded
     * onto the 26-episode card anyway because "several files" was checked before "is it a film".
     */
    private fun isFilm(files: List<MediaItem>): Boolean =
        files.any { standsAlone(it) && it.contentTag != ContentKind.EXTRA }

    /** One or two files that name no episode past 1 and no film — a 特典, not a work. */
    private fun isTiny(files: List<MediaItem>): Boolean =
        files.size <= 2 && files.all { !standsAlone(it) && (it.episode ?: 0) <= 1 }

    /**
     * A file that is a work in its own right rather than one instalment of something.
     *
     * Both questions above want "is this an episode of the folder, or not", and read MOVIE as the
     * answer to it. Since the scanner stopped calling every unidentified file a film, UNKNOWN is
     * where those files went — and it means the same thing here it always did: nothing said this was
     * an instalment. Reading it as "not a film" instead would quietly start folding unidentified
     * films into the box they shipped in, which is the exact bug [isFilm] exists to prevent.
     */
    private fun standsAlone(item: MediaItem): Boolean =
        item.kind == MediaKind.MOVIE || item.kind == MediaKind.UNKNOWN

    /**
     * The show folder's turn: a tiny entry in a SUBFOLDER of a big show is that show's 特典.
     *
     * `白箱 SHIROBAKO/[VCB-Studio] Exodus! [Ma10p_1080p]/` holds two credit variants of the show's
     * in-story anime, which AniList lists as an entry of its own — a card of two clips beside the
     * 55-file show. A subfolder is its own release folder, so the pass above never sees the two
     * together; here the show does. Films, numbered runs and user pins are works and stay; so does
     * anything in a folder that holds no clear majority work (a franchise box).
     */
    private fun showPass(all: List<MediaItem>, out: MutableMap<String, MediaItem>, touched: Set<String>?) {
        all.groupBy { FilenameParser.animeShowRoot(it.path) }.forEach { (show, group) ->
            if (show == null || group.size <= SHOW_MIN_FILES) return@forEach
            // A show root spans several release folders; the release pass may have changed a file in
            // one of them, so this asks the same question of the whole root rather than one folder.
            if (touched != null && group.none { it.id in touched }) return@forEach
            if (FilenameParser.isShelfFolder(show.substringAfterLast('/'))) return@forEach
            val matched = group.filter { it.scrapeStatus == ScrapeStatus.MATCHED && identityOf(it) != null }
            val byId = matched.groupBy { identityOf(it)!! }
            val (dominant, domFiles) = byId.maxByOrNull { it.value.size } ?: return@forEach
            if (domFiles.size < SHOW_MIN_FILES || domFiles.size * 2 <= matched.size) return@forEach
            val rep = domFiles.firstOrNull { it.contentTag != ContentKind.EXTRA } ?: domFiles.first()
            val domTitles = titlesOf(domFiles)
            byId.forEach { (id, files) ->
                if (id == dominant || !isTiny(files) || files.any { it.identityPinned }) return@forEach
                // …unless it says what it is, and what it is belongs to this show. `guilty crown -
                // lost christmas ova`, alone in an OVA/ subfolder, is a work filed inside its
                // series' folder, not the series' 特典.
                if (announcesItself(files, domTitles)) return@forEach
                files.forEach { out[it.id] = adopt(it, rep, dominant) }
            }
        }
    }

    /**
     * The names and the year a REJECTED match left behind.
     *
     * Taking the title back from the file name was only half of it: the same clip kept the other
     * work's [MediaItem.originalTitle], its [MediaItem.aliases], its year and its synopsis, none of
     * which the card ever shows — so nothing looked wrong. They are read all the same. The library
     * search matches on originalTitle and aliases, so a G-Reco bonus clip that had briefly matched
     * 勇者王ガオガイガー answered a search for GaoGaiGar; [titlesOf] feeds them to [announcesItself],
     * where a wrong name decides whether a sibling is a work of its own. Measured on a real shelf:
     * 156 files carried another work's original title, 147 of them another work's year.
     */
    private fun MediaItem.withoutRejectedIdentity(rep: MediaItem) = copy(
        title = FilenameParser.parse(fileName).title.ifBlank { title },
        originalTitle = null,
        aliases = emptyList(),
        year = rep.year,
        overview = rep.overview,
    )

    /**
     * Whether this file's original title names something other than the work it sits on.
     *
     * Asked of the NAME, not of the id: a file adopted by an earlier pass already carries the right
     * id, so "is the identity changing" answers no and the stale name survives every later pass.
     * The G-Reco bonus discs sat in a `特典DISC/` subfolder wearing 勇者王ガオガイガー for exactly
     * that reason.
     */
    private fun wearsAnotherName(item: MediaItem, rep: MediaItem): Boolean {
        val own = item.originalTitle?.takeIf { it.isNotBlank() } ?: return false
        val mine = StringSimilarity.normalizeTitle(own)
        if (mine.length < 2) return false
        return titlesOf(listOf(rep)).none { it == mine || it.contains(mine) || mine.contains(it) }
    }

    /**
     * An extra already sitting on the right card, but still wearing a name from a match rejected by
     * an earlier pass. Re-running reconciliation repairs it — no re-scrape needed — and returns null
     * when there is nothing to repair, so unchanged items stay out of the plan.
     */
    private fun scrubbed(item: MediaItem, rep: MediaItem): MediaItem? =
        item.takeIf { it.contentTag == ContentKind.EXTRA && wearsAnotherName(it, rep) }
            ?.withoutRejectedIdentity(rep)

    private fun adopt(item: MediaItem, rep: MediaItem, dominant: String): MediaItem {
        // A file whose identity is being REPLACED carries the name, the year and the synopsis of the
        // match just rejected — the 药屋 特典 clip was still called 数码宝贝拯救者 on its card. A file
        // that simply had no identity keeps its own.
        val hadOther = identityOf(item) != null && identityOf(item) != dominant
        val base = if (hadOther || wearsAnotherName(item, rep)) item.withoutRejectedIdentity(rep) else item
        return base.copy(
            showTitle = rep.showTitle ?: rep.title,
            overview = base.overview ?: rep.overview,
            genres = rep.genres,
            cast = rep.cast,
            crew = rep.crew,
            rating = rep.rating,
            ratings = rep.ratings,
            runtime = rep.runtime,
            certification = rep.certification,
            year = base.year ?: rep.year,
            posterPath = rep.posterPath,
            backdropPath = rep.backdropPath,
            dominantColor = rep.dominantColor,
            tmdbId = rep.tmdbId,
            collectionKey = rep.collectionKey ?: dominant,
            kind = MediaKind.EPISODE,
            contentTag = ContentKind.EXTRA, // a straggler in a show folder is an extra
            scrapeStatus = ScrapeStatus.MATCHED,
            ratingsVersion = rep.ratingsVersion,
        )
    }

    // ───────────────────────── folder geometry ─────────────────────────

    /**
     * Release folder = the dir holding the main files. Inside an extras pack EVERYTHING is an extra,
     * even a non-marker per-extra subfolder (`…/X.Extras-Grym/The.Shooting.of.X/file`), so the release
     * folder is the parent of the SHALLOWEST extras/container folder among the file's nearest dirs —
     * which makes a pack's files share the main feature's folder and reconcile onto it.
     */
    fun releaseFolder(path: String): String {
        val norm = path.replace('\\', '/').trimEnd('/')
        val dirs = norm.split('/').dropLast(1) // drop the filename
        if (dirs.isEmpty()) return norm
        val from = maxOf(0, dirs.size - 4) // only the nearest folders, never the NAS root
        for (i in from until dirs.size) {
            if (dirs[i].lowercase() in EXTRA_DIRS || isExtraFolder(dirs[i])) {
                return dirs.take(i).joinToString("/").ifBlank { norm }
            }
        }
        return dirs.joinToString("/")
    }

    /** Is [path] reached from [releaseFolder] through an extras subfolder (not a container dir)? */
    fun extrasSubfolder(path: String, releaseFolder: String): Boolean {
        val norm = path.replace('\\', '/').trimEnd('/')
        val between = norm.removePrefix(releaseFolder).trim('/').split('/').dropLast(1) // dirs below release, sans file
        return between.any { seg ->
            val n = seg.lowercase()
            n !in CONTAINER_DIRS && (n in EXTRA_DIRS || isExtraFolder(seg))
        }
    }

    /**
     * A decorated extras subfolder — `Memento.Extras-Grym`, `The.Aviator.Extras`, `Frost.Nixon.Bonus`.
     * Matched as a delimited token (not a substring) so a real title like "Extraction" isn't climbed.
     *
     * Japanese and Chinese have no delimiters to match on, so there a marker can only be found as a
     * SUBSTRING — and a substring is only safe when the name is little else besides. `本篇特典`,
     * `特典DISC`, `映像特典` are extras folders; `[DBD-Raws][药屋少女的呢喃][01-24TV全集+特典映像][1080P]…`
     * is the show's own release folder, which merely lists 特典映像 among its contents — read as an
     * extras folder, all 69 of its files would climb out of the card they belong to. A length cap
     * separates the two, and no real title is four characters of 特典.
     */
    fun isExtraFolder(name: String): Boolean =
        EXTRA_FOLDER_RE.containsMatchIn(name) ||
            (name.length <= CJK_EXTRA_MAX && CJK_EXTRA.containsMatchIn(name))

    /** Markers that only a substring can find. `映像` and `预告` are NOT here: 映像の世紀 is a work. */
    private val CJK_EXTRA = Regex("""特典|花絮|メニュー""")
    private const val CJK_EXTRA_MAX = 8

    /** Container dirs that hold the MAIN feature (not extras) — never demote a file inside them. */
    val CONTAINER_DIRS = setOf("bdmv", "stream", "video_ts", "disc", "disc1", "disc2")

    // Subfolder names that hold a show's extras/discs — climbed into the parent release folder
    // for reconciliation, so /SPs /Extras /BDMV files belong to the main feature beside them.
    val EXTRA_DIRS = setOf(
        "sps", "sp", "specials", "special", "extras", "extra", "bonus", "menu", "pv", "cm",
        "cds", "cd", "scans", "scan", "bdmv", "stream", "video_ts", "subs", "subtitles",
        // Scene releases drop a short preview clip in a /Sample (or /Trailer, /Featurettes)
        // subfolder of the movie's release folder; climb it so the clip reconciles onto the
        // feature as an extra instead of forming its own "Sample" card.
        "sample", "samples", "trailer", "trailers", "featurette", "featurettes",
        // …and how a Chinese or Japanese release names the same thing. Without these the
        // folder was never climbed, so `药屋少女的呢喃/特典映像/…Tokuten.mkv` formed its own
        // group of one, reconciliation skipped it (group.size < 2), and the clip kept the
        // 数码宝贝拯救者 its filename had fuzzy-matched.
        "特典", "特典映像", "映像特典", "特典disc", "花絮", "映像", "おまけ", "音声特典", "特典cd",
        // A recap compilation belongs to the show it recaps, exactly like 特典 belongs to its film.
        "総集編", "总集编", "總集編", "soushuuhen",
    )

    /** Decorated extras subfolder (`X.Extras-Grym`, `X.Bonus`…) — marker as a delimited token. */
    val EXTRA_FOLDER_RE = Regex(
        """(?i)(^|[.\-_ ])(extras|specials|featurettes?|making|interviews?|deleted|bonus|""" +
            """sample|trailers?|picture[.\-_ ]?drama|pictdrama|drama[.\-_ ]?cd|nc(op|ed)|""" +
            """clean[.\-_ ]?(op|ed|ops|eds)|creditless|映像特典|特典|花絮|映像|预告)([.\-_ ]|$)""",
    )
}
