package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.playback.QualityTags

/**
 * What the detail page's big button plays, and what it says.
 *
 * In order: the file being watched (the one last touched, if any is part-way through); else the
 * first unwatched main file after the last watched one — the next episode, not episode 1 again;
 * else the first main file. Extras never lead. Pure, so the choice is tested rather than eyeballed.
 *
 * The choice is a [Kind], not a sentence: callers decide by the kind and render the words in the
 * reader's language. (Reading the words back — `label.startsWith("播放 · ")` — worked only for a
 * Chinese reader, and silently emptied the "next up" row for everyone else.)
 */
object DetailPlay {
    enum class Kind { RESUME, NEXT, RESTART, START }

    data class Choice(val item: MediaItem, val kind: Kind)

    fun choose(ordered: List<MediaItem>, numberedRun: Boolean = false, seasonal: Boolean = false): Choice? {
        if (ordered.isEmpty()) return null
        ordered.filter { it.progress.started && !it.progress.watched }
            .maxByOrNull { it.progress.updatedAt }
            ?.let { return Choice(it, Kind.RESUME) }
        val mains = mains(ordered, numberedRun, seasonal)
        val lastWatched = mains.indexOfLast { it.progress.watched }
        if (lastWatched >= 0) {
            mains.drop(lastWatched + 1).firstOrNull { !it.progress.watched }?.let {
                return Choice(it, Kind.NEXT)
            }
            // Everything watched: start over from the beginning.
            return Choice(mains.first(), Kind.RESTART)
        }
        return Choice(mains.first(), Kind.START)
    }

    /**
     * What the big button does with [item] once somebody has chosen it to play (a film's other
     * version, say): go on where it was left, start it over, or start it.
     */
    fun choiceFor(item: MediaItem): Choice = Choice(
        item,
        when {
            item.progress.started && !item.progress.watched -> Kind.RESUME
            item.progress.watched -> Kind.RESTART
            else -> Kind.START
        },
    )

    /**
     * The versions of the film [item] is one of ([alternates]), in the list's order — 七武士's 720P
     * and its 4K — or just [item] when it has no other.
     */
    fun versionsOf(ordered: List<MediaItem>, item: MediaItem, numberedRun: Boolean = false): List<MediaItem> {
        val alts = alternates(ordered, numberedRun)
        val stands = alts[item.id] ?: item.id
        return ordered.filter { it.id == stands || alts[it.id] == stands }.ifEmpty { listOf(item) }
    }

    /**
     * The files a card is about — its episodes, or its feature — without the extras around them.
     * A card of nothing but extras has nothing else, so then all of it counts.
     *
     * A show's ([seasonal]) are the episodes its page lists under its seasons (DetailSections), and
     * nothing else: a music video named for its song, a film filed with the episodes, read as features,
     * sorted ahead of episode 1 — 进击的巨人's 播放 played 「Guren no Yumiya」, RahXephon's a promo,
     * Evangelion's The End of Evangelion — while the page filed them under 特典 and opened there.
     * Of those, a recap the scan took for an extra (86's 11.5, 18.5, 21.5) keeps its place in the
     * list but is not an episode to count, or to wait for before the show reads seen.
     */
    fun mains(ordered: List<MediaItem>, numberedRun: Boolean = false, seasonal: Boolean = false): List<MediaItem> {
        if (seasonal) {
            val listed = listed(ordered, numberedRun)
            if (listed.isNotEmpty()) return listed.filter { it.contentTag != ContentKind.EXTRA }.ifEmpty { listed }
        }
        // A second copy of an episode — the page files it under 特典 (DetailSections) — is not one
        // to play next, count, or mark: the button would have offered a TVRip copy over the BDRip.
        // Nor is a film's other version: 七武士 seen in 4K offered its 720p as the next thing to play.
        // A disc that names its feature ("[MAIN_MOVIE]") has said which file is the film; the rest of it
        // is the disc's — a stage greeting, a fan event — however the scan tagged it.
        ordered.filter { EpisodeNaming.marksMain(it.fileName) }.takeIf { it.isNotEmpty() }?.let { return it }
        val copies = DetailSections.copies(ordered, numberedRun)
        val versions = alternates(ordered, numberedRun)
        return ordered.filter { isMain(it, numberedRun) && it.id !in copies && it.id !in versions }
            // A card of OVAs alone — the scan files every OVA as an extra — is about its OVAs, not the
            // disc's menus and music clips filed with them.
            .ifEmpty { ordered.filter { EpisodeNaming.parse(it, numberedRun).cat == EpisodeNaming.Cat.OVA } }
            .ifEmpty { ordered }
    }

