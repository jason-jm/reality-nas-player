package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.data.model.MediaKind
/**
 * The guard that decides whether a database hit really is the thing we searched for.
 *
 * Lives apart from [ScrapeEngine] so the identity probe (see `eval/README.md`) can replay the exact
 * accept/reject decision against the live API without constructing the whole pipeline.
 */
object AnimeMatch {

    /** How many of AniList's relevance-ordered results to consider before giving up on a query. */
    // The whole page. AniList buries a franchise's own S1 under its spin-offs ("Love Live!" →
    // S1 ranks 9th), and [corresponds] is what filters noise — a smaller window here just
    // re-introduces the truncation the wider page exists to fix.
    private const val TOP_N = 12

    /**
     * The best hit for [query] among [results], or null when none of them really is that work.
     *
     * AniList orders by its own relevance, and a CM / short / spin-off entry regularly outranks the
     * work itself ("EVA" → Petit Eva: Evangelion@School, "G之复国运动剧场版" → minimini TVCM). Looking
     * only at the first result therefore both accepted junk and threw away queries whose right
     * answer sat one row down, so consider the top few and take the most popular one that passes
     * [corresponds] — same popularity tie-break the TMDB matcher uses.
     */
    fun pick(
        query: String,
        results: List<MetaResult>,
        season: Int? = null,
        wantMovie: Boolean = false,
        pathForm: String? = null,
        filmNumber: Int? = null,
    ): MetaResult? {
        val corresponding = results.take(TOP_N).filter { corresponds(query, it) }
        // A box of numbered films, and a file numbered like an episode: the Nth file is the Nth
        // film. Decided HERE, before the instalment filter below, which would otherwise drop every
        // Border but the first as "a sequel the query did not ask for" — the query is the box's
        // name and asks for none of them by number; the file does.
        if (season == null && filmNumber != null) numberedFilm(query, corresponding, filmNumber)?.let { return it }
        val franchise = corresponding
            // An entry whose own name carries an instalment number the query does not is a
            // DIFFERENT work, not a worse spelling of this one — so it is dropped, not merely ranked
            // low. [unaskedSequel] already said as much but only as a tie-break among survivors, and
            // a tie-break decides nothing when the sequel is the ONLY survivor: AniList answers
            // "機動警察パトレイバー the Movie" with exactly one entry, the SECOND film, and the first
            // film's own card went to it. Returning nothing here is the better failure — the next
            // query gets its turn, and an unscraped card is honest where a wrong one is silent.
            //
            // Never when the path numbers the work itself: a season, or a query that carries the
            // number, is the case [seasonFit] and [byAirDate] exist to decide. Nor when the entry
            // IS what was asked for — "Natsume Yuujinchou Shi" names season four in a word rather
            // than a digit, and the entry that answers it to the letter still carries "Season 4" in
            // its English title. An exact title outranks any number read off a different one.
            .let {
                if (season != null) it
                else it.filterNot { r -> specificity(query, r) < 3 && unaskedSequel(query, r) }
            }
        // …unless the works themselves are called that. 「逮捕しちゃうぞ Special」 is the name of a
        // 1999 series, not a note about its form, and reading it as one preferred a 2008 one-shot
        // whose AniList format happens to be SPECIAL over the exact title the search returned first.
        val wantForm = (formOf(query) ?: pathForm)
            ?.takeUnless { want -> franchise.any { namesItsOwnForm(it, want) } }
        return byAirDate(franchise, season, wantMovie)
            ?: franchise.maxWithOrNull(
                compareBy(
                    { formFit(wantMovie, it) },
                    { seasonFit(season, it) },
                    { formatFit(wantForm, it) },
                    { specificity(query, it) },
                    { coverage(query, it) },
                    { if (unaskedSequel(query, it)) 0 else 1 },
                    { it.popularity },
                ),
            )
    }

