package com.therealityteam.videoplayer.data.scrape

/**
 * Step ③ helpers: fuzzy string matching so messy titles still map to the right metadata
 * entry. Pure, dependency-free implementations of Levenshtein and Jaro-Winkler.
 */
object StringSimilarity {

    /** Levenshtein edit distance (rolling two-row DP). */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost,
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /** Levenshtein similarity in 0..1 (1 = identical). */
    fun levenshteinRatio(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    /** Jaro similarity in 0..1. */
    fun jaro(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val matchDistance = maxOf(a.length, b.length) / 2 - 1
        val aMatches = BooleanArray(a.length)
        val bMatches = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val start = maxOf(0, i - matchDistance)
            val end = minOf(i + matchDistance + 1, b.length)
            for (j in start until end) {
                if (bMatches[j] || a[i] != b[j]) continue
                aMatches[i] = true; bMatches[j] = true; matches++; break
            }
        }
        if (matches == 0) return 0.0
        var t = 0.0
        var k = 0
        for (i in a.indices) {
            if (!aMatches[i]) continue
            while (!bMatches[k]) k++
            if (a[i] != b[k]) t += 0.5
            k++
        }
        val m = matches.toDouble()
        return (m / a.length + m / b.length + (m - t) / m) / 3.0
    }

    /** Jaro-Winkler similarity (boosts common prefixes). */
    fun jaroWinkler(a: String, b: String, p: Double = 0.1): Double {
        val j = jaro(a, b)
        var prefix = 0
        val maxPrefix = minOf(4, minOf(a.length, b.length))
        while (prefix < maxPrefix && a[prefix] == b[prefix]) prefix++
        return j + prefix * p * (1 - j)
    }

    /**
     * Punctuation — in every script, not only ASCII.
     *
     * `\p{Punct}` is the POSIX class, which is US-ASCII by definition, and a Japanese release name
     * is full of punctuation that is not. `〜` (U+301C) is the one that cost the most: VCB-Studio
     * and most Japanese rips wrap a subtitle in it, so `Mushoku Tensei S2 ~Isekai Ittara Honki
     * Dasu~` tokenised as `~isekai` and `dasu~` — two words AniList's own entry for that very
     * season appeared not to know, which is exactly the evidence [AnimeMatch.namesAnotherWork]
     * weighs, and it refused the correct match for all 55 files.
     *
     * [\p{S}] joins it for the symbols that behave as punctuation inside a name — `Lucky☆Star`
     * shares not one token with `Lucky Star` without it, which this file already knew.
     */
    private val PUNCT = Regex("""[\p{Punct}\p{P}\p{S}]""")
    private val SPACES = Regex("""\s+""")

    /**
     * The same question, asked over and over: a folder's name once per file inside it, a scraped
     * title once per episode of its show. Grouping a 9.6k-file library spends real seconds here, so
     * the answers are kept. Bounded, and cleared wholesale when full — a library's distinct names
     * number in the tens of thousands, and what matters is that a burst of repeats is cheap.
     *
     * Concurrent because it is: names are warmed on every core before grouping, and the scraper
     * compares titles from its own threads.
     */
    private val normalized = java.util.concurrent.ConcurrentHashMap<String, String>(4096)

    /** Lowercase, strip punctuation, collapse whitespace — for comparing titles. */
    fun normalizeTitle(s: String): String {
        normalized[s]?.let { return it }
        // NFKC, for two families of silent mismatch. Composition: a filename that reaches us over
        // SMB from a macOS host is DECOMPOSED — スペシャル arrives as ス + ヘ + U+309A, never equal to
        // the composed ペ in a literal or a database title. Compatibility: Japanese rips freely mix
        // width — a folder says 鎌倉殿の13人 while its files say 鎌倉殿の１３人 — and the fullwidth
        // １３ is a different string from 13 to every comparison here, which is how a release folder
        // failed to anchor its own recap files. Every caller compares; nothing displays this.
        val out = foldLatin(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).lowercase())
            .replace(PUNCT, " ")
            .replace(SPACES, " ")
            .trim()
        if (normalized.size > 32_768) normalized.clear()
        normalized[s] = out
        return out
    }

    /**
     * Accents off Latin letters: `shōgun` is `shogun`, `amélie` is `amelie`, `pokémon` is `pokemon`.
     * A file name is almost always typed without them and the database spells them, so FX's *Shōgun*
     * (2024) scored as a different word from `Shogun.S01…` — no names-agree, half credit — and lost
     * to a 1990 Japanese series. Only marks that sit on a LATIN letter go: kana keep their voicing
     * (ペ is ヘ with a mark, and バカ is not ハカ), Hangul is recomposed as it was. The few Latin
     * letters with no decomposition are spelled as keyboards spell them (ø o, ß ss, æ ae…).
     */
    private fun foldLatin(s: String): String {
        if (s.all { it.code < 0x80 }) return s
        val decomposed = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(LATIN_MARKS, "")
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) sb.append(LATIN_LETTERS[ch] ?: ch.toString())
        return java.text.Normalizer.normalize(sb, java.text.Normalizer.Form.NFC)
    }

    private val LATIN_MARKS = Regex("""(?<=\p{IsLatin})\p{Mn}+""")
    private val LATIN_LETTERS = mapOf(
        'ø' to "o", 'ł' to "l", 'đ' to "d", 'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ı' to "i", 'þ' to "th", 'ð' to "d",
    )

    /**
     * Character-set Jaccard (0..1). Robust for CJK titles where there are no word tokens and
     * Jaro-Winkler's prefix boost is misleading (両「UFO…」strings overlap only on "UFO"). Spaces and
     * punctuation are dropped before comparing.
     */
    fun charOverlap(a: String, b: String): Double {
        val sa = normalizeTitle(a).filterNot { it == ' ' }.toSet()
        val sb = normalizeTitle(b).filterNot { it == ' ' }.toSet()
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        val inter = sa.intersect(sb).size
        return inter.toDouble() / (sa.size + sb.size - inter)
    }

    /** Blended title similarity (Jaro-Winkler weighted with Levenshtein). */
    fun titleSimilarity(a: String, b: String): Double {
        val na = normalizeTitle(a)
        val nb = normalizeTitle(b)
        if (na == nb) return 1.0
        return 0.6 * jaroWinkler(na, nb) + 0.4 * levenshteinRatio(na, nb)
    }
}