    /**
     * A film's other versions ([versionSets]), each mapped to the one that stands for the film among
     * [mains]: the version watched or being watched last, else the first listed. 七武士 in 720p and
     * in 4K is one film to play, count and finish — seeing either finishes it, 重看 replays the one
     * seen; the page still lists both, and either plays in the film's place.
     */
    fun alternates(ordered: List<MediaItem>, numberedRun: Boolean = false): Map<String, String> {
        val features = ordered.filter { isMain(it, numberedRun) && EpisodeNaming.parse(it, numberedRun).cat == EpisodeNaming.Cat.FEATURE }
        if (features.size < 2) return emptyMap()
        // Where two databases each identified a copy, the film the card is: the identity most of its
        // files carry, the release with its 特典 (LibraryGrouping kept that one's identity for the
        // card). Its copy stands for the film until another is played — 闪光的哈萨维's 播放 had played
        // its 2160p, and a BDRip that sorts first by name must not take that over.
        // On a tie, TMDB's, as the card's own identity is.
        val own = ordered.mapNotNull(FilmIdentity::key).groupingBy { it }.eachCount().entries
            .maxWithOrNull(compareBy({ it.value }, { FilmIdentity.isTmdb(it.key) }))?.key
        val out = HashMap<String, String>()
        versionSetsOf(features).forEach { idx ->
            val set = idx.map { features[it] }
            val stands = set.filter { it.progress.started || it.progress.watched }.maxByOrNull { it.progress.updatedAt }
                ?: set.firstOrNull { own != null && FilmIdentity.key(it) == own }
                ?: set.first()
            set.forEach { if (it.id != stands.id) out[it.id] = stands.id }
        }
        return out
    }

    /** The files a show's page lists under its seasons (or its one episode list), in [ordered]'s order. */
    fun listed(ordered: List<MediaItem>, numberedRun: Boolean = false): List<MediaItem> {
        val ids = DetailSections.of(ordered, numberedRun)
            .filter { it.kind == DetailSections.Kind.SEASON || it.kind == DetailSections.Kind.EPISODES }
            .flatMapTo(HashSet()) { sec -> sec.items.map { it.id } }
        return if (ids.isEmpty()) emptyList() else ordered.filter { it.id in ids }
    }

    fun isMain(item: MediaItem, numberedRun: Boolean = false): Boolean {
        if (item.contentTag == ContentKind.EXTRA) return false
        val cat = EpisodeNaming.parse(item, numberedRun).cat
        return cat == EpisodeNaming.Cat.EPISODE || cat == EpisodeNaming.Cat.FEATURE
    }

    /**
     * What 全部标为已看 / 全部标为未看 would do to a card: which files it changes, which way, and how
     * many of those are part-way through — they lose their place.
     *
     * Only the main files count, the way the big button counts them. Counting the extras too, a card
     * with NCOPs and PVs nobody watches could never read "all watched" while its button already said
     * 重看. And only the files whose state really changes are touched: marking a watched file watched
     * again still moves its "last watched" time, which reorders 历史 for nothing.
     */
    data class MarkAll(val targets: List<MediaItem>, val watched: Boolean, val partWatched: Int) {
        /** Every main file is watched already, so the action goes the other way. */
        val allWatched: Boolean get() = !watched

        /** Ask first when more than one file's progress changes, or somebody's place in a file goes. */
        val needsConfirm: Boolean get() = targets.size > 1 || partWatched > 0
    }

