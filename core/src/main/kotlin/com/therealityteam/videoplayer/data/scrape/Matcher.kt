package com.therealityteam.videoplayer.data.scrape

import kotlin.math.abs

/**
 * Step ③: pick the best metadata candidate for a parsed filename by blending title
 * similarity with year proximity, the provider's own relevance rank, and popularity.
 *
 * Popularity + rank disambiguate near-duplicate titles (e.g. "Your Name." pop 24.9 vs the
 * obscure "What's Your Name?" pop 0.0), which title similarity alone gets wrong. Returns null
 * if nothing clears the bar — a wrong match (wrong poster) is worse than none.
 */
object Matcher {

    data class Scored(val result: MetaResult, val score: Double)

    private const val ACCEPT_THRESHOLD = 0.55

    /** Popularity at/above this counts as "clearly the well-known title" (full pop bonus). */
    private const val POPULAR = 20.0

    /** @param rank the candidate's index in the provider's relevance-ordered results (0 = top). */
    fun score(parsed: ParsedName, candidate: MetaResult, rank: Int = 0): Double {
        var titleSim = StringSimilarity.titleSimilarity(parsed.title, candidate.title)
        var simLen = candidate.title.trim().length
        // The original title, and the English one a reader of another language's search carries (Der Pate is
        // also The Godfather; a German shelf has both kinds of file name).
        listOfNotNull(candidate.originalTitle, candidate.englishTitle).forEach {
            val s = StringSimilarity.titleSimilarity(parsed.title, it)
            if (s > titleSim) {
                titleSim = s
                simLen = it.trim().length
            }
        }
        // A very short name is contained in almost any longer one, and Jaro's m/|query| term is 1.0
        // whenever it is: "明治" scores 0.49 against 浪客剑心－明治剑客浪漫谭－, which with the rank and
        // popularity bonuses cleared the bar and filed five NHK documentaries under Rurouni Kenshin.
        // What separates that from a real hit is WHERE the name sits: a candidate that BEGINS with
        // the query is the same work carrying a subtitle or a second language ("东京教父 Tokyo
        // Godfathers"), while a query buried in the middle of a longer name is a coincidence.
        val nq = StringSimilarity.normalizeTitle(parsed.title)
        if (nq.length in 2..4 && simLen > nq.length * 3) {
            val opens = namesOf(candidate).any { StringSimilarity.normalizeTitle(it).startsWith(nq) }
            if (!opens) titleSim = 0.0
        }
        val yearScore = when {
            parsed.year == null || candidate.year == null -> 0.0
            else -> when (val d = abs(parsed.year - candidate.year)) {
                0 -> 0.30
                1 -> 0.10
                else -> -0.20 * d
            }
        }
        val rankScore = when (rank) {
            0 -> 0.12
            1 -> 0.06
            2 -> 0.03
            else -> 0.0
        }
        // Absolute (not candidate-relative) so a field of obscure junk gets no pop bonus.
        val popScore = (candidate.popularity / POPULAR).coerceIn(0.0, 1.0) * 0.15
        // Cross-language titles (esp. anime: a romaji query like "Shingeki no Kyojin" vs the
        // localized name "进击的巨人" / original "進撃の巨人") score low on string similarity even
        // when correct. Trust the provider for a specific query that returns this as its popular,
        // top-ranked hit — TMDB matched it via an alternative title we can't see here.
        // …but only ACROSS scripts. In the same script a low similarity is a coincidence, not a
        // translation: "Conversations with History Robert O Keohane" shares one word with "Chance,
        // History, Art...", and rank, popularity and this bonus carried it over the bar.
        val specific = parsed.title.trim().length >= 5
        val crossScript = hasCjk(parsed.title) != namesOf(candidate).any(::hasCjk)
        val aliasBonus = if (rank == 0 && specific && titleSim < 0.5 && candidate.popularity >= POPULAR && crossScript) 0.22 else 0.0
        // With no year to confirm it, a same-script hit is carried by its NAME or by nothing: the
        // character blend above gives two long titles that share letters 0.5 and more, so the words
        // have to agree as well. Half credit otherwise — rank and popularity never make a match.
        val unconfirmed = yearScore <= 0.0 && !crossScript && !(titleSim >= 0.5 && namesAgree(parsed.title, candidate))
        return if (unconfirmed) titleSim * 0.5 else titleSim + yearScore + rankScore + popScore + aliasBonus
    }

