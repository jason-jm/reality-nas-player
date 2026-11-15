package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.data.model.ContentKind

/**
 * Classifies a file within a franchise so the library can keep movies (剧场版) as their own card
 * and fold OVA/OAD/Special/特典/NCOP/PV/CM… into the show's 特典 section, leaving everything else
 * as a normal episode. Heuristic over the filename + its folders; tune the token lists as
 * real-world naming demands.
 */
object ContentClassifier {

    // 剧场版 → its own movie card.
    private val MOVIE = Regex("""(?i)剧场版|劇場版|劇場|gekijou(?:ban)?|\bthe[.\-_ ]+movie\b""")

    /**
     * Markers that name a NON-WORK by themselves: a clean opening, a disc menu, a promo clip, a
     * next-episode preview, the making-of. A file carrying one is a 特典 of whatever it sits beside
     * and nothing else — so it never keeps a database identity of its own in folder reconciliation
     * (see [FolderReconcile]) and the marker is dropped from its search query ([stripMarkers]),
     * which is how `… Kaikou NCOP` and `… Kaikou TV Spots` come to ask for the show they belong to.
     *
     * OVA / OAD / SP / Special are deliberately NOT here: an OVA is regularly a work with an entry
     * of its own (R.O.D's 2001 OVA beside its 2003 TV series), and a "special" can be.
     *
     * A disc menu is named `Menu`, `MenuJP`, `Top.Menu`. 「The Menu」(2022) and 「The Interview」
     * (2014) are films — read as extras, a feature was filed away from its own release.
     */
    private const val STRONG_ALTS =
        """\bNC[.\-_ ]?(?:OP|ED)\d*\b|\bNCOP\b|\bNCED\b|\bOPED\b|\bNC\b|\bcreditless\b|\btextless\b|""" +
            """\bclean[.\-_ ]?(?:op|ed|opening|ending)s?\d*\b|ノンクレジット|ノンテロップ|ノンクレ|特番|""" +
            """(?<!\bthe[.\-_ ])\bmenu\b|\bPV\d*\b|\bCM\d*\b|\bTVCM\b|\bTV[.\-_ ]?spots?\b|\bMV\b|""" +
            """\bmusic[.\-_ ]?(?:video|clip)s?\b|\beyecatch(?:es)?\b|\byokoku\b|予告|特報|番宣|\bteasers?\b|""" +
            """\btrailers?\b|\bpromos?\b|\bpromotion(?:al)?[.\-_ ]?(?:video|movie|clip)s?\b|\bpreview\d*\b|""" +
            """\bdigest\b|\bmaking(?:[.\-_ ]of)?\b|(?<!\bthe[.\-_ ])\binterviews?\b|\bcommentary\b|""" +
            """\bfeaturettes?\b|\bbehind[.\-_ ]the[.\-_ ]scenes\b|\bdeleted[.\-_ ]scenes?\b|""" +
            """\bgag[.\-_ ]reel\b|\bbloopers?\b|\beizou[.\-_ ]?tokuten\b|\btokuten\b|映像特典|特典映像|特典|""" +
            """制作特辑|预告|花絮|picture[.\-_ ]?drama|pictdrama|drama[.\-_ ]?cd|\bbooklet\b|\bsample\b|""" +
            """\bscans?\b|\bbonus(?:[.\-_ ]material)?\b|総集編|总集编|\brecap\b|\bomake\b|""" +
            """\bnext[.\-_ ]ep(?:isode)?\b|\b(?:OP|ED)\d+(?:v\d+)?\b|\b(?:OP|ED)\d*\s*[「『｢]|メニュー|""" +
            """主題歌|主题歌|テーマソング|chimichara"""

    /** Weak markers: usually an extra, sometimes a work of its own — kept out of [STRONG_ALTS]. */
    private const val WEAK_ALTS = """\bOVA\b|\bOAV\b|\bOAD\b|\bSP\b|\bspecial\b"""

    // Non-feature extras → the show's 特典 bucket (OVA included, per spec).
    private val EXTRA = Regex("(?i)$WEAK_ALTS|$STRONG_ALTS")
    private val STRONG = Regex("(?i)$STRONG_ALTS")