    /**
     * 标为已看（含之前各集）: [item] and the episodes before it not seen yet — what somebody who has
     * watched up to here somewhere else means. Only an episode or feature (not an extra) reaches back,
     * and only over the main files, in the list's order; empty when there is nothing before it to mark.
     */
    fun earlierUnwatched(ordered: List<MediaItem>, item: MediaItem, numberedRun: Boolean = false, seasonal: Boolean = false): List<MediaItem> {
        if (!seasonal && !isMain(item, numberedRun)) return emptyList()
        val mains = mains(ordered, numberedRun, seasonal)
        val at = mains.indexOfFirst { it.id == item.id }
        if (at <= 0) return emptyList()
        return mains.subList(0, at).filterNot { it.progress.watched }
    }

    /**
     * Labels that say one thing for several files — two 预告, three 特典 of one kind — numbered in the
     * list's order ("预告 1", "预告 2"), so a row says which it is without its file name; a label that
     * occurs once stays as it is.
     */
    fun numberRepeats(labels: List<String>): List<String> {
        val counts = labels.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        return labels.map { l -> if ((counts[l] ?: 0) < 2) l else "$l ${seen.merge(l, 1, Int::plus)}" }
    }

    fun markAll(ordered: List<MediaItem>, numberedRun: Boolean = false, seasonal: Boolean = false): MarkAll {
        val mains = mains(ordered, numberedRun, seasonal)
        val toWatched = !mains.all { it.progress.watched }
        val targets = if (toWatched) mains.filterNot { it.progress.watched } else mains
        return MarkAll(targets, toWatched, targets.count { it.progress.started && !it.progress.watched })
    }

    /** How far through a file is: seen, part-way (with the minutes left), or merely opened. */
    sealed interface RowState {
        object Watched : RowState
        data class Left(val minutes: Int) : RowState
        object Started : RowState
    }

    fun rowState(item: MediaItem): RowState? {
        val p = item.progress
        return when {
            p.watched -> RowState.Watched
            p.started && p.durationMs > 0 ->
                RowState.Left(((p.durationMs - p.positionMs) / 60_000L).coerceAtLeast(1).toInt())
            p.started -> RowState.Started
            else -> null
        }
    }

    /**
     * Names that tell files apart where the parser has one word for all of them — an album's six
     * tracks all read 正片: each file's own name, without its extension and without the words every
     * one of them begins (or ends) with.
     *
     * The cut falls only between words and outside brackets, so `Track 01`/`Track 02` keep their
     * numbers and `[01] Intro` keeps its bracket; when that would leave some file with nothing of its
     * own, or two files with the same name, every file keeps its whole name instead. Names are
     * compared as words, not as spellings: "Seven Samurai 1954" and "Seven.Samurai.1954" share all
     * three — compared by character they shared "Seven", and lost it from the title.
     */
    fun distinctNames(fileNames: List<String>): List<String> {
        val stems = fileNames.map { it.substringBeforeLast('.', it).trim() }
        if (stems.size < 2) return stems
        val head = cutPoint(stems, longestShared(stems) { s, i -> s[i] }, fromEnd = false)
        val rest = stems.map { it.substring(head) }.takeIf { r -> r.none { it.isBlank() } } ?: stems
        val tail = cutPoint(rest, longestShared(rest) { s, i -> s[s.length - 1 - i] }, fromEnd = true)
        val core = rest.map { it.substring(0, it.length - tail) }.takeIf { r -> r.none { it.isBlank() } } ?: rest
        val names = core.map { it.trim(*SPACING) }
        return if (names.any { it.isEmpty() } || names.toSet().size < names.size) stems else names
    }

    /** How many characters all of [names] share, reading each with [at] (front or back). */
    private fun longestShared(names: List<String>, at: (String, Int) -> Char): Int {
        val limit = names.minOf { it.length }
        var n = 0
        while (n < limit && names.all { fold(at(it, n)) == fold(at(names[0], n)) }) n++
        return n
    }

    /** A character as names are compared: a space, a dot and an underscore all part words; case aside. */
    private fun fold(c: Char): Char = if (c in WORD_GAPS) ' ' else c.lowercaseChar()

    private val WORD_GAPS = charArrayOf(' ', '.', '_')

    /**
     * The longest of the first [shared] characters (counted [fromEnd] or from the front) that can be
     * cut from every name without splitting a word or a bracket.
     */
    private fun cutPoint(names: List<String>, shared: Int, fromEnd: Boolean): Int {
        for (n in shared downTo 1) {
            if (names.all { s -> cuttable(s, if (fromEnd) s.length - n else n, fromEnd) }) return n
        }
        return 0
    }

