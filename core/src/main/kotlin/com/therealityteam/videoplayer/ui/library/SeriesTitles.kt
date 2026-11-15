package com.therealityteam.videoplayer.ui.library

/**
 * 系列作品: the other cards of this card's series in this library, in the order they came out —
 * 黑客帝国 with 黑客帝国2：重装上阵 and 黑客帝国3：矩阵革命, the 星球大战 films with their 外传.
 *
 * A series is read from the titles the cards already carry, nothing fetched: a name up to the
 * number, colon or space that follows it ("星球大战" of 「星球大战4：新希望」, "机动战士高达" of
 * 「机动战士高达 逆袭的夏亚」), shared by two or more identified cards, at least one of which numbers
 * itself right after it (黑客帝国2, 银翼杀手2049, 攻壳机动队2 无罪). A shared word alone is no
 * series — 星际穿越 and 星际迷航 are two names. A name that runs on from a series' name belongs to
 * it: 星球大战外传 to 星球大战, 机动战士高达SEED to 机动战士高达.
 */
object SeriesTitles {

    fun of(target: MediaGroup, library: List<MediaGroup>, limit: Int = 24): List<MediaGroup> =
        seriesOf(target, library)?.cards?.take(limit).orEmpty()

    /** [target]'s series, named, with its other cards in the order they came out — null when it has none. */
    fun seriesOf(target: MediaGroup, library: List<MediaGroup>): Series? {
        val own = keyOf(target) ?: return null
        val keyed = library.mapNotNull { card -> keyOf(card)?.let { card to it } }
        // The series is the shortest name this card's begins with that two cards share and one of
        // them numbers — 星球大战 for 「星球大战外传1：侠盗一号」, not 星球大战外传.
        val stem = keyed.map { it.second }.filter { own.startsWith(it) }.distinct().sortedBy { it.length }
            .firstOrNull { s ->
                val members = keyed.filter { (_, k) -> k.startsWith(s) }
                members.size > 1 && members.any { (card, _) -> numbered(card.title, s) }
            } ?: return null
        val others = keyed.asSequence()
            .filter { (card, k) -> card.key != target.key && k.startsWith(stem) }
            .map { it.first }
            .sortedWith(compareBy({ it.year }, { it.title }))
            .toList()
        return Series(displayName(stem), others)
    }

    /** A series of the library: the name its titles share, and its cards in the order they came out. */
    data class Series(val name: String, val cards: List<MediaGroup>) {
        /**
         * [card]'s name with the series' own taken off, for where the series is already named:
         * 「机动战士高达 逆袭的夏亚」 is 逆袭的夏亚, 「星球大战4：新希望」 4：新希望 — and 黑客帝国, which is
         * nothing but the series' name, stays itself.
         */
        fun shortTitle(card: MediaGroup): String {
            val full = card.title.trim()
            if (!full.startsWith(name, ignoreCase = true)) return full
            return full.substring(name.length).trimStart(' ', '　', '：', ':', '-', '–', '·', '・', ',', '，').ifBlank { full }
        }
    }

    /**
     * Every series in [library] — the same series [of] finds for each of its cards, with the stem
     * they share as its name — the largest first.
     */
    fun all(library: List<MediaGroup>): List<Series> {
        val keyed = library.mapNotNull { card -> keyOf(card)?.let { card to it } }
        val stems = keyed.map { it.second }.distinct().filter { s ->
            val members = keyed.filter { (_, k) -> k.startsWith(s) }
            members.size > 1 && members.any { (card, _) -> numbered(card.title, s) }
        }
        // A stem that runs on from a shorter one is that series (星球大战外传 is 星球大战).
        return stems.filter { s -> stems.none { it != s && s.startsWith(it) } }
            .map { stem ->
                Series(
                    name = displayName(stem),
                    cards = keyed.filter { (_, k) -> k.startsWith(stem) }.map { it.first }.sortedWith(compareBy({ it.year }, { it.title })),
                )
            }
            .sortedWith(compareByDescending<Series> { it.cards.size }.thenBy { it.name })
    }

    /** A stem as a name to read: "the Garden of sinners Chapter" is the Garden of sinners. */
    private fun displayName(stem: String): String =
        stem.trim().replace(TRAILING_PART_WORD, "").trim(' ', '-', '–', ':', '：', ',', '，').ifBlank { stem.trim() }

    private val TRAILING_PART_WORD = Regex("""(?i)\s+(?:chapter|part|vol|volume|season|episode|ep|no)\.?$""")

    /**
     * Where [target] stands in its [series] (as [of] lists it, without the target): the index of the
     * first card that came out after it — [series]'s size when it is the latest.
     */
    fun indexAfter(target: MediaGroup, series: List<MediaGroup>): Int =
        series.indexOfFirst { compareValuesBy(it, target, { c -> c.year }, { c -> c.title }) > 0 }.let { if (it < 0) series.size else it }

    /** What comes next in [target]'s series that has not been seen — 黑客帝国2 after 黑客帝国 — or null. */
    fun nextAfter(target: MediaGroup, library: List<MediaGroup>): MediaGroup? {
        val series = of(target, library)
        return series.drop(indexAfter(target, series)).firstOrNull { WallFilter.watchOf(it) != WallFilter.Watch.WATCHED }
    }

    /** A card's name up to its number, colon or first space — the part a series shares; null if too short to mean one. */
    internal fun keyOf(card: MediaGroup): String? = card.year?.let { keyOf(card.title) }

    internal fun keyOf(title: String): String? {
        val t = title.trim()
        val cjk = t.firstOrNull()?.let(::isCjk) == true
        val key = t.takeWhile { c -> !c.isDigit() && c !in STOPS && !(cjk && c.isWhitespace()) }.trim()
        return key.takeIf { if (cjk) it.count(::isCjk) >= 2 else it.length >= 4 }
    }

    /** [title] numbers itself right after [stem]: 黑客帝国2, 攻壳机动队2 无罪, 机动战士高达00. */
    private fun numbered(title: String, stem: String): Boolean {
        val after = title.trim().removePrefix(stem).trimStart()
        return after.isNotEmpty() && after.length < title.trim().length && (after[0].isDigit() || after[0] in CJK_NUMERALS)
    }

    private fun isCjk(c: Char): Boolean = Character.UnicodeScript.of(c.code).let {
        it == Character.UnicodeScript.HAN || it == Character.UnicodeScript.HIRAGANA || it == Character.UnicodeScript.KATAKANA
    }

    private const val STOPS = "：:·・（(【[《「—–_,，."
    private const val CJK_NUMERALS = "一二三四五六七八九十"
}