    /**
     * Does the FILE's own name claim a work this entry has never heard of?
     *
     * A tie-in that ships inside a series' box is answered by no database under its own name: every
     * query for `Sword Art Online -Hollow Fragment-`, `Turn A Gundam Movie II Moon Butterfly` or
     * `zx end of evangelion` comes back empty. The search then falls through to a BROADER query —
     * the folder, the base title — which answers with the umbrella work, and its identity is applied
     * to a file that is not it. 33 SAO game reels, Guilty Crown's 4-koma shorts, Lain's production
     * footage, Macross F's サヨナラノツバサ and Cowboy Bebop's film all went that way.
     *
     * The residue gives it away: the words the FILE calls itself by that the ENTRY does not know.
     * One such word is a spelling difference and is ignored; two are a name of something else.
     * Release words, episode tags and 特典 markers are not evidence of anything, and neither is a
     * version of the same work — a director's cut of Evangelion is still Evangelion.
     *
     * Measured over a 4,804-file anime shelf: 91 files, and refusing their matches took pairwise F1
     * from 0.9909 to 0.9966 and IoU@0.9 from 0.9779 to 0.9807 across the whole library.
     */
    fun namesAnotherWork(ownTitle: String, r: MetaResult): Boolean = residueOf(ownTitle, r).size >= 2

    /** The words in [ownTitle] that [r] does not account for — the evidence [namesAnotherWork] weighs. */
    fun residueOf(ownTitle: String, r: MetaResult): List<String> =
        residueOf(ownTitle, listOfNotNull(r.title, r.originalTitle, r.romajiTitle, r.englishTitle) + r.synonyms)

    /** The same question asked of a plain set of names — what a sibling file on a card is called. */
    fun residueOf(ownTitle: String, names: Collection<String>): List<String> {
        val theirs = names
            .flatMap { StringSimilarity.normalizeTitle(it).split(' ') }
            .filter { it.isNotBlank() }
            .toSet()
        if (theirs.isEmpty()) return emptyList()
        // Markers come out as PHRASES before tokenising. `Code Geass R2 Picture Drama 9.34` names
        // the FORM of a 特典, not a work, and neither `picture` nor `drama` is a marker on its own —
        // a test asked one word at a time cannot see a marker written as two.
        val mine = StringSimilarity.normalizeTitle(ContentClassifier.withoutMarkers(ownTitle)).split(' ')
        // Extra words are evidence only when the REST of the name agrees. With no word in common
        // there is nothing to have added them to: `浪客剑心 追忆篇` shares not one token with
        // `Rurouni Kenshin OVA: Trust & Betrayal`, which is the very same work under a Chinese name,
        // and `Lucky Star` shares none with `Lucky☆Star` because a ☆ is not ASCII punctuation and so
        // never became a space. Judged by residue alone, both read as another work.
        if (mine.none { it.length > 1 && it in theirs }) return emptyList()
        return mine.filter { w ->
            w.length > 2 && w !in theirs && !AnimeQueries.isNoiseToken(w) && !VERSION_WORD.matches(w)
        }
    }

    /** A word that names a VERSION of a work rather than another work. */
    private val VERSION_WORD = Regex(
        """(?i)directors?|cut|uncut|uncensored|version|edition|renewal|proper|repack|clean|ops|eds|""" +
            // `remaster` belongs with `edition` and `renewal`, and its absence cost a card: the
            // residue of `Mobile Suit Gundam SEED Destiny HD Remaster - 03 - - kewenyu` against the
            // entry for that very series was [remaster, kewenyu] — an uploader's signature and an
            // edition, two words, which is the threshold — so the correct match was refused and 88
            // files took the entry for SEED instead.
            """remaster(?:ed)?|restored|""" +
            """remux|complete|collection|disc|box|vol|part|chapter""",
    )

    /**
     * The FORM a query names — `R O D OVA`, `機動警察パトレイバー OVA`, `Patlabor TV`. The database
     * answers such a query with the whole franchise, TV series first by popularity, and nothing
     * below told the OVA apart from the series it shares a name with: R.O.D's OVA volumes landed
     * on the TV entry. Ranked above [specificity], since a title rarely spells its own form.
     */
    private val FORM_WORD = Regex("""(?i)(?<![A-Za-z])(OVA|OAD|OAV|ONA|SPECIAL|TV)(?![A-Za-z])""")