    /** The strong markers that can OPEN a name without being part of it (`NCOP Ver.2`, `Menu Vol.03`,
     *  `Eyecatch Collection`). "Trailer", "Preview", "Making"… open real titles, so they are only ever
     *  stripped from the END. */
    private const val START_ALTS =
        """\bNC[.\-_ ]?(?:OP|ED)\d*\b|\bNCOP\b|\bNCED\b|\bOPED\b|\bNC\b|\bcreditless\b|\btextless\b|""" +
            """\bclean[.\-_ ]?(?:op|ed|opening|ending)s?\d*\b|ノンクレジット|ノンテロップ|ノンクレ|特番|(?<!\bthe[.\-_ ])\bmenu\b|""" +
            """\bPV\d*\b|\bCM\d*\b|\bTVCM\b|\bTV[.\-_ ]?spots?\b|\bMV\b|\beyecatch(?:es)?\b|\byokoku\b|""" +
            """予告|特報|番宣|\beizou[.\-_ ]?tokuten\b|\btokuten\b|映像特典|特典映像|特典|制作特辑|预告|花絮|""" +
            """picture[.\-_ ]?drama|pictdrama|drama[.\-_ ]?cd|\bbooklet\b|\bscans?\b|総集編|总集编|\bomake\b|""" +
            """\bdigest\b|\b(?:OP|ED)\d+(?:v\d+)?\b"""

    /** A counter riding on a marker: `PV2`, `Preview 07`, `NCOP Ver.2`, `Trailer v2`, `CG1`. */
    private const val STUB = """(?:[.\-_ ]*(?:ver\.?[.\-_ ]*\d+|v\d+|[A-Za-z]{0,2}\d{1,3}))?"""
    private val STRONG_END = Regex("""(?i)(?:^|[.\-_ \[(（【])(?:$STRONG_ALTS)$STUB[.\-_ )）\]】]*$""")
    private val STRONG_START = Regex("""(?i)^[.\-_ \[(（【]*(?:$START_ALTS)$STUB(?=[.\-_ )）\]】]|$)""")

    private val EXTRA_FOLDER = setOf(
        "specials", "special", "sps", "sp", "extras", "extra", "特典", "花絮", "scans", "scan",
        "cds", "cd", "menu", "pv", "cm", "oad", "oads", "ova", "ovas", "bonus",
        // …and the Jellyfin / Kodi extras folders the word vocabulary does not already cover.
        // `shorts` and `other` are left out on purpose: this set is tested against EVERY ancestor
        // folder, so a shelf someone calls `Shorts` or `Other` would turn its whole contents into
        // extras — a worse failure than missing a clip.
        "scenes",
    )

    /**
     * Does this file belong to a 劇場版 (rather than the series it sits next to)? Used for card
     * keying, so it is deliberately stricter than [classify]: a folder that names a film covers
     * everything inside it (feature + its own 特典), while a 劇場版 mention in the FILENAME only
     * counts when the file isn't itself an extra — a series' 「ノンテロップED(劇場版)」 belongs to the
     * series card, not to a film card of its own.
     */
    fun isMovie(fileName: String, path: String): Boolean = movieCache.memo(path) {
        val folders = path.replace('\\', '/').split('/').dropLast(1)
        if (folders.any { MOVIE.containsMatchIn(it) }) return@memo true
        MOVIE.containsMatchIn(words(fileName)) && !EXTRA.containsMatchIn(words(fileName))
    }

    // Grouping classifies every item on every recompute, and each classification runs a large
    // alternation over the name and every folder above it — memoised by path, bounded like the
    // parser's own cache (a library is ~10k names; clear on overflow).
    private val classifyCache = java.util.concurrent.ConcurrentHashMap<String, ContentKind>()

    /** Start from cold. Only a benchmark wants this — the memo is otherwise the whole point. */
    fun forgetClassifications() = classifyCache.clear()

    /** The paths classified so far, as what — for [NameMemo] to keep across launches. */
    fun classificationsSoFar(): Map<String, ContentKind> = HashMap(classifyCache)

    /** Paths classified in an earlier launch of this same build ([NameMemo]), taken as classified. */
    fun primeClassifications(kinds: Map<String, ContentKind>) = classifyCache.putAll(kinds)
    private val movieCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val strongCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private const val CACHE_LIMIT = 32_768

    private fun <V : Any> java.util.concurrent.ConcurrentHashMap<String, V>.memo(key: String, compute: () -> V): V {
        get(key)?.let { return it }
        if (size > CACHE_LIMIT) clear()
        return compute().also { put(key, it) }
    }

    /**
     * `_` is a word character to the regex engine, so `\b` never fires inside `[Movie_Promo]` or
     * `[NC_OP]` — the markers are matched against the name with its underscores read as spaces.
     */
    private fun words(name: String): String = name.replace('_', ' ')

