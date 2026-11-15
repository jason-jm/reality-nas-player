package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import java.text.Normalizer

/** How the poster wall is ordered. Its words and icons are each app's own (LibraryLabels). */
enum class LibSort { RECENT, TITLE, RATING, YEAR, WATCHED }

/**
 * What a card can be found by.
 *
 * A headset keyboard is slow, and the titles are mostly Chinese and Japanese: the search has to
 * answer to the first letters of the pinyin (`djsy` → 刀剑神域) and to romaji (`sodoato` →
 * ソードアート・オンライン) as readily as to the characters themselves, and to the original title,
 * the folder the user filed it under and the cast. Keys are built once per card at grouping time;
 * a query is normalised the same way (case, spaces, punctuation all dropped) and matched by
 * containment, so `sword art` finds "Sword Art Online" and `刀剑 神域` finds 刀剑神域.
 */
object SearchKeys {
    private val STRIP = Regex("""[\s\p{P}\p{S}]+""")
    private val CJK = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}]""")
    private val MARKS = Regex("""\p{M}+""")
    private val WORD_GAP = Regex("""[\s'\-]+""")

    fun hasCjk(s: String): Boolean = CJK.containsMatchIn(s)

    /** Text a Latin keyboard cannot type: CJK, and Cyrillic, Greek or Thai (romanised for the search as well). */
    fun needsRomanizing(s: String): Boolean = CJK.containsMatchIn(s) || OTHER_SCRIPTS.containsMatchIn(s)

    private val OTHER_SCRIPTS = Regex("""[\p{IsCyrillic}\p{IsGreek}\p{IsThai}]""")

    /**
     * Lowercased, NFC, with whitespace, punctuation and symbols removed — and accents off Latin, Greek and Cyrillic
     * letters, on the card's names and on the query alike: `amelie` finds Amélie, `strasse` Straße, `елки` Ёлки,
     * `ψυχω` Ψυχώ, `istanbul` İstanbul. Kana keep their voicing and Hangul its syllables.
     */
    fun normalize(s: String): String {
        val lower = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase()
        if (lower.all { it.code < 0x80 }) return lower.replace(STRIP, "")
        val bare = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(FOLDED_MARKS, "")
        val sb = StringBuilder(bare.length)
        for (ch in bare) sb.append(KEYBOARD[ch] ?: ch.toString())
        return Normalizer.normalize(sb, Normalizer.Form.NFC).replace(STRIP, "")
    }

    private val FOLDED_MARKS = Regex("""(?<=[\p{IsLatin}\p{IsGreek}\p{IsCyrillic}])\p{Mn}+""")

    /** Letters with no decomposition, as keyboards spell them. */
    private val KEYBOARD = mapOf(
        'ø' to "o", 'ł' to "l", 'đ' to "d", 'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ı' to "i", 'þ' to "th", 'ð' to "d",
        'ς' to "σ",
    )

    /** The romanisation with accents and spaces dropped, plus the initials of its syllables. */
    private fun romanForms(romanized: String): List<String> {
        val ascii = Normalizer.normalize(romanized, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
        val words = ascii.split(WORD_GAP).map { w -> w.filter(Char::isLetterOrDigit) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return buildList {
            add(words.joinToString(""))
            if (words.size >= 2) add(words.joinToString("") { it.first().toString() })
        }
    }

    /** The plain keys for a card named by [names] — no romanisation, cheap enough for first paint. */
    fun plain(names: Iterable<String>): List<String> =
        names.map(::normalize).filter { it.isNotEmpty() }.distinct()

    /** [keys] plus the romanised forms of the CJK [titles]; [romanize] returns null where it cannot. */
    fun withRomanized(keys: List<String>, titles: Iterable<String>, romanize: (String) -> String?): List<String> {
        val out = LinkedHashSet(keys)
        for (raw in titles) {
            if (needsRomanizing(raw)) romanize(raw)?.takeIf { it.isNotBlank() }?.let { out += romanForms(it) }
        }
        // …and every name in simplified Han ([HanFold]) — the query is folded too, so 「宫崎骏」 finds
        // a card that credits 宮崎駿 and 「进击」 the one called 進撃の巨人.
        for (k in keys) HanFold.fold(k).let { if (it != k) out += it }
        return out.toList()
    }

    /** The keys for a card named by [names], romanised where CJK — the two steps above at once. */
    fun of(names: Iterable<String>, romanize: (String) -> String?): List<String> =
        withRomanized(plain(names), names, romanize)

    fun matches(keys: List<String>, query: String): Boolean = Query(query).matches(keys)

    /**
     * A query made ready once for a whole library's cards: normalised and folded a single time, not
     * once a card — 909 times a keystroke, on the main thread, was most of a search's 15-20 ms.
     */
    class Query(raw: String) {
        /** As typed, trimmed: what a title or a file name is looked through for, case aside. */
        val text: String = raw.trim()
        /** As the keys are: normalised ([normalize]). */
        val normalized: String = normalize(raw)
        /** …and in simplified Han ([HanFold]). */
        val folded: String = HanFold.fold(normalized)

        fun matches(keys: List<String>): Boolean =
            normalized.isEmpty() || keys.any { it.contains(normalized) || it.contains(folded) }

        /** Whether one of [keys] other than [people]'s begins with the query: the card's own name, begun. */
        fun begins(keys: List<String>, people: Collection<String> = emptyList()): Boolean =
            normalized.isNotEmpty() && keys.any { k -> (k.startsWith(normalized) || k.startsWith(folded)) && k !in people }
    }

    /** The title as it sorts A→Z: pinyin / romaji for a CJK title, else the title itself. */
    fun titleKey(title: String, romanize: (String) -> String?): String {
        val roman = if (hasCjk(title)) romanize(title)?.takeIf { it.isNotBlank() } else null
        val base = roman ?: title
        return Normalizer.normalize(base, Normalizer.Form.NFD).replace(MARKS, "").lowercase().trim()
    }
}

/** The wall's orderings. */
object LibrarySort {
    /** Which site's score stands for the card, in order of preference. */
    private val ORDER = listOf(
        RatingSource.IMDB, RatingSource.DOUBAN, RatingSource.TMDB, RatingSource.BANGUMI,
        RatingSource.MAL, RatingSource.ANILIST, RatingSource.METACRITIC, RatingSource.ROTTEN_TOMATOES,
    )
    private val NUMBER = Regex("""\d+(?:\.\d+)?""")

    /** Fewer votes than this and a score says nothing — TMDB gives a concert video with one vote a 10.0. */
    private const val MIN_VOTES = 20

    private fun numeric(r: RatingScore): Double? {
        val num = NUMBER.find(RatingScore.stored(r.value))?.value?.toDoubleOrNull() ?: return null
        return if (r.value.contains('%') || num > 10) num / 10 else num
    }

    /**
     * Is a score worth ordering by? Not on too few votes where the count is known; and not a
     * lone TMDB score of 9.5+ where it isn't (a library scraped before counts were kept) — the
     * films that score that high on TMDB alone are the ones one or two people rated.
     */
    private fun reliable(r: RatingScore, all: List<RatingScore>): Boolean {
        r.votes?.let { return it >= MIN_VOTES }
        // No count of its own (IMDb's never has one here): when the other sites' counts are all
        // known to be tiny, so is its audience. A concert video IMDb rated 9.3 was one TMDB had on
        // 19 votes, a violin lesson 9.0 on one — and they headed the 评分 order above 黑暗骑士.
        val counted = all.mapNotNull { it.votes }
        if (counted.isNotEmpty() && counted.max() < MIN_VOTES) return false
        if (r.source == RatingSource.TMDB && all.all { it.source == RatingSource.TMDB }) {
            return (numeric(r) ?: 0.0) < 9.5
        }
        return true
    }

    /** One comparable score on a 0–10 scale — "8.2" as is, "91%" and Metacritic's "74" as 9.1 / 7.4. */
    fun ratingValue(ratings: List<RatingScore>): Double = ratingFor(ratings)?.let(::numeric) ?: 0.0

    /** The score 评分 orders a card by — the most trusted site's that has enough votes — or null for none. */
    fun ratingFor(ratings: List<RatingScore>): RatingScore? {
        val usable = ratings.filter { reliable(it, ratings) }
        return ORDER.firstNotNullOfOrNull { src -> usable.firstOrNull { it.source == src } }
    }

    private fun addedAt(g: MediaGroup): Long = g.items.maxOf { it.addedAt }
    private fun watchedAt(g: MediaGroup): Long = g.items.maxOf { it.progress.updatedAt }

    /** Did any database name this card, or is its title just a file name? */
    fun identified(g: MediaGroup): Boolean = g.items.any { it.scrapeStatus == ScrapeStatus.MATCHED }

    fun apply(groups: List<MediaGroup>, sort: LibSort): List<MediaGroup> = when (sort) {
        LibSort.RECENT -> groups.sortedByDescending(::addedAt)
        // A→Z puts the works first and the files nobody could name after them. A library with an
        // untidy corner opened on "00 Footage", "01-Pick", "03-BROLL Extra" — file names standing in
        // for titles, alphabetically ahead of everything a database knows.
        LibSort.TITLE -> if (TitleOrder.byPinyin()) {
            groups.sortedWith(
                compareBy<MediaGroup> { !identified(it) }
                    .thenBy { it.titleKey.isEmpty() }.thenBy { it.titleKey }.thenBy { it.title.lowercase() },
            )
        } else {
            // Every other reader: their language's collation over the titles themselves (TitleOrder).
            val collator = TitleOrder.collator()
            val keys = groups.associateWith { collator.getCollationKey(TitleOrder.sortName(it.title)) }
            groups.sortedWith(
                compareBy<MediaGroup> { !identified(it) }.thenBy { it.title.isBlank() }
                    .thenComparator { a, b -> keys.getValue(a).compareTo(keys.getValue(b)) },
            )
        }
        LibSort.RATING -> groups.sortedWith(
            compareByDescending<MediaGroup> { ratingValue(it.ratings) }.thenByDescending(::addedAt),
        )
        LibSort.YEAR -> groups.sortedWith(
            compareBy<MediaGroup> { it.year == null }.thenByDescending { it.year ?: 0 }.thenByDescending(::addedAt),
        )
        LibSort.WATCHED -> groups.sortedWith(
            compareByDescending<MediaGroup>(::watchedAt).thenByDescending(::addedAt),
        )
    }
}