    private fun formOf(query: String): String? =
        when (FORM_WORD.find(query)?.groupValues?.get(1)?.uppercase()) {
            "OVA", "OAD", "OAV" -> "OVA"
            "ONA" -> "ONA"
            "SPECIAL" -> "SPECIAL"
            "TV" -> "TV"
            else -> null
        }

    /**
     * Does this entry spell [want] inside its own NAME, where it is part of the title and not a
     * note about the form?
     *
     * Its primary titles only. A synonym carrying the form word is the opposite signal: someone
     * added "ROD OVA" to R.O.D -READ OR DIE- precisely BECAUSE the form tells it from the TV
     * series, and counting it disabled the very hint that tells them apart.
     */
    private fun namesItsOwnForm(r: MetaResult, want: String): Boolean =
        listOfNotNull(r.title, r.originalTitle, r.romajiTitle, r.englishTitle)
            .map { StringSimilarity.normalizeTitle(it) }.filter { it.isNotEmpty() }
            .any { t ->
            FORM_WORD.findAll(t).any { m ->
                when (m.groupValues[1].uppercase()) {
                    "OVA", "OAD", "OAV" -> "OVA"
                    "ONA" -> "ONA"
                    "SPECIAL" -> "SPECIAL"
                    "TV" -> "TV"
                    else -> null
                } == want
            }
        }

    private fun formatFit(want: String?, r: MetaResult): Int = when {
        want == null || r.format == null -> 0
        r.format == want -> 1
        want == "TV" && r.format == "TV_SHORT" -> 1
        else -> 0
    }

    /**
     * A sequel the query did not ask for. `Kidou Keisatsu Patlabor - Movie` names the first film;
     * the second is the more popular entry, and with nothing else to separate them popularity took
     * it. The same put Innocence on `Ghost in the Shell`'s query. An entry whose own titles carry a
     * sequel number the query lacks ranks below one whose titles don't — only as a tie-break after
     * [specificity], so an exact title still wins, and never when the query carries a number itself.
     */
    private val SEQUEL_TOKEN = Regex(
        """(?i)(?:^|[\s:_-])(?:[2-9]|ii|iii|iv|2nd|3rd|4th|second|third|fourth)(?:[\s:_-]|$)|第[二三四五]""",
    )

    /**
     * A number in a title is the work's PLACE IN A SERIES only when it is not part of a name.
     *
     * Normalising turns every punctuation mark into a space, which is what lets `Patlabor 2: the
     * Movie` be read — and also what makes `Guilty Crown: 4-koma Gekijou` and `3-gatsu no Lion`
     * look exactly like it. Two things separate them, and both need the title as written: a number
     * that OPENS a title is what the work is called, and a number hyphenated straight onto the word
     * it counts is a counter. This mattered little while [unaskedSequel] only broke ties; as a
     * filter a wrong yes costs the entry its identity.
     */
    private val COUNTER = Regex("""\d+-\p{L}""")

    private fun namesAnInstalment(raw: String): Boolean {
        val m = SEQUEL_TOKEN.find(StringSimilarity.normalizeTitle(raw)) ?: return false
        return m.range.first > 0 && !COUNTER.containsMatchIn(raw)
    }

    private fun unaskedSequel(query: String, r: MetaResult): Boolean {
        val nq = StringSimilarity.normalizeTitle(query)
        if (nq.any(Char::isDigit) || SEQUEL_TOKEN.containsMatchIn(nq)) return false
        return listOfNotNull(r.title, r.romajiTitle, r.englishTitle).any(::namesAnInstalment)
    }