    /** Does the NAME say this file is a non-work (see [STRONG_ALTS])? */
    /**
     * The name with every 特典 marker taken out, wherever it sits: `Code Geass R2 Picture Drama
     * 9.34` → `Code Geass R2 9.34`.
     *
     * [stripMarkers] only takes them off the ENDS, and rightly so — it is rewriting a search query,
     * and a marker in the middle of a name may be part of the name. This is for weighing a name
     * against a database entry, where a marker is never evidence of anything, and a marker written
     * as two words ("picture drama", "TV spot") is invisible to a test asked one word at a time.
     */
    fun withoutMarkers(s: String): String = EXTRA.replace(s, " ")

    fun isStrongExtra(fileName: String): Boolean =
        strongCache.memo(fileName) { STRONG.containsMatchIn(words(fileName)) }

    /**
     * The title with the 特典 marker taken off, for the search: `Ginga Eiyuu Densetsu Die Neue
     * These - Kaikou NCOP` → `… - Kaikou`, `Show Name - TV Spots` → `Show Name`, `Eyecatch
     * Collection` → `Collection` (which the signal-less guard then drops, so the folder's own title
     * asks instead). A marker in the middle of a name is left alone — it may be the name.
     */
    fun stripMarkers(title: String): String {
        var t = title.trim()
        var changed = false
        repeat(4) {
            val cur = t.trimEnd(' ', '-', '–', '—', '_', '.', ':', '：', '~', '～')
            val end = STRONG_END.find(cur)
            if (end != null && end.range.first > 0) {
                t = cur.substring(0, end.range.first)
                changed = true
                return@repeat
            }
            val start = STRONG_START.find(cur)
            if (start != null && start.range.last < cur.length - 1) {
                t = cur.substring(start.range.last + 1)
                changed = true
                return@repeat
            }
            // Nothing to strip: hand the name back untouched (its own trailing `~` included).
            return if (changed) cur.trim() else title.trim()
        }
        return t.trim()
    }

    fun classify(fileName: String, path: String): ContentKind =
        classifyCache.memo(path) { classifyUncached(fileName, path) }

    /**
     * How Jellyfin and Kodi mark a film's extras: a keyword on the end of the file's own name,
     * `Blade Runner 2049 (2017)-behindthescenes.mkv`. Written solid, with no separators inside,
     * which is exactly why the word vocabulary above — which knows `behind the scenes` — walks past
     * them. It is the mainstream convention for libraries organised by those two, and five of its
     * eight keywords were reading as features: behind the scenes, deleted, scene, short, other.
     *
     * Only at the very end and only after a hyphen, as the convention itself specifies. A film
     * called `The Short`, `Deleted` or `Scene` has to stay a film.
     */
    private val TRAILING_EXTRA_SUFFIX = Regex(
        """(?i)-(?:behind[.\-_ ]?the[.\-_ ]?scenes|deleted(?:[.\-_ ]?scenes?)?|featurettes?|""" +
            """interviews?|scenes?|shorts?|trailers?|others?|clips?)$""",
    )

    private fun classifyUncached(fileName: String, path: String): ContentKind {
        if (TRAILING_EXTRA_SUFFIX.containsMatchIn(fileName.substringBeforeLast('.'))) {
            return ContentKind.EXTRA
        }
        // Extras first, as [isMovie] already has it: a film's own 特典 carry the film's name, so
        // `[Ghost in the Shell：The New Movie][SP1]` and `劇場版 魔法科高校の劣等生 … Menu` used to
        // read as features because their TITLE says "movie" — and each then broke off its own card.
        when {
            EXTRA.containsMatchIn(words(fileName)) -> return ContentKind.EXTRA
            MOVIE.containsMatchIn(words(fileName)) -> return ContentKind.MOVIE
        }
        // Innermost folder first — the one a file sits in describes it, the ones above only say
        // where it was filed. `劇場版 GのレコンギスタⅠ…/本篇特典/TVCM.mkv` is the film's 特典; read
        // from the outside in, the 劇場版 folder answered first and called the trailer a feature.
        val folders = path.replace('\\', '/').split('/').dropLast(1).asReversed()
        for (f in folders) {
            // A decorated extras SUBFOLDER — `…_Code_Geass_R2_Picture_Drama_(1920x1080)`, `Bonus
            // Material/Clean OPs` — never matches the plain-name set, so test the pattern too.
            if (f.lowercase() in EXTRA_FOLDER || EXTRA.containsMatchIn(words(f))) return ContentKind.EXTRA
            if (MOVIE.containsMatchIn(f)) return ContentKind.MOVIE
        }
        return ContentKind.MAIN
    }
}