    /** How far behind an answer without an unasked number may be and still be preferred. */
    private const val UNASKED_NUMBER = 0.15

    /** The names a hit answers to: its title, its original title, and the English one when the search carried it. */
    private fun namesOf(candidate: MetaResult): List<String> =
        listOfNotNull(candidate.title, candidate.originalTitle, candidate.englishTitle)

    /** A sequel's number: one to three digits (a year is the year score's business) or II to IX. */
    private val NUMBER = Regex("""\d{1,3}|ii|iii|iv|vi|vii|viii|ix""")
    private val ROMAN = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9")

    private fun numbersOf(name: String): Set<String> = StringSimilarity.normalizeTitle(name).split(' ')
        .filter { NUMBER.matches(it) }.map { ROMAN[it] ?: it.trimStart('0').ifEmpty { "0" } }.toSet()

    /** Does every name of [candidate] carry a number that [query] does not? */
    fun unaskedNumber(query: String, candidate: MetaResult): Boolean {
        val asked = numbersOf(query)
        val names = namesOf(candidate).filter { it.isNotBlank() }
        return names.isNotEmpty() && names.all { (numbersOf(it) - asked).isNotEmpty() }
    }

    private val STOP = setOf("the", "a", "an", "of", "and", "with", "in", "on", "to", "for", "at", "by", "o", "de", "la", "le", "no")

    /** A name's content words, as [namesAgree] counts them: normalized, without "the", "of"… */
    fun contentWords(name: String): Set<String> =
        StringSimilarity.normalizeTitle(name).split(' ').filter { it.isNotBlank() && it !in STOP }.toSet()

    /**
     * Do the two names say the same thing, word for word: at least half of the shorter side's
     * content words in the other. A name with no spaces (CJK) agrees when one contains the other
     * or they share most of their characters.
     */
    fun namesAgree(query: String, candidate: MetaResult): Boolean {
        val nq = StringSimilarity.normalizeTitle(query)
        return namesOf(candidate).any { c ->
            val nc = StringSimilarity.normalizeTitle(c)
            val qw = contentWords(query)
            val cw = contentWords(c)
            when {
                // A name with no spaces (CJK) must be CONTAINED — 美国魂 shares three of 美国招魂's four
                // characters and is a different film — and a short name inside a much longer title is
                // no more than a shared phrase: 金融危机 is in 坑爹：金融危机真相, and is not it. Overlap
                // only stands in for long names.
                // The extra length that counts is in the candidate's own script: 东京教父 Tokyo
                // Godfathers is 东京教父 with its English name beside it, and agrees.
                qw.size <= 1 || cw.size <= 1 -> nq.contains(nc) ||
                    (nc.contains(nq) && nc.replace(LATIN_RUN, "").length <= nq.length + 3) ||
                    // …and character overlap only between long names, and mostly: 美国为何而战 shares
                    // six of 我们为何而战：美国的参战's ten characters and is a different film.
                    (nq.length >= 8 && nc.length >= 8 && StringSimilarity.charOverlap(nq, nc) >= 0.7)
                // Half the shorter name's words, and never just ONE of two: Conversations with
                // History and Chance, History, Art... share "history" and nothing else. A one-word
                // side (Léon / Léon: The Professional) is judged by that one word.
                else -> {
                    val shared = (qw intersect cw).size
                    val shorter = minOf(qw.size, cw.size)
                    shared * 2 >= shorter && (shorter < 2 || shared >= 2)
                }
            }
        }
    }

    /** Latin letters, digits and spaces — what a bilingual title adds beside its CJK name. */
    private val LATIN_RUN = Regex("""[a-z0-9\s'’:\-]+""")