    /**
     * A film asked for by name should not land on the series it spun off from.
     *
     * AniList gives each film its own entry, and the search returns it — but the TV series is
     * always the more popular hit, so popularity handed it the card. Both 超时空要塞F films landed
     * on the TV entry as `kind=EPISODE`, and the same happened to the EVA and G-Reco films: a whole
     * shelf of separate films collapsed onto whichever series shared their name.
     *
     * Only applied when the NAME marks a film (劇場版 / the Movie), so a series file is unaffected —
     * an OVA is not a MOVIE in AniList's vocabulary and must keep ranking on its own merits.
     */
    private fun formFit(wantMovie: Boolean, r: MetaResult): Int =
        if (wantMovie && r.kind == MediaKind.MOVIE) 1 else 0

    /**
     * A film's own number, as the database writes it: `Border:3`, `Chapter 5`, `Movie 2`, 第七章.
     *
     * A box of films is ripped as if it were a series — `Ghost in the Shell Arise[01]` … `[04]` are
     * four films numbered like four episodes — and the query is then the box's name, which the
     * database answers with ALL its films. Popularity chose among them, so every file became the
     * first: 4 of Arise's, 7 of 空の境界's. Only a keyword-prefixed number counts; a bare trailing
     * digit is a title (`Gundam 00`, `White Album 2`) and a decimal is a version (`2.0`).
     */
    private val FILM_ORDINAL = Regex(
        """(?i)\b(?:border|chapter|part|film|movie|episode|vol(?:ume)?)\s*[:：.#\-]?\s*(\d{1,2})\b""" +
            """|第\s*(\d{1,2}|[一二三四五六七八九十])\s*[章部話话集]""",
    )

    internal fun filmOrdinal(r: MetaResult): Int? =
        listOfNotNull(r.englishTitle, r.romajiTitle, r.originalTitle, r.title).firstNotNullOfOrNull { t ->
            FILM_ORDINAL.find(t)?.let { m ->
                m.groupValues[1].toIntOrNull() ?: m.groupValues[2].let { g ->
                    g.toIntOrNull() ?: "一二三四五六七八九十".indexOf(g).takeIf { it >= 0 }?.plus(1)
                }
            } ?: romanOrdinal(t)
        }