    /**
     * Whether a cut at [i] falls between words — at an end, or beside spacing or a bracket — and the
     * part it takes away (before [i], or from [i] on when [fromEnd]) holds whole brackets only:
     * "[Show][EX_BOX1][…]" against "[Show][EX_BOX2][…]" had come out "BOX1][…]". Only that part is
     * looked at, so one name with a bracket left open in its middle does not hold every cut back.
     */
    private fun cuttable(s: String, i: Int, fromEnd: Boolean): Boolean {
        if (i <= 0 || i >= s.length) return true
        val cut = if (fromEnd) s.substring(i).reversed() else s.substring(0, i)
        val (opens, closes) = if (fromEnd) CLOSING to OPENING else OPENING to CLOSING
        var depth = 0
        for (c in cut) {
            if (c in opens) depth++ else if (c in closes && depth > 0) depth--
        }
        if (depth != 0) return false
        val before = s[i - 1]
        val after = s[i]
        return before in SPACING || before in CLOSING || after in SPACING || after in OPENING
    }

    /** How one version of a film is told from the others: by what it is, or by its own words. */
    sealed interface VersionLabel {
        data class Tags(val tags: List<QualityTags.Tag>) : VersionLabel
        data class Name(val name: String) : VersionLabel
    }

    /**
     * How each version of a film among [fileNames] ([versionSets]) is told apart: by what it is —
     * "720P", "4K · 杜比视界 · Remux" — when its name claims [QualityTags] no other version of the set
     * claims; else by its own words past the title the set shares ("BRRip.H264.AAC.Gopo" beside 乱's
     * 4K remux). By index into [fileNames]; files in no set are left to [distinctNames].
     */
    fun versionLabels(fileNames: List<String>): Map<Int, VersionLabel> = versionLabels(fileNames, versionSets(fileNames))

    /** [versionLabels] of whole films, with the copies two databases joined that no name does ([versionSetsOf]). */
    @JvmName("versionLabelsOfFeatures")
    fun versionLabels(features: List<MediaItem>): Map<Int, VersionLabel> =
        versionLabels(features.map { it.fileName }, versionSetsOf(features))

    private fun versionLabels(fileNames: List<String>, sets: List<List<Int>>): Map<Int, VersionLabel> {
        val out = HashMap<Int, VersionLabel>()
        sets.forEach { idx ->
            val tags = idx.map { QualityTags.of(fileNames[it]) }
            val claims = tags.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
            val names = distinctNames(idx.map { fileNames[it] })
            idx.forEachIndexed { k, i ->
                out[i] = if (tags[k].isNotEmpty() && claims[tags[k]] == 1) VersionLabel.Tags(tags[k]) else VersionLabel.Name(names[k])
            }
        }
        return out
    }