    private fun hasCjk(s: String) = s.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF || it.code in 0xAC00..0xD7AF }

    fun ranked(parsed: ParsedName, candidates: List<MetaResult>): List<Scored> =
        candidates.mapIndexed { i, c -> Scored(c, score(parsed, c, i)) }.sortedByDescending { it.score }

    /** How many of a search's hits are worth asking for their other names: each is a request. */
    const val ALTERNATIVE_CANDIDATES = 3

    /**
     * The hit a search returned that is called, by one of its OTHER names, exactly what the file
     * says, when none answers by its own: TMDB files Andor as 「Andor」 and returns it for 「Star Wars
     * Andor」 through the alternative title 「Star Wars: Andor」, which a search result does not carry,
     * so the name alone scored 0.22 and a season of the show went unmatched.
     *
     * Equality, once normalized, and not the blended score [best] uses: an entry has dozens of other
     * names in every language, and scoring a query against all of them finds a coincidence somewhere
     * — 「最后-Final4」 reached a comedy through its alternative title 「Friday 4」, across scripts,
     * where the words-agree guard does not apply. Every right answer measured was an exact one. A year
     * the file gives must agree to within one. [alternatives] holds the names per provider id for the
     * hits worth asking about ([alternativeCandidates]); the first such hit in the search's own
     * order is taken.
     */
    fun bestByAlternative(
        parsed: ParsedName,
        candidates: List<MetaResult>,
        alternatives: Map<Int, List<String>>,
    ): MetaResult? {
        val nq = StringSimilarity.normalizeTitle(parsed.title)
        if (nq.replace(" ", "").length < 3) return null
        return candidates.firstOrNull { c ->
            val yearAgrees = parsed.year == null || c.year == null || abs(parsed.year - c.year) <= 1
            yearAgrees && alternatives[c.providerId].orEmpty().any { StringSimilarity.normalizeTitle(it) == nq }
        }
    }

    /** [bestByAlternative], every such hit and not the first: the caller has a guard the first may fail. */
    fun allByAlternative(
        parsed: ParsedName,
        candidates: List<MetaResult>,
        alternatives: Map<Int, List<String>>,
    ): List<MetaResult> {
        val nq = StringSimilarity.normalizeTitle(parsed.title)
        if (nq.replace(" ", "").length < 3) return emptyList()
        return candidates.filter { c ->
            val yearAgrees = parsed.year == null || c.year == null || abs(parsed.year - c.year) <= 1
            yearAgrees && alternatives[c.providerId].orEmpty().any { StringSimilarity.normalizeTitle(it) == nq }
        }
    }

    /** The hits of a search worth asking for their other names: the best-scored few. */
    fun alternativeCandidates(parsed: ParsedName, candidates: List<MetaResult>): List<MetaResult> =
        ranked(parsed, candidates).take(ALTERNATIVE_CANDIDATES).map { it.result }

    /**
     * The best answer that clears the bar — except that an answer carrying a number in every name it
     * has and in none of the query's words is a sequel the file did not ask for, and loses to an
     * acceptable answer without one within [UNASKED_NUMBER] of it: `Kidou Keisatsu Patlabor The
     * Movie` scored Patlabor 2: The Movie 0.76 over Patlabor: The Movie 0.72 on rank and popularity
     * alone, and a remaster of the first film was filed as the second. It only ever chooses between
     * answers that clear the bar: a lone numbered answer stands, as the 2008 Sound Horizon concert
     * 「Triumph of Territorial Expansion II」 does for a folder that says 第二次 where the query cannot.
     */
    fun best(parsed: ParsedName, candidates: List<MetaResult>): MetaResult? {
        val acceptable = ranked(parsed, candidates).filter { it.score >= ACCEPT_THRESHOLD }
        val top = acceptable.firstOrNull() ?: return null
        if (!unaskedNumber(parsed.title, top.result)) return top.result
        return acceptable.firstOrNull { !unaskedNumber(parsed.title, it.result) && top.score - it.score <= UNASKED_NUMBER }
            ?.result ?: top.result
    }
}