    /**
     * A Roman numeral standing as a word, II to IX, written in capitals: how a set of films numbers
     * itself when no keyword does — `Mobile Suit Zeta Gundam: A New Translation II - Lovers`. Not I,
     * V or X, which are letters of titles as often as numbers (Gundam X, V Gundam, "I" the word).
     * Like [FILM_ORDINAL] it only ever decides inside a SET of numbered films.
     */
    fun romanOrdinal(title: String): Int? =
        ROMAN_ORDINAL.find(java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC))
            ?.let { ROMAN_VALUE[it.value] }

    private val ROMAN_ORDINAL = Regex("""(?<![A-Za-z])(?:VIII|VII|VI|IV|IX|III|II)(?![A-Za-z])""")
    private val ROMAN_VALUE = mapOf("II" to 2, "III" to 3, "IV" to 4, "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9)

    /**
     * The film numbered [n] among a set of numbered films — and only among a SET: one entry that
     * happens to say "Part 2" is a title, not a numbering, and must not catch a file that says 2.
     */
    private fun numberedFilm(query: String, franchise: List<MetaResult>, n: Int): MetaResult? {
        val numbered = franchise
            .filter { it.kind == MediaKind.MOVIE || it.format == "OVA" }
            .mapNotNull { r -> filmOrdinal(r)?.let { it to r } }
        if (numbered.map { it.first }.distinct().size < 2) return null
        // …and only when the films ARE what was asked for. A series can carry a numbered film
        // spin-off in its results — `Soukyuu no Fafner` answers with the 26-episode series AND
        // `Fafner THE BEYOND Part 2/3/4` — and episodes [02]…[04] of the series became Parts 2–4 of
        // the spin-off. Where a series answers the query more specifically than the films do, the
        // file is its episode; a box of films has no such series (空の境界), or one no closer to the
        // name than the films are (Arise's recut is "Arise: Alternative Architecture").
        val filmSpec = numbered.maxOf { specificity(query, it.second) }
        if (franchise.any { it.kind != MediaKind.MOVIE && it.format != "OVA" && specificity(query, it) > filmSpec }) return null
        return numbered.filter { it.first == n }.map { it.second }.minByOrNull { it.providerId }
    }

    /**
     * The season a path names, when the DATABASE does not number it.
     *
     * [seasonFit] can only rank an entry that says which season it is, in some spelling. Plenty of
     * franchises are titled by subtitle instead: AniList calls 零之使魔's second season
     * `Zero no Tsukaima: Futatsuki no Kishi`, with no "2" in any of its titles. Every candidate then
     * scores zero, popularity decides, and season 1 — always the most popular — takes the card.
     * 零之使魔 and 零之使魔2 landed on one card exactly this way, as did 攻壳机动队 S.A.C. and its
     * second season.
     *
     * When nothing in the franchise names a season, its own chronology does: sort by year and take
     * the Nth. Only applied when no candidate carries a season marker (so anything [seasonFit] can
     * decide is still decided there), and only when the franchise actually has that many entries —
     * asking for season 4 of a franchise with two entries means the query was wrong, not the order.
     */
    private fun byAirDate(franchise: List<MetaResult>, season: Int?, wantMovie: Boolean): MetaResult? {
        if (season == null || season < 2) return null
        // `劇場版2` means the second FILM, not the second season — order the films among themselves.
        val pool = if (wantMovie) franchise.filter { it.kind == MediaKind.MOVIE } else franchise
        if (pool.size < season) return null
        if (pool.any { seasonFit(season, it) > 0 }) return null
        // A year is what orders them; an entry without one cannot be placed, so this stays out of it.
        val dated = pool.filter { it.year != null }.sortedWith(compareBy({ it.year }, { it.providerId }))
        // …and only when the years actually SEPARATE them. Seasons air in different years; a set
        // that shares one is a single season's formats — "Zero no Tsukaima: Princesses no Rondo"
        // answers with the TV run, an OVA and a Picture Drama, all 2008, and "the third by year"
        // was then decided by database id and handed season three to the Picture Drama. Where a
        // franchise really does run two cours in one year, the database numbers them and [seasonFit]
        // has already had its say.
        if (dated.map { it.year }.distinct().size != dated.size) return null
        return if (dated.size >= season) dated[season - 1] else null
    }

    /**
     * Whether a hit is the SEASON the path asked for, ranked above everything else.
     *
     * `Mobile Suit Gundam 00 1080P BDRIP/2nd_Season/` cannot say so in its query — AniList answers
     * "Mobile Suit Gundam 00 Season 2" and "…2nd Season" with nothing at all. What it does do is
     * return BOTH seasons for the plain title (2581 and 3927 "…Second Season"), so the season the
     * folder names picks between them. A first season, or a path that names no season, is unaffected.
     */
    private val ORDINAL_WORD = mapOf(2 to "second", 3 to "third", 4 to "fourth", 5 to "fifth")
    private val ROMAN = mapOf(2 to "ii", 3 to "iii", 4 to "iv", 5 to "v")

    internal fun seasonFit(season: Int?, r: MetaResult): Int {
        if (season == null || season < 2) return 0
        val titles = (listOfNotNull(r.title, r.originalTitle, r.romajiTitle, r.englishTitle) + r.synonyms)
        // …including the way a database spells it: AniList's season 2 of Gundam 00 is titled
        // "Second Season", not "2nd Season", and its Roman-numeral siblings write II.
        val word = ORDINAL_WORD[season] ?: "(?!)"
        val roman = ROMAN[season] ?: "(?!)"
        // …and the form where the number simply ENDS the title, which is how a database writes
        // "White Album 2" and 现视研2 — there is no "season" word to look for.
        // …and a bare ordinal — "S.A.C. 2nd GIG" names its season as surely as "2nd Season" does.
        // Without it every candidate scored zero, the air-date order was consulted instead, and a
        // results page that happened to omit season 1 made "the second by year" the recap film.
        val marker = Regex(
            """(?i)(?:^|[\s:_-])(?:season\s*$season|$season(?:nd|rd|th)?\s*season|$word\s*season|""" +
                """第\s*$season\s*[期季]|s$season|$roman|$season(?:nd|rd|th))(?:\b|$)|\s?$season\s*$""",
        )
        return if (titles.any { marker.containsMatchIn(it) }) 1 else 0
    }

    /**
     * How specifically a hit answers the query — ranked ABOVE popularity when picking.
     *
     * Popularity alone made a season query collapse onto its franchise: "Natsume Yuujinchou Shi"
     * accepts season 1 too (the query merely CONTAINS that title), and season 1 is far more popular,
     * so season 4 lost to it. An entry that carries the query as a title, or contains it, is
     * answering what was asked; one whose title is merely a shorter piece of the query is not.
     */
    private fun specificity(query: String, r: MetaResult): Int {
        val nq = StringSimilarity.normalizeTitle(query)
        val cands = (listOfNotNull(r.title, r.originalTitle, r.romajiTitle, r.englishTitle) + r.synonyms)
            .map { StringSimilarity.normalizeTitle(it) }.filter { it.isNotEmpty() }
        return when {
            cands.any { it == nq } -> 3              // this entry IS what was searched
            cands.any { containsWord(it, nq) } -> 2  // a longer official title that includes it
            else -> 1                                // matched some other way (word overlap, shorter form)
        }
    }

    /**
     * Substring containment that respects word boundaries for Latin text.
     *
     * Plain `contains` let a query match INSIDE a word: the folder `甜蜜吻痕 SS Plus` searches
     * "SS Plus" (the Chinese name is dropped by the Latin-run extraction), and "macross plus"
     * contains that as `macro|ss plus` — so Amagami SS+ plus lost to the far more popular
     * Macross Plus. CJK has no word delimiters, so the boundary is only required where the needle
     * actually starts/ends with a Latin letter or digit.
     */
    private fun containsWord(hay: String, needle: String): Boolean {
        if (needle.isEmpty() || needle.length > hay.length) return false
        var i = hay.indexOf(needle)
        while (i >= 0) {
            val startOk = i == 0 || !isWordChar(needle.first()) || !isWordChar(hay[i - 1])
            val end = i + needle.length
            val endOk = end == hay.length || !isWordChar(needle.last()) || !isWordChar(hay[end])
            if (startOk && endOk) return true
            i = hay.indexOf(needle, i + 1)
        }
        return false
    }

    /** Latin letters/digits delimit words; CJK does not (no spaces between characters). */
    private fun isWordChar(c: Char) = c.isLetterOrDigit() && c.code < 0x2E80

    /**
     * Does an AniList hit actually correspond to [query]? AniList's search returns the most popular
     * partial match for ANY input, so an unresolvable query (a Chinese-only name, a typo) comes back
     * as a big unrelated title (Digimon, Black Rock Shooter). Accept only when the query genuinely
     * resembles one of the entry's titles (romaji / english / native / synonyms). Word-set agreement
     * is required for multi-word Latin titles — Jaro-Winkler's prefix boost alone would wave through
     * "Murder Princess" → "Murder Mystery"; CJK / single tokens fall back to character-set overlap.
     */
    fun corresponds(query: String, r: MetaResult): Boolean {
        val nq = StringSimilarity.normalizeTitle(query)
        if (nq.length < 2) return false
        val qWords = nq.split(' ').filter { it.isNotEmpty() }
        val cands = (listOfNotNull(r.title, r.originalTitle, r.romajiTitle, r.englishTitle) + r.synonyms)
            .map { StringSimilarity.normalizeTitle(it) }.filter { it.isNotEmpty() }
        return cands.any { c -> titlePlausible(nq, qWords, c) }
    }

    private val STOP_WORDS = setOf("and", "the", "of", "a", "an", "to", "in", "no", "wa", "ga")

    private fun titlesOf(r: MetaResult): List<String> =
        (listOfNotNull(r.title, r.originalTitle, r.romajiTitle, r.englishTitle) + r.synonyms)
            .map { StringSimilarity.normalizeTitle(it) }.filter { it.isNotEmpty() }

    /**
     * How many of the query's content words an entry's titles account for — after [specificity],
     * before popularity. `zx.end.of.evangelion.renewal.v3` names The End of Evangelion by two of
     * its words and the TV series by one; on popularity alone the series took the file. A short
     * token that prefixes a title word counts as that word (EVA → Evangelion), as in [corresponds].
     */
    private fun coverage(query: String, r: MetaResult): Int {
        val q = StringSimilarity.normalizeTitle(query).split(' ').filter { it.isNotEmpty() && it !in STOP_WORDS }
        if (q.isEmpty()) return 0
        return titlesOf(r).maxOfOrNull { c ->
            val cc = c.split(' ').filter { it.isNotEmpty() }.toSet()
            q.count { w -> w in cc || (w.length in 3..4 && cc.any { it.length > w.length && it.startsWith(w) }) }
        } ?: 0
    }

    private fun titlePlausible(nq: String, qWords: List<String>, c: String): Boolean {
        if (c == nq) return true
        if (nq.length >= 4 && containsWord(c, nq)) return true // base name ⊂ full ("code geass" ⊂ …)
        if (c.length >= 4 && containsWord(nq, c)) return true
        if (qWords.size >= 2) {
            // Function words carry no identity, and a short token that PREFIXES a candidate word is
            // an abbreviation of it: `EVA Death and Rebirth` asks for Neon Genesis Evangelion: Death &
            // Rebirth, and counted as four words of which only two agreed, it was refused — so the
            // film went looking under "Evangelion" alone and landed on the series.
            val q = qWords.filterNot { it in STOP_WORDS }.toSet().ifEmpty { qWords.toSet() }
            val cc = c.split(' ').filter { it.isNotEmpty() && it !in STOP_WORDS }.toSet()
            val inter = q.count { w -> w in cc || (w.length in 3..4 && cc.any { it.length > w.length && it.startsWith(w) }) }
            // (nearly) the same words — rejects "murder princess" vs "murder mystery" (jaccard 0.33)
            if (inter >= q.size || inter.toDouble() / (q + cc).size >= 0.6) return true
        }
        // CJK or single token: character-set overlap (no misleading prefix boost).
        return StringSimilarity.charOverlap(nq, c) >= 0.7
    }

    /**
     * Whether a TMDB entry is the AniList work by name: its original title holds the work's own
     * ([anilistOriginal], the native title) whole — `劇場版 ソードアート・オンライン -オーディナル・
     * スケール-` holds AniList's `ソードアート・オンライン -オーディナル・スケール-`. Never the other way:
     * a TMDB name SHORTER than the work's is the franchise or the whole show — `キノの旅` for the
     * 2003 series AniList calls `キノの旅 -the Beautiful World-` may well be it, but `銀河英雄伝説 Die
     * Neue These` for its first season `邂逅` is not, and neither is a film's series. And TMDB's
     * first answer for Zeta Gundam's first film is the second, so this is asked of every candidate.
     */
    fun sameWorkByName(anilistOriginal: String?, tmdbOriginal: String?): Boolean {
        // Zeta Gundam's Ζ is Greek on AniList and Latin on TMDB for the same film.
        val a = StringSimilarity.normalizeTitle(anilistOriginal ?: return false).replace('ζ', 'z')
        val t = StringSimilarity.normalizeTitle(tmdbOriginal ?: return false).replace('ζ', 'z')
        if (a.length < 2 || t.isEmpty()) return false
        return t == a || t.replace(" ", "").contains(a.replace(" ", ""))
    }

    /**
     * The Chinese name TMDB gives a work, when it gives one: written in Han characters with no kana —
     * TMDB falls back to the original title where it has no translation, and `CLANNAD` or a Japanese
     * title is no Chinese name. The katakana middle dot is Chinese punctuation too (新・福音战士剧场版).
     */
    fun chineseName(tmdbTitle: String?): String? =
        tmdbTitle?.trim()?.takeIf { t -> t.any { it in '一'..'鿿' } && t.none { it in '぀'..'ヿ' && it != '・' } }
}