    /**
     * Which of [fileNames] are versions of one film — sets of two or more, as indexes. Versions share
     * a title ([release]): "Seven Samurai 1954 Restored 720p…" and "Seven.Samurai.1954.2160p…", or
     * "[VCB-Studio] Yuru Camp Movie [Ma10p_1080p]" and "…[Ma10p_2160p_DoVi]". Not versions:
     * - files named alike but for a number — "Vanity Fair (2004) DVDRip…1" and "…2" are its halves;
     * - files whose title the name does not anchor with a year, unless they agree in every word but
     *   the formats: "[SAO][BDRIP][Introduction]" and "[SAO][BDRIP][Explanation]" are two videos.
     */
    /**
     * [versionSets] of [features] — whole films, on one card — and beside them the copies two
     * databases each identified as one film ([FilmIdentity.oneFilmTwoDatabases]): 闪光的哈萨维's
     * 2160p release named in Chinese and the BDRip called "Mobile Suit Gundam - Hathaway's Flash"
     * share no word a name could match. A part of a film (CD2, 上) is no copy of it, nor is an extra
     * filed with it: the release's FAN GATHERING carries the film's identity, and is a featurette.
     * By index.
     */
    fun versionSetsOf(features: List<MediaItem>): List<List<Int>> {
        val byName = versionSets(features.map { it.fileName })
        if (features.size < 2) return byName
        val parent = IntArray(features.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        byName.forEach { idx -> idx.drop(1).forEach { parent[root(it)] = root(idx.first()) } }
        val whole = features.map { it.kind == MediaKind.MOVIE && it.contentTag != ContentKind.EXTRA && !isPart(it.fileName) }
        var joined = false
        for (a in features.indices) for (b in a + 1 until features.size) {
            if (whole[a] && whole[b] && root(a) != root(b) && FilmIdentity.oneFilmTwoDatabases(features[a], features[b])) {
                parent[root(b)] = root(a)
                joined = true
            }
        }
        if (!joined) return byName
        return features.indices.groupBy(::root).values.filter { it.size > 1 }
    }

    /**
     * Which part of a film [fileName] is — CD2 → 2, Part One → 1, 上 → 1, 下 → 2 — or null for a
     * film whole in one file. The player names the part it plays: two files of one film read alike.
     */
    fun partNumber(fileName: String): Int? {
        val words = fileName.substringBeforeLast('.', fileName).lowercase().split(STEM_GAPS).filter { it.isNotEmpty() }
        words.firstNotNullOfOrNull { w -> PART_MARK.matchEntire(w)?.let { m -> m.groupValues[1].toIntOrNull() } }?.let { return it }
        words.zipWithNext().firstNotNullOfOrNull { (a, b) ->
            if (a !in PART_WORDS) null else b.toIntOrNull() ?: PART_NUMBERS.indexOf(b).takeIf { it >= 0 }?.let { it % 4 + 1 }
        }?.let { return it }
        return words.firstNotNullOfOrNull { w -> if (w in CJK_FIRST) 1 else if (w in CJK_PARTS) 2 else null }
    }

    /** A part of a film by its name — CD2, Part 1, Disc 2, 上 — wherever in the name it stands. */
    private fun isPart(fileName: String): Boolean {
        val words = fileName.substringBeforeLast('.', fileName).lowercase().split(STEM_GAPS).filter { it.isNotEmpty() }
        return words.any { PART_MARK.matches(it) || it in CJK_PARTS } ||
            words.zipWithNext().any { (a, b) -> a in PART_WORDS && (b.all(Char::isDigit) || b in PART_NUMBERS) }
    }

    fun versionSets(fileNames: List<String>): List<List<Int>> {
        if (fileNames.size < 2) return emptyList()
        val releases = fileNames.map { release(it) }
        return fileNames.indices.filter { releases[it] != null }
            .groupBy { releases[it]!!.stem }
            .values.filter { it.size > 1 }
            .filter { idx ->
                val set = idx.map { releases[it]!! }
                val numbers = set.map { r -> r.rest.filter { w -> w.all(Char::isDigit) } }
                val parts = set.map { r -> r.rest.filterNot { w -> w.all(Char::isDigit) } }.toSet().size == 1 && numbers.toSet().size > 1
                !parts && (set.first().byYear || set.map { r -> r.rest.filterNot { w -> isFormatWord(w) || w == "ray" || w.all(Char::isDigit) } }.toSet().size == 1)
            }
    }

    /** A release name read as a title ([stem]) and what follows it ([rest]), in lower-case words. */
    private class Release(val stem: String, val rest: List<String>, val byYear: Boolean)

    /**
     * [fileName]'s title, as words: up to and with its year ("seven samurai 1954" — the year keeps
     * Blade Runner and Blade Runner 2049 apart), or up to its first format word, past a leading
     * bracket (a release group, or the title in another script: "[末代皇帝].The.last.emperor.1987").
     * Null when nothing says where the title ends, or when the name is not one whole film:
     * - between the year and the formats only an edition may stand — Restored, PROPER, IMAX,
     *   Extended. Any other words name another thing of that year: "G5 2007 Chaos" and "G5 2007
     *   Spirits" are two songs, "November 2006 Press Conference" is not the 2006 film;
     * - anywhere after the title, a part's mark — CD2, Part 1, Disc 2, 上 — makes the file a part of
     *   the film (found after the formats as often as before them: "DVDRip.XviD-cd1").
     */
    private fun release(fileName: String): Release? {
        val stemName = fileName.substringBeforeLast('.', fileName)
        val name = LEADING_BRACKET.find(stemName)?.let { m -> stemName.substring(m.range.last + 1).takeIf { it.isNotBlank() } } ?: stemName
        val words = name.lowercase().split(STEM_GAPS).filter { it.isNotEmpty() }
        val year = words.indexOfFirst { YEAR.matches(it) }
        val format = words.indexOfFirst { isFormatWord(it) }
        val byYear = year >= 0 && (format < 0 || year < format)
        val end = when {
            byYear -> {
                val edition = words.subList(year + 1, if (format < 0) words.size else format)
                if (!edition.all { it in EDITION_WORDS }) return null
                year + 1
            }
            format > 0 -> format
            else -> return null
        }
        val rest = words.drop(end)
        val part = rest.any { PART_MARK.matches(it) || it in CJK_PARTS } ||
            rest.zipWithNext().any { (a, b) -> a in PART_WORDS && (b.all(Char::isDigit) || b in PART_NUMBERS) }
        return if (part) null else Release(words.take(end).joinToString(" "), rest, byYear)
    }

    private fun isFormatWord(w: String): Boolean = w in FORMAT_WORDS || RESOLUTION.matches(w) || FRAME_SIZE.matches(w)

    private val STEM_GAPS = Regex("[\\s._\\-\\[\\]()（）【】,，、+]+")
    private val YEAR = Regex("(?:19|20)\\d\\d")
    private val RESOLUTION = Regex("\\d{3,4}[pi]")
    private val FRAME_SIZE = Regex("\\d{3,4}x\\d{3,4}")
    private val LEADING_BRACKET = Regex("^\\s*[\\[【][^\\]】]*[\\]】]")
    /** What may stand between a film's year and its formats in a version of it. */
    private val EDITION_WORDS = setOf(
        "restored", "remastered", "remaster", "proper", "repack", "readnfo", "rerip", "internal", "limited",
        "unrated", "uncut", "uncensored", "extended", "theatrical", "director's", "directors", "dc", "cut",
        "final", "imax", "criterion", "special", "edition", "ultimate", "collector's", "collectors",
        "anniversary", "hybrid", "open", "matte", "widescreen", "ws", "redux", "version",
    )

    /** A part's mark in one word: cd2, part1, disc2, pt1. */
    private val PART_MARK = Regex("(?:cd|disc|disk|part|pt)(\\d{1,2})")
    /** …or in two: "Part 1", "Part Two", "Disc 2". */
    private val PART_WORDS = setOf("part", "pt", "cd", "disc", "disk", "vol", "volume")
    /** In order: words, then numerals ([partNumber] reads the place in each four). */
    private val PART_NUMBERS = listOf("one", "two", "three", "four", "i", "ii", "iii", "iv")
    private val CJK_PARTS = setOf("上", "下", "上集", "下集", "上部", "下部", "前编", "后编", "前編", "後編", "前篇", "后篇", "後篇")
    /** The first of two halves, of [CJK_PARTS]; the rest are the second. */
    private val CJK_FIRST = setOf("上", "上集", "上部", "前编", "前編", "前篇")

    private val FORMAT_WORDS = setOf(
        "4k", "8k", "uhd", "bluray", "blu", "bdrip", "brrip", "bdremux", "remux", "bd", "web", "webdl",
        "webrip", "dl", "hdtv", "dvdrip", "dvd", "hdrip", "x264", "x265", "h264", "h265", "hevc", "avc", "xvid",
        "divx", "hdr", "hdr10", "dovi", "dv", "sdr", "hlg", "10bit", "8bit", "hi10p", "ma10p", "main10",
        "aac", "ac3", "eac3", "dts", "dtshd", "truehd", "atmos", "flac", "ddp", "dd", "lpcm", "opus", "mp3",
        "chap", "chs", "cht", "gb", "big5",
    )

    private val SPACING = charArrayOf(' ', '.', '_', '-', '–', '—', '·', ',', '，', '、', '　', '|', '~', ':', '：')
    private val OPENING = charArrayOf('[', '(', '【', '「', '『', '《', '（')
    private val CLOSING = charArrayOf(']', ')', '】', '」', '』', '》', '）')
}
