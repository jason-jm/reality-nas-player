package com.therealityteam.videoplayer.data.scrape

/**
 * Builds the ordered list of search strings [ScrapeEngine] tries when identifying an anime file.
 *
 * Extracted from the engine because query ORDER and query QUALITY are where identification actually
 * goes wrong, and both are pure string work that can be regression-tested without the network:
 * a single signal-less query ("OVA TV", left over from the folder `逮捕令_逮捕しちゃうぞ_OVA.TV.MOVIE`)
 * once fuzzy-matched a random popular show and filed all 221 files of that franchise under it.
 */
object AnimeQueries {

    /**
     * Release/format words that never carry a title on their own. A query made ONLY of these
     * (plus numbers) has no signal — the DB will still return its most popular hit for it.
     */
    private val NOISE_TOKENS = setOf(
        "tv", "ova", "oad", "oav", "ona", "sp", "sps", "special", "specials", "movie", "movies",
        "gekijouban", "劇場版", "剧场版", "season", "seasons", "part", "vol", "volume", "box",
        "bdbox", "bd", "dvd", "bdrip", "dvdrip", "tvrip", "bluray", "blu", "ray", "web", "webrip",
        "hdtv", "raw", "raws", "remux", "menu", "cd", "cds", "scan", "scans", "extra", "extras",
        "bonus", "complete", "全集", "映像特典", "特典", "the", "a", "an", "of", "and",
        "1st", "2nd", "3rd", "4th", "5th", "st", "nd", "rd", "th", "no", "op", "ed", "ncop", "nced",
        "pv", "cm", "disc", "trailer", "preview", "sample", "final", "end", "ending", "opening",
        // …and the words a 特典 file is left with once its marker is stripped (`Eyecatch Collection`,
        // `Menu Vol.03`, `EP24 15sec. Next EP Yokoku`, `Eizou Tokuten`), plus subtitle/audio
        // container tags a parenthetical carries (`BD Hi10P 1440x1080 WV SUP`).
        "collection", "sup", "pgs", "wv", "ape", "tta", "eizou", "tokuten", "next", "yokoku", "spot",
        "spots", "tvcm", "eyecatch", "digest", "teaser", "promo", "making", "interview", "commentary",
        "ver", "u2", "ep", "eps", "episode", "episodes", "main", "cg", "sec",
        "softsub", "softsubs", "hardsub", "hardsubs", "chap", "chapter", "chapters", "fra", "spa", "ger", "ita", "kor", "chs", "cht",
        "disk", "menu", "メニュー", "メニュー画面", "notice", "content", "contents", "trailers", "teasers",
        // Disc-structure folders. A BDMV tree bottoms out in `…/BD_ROM/BDMV/STREAM/00002.m2ts`, so the
        // immediate folder name is "STREAM" — which AniList happily answers with 洛克人EXE STREAM, and
        // the guard accepts because the query is a substring of that title.
        "stream", "bdmv", "bdrom", "rom", "video_ts", "certificate", "playlist", "clipinf", "backup",
        // quality words that survive tokenising a compound tag ("DTS-HD" → "dts" + "hd")
        "hd", "sd", "fhd", "uhd", "4k", "8k", "hdr", "sdr", "dv", "dovi", "hi10p", "ma10p", "10bit",
        "8bit", "dual", "multi", "chs", "cht", "gb", "big5", "jp", "jpn", "eng", "chi", "sub", "subs",
    )

    // `+`, `&`, `/` and parentheses join tags as readily as dots do: `FLACx3+AAC softSub(chi+eng+fra)`.
    // …and so does a colon, full-width included: `softsubs：chi+eng` stayed ONE token, which no
    // release-word test could recognise, and a whole parenthetical of tags therefore read as a title.
    private val SPLIT = Regex("""[\s._\-　+&/()（）:：,，、]+""")

    /** Resolution / codec / container tokens — same idea as [NOISE_TOKENS], but pattern-shaped. */
    private val NOISE_PATTERN = Regex(
        """(?i)^(\d{3,4}[pi]|\d{3,4}x\d{3,4}|x26[45]|h\.?26[45]|hevc|avc|xvid|divx|""" +
            """(hi|ma)?\d{1,2}bits?|hi10p|ma10p|(flac|aac|ac3|dts|opus)x\d|flac|aac|ac3|dts(-?hd)?(ma)?|truehd|atmos|""" +
            """mkv|mp4|avi|rmvb|wmv|m2ts|ts|iso|v\d+|ep\d+|\d+sec|[a-z]{1,2}\d{1,2}|u\d|""" +
            // `S01E07` in one token, and an audio tag with its channel count glued on (`FLAC5`).
            """s\d{1,2}e\d{1,3}|(?:flac|aac|ac3|eac3|dts|ddp?|opus|truehd)\d|""" +
            // A channel count leads with its digit, which the letter-first pattern above cannot see:
            // `少女革命ウテナ 第35話「…」(1080p x264 FLAC 2ch+6ch 10bit chap)` sent
            // `1080p x264 FLAC 2ch+6ch 10bit chap softsubs：chi+eng` as its FIRST query, because two
            // tokens of the parenthetical were not known to be release words.
            """\d{1,2}ch|divx\d*|xvid\d*)$""",
    )

    /** Is this ONE token a release/format word rather than part of a title? */
    fun isNoiseToken(token: String): Boolean {
        val k = token.trim().lowercase()
        return k.isEmpty() || k in NOISE_TOKENS || k.all { it.isDigit() } || NOISE_PATTERN.matches(k)
    }

    /**
     * True when [s] carries no title at all — every token is a release/format word or a number.
     * Such a query must never be searched: DB fuzzy search answers it with an unrelated popular
     * title, and the similarity guard can still pass it on character overlap alone.
     */
    fun isSignalless(s: String?): Boolean {
        val t = (s ?: "").trim()
        if (t.isEmpty()) return true
        val tokens = SPLIT.split(t).map { it.trim('!', '?', '(', ')', ':', '~', '～', ',', '、') }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        // NOTE: deliberately NOT FilenameParser.isJunkTitle here — that treats any 1-char token as
        // junk, which would condemn acronym titles ("R O D", "D C") whose every token is short.
        return tokens.all { tok ->
            val k = tok.lowercase()
            k in NOISE_TOKENS || k.all { it.isDigit() } || NOISE_PATTERN.matches(k)
        }
    }

    /**
     * Ordered candidate queries for one file.
     *
     * FOLDER names come first so a clean season folder ("零之使魔2", "狼与香辛料II") matches its OWN
     * season entry instead of collapsing onto S1 — but only folders that actually name something;
     * a folder that parses down to release words is dropped so the filename's own title gets its
     * turn instead of an unrelated fuzzy hit sticking first.
     */
    fun build(
        path: String,
        parsed: ParsedName,
        anime: AnimeParse.Parsed,
        siblings: Collection<String> = emptyList(),
    ): List<String> = withoutEditions(candidates(path, parsed, anime, siblings)).filterNot(::isSignalless)

    /**
     * Each candidate, followed by the same candidate without its edition marker.
     *
     * Right behind its own source rather than at the end, so the specific-first order every other
     * rule here depends on is untouched: the edition-bearing form is still asked first, and the
     * shorter one only gets its turn when the database has never heard of the edition — which, for
     * `Mobile Suit Gundam SEED Destiny HD Remaster`, is every time.
     */
    private fun withoutEditions(qs: List<String>): List<String> =
        qs.flatMap { q -> listOfNotNull(q, FilenameParser.withoutEdition(q)) }.distinct()

    /**
     * The ordered candidates BEFORE the signal-less guard. Production always goes through [build];
     * this exists so the identity probe can replay what the old behaviour would have searched.
     */
    fun candidates(
        path: String,
        parsed: ParsedName,
        anime: AnimeParse.Parsed,
        siblings: Collection<String> = emptyList(),
    ): List<String> {
        // A 特典 marker in the name (`… Kaikou NCOP`, `… - TV Spots`, `Show Trailer`) is not part of
        // the title, and searched with it the show is never found — the NC opening then fell through
        // to TMDB and formed a card of its own beside the show it belongs to.
        val fileTitle = ContentClassifier.stripMarkers(parsed.title)
        // Anitomy keeps a quoted episode subtitle in its title — `R.O.D －THE TV－ 第07話 ｢藪の中｣` —
        // and beside an episode number that quote is the episode's, not the work's.
        // The same goes for a 特典 clip's quote — `ノンクレジットOP「そのままの君でいて」` names its song.
        val episodic = anime.episode != null || parsed.episode != null
        val quoteIsNotTheName = episodic || ContentClassifier.isStrongExtra(path.substringAfterLast('/'))
        // Anitomy's title is trusted only when it is a title: on a dotted Chinese scene name it gives
        // up part-way and hands back `攻壳机动队 新剧场版 Ghost in the Shell The New Movie 2015 BD1080P` —
        // a year or a resolution inside it means the parser's own reading is the better one.
        val full = anime.title?.takeIf { it.isNotBlank() && !RELEASE_INSIDE.containsMatchIn(it) }
            ?.let { if (quoteIsNotTheName) it.replace(QUOTED_TAIL, "").trim() else it }
            ?.let(ContentClassifier::stripMarkers)?.takeIf { it.isNotBlank() } ?: fileTitle
        // The show folder under the Anime root is the cleanest franchise title (often the only place
        // the title is un-garbled): `…/Anime/银河英雄传说/Legend…002.mkv`.
        val showFolder = FilenameParser.animeShowFolder(path)?.let(::folderTitle)
        // The IMMEDIATE folder holding the file is the most specific title source — a per-movie
        // subfolder inside a collection (`…/宮崎駿全集…/天空の城ラピュタ/file`), which the top show
        // folder ("Miyazaki Hayao All Movies") and the fansub filename both miss.
        // …but not when that immediate folder is season/revision FILING (`2nd_Season/`,
        // `Version 2/`, `特典/`): those name how the files are shelved, not what they are, and
        // "Version 2" as a query is how thirteen Gundam 0083 episodes went looking for season 2.
        val parentFolder = FilenameParser.folderName(path)
            ?.takeIf { !FilenameParser.isSeasonFolder(it) }
            ?.let(::folderTitle)
            ?.takeIf { it != showFolder }
        // fansubTitle reads the real title out of all-bracket names that Anitomy mis-parses
        // ([POPGO][FREEWIND][TALES_OF_PHANTASIA]… → "TALES OF PHANTASIA", not the "FREEWIND" group).
        val fansub = FilenameParser.fansubTitle(path.substringAfterLast('/'), FilenameParser.folderName(path))
            ?.let(ContentClassifier::stripMarkers)?.takeIf { it.isNotBlank() }
        // A release folder often keeps the work's REAL title in a parenthetical while the outside
        // text names the franchise: `Patlabor_機動警察(機動警察パトレイバー the Movie)_MOVIE.U2`,
        // `Taiho_逮捕令(逮捕你喲,逮捕しちゃうぞ)_OVA`. parse() strips parentheses, so the whole box —
        // TV, both OVA runs, three films — searched the franchise name and landed on one entry,
        // collapsing nine works onto one card. Tried FIRST, since it is the most specific thing
        // the path says; a junk parenthetical ("raw,東京毀滅戰") is dropped by the signal-less guard
        // and the accept guard, so this can only add resolution, never take it away.
        // …and read them from the nearest few ancestors, not just the immediate one: a work folder
        // is regularly one level up, above a quality directory (`…(逮捕你喲,逮捕しちゃうぞ)_OVA/DVDRip.AVC/`).
        // …never from a SHELF: `新海诚（Makoto Shinkai）全集` holds the director's name, which AniList
        // answered with his most popular film, and 星之声 was filed under 你的名字。.
        val parenQ = path.replace('\\', '/').split('/').dropLast(1).asReversed().take(3)
            .filterNot(FilenameParser::isShelfFolder)
            .flatMap(::parenTitles).distinct()
        val rawParent = FilenameParser.folderName(path)
        val rawShow = FilenameParser.animeShowFolder(path)
        // A folder that marks the FORM as well as the work names a DIFFERENT entry than the bare
        // title does: AniList answers "機動警察パトレイバー" with the TV series, "機動警察パトレイバー OVA"
        // with the OVA (1289) and "機動警察パトレイバー 劇場版" with the film (1095). The marker is taken
        // from the folder text and appended verbatim, since that is the wording the database matched.
        // Innermost folder first, and never from one that lists SEVERAL forms: the box above
        // 逮捕令's TV run is called `逮捕令_逮捕しちゃうぞ_OVA.TV.MOVIE`, which is an inventory of what
        // it holds. Reading its first word made 97 TV episodes ask for "逮捕しちゃうぞ OVA", find
        // nothing, and fall back onto the bare title — which AniList answers with the OVA.
        val formMark = FilenameParser.folderChain(path).ifEmpty {
            listOfNotNull(rawParentOf(path), FilenameParser.animeShowFolder(path))
        }.firstNotNullOfOrNull { seg -> soleFormMark(seg) }
        // …but only when the parenthetical says no more than the BOX does, so the form is the only
        // thing distinguishing this folder from its siblings. Where the parenthetical already names
        // a specific work, appending the form just breaks a query that works:
        //     "機動警察パトレイバー"            -> the TV series      "機動警察パトレイバー OVA" -> 1289, the OVA
        //     "WXIII 機動警察パトレイバー"      -> 1290, the film      "WXIII 機動警察パトレイバー MOVIE" -> []
        val boxName = normalizeForCompare(FilenameParser.animeShowFolder(path) ?: "")
        val formQ = listOfNotNull(
            parenQ.firstOrNull()
                ?.takeIf { formMark != null && !it.contains(formMark, ignoreCase = true) }
                ?.takeIf { boxName.isNotBlank() && boxName.contains(normalizeForCompare(it)) }
                ?.let { "$it $formMark" },
        )
        // What the PATH says most specifically, in order: the work's own parenthetical + its form,
        // then the parenthetical alone, then the season it explicitly names.
        val folderHead = formQ + parenQ
        // "<franchise> Season N" is SYNTHESIZED: the folder carried a sequel marker the parsed title
        // lost, and a number was put back in its place. It belongs ahead of the bare franchise name,
        // which cannot tell seasons apart at all — but behind anything the file actually SPELLS.
        // `[POPGO][Ghost_in_the_Shell][S.A.C._2nd_GIG]` asked "Ghost in the Shell Season 2" first and
        // AniList answered with SAC_2045's second season, a 2020 series; the name the file spells,
        // "Ghost in the Shell S A C 2nd GIG", is the 2004 one.
        val seasonQ = parentFolder?.let { withLostSequelMarker(rawParent, it) }.orEmpty() +
            showFolder?.let { withLostSequelMarker(rawShow, it) }.orEmpty()
        val folderTail = listOfNotNull(parentFolder, showFolder)
            .flatMap {
                // A trailing bare "1" is SEASON numbering, and no database has a "season 1" entry —
                // the base title IS season 1. `Full Metal Panic! 1 1080P BDRIP/` asked for
                // "Full Metal Panic! 1", whose only hit is the 2018 recap film Boy Meets Girl
                // (115914); "Full Metal Panic!" gives the TV series (71). So the stripped form
                // goes first, and the numbered one stays behind it.
                listOfNotNull(SEASON_ONE.find(it)?.let { m -> it.removeRange(m.range).trim() }) +
                    listOfNotNull(dropRedundantAbbreviation(it), it, AnimeParse.baseTitle(it))
            }
        val folderQ = folderHead + folderTail
        // Fansub/Anitomy filenames keep base-first to fold a single show's cours together — EXCEPT
        // when the name explicitly marks a sequel season ("Sword Art Online II"), where the base
        // form would collapse season 2 onto season 1's entry.
        // A single-word title is the fansub's SHORTHAND for the show — `[HKG&CASO][Seitokai][01]` — and
        // AniList answers it with the most popular show that starts with the word (妄想学生会), not
        // the one the folder holds (学生会的一己之见). A sibling file that spells the name out settles it.
        val fileQ = listOfNotNull(siblingExpansion(full, siblings), fansub, full, fileTitle).flatMap {
            if (hasSequelMarker(it)) listOf(it, AnimeParse.baseTitle(it)) else listOf(AnimeParse.baseTitle(it), it)
        }
        // A name-decimal (Evangelion 1.11, GITS 2.0) is the database's own index key, and the
        // subtitle after it is where matching goes to die: AniList answers "Evangelion 1.11"
        // with the film, "Evangelion 1.11 You Are Alone" with nothing. These outrank the folder
        // TAIL: the generic franchise word lives there, and whichever query answers first wins.
        val decimalQ = listOfNotNull(fansub, full, fileTitle).mapNotNull { t ->
            NAME_DECIMAL_END.find(t)
                // `BD 1080p 8bits 5.1 AAC` — an audio layout is a tag, not a name-decimal.
                ?.takeIf { m -> m.value.substringAfterLast(' ') !in AUDIO_LAYOUTS }
                ?.let { m -> t.take(m.range.last + 1).trim() }
                ?.takeIf { c -> c != t && c.any(Char::isLetter) }
        }.distinct()
        // The FILE wins when it names the folder's work PLUS something more — `读或死R.O.D/` holds
        // `R.O.D －THE TV－ 第07話` beside `R.O.D －READ OR DIE－ OVA 第01話`, and folder-first put all
        // 63 files on one entry. A file that merely repeats the folder's title (or adds an episode
        // number, which the parse already removed) is not more specific and changes nothing.
        // compare against every folder-derived query, not just the raw folder names: the abbreviation
        // reduction turns `EVA Evangelion` into `Evangelion`, which is what the file extends
        // …compared against the folder titles WITH their sequel numbering stripped as well. A box
        // named for the season rather than the work — `Full Metal Panic! 2/` holding
        // `Full Metal Panic Fumoffu 01.mkv` — otherwise wins, and AniList answers "Full Metal
        // Panic! 2" with One Night Stand (115916) while the file's own title gives Fumoffu (72).
        val folderTitles = folderQ.flatMap { listOf(it, SEQUEL_MARKER.replace(it, "")) }
            .map(::normalizeForCompare).filter { it.isNotBlank() }
        // A FILM that names the folder's work in other words is also more specific:
        // `[Turn_A_Gundam][Movie_II_Moon_Butterfly]` under `Mobile Suit Gundam Turn_A_Gundam Movie`
        // shares the work's words and adds the film's own, and the box name alone put both films on
        // the first. Only where the folder says it holds films and the file numbers no episode:
        // a show's specials (`Sword Art Offline 8`, `Mobile Suit Information`) share words with
        // their show just the same, and asked about by name they would leave its card.
        val filmFolder = listOfNotNull(rawParent, rawShow).any { MOVIE_FOLDER.containsMatchIn(it) }
        val folderWords = if (filmFolder && !episodic) folderQ.map { contentWords(it) }.filter { it.size >= 2 } else emptyList()
        val moreSpecific = fileQ.filter { f ->
            val n = normalizeForCompare(f)
            folderTitles.any { fo ->
                fo.isNotBlank() && n.length > fo.length && n.startsWith(fo) &&
                    // …and what it adds must be WORDS. `Evangelion - 01` extends `Evangelion` by an
                    // episode number, which names no work and would be asked about for every episode.
                    n.drop(fo.length).any(Char::isLetter)
            } || contentWords(f).let { fw ->
                folderWords.any { fo -> (fw intersect fo).size >= 2 && (fw - fo).any { w -> w.any(Char::isLetter) } }
            }
        }.minByOrNull { it.length } // the shortest extension is the title; longer ones carry episode text
        // A quoted name in the FILE — `攻殻機動隊 S.A.C. OVA「Individual Eleven」` — is the work's own
        // title when no episode number stands beside it (with one, it is the episode's). The folder
        // above names the box ("Special Edition"), and asked first it put both OVAs on one entry.
        // Not a quote that OPENS the name (`『スカーRaws』Ichiban Ushiro no Daimaou` — the group's
        // signature), and not on a file the name marks as a 特典 (an OP「song」 clip names its song).
        val fileName = path.substringAfterLast('/')
        val quotedQ = if (parsed.episode == null && anime.episode == null && !ContentClassifier.isStrongExtra(fileName)) {
            QUOTED.findAll(fileName)
                .filter { it.range.first > 0 && fileName.substring(0, it.range.first).any { c -> c.isLetterOrDigit() } }
                .map { it.groupValues[1].replace(PAREN_ANY, " ").replace(DOTS_UNDERSCORES, " ").replace(MULTI_SPACE, " ").trim() }
                .filter { it.length >= 4 && !isSignalless(it) && !FilenameParser.isJunkTitle(it) }.toList()
        } else emptyList()
        // …placed after the path's explicit evidence but ahead of the plain folder title, which
        // is the thing it is more specific THAN.
        // Last of all, the name before a subtitle wrapped in dashes. `R.O.D -The Complete- OVA Vol.1`
        // is asked whole, in Anitomy's truncated reading, and by its folder — none of which AniList
        // knows — while plain "R.O.D" returns both the TV entry and the OVA. It goes LAST because
        // for many works the dashes hold part of the real name: 86 -不存在的战区- is not "86". Only a
        // file that nothing else could answer ever reaches it.
        val dashTail = listOfNotNull(fansub, full, fileTitle).mapNotNull(::beforePairedDash).distinct()
        // …and after even that, the distinctive tail of a Latin name. See [latinTail].
        val latinTails = listOfNotNull(fansub, full, fileTitle).mapNotNull(::latinTail).distinct()
        // drop un-parsed fansub bracket junk and bare episode numbers
        val asked = (folderHead + listOfNotNull(moreSpecific) + seasonQ + quotedQ + decimalQ +
            folderTail + fileQ + dashTail + latinTails)
            .filter { it.isNotBlank() && '[' !in it && ']' !in it && !FilenameParser.isJunkTitle(it) }
            .distinct()
        // Each query is followed by the same name re-spelt the way a database indexes it — without
        // the film word, without the abbreviation that opens it. A re-spelling is the same evidence
        // in another spelling, so it belongs beside the query it came from and ahead of everything
        // weaker: both Macross films sat on the TV entry because the box's franchise name answered
        // once the film's own spelling had not.
        //
        // Unless the list already asks it further down. There the spelling belongs to a BROADER
        // query and has to keep that broader place: dropping 剧场版 from `魔法少女奈叶 剧场版` gives
        // `魔法少女奈叶`, which the anime root already asks — moved up beside the folder it came
        // from, the TV series would have answered ahead of the film's own English title.
        return asked.flatMap { q -> listOf(q) + respellings(q).filter { it !in asked } }.distinct()
    }

    /** The same name as a database is more likely to index it. See the call site for the ordering. */
    private fun respellings(query: String): List<String> =
        listOfNotNull(
            withoutFilmWord(query), withoutLeadingAbbreviation(query), withoutTrailingFormWord(query),
            unglued(query), withPossessive(query), upToItsNumber(query),
        )

    /**
     * `Turn A Gundam II Moon Butterfly` → `Turn A Gundam II`: the name up to the instalment's own
     * numeral, the subtitle after it left out.
     *
     * A subtitle is where a ripper's spelling and the database's part ways — AniList calls the
     * second ∀ Gundam film "Moonlight Butterfly", and neither the file's name nor its re-spellings
     * found it. What was left was the franchise, which answers with the FIRST film. The name up to
     * its numeral says which instalment, and AniList answers "Turn A Gundam II" with exactly that
     * film. Only a capitalised Roman numeral II to IX (a digit is as often part of a name, Gundam 00),
     * and only with words after it.
     */
    internal fun upToItsNumber(query: String): String? {
        val m = INSTALMENT_NUMERAL.find(query) ?: return null
        if (query.substring(m.range.last + 1).none(Char::isLetter)) return null
        val cut = query.substring(0, m.range.last + 1).trim()
        // …and without a film word, as the database indexes the instalment: `Turn A Gundam Movie II`.
        return (withoutFilmWord(cut) ?: cut).takeIf { it.length >= 4 && !isSignalless(it) }
    }

    private val INSTALMENT_NUMERAL = Regex("""(?<![A-Za-z])(?:VIII|VII|VI|IV|IX|III|II)(?![A-Za-z])""")

    /** A lowercase letter followed by a capital — where a glued name had its space. */
    private val GLUED = Regex("""(?<=[a-z])(?=[A-Z])""")

    /**
     * `StarshipOperators` → `Starship Operators`.
     *
     * A ripper who strips the spaces out of a name strips out what the database indexes by:
     * AniList answers `StarshipOperators` with nothing and `Starship Operators` with the series.
     * Only a query that is ONE unbroken word, and only where every piece it comes apart into is a
     * word of its own: `JoJo` would give `Jo Jo` and `xxxHOLiC` would give `xxx HOLi C`, and a
     * capital inside a short word is a spelling rather than a lost space.
     */
    internal fun unglued(title: String): String? {
        // One unbroken word of letters and digits, nothing else. A name that still carries dashes
        // or brackets is a filename, and splitting its capitals only makes a longer filename —
        // `StarshipOperators-01-CountDown` became `Starship Operators-01-Count Down`, which is no
        // more findable than what it came from.
        if (!title.all { it.isLetterOrDigit() } || !GLUED.containsMatchIn(title)) return null
        val parts = GLUED.split(title)
        // Every piece has to be a word. `JoJo` is written that way on purpose and comes apart as
        // `Jo Jo`; `xxxHOLiC` as `xxx HOLi C`. A capital inside a SHORT word is a spelling, not a
        // lost space.
        if (parts.size < 2 || parts.any { it.length < 3 }) return null
        return parts.joinToString(" ").takeIf { it.length >= 4 && !isSignalless(it) }
    }

    /**
     * `Gundam Chars Counterattack` → `Gundam Char's Counterattack`.
     *
     * An apostrophe is not a character a filename keeps, and a possessive without it is a word no
     * database has: `Chars Counterattack`, `Kikis Delivery Service` and `Howls Moving Castle` all
     * return NOTHING from AniList, while every one of them with the apostrophe put back returns its
     * film first. Only a word that is not the last — the possessive is a name owning the noun after
     * it, and a trailing `…Operators` is a plural.
     */
    internal fun withPossessive(title: String): String? {
        val w = title.split(' ').toMutableList()
        if (w.size < 2) return null
        val i = w.indices.firstOrNull { i ->
            i < w.size - 1 && w[i].length >= 4 && w[i].endsWith("s") &&
                w[i].all { it.isLetter() } && '\'' !in w[i]
        } ?: return null
        w[i] = w[i].dropLast(1) + "'s"
        return w.joinToString(" ").takeIf { !isSignalless(it) }
    }

    /**
     * The same name without the form word that ENDS it.
     *
     * `guilty crown - lost christmas ova` finds nothing at AniList; the same name without the `ova`
     * is the work (#13411). Measured both halves of that difference separately — the dash is fine,
     * the trailing form word is what empties the result.
     *
     * Stripping a form word was measured, earlier, to cost more than it won — but that was as a
     * REPLACEMENT. As a re-spelling it can only be reached once the name as written has come back
     * empty, and the form the path names still reaches the picker through [formOfPath] either way.
     * A title that really ends in one (`R.O.D the TV`) answers its own query first, and where the
     * shortened form is already in the list it keeps the place it already had.
     */
    private val TRAILING_FORM = Regex("""(?i)[\s:：\-－_～~]+(?:ova|oad|oav|ona|sp|special|tv)\s*$""")

    /** A subtitle separator — what tells `guilty crown - lost christmas` from a bare franchise name. */
    private val SUBTITLE_MARK = Regex("""\S\s*[:：\-－~～]\s*\S""")

    internal fun withoutTrailingFormWord(title: String): String? {
        val cut = TRAILING_FORM.replace(title, "").trim()
        if (cut == title || cut.length < 4) return null
        // Only where a SUBTITLE survives. Without this the rule hands back the bare franchise name —
        // `Patlabor OVA` → `Patlabor`, `Shingeki no Kyojin OVA` → `Shingeki no Kyojin` — and puts it
        // in front of the franchise fallback, which is the very thing these re-spellings exist to
        // get ahead of. A work whose name is only the franchise plus its form is already asked by
        // the folder below; it has nothing to gain here and everything to lose.
        if (!SUBTITLE_MARK.containsMatchIn(cut)) return null
        return cut.takeIf { !isSignalless(it) && !FilenameParser.isJunkTitle(it) }
    }

    /**
     * The same query without the word that says it is a film — last resort, after everything that
     * keeps the word has come back empty.
     *
     * A film word inside a title is how a release spells it, not how a database indexes it:
     * "Macross Frontier The Movie-The False Songstress" finds nothing at AniList, and the same name
     * without "The Movie" is the film. But dropping it is not always right — "Ghost in the Shell
     * The Movie" is the 2015 film and "Ghost in the Shell" the 1995 one, and both are real answers.
     * So it never replaces the query it came from, only follows it: asking without the word can
     * help only where asking with it came back empty. It does go ahead of the FOLDER\'s name,
     * though — both films sat on the Macross Frontier TV entry because the box\'s franchise name
     * answered once the file\'s own spelling had not.
     *
     * Only film words. The form words a path carries — OVA, ONA, TV — already reach the picker as a
     * hint through [formOfPath], and stripping those from the query itself was measured to cost
     * more than it won.
     */
    // Takes the separators around the word with it: dropping `The Movie` out of
    // `Macross Frontier The Movie-The False Songstress` must not leave a dangling `-`.
    private val FILM_WORD = Regex(
        """[\s\-\u2010\uFF0D:：·・]*""" +
            """(?:(?i)(?<![A-Za-z])(?:the\s+)?(?:movie|gekijou?-?ban)(?![A-Za-z])|劇場版|剧场版|映画)""" +
            """[\s\-\u2010\uFF0D:：·・]*"""
    )

    /**
     * The same query without the abbreviation that OPENS it.
     *
     * A release folder routinely files a work under the franchise's short name plus the work\'s own:
     * `EVA Death and Rebirth`. No database indexes that concatenation — AniList answers it with
     * nothing, the franchise name below answers instead, and the film lands on the TV series. Drop
     * the `EVA` and the same name is the film (#31). [dropRedundantAbbreviation] already covers the
     * other shape, where the short name is a PREFIX of the long one (`EVA Evangelion` →
     * `Evangelion`); this is for when what follows names a different work entirely.
     *
     * Measured over the whole anime shelf, an unguarded version of this fired on 112 files and most
     * of what it produced was rubbish standing in front of the franchise name that would otherwise
     * have answered — `1-26 DC+EoE`, `1920x1080 x264 AAC 2ch`, `VFR AAC`. Hence three conditions,
     * each one earned from that list: the opening word must not be one the vocabulary already knows
     * as noise (`THE NEXT GENERATION パトレイバー` is not an abbreviation and a title), what follows
     * must begin with a letter, and it must read like a title rather than more capitals — two words
     * at least, one of them spelt in ordinary case.
     */
    private val LEADING_ABBREV = Regex("""^([A-Z]{2,4})[.\s_-]+(?=\S)""")

    internal fun withoutLeadingAbbreviation(title: String): String? {
        val m = LEADING_ABBREV.find(title) ?: return null
        if (m.groupValues[1].lowercase() in NOISE_TOKENS) return null
        val rest = title.substring(m.range.last + 1).trim()
        if (!rest.first().isLetter()) return null
        val words = rest.split(Regex("""\s+""")).filter { w -> w.any(Char::isLetterOrDigit) }
        if (words.size < 2 || words.none { w -> w.any { c -> c.isLowerCase() } }) return null
        return rest.takeIf { it.length >= 4 && !isSignalless(it) && !FilenameParser.isJunkTitle(it) }
    }

    internal fun withoutFilmWord(title: String): String? {
        if (!FILM_WORD.containsMatchIn(title)) return null
        val cut = FILM_WORD.replace(title, " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim('-', '\u2010', '\uFF0D', ':', '：', '·', '・')
            .trim()
        return cut.takeIf { it.length >= 2 && it != title && !FilenameParser.isJunkTitle(it) }
    }

    /**
     * A folder often names a work as "<abbreviation> <full title>" — `EVA Evangelion`,
     * `GITS Ghost in the Shell`. No database indexes that concatenation: AniList answers
     * "EVA Evangelion" with its ONE entry containing both tokens (Petit Eva: Evangelion@School,
     * popularity 7k) and never returns Neon Genesis Evangelion (popularity 488k) at all — so the
     * picker cannot rescue it, the query itself has to go.
     *
     * When a token is a prefix of a later, longer token (`EVA` ⊂ `Evangelion`), it is that
     * abbreviation, so the reduced title is tried FIRST and the original kept as the fallback.
     */
    /**
     * A sequel/season marker that makes a title name ONE season rather than the whole franchise:
     * `R2`, `II`, `2nd Season`, `S2`, `第二季`, `Season 2`, a trailing `2`. Deliberately narrow —
     * a bare number only counts at the very end, so "86" or "Fate/Zero" aren't misread.
     */
    private val SEQUEL_MARKER = Regex(
        """(?i)(^|[\s._-])(r[2-9]|i{2,3}|iv|v|s[2-9]|[2-9](nd|rd|th)?\s*(season|期|季)?|""" +
            """season\s*[2-9]|part\s*[2-9]|第\s*[二三四五六七八九2-9]\s*[季期])\s*$""",
    )

    fun hasSequelMarker(title: String): Boolean = SEQUEL_MARKER.containsMatchIn(title.trim())

    /**
     * The sequel marker a folder NAME carries but its parsed title lost. `Code Geass 反叛的鲁路修 R2
     * 1080P BDRIP` parses to just "Code Geass" — the Latin-run extraction keeps the longest run of
     * consecutive non-CJK tokens, and a lone "R2" after the Chinese name is one token, below its
     * threshold. Both seasons then searched "Code Geass" and landed on the same entry, so R1 and R2
     * shared a card. Re-attaching the marker gives the season its own query, tried first.
     */
    /**
     * The season the PATH names, in any of the ways a folder writes it — `2nd_Season`, `Season 2`,
     * `S2`, `第2期`. Read from the nearest folders and the filename, nearest first, because a box
     * (`Mobile Suit Gundam 00 1080P BDRIP/2nd_Season/`) says it one level down from the work.
     */
    fun seasonOf(path: String): Int? =
        // FOLDERS only: an episode number in the filename ("… - 05 RAW") reads as a bare digit and
        // would be taken for a season.
        path.replace('\\', '/').split('/').dropLast(1).asReversed().take(3)
            .firstNotNullOfOrNull { seg ->
                // `Version 2/` is a RIP revision folder (a re-encode of the same work), not a
                // second season — reading its 2 as a season made seasonFit prefer a picture-drama
                // whose title happens to end in 2 over the series itself.
                // …and name-decimals: a box called `EVA 1.11 2.22 3.33` is three FILMS, and the
                // space before "2.22" read as season 2 — which sent every file in it to 破.
                val cleaned = NAME_DECIMAL_STRIP.replace(VERSION_TAG.replace(seg, " "), " ")
                    .substringBefore("1080").substringBefore("720").trim()
                SEASON_N.find(cleaned)?.takeUnless { opensTheName(cleaned, it) }
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: FORM_ORDINAL.find(cleaned)?.groupValues?.get(1)?.let(::cjkNumber)
                    ?: CJK_SEASON.find(cleaned)?.groupValues?.get(1)?.toIntOrNull()
                    ?: CN_SEASON.find(cleaned)?.groupValues?.get(1)?.let(::cjkNumber)
            }

    /**
     * A CJK name writes its season number straight onto the title — `零之使魔2`, `进击的巨人2` —
     * where a Latin one would leave a space. [SEASON_N] wants a separator before the digit and
     * finds none, so those folders named no season at all and the machinery that exists for exactly
     * this case ([AnimeMatch.byAirDate], for a franchise AniList titles by subtitle rather than
     * number) was never reached: 零之使魔 and 零之使魔2 shared one card.
     *
     * Only when the rest of the segment is CJK and the digit ENDS it. `攻殻機動隊2 無罪` is a film
     * called that, not a second season, and `頭文字D2` mixes scripts — neither is this pattern.
     */
    /**
     * `剧场版2`, `劇場版3` — which FILM this is, written straight onto the form word.
     *
     * [AnimeMatch.byAirDate] was built knowing that "劇場版2 means the second FILM, not the second
     * season", and orders a franchise's films among themselves when it is told the number. Nothing
     * ever told it: [SEASON_N] wants a separator before the digit and 版2 has none, and [CJK_SEASON]
     * wants the whole segment to be CJK while this one begins "Macross Frontier". So the second
     * Macross F film asked for "Macross Frontier", got the TV series — its name is no help either,
     * since the fansub calls it The Wings of Goodbye and AniList calls it The Wings of Farewell.
     *
     * Only after a form word, so `攻殻機動隊2 無罪` — a film CALLED 2 — is untouched, and only a
     * single digit, so a series number like 劇場版21 is left to say what it says.
     */
    private val FORM_ORDINAL = Regex("""(?:劇場版|剧场版|映画)\s*([2-9２-９])(?![0-9])""")

    private val CJK_SEASON = Regex("""^[\p{IsHan}\p{IsHiragana}\p{IsKatakana}·・々〆ヶ]{2,}([2-9])$""")

    /**
     * `第3季`, `第三期` — how a Chinese or Japanese shelf numbers a season FOLDER.
     *
     * [seasonFit] has always understood this inside a database TITLE; the path side never did, so a
     * library filed as `进击的巨人/第3季/` named no season at all. Nothing on this project's own shelf
     * is filed that way, which is why it went unseen — the shelves it was tuned against write their
     * season as a trailing digit instead.
     *
     * Season one is left out on purpose, as everywhere else: no database gives it an entry of its
     * own. `部` is left out too — `第三部` is usually the third FILM, not a third season.
     *
     * The marker has to END the segment. Buried in a longer name it is describing something, not
     * naming this folder\'s season: `…_OVA.U2 (後期OVA,TV系列第2期)` holds the later OVA and mentions
     * the TV run it followed, and reading that as season two sent sixteen OVA files after the
     * series\' second season.
     */
    private val CN_SEASON = Regex("""第\s*([2-9２-９]|[二三四五六七八九])\s*[期季]\s*$""")

    private fun cjkNumber(s: String): Int? = when (val c = s.first()) {
        in '2'..'9' -> c - '0'
        in '２'..'９' -> c - '２' + 2
        else -> "一二三四五六七八九".indexOf(c).takeIf { it >= 0 }?.plus(1)
    }

    /**
     * A number that OPENS a name is what the work is called, not which season it is.
     *
     * `3-gatsu no Lion`, `5 Centimeters per Second`, `2 Broke Girls` — a shelf that names its works
     * in Latin has plenty of them, and every one was being read as a season, which then sent the
     * picker looking for a third or fifth entry of a franchise that has neither. (The same shape
     * was already guarded on the matching side; the path side was not.)
     *
     * It is still a season when the segment SAYS so — `2nd Season`, `3期` — or when the number is
     * the whole segment, which is how a library that files seasons as `Show/1/`, `Show/2/` says it.
     */
    private val SEASON_SAID = Regex("""(?i)nd|rd|th|season|期|季""")

    private fun opensTheName(segment: String, m: MatchResult): Boolean =
        m.range.first == 0 && segment.length > m.value.length && !SEASON_SAID.containsMatchIn(m.value)

    /** A trailing "1" / "S1" / "第一季" — season numbering for the FIRST season, which no database
     *  gives an entry of its own. */
    private val SEASON_ONE = Regex("""(?i)[\s._-](?:s|season\s*)?1(?:st)?(?:\s*season)?\s*$|第\s*[一1]\s*[季期]\s*$""")

    /** Standalone decimals, for stripping before season detection (they are names, not seasons). */
    private val NAME_DECIMAL_STRIP = Regex("""(?<![0-9A-Za-z.])[0-9]{1,3}\.[0-9]{1,3}(?![0-9A-Za-z.])""")

    private val AUDIO_LAYOUTS = setOf("2.0", "5.1", "7.1")

    /** A 「quoted」 name in a file name. */
    private val QUOTED = Regex("""[「『｢]([^」』｣]{2,60})[」』｣]""")

    /** A quoted subtitle and everything after it. */
    private val QUOTED_TAIL = Regex("""\s*[「『｢].*$""")
    private val PAREN_ANY = Regex("""[(（][^)）]*[)）]""")
    private val DOTS_UNDERSCORES = Regex("""[._]+""")
    private val MULTI_SPACE = Regex("""\s{2,}""")
    private val ANY_CJK = Regex("""[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]""")

    /**
     * The distinctive tail of a Latin name, for a romanisation the database does not use.
     *
     * `Mahoushoujo Ririkaru Nanoha A's` returns NOTHING from AniList, which spells that same title
     * `Mahou Shoujo Lyrical Nanoha A's` — a different romanisation of every word except the last
     * two. `Nanoha A's` returns the entry first. That is the shape of the disagreement: romanisers
     * argue about the formal words at the front (Mahoushoujo / Mahou Shoujo, Ririkaru / Lyrical)
     * and leave the name itself alone.
     *
     * Dead last, behind even [beforePairedDash], because it throws real words away. Only a file
     * that nothing else could answer ever reaches it, and the hit it produces still has to pass
     * the same correspondence check as any other.
     */
    private fun latinTail(q: String): String? {
        if (ANY_CJK.containsMatchIn(q)) return null
        val w = q.split(' ', '\u3000').filter { it.isNotBlank() }
        if (w.size < 4) return null
        return w.takeLast(2).joinToString(" ").takeIf { it.length >= 6 && !isSignalless(it) }
    }

    /** The first standalone decimal in a title (`1.11`, `2.0`) — the version-number name cut. */
    private val NAME_DECIMAL_END = Regex("""^.*?\d{1,3}\.\d{1,3}(?![0-9A-Za-z.])""")

    /** A re-encode revision marker (`Version 2`, `v2`) — never season material. */
    private val VERSION_TAG = Regex("""(?i)\bversion[\s._-]*\d+|(?<=[\s._\-\[])v\d+(?=[\s._\-\]]|${'$'})""")

    /** A season number written any of the ways a folder writes it: `S2`, `Season 2`, `2nd Season`. */
    private val SEASON_N = Regex("""(?i)(?:^|[\s._\-\[])(?:s|season\s*|)([2-9])(?:nd|rd|th)?(?:\s*season)?(?:[\s._\-\]]|$)""")

    /**
     * Queries that restore a season the title lost, most useful FIRST.
     *
     * Two separate failures were folded into one bug here. `摇曳露营 Yuru Camp Season 2 […]` parses
     * to "Yuru Camp" — the season is captured as a NUMBER and vanishes from the title — so the
     * query was the base form and season 2 landed on season 1's entry. And where the marker did
     * survive, it survived in the folder's own notation: AniList answers "Mushoku Tensei S2" with
     * NOTHING, while "Mushoku Tensei Season 2" returns the right entry (146065). So the marker is
     * normalised rather than copied, and the raw form is kept as a second chance.
     */
    private fun withLostSequelMarker(rawFolder: String?, title: String): List<String> {
        val raw = rawFolder ?: return emptyList()
        if (hasSequelMarker(title)) return emptyList()
        val base = AnimeParse.baseTitle(title).ifBlank { title }
        // A name-decimal (`Ghost in the Shell 2.0`, `EVA 1.11`) and a revision tag (`Version 2`) are
        // names, not seasons — exactly as [seasonOf] treats them. Read as season 2, "Ghost in the
        // Shell Season 2" asked for the second FILM by air date and got Innocence, not 2.0.
        val clean = NAME_DECIMAL_STRIP.replace(VERSION_TAG.replace(raw, " "), " ")
        val n = FilenameParser.parse(clean).season?.takeIf { it >= 2 }
            ?: SEASON_N.find(clean.substringBefore("1080").substringBefore("720"))
                ?.groupValues?.get(1)?.toIntOrNull()
        // Nothing was lost when the title still says it — `The Movie 2nd A's` carries its "2nd" in
        // the middle, and "The Movie 2nd A's Season 2" is a query for nothing at all.
        if (n != null && Regex("""(?i)(?:^|[\s._-])(?:$n(?:nd|rd|th)?|${ROMAN_N[n] ?: "(?!)"})(?=[\s._-]|$)""").containsMatchIn(title)) {
            return emptyList()
        }
        val marker = SEQUEL_MARKER.find(FilenameParser.parse(clean).title.ifBlank { clean })?.value?.trim()
            ?: SEQUEL_MARKER.find(clean.substringBefore("1080").substringBefore("720").trim())?.value?.trim()
        return listOfNotNull(
            n?.let { "$base Season $it" },
            marker?.let { "$title $it".trim() },
        ).filter { it != title }.distinct()
    }

    /**
     * The title a FOLDER names. Normally the ordinary parse, but an all-bracket folder
     * (`[philosophy-raws][Sword Art Online II]`) parses to nothing once brackets are stripped —
     * and that folder is exactly where a season lives, so its name has to be read the same way a
     * fansub FILE name is. Without this the season-2 subfolder contributed no query and the
     * generic show folder above it won, putting SAO II on SAO's card.
     */
    /** How a folder marks the FORM of the work it holds — an OVA run, a film, a special. */
    // `_` is a word character, so \b never fires in `…_OVA.U2` — the separator class has to be explicit.
    /** A subtitle wrapped in a PAIR of dashes — `-The Complete-`, `－THE TV－` — and all that follows. */
    private val PAIRED_DASH = Regex("""(?<=\S)\s*[-－‐]\s*[^-－‐]{2,40}?\s*[-－‐].*$""")

    /** The name before such a subtitle, or null when there is none or nothing would be left. */
    private fun beforePairedDash(title: String): String? {
        val cut = PAIRED_DASH.find(title)?.let { title.take(it.range.first) }?.trim() ?: return null
        return cut.takeIf { it.length >= 2 && it.any(Char::isLetterOrDigit) && !FilenameParser.isJunkTitle(it) && it != title }
    }

    private val FORM_MARK = Regex("""(?i)劇場版|剧场版|(?<![A-Za-z])(?:OVA|OAD|ONA|MOVIE|TV)(?![A-Za-z])""")

    /**
     * The form the PATH names for this file — OVA, TV, ONA — whatever query is being asked.
     *
     * A form-marked query often finds nothing ("機動警察パトレイバー TV" is not how AniList spells it),
     * and the search falls through to the bare title. The form was then lost along with the query
     * that spelled it, and 48 TV episodes took the OVA entry the franchise name returns first.
     * Being a TV run is a property of the FILE, not of the string that happened to find something.
     */
    /**
     * The form a file's own type element names (Anitomy's `[TV]`, `[OVA]`), in [formOfPath]'s terms,
     * or null. `[SumiSora][Munto][TV][01]` says TV where its folder, `Munto`, says nothing.
     */
    fun formOfType(type: String?): String? = when (type?.trim()?.uppercase()) {
        "OVA", "OAD", "OAV" -> "OVA"
        "ONA" -> "ONA"
        "TV" -> "TV"
        else -> null
    }

    fun formOfPath(path: String): String? = FilenameParser.folderChain(path)
        .firstNotNullOfOrNull { soleFormMark(it) }
        ?.let { mark ->
            when (mark.uppercase()) {
                "OVA", "OAD" -> "OVA"
                "ONA" -> "ONA"
                "TV" -> "TV"
                else -> null // 劇場版 / MOVIE is what wantMovie already carries
            }
        }

    /**
     * The form a folder names: the one it says most often, or null when it names none — or names
     * two equally, which is an inventory rather than a form.
     *
     * `逮捕令_逮捕しちゃうぞ_OVA.TV.MOVIE` lists what the box holds, one mention each, and picking any
     * of them is a guess. `Patlabor_機動警察(…)_OVA.U2 (後期OVA,TV系列第2期)` says OVA twice and
     * mentions the TV run it belongs beside once: that folder holds an OVA.
     */
    private fun soleFormMark(segment: String): String? {
        val marks = FORM_MARK.findAll(segment).map { it.value.trim() }.toList()
        if (marks.isEmpty()) return null
        val byForm = marks.groupBy { it.uppercase() }
        val top = byForm.values.maxOf { it.size }
        val winners = byForm.values.filter { it.size == top }
        return if (winners.size == 1) winners.first().first() else null
    }

    private fun rawParentOf(path: String) = FilenameParser.folderName(path)

    /** A release year or resolution/codec token — inside a title, the sign of an unparsed name. */
    private val RELEASE_INSIDE = Regex(
        """(?i)(?<![0-9A-Za-z])(?:19|20)\d{2}(?![0-9A-Za-z])|(?<![0-9A-Za-z])(?:bd|hd)?\d{3,4}[pi](?![0-9A-Za-z])|(?<![0-9A-Za-z])x26[45](?![0-9A-Za-z])""",
    )

    /** A folder that says it holds a film (or several). */
    private val MOVIE_FOLDER = Regex("""(?i)劇場版|剧场版|(?<![A-Za-z])(?:MOVIES?|FILMS?)(?![A-Za-z])""")

    /** The words that carry a title, lowercased — no function words, no release words. */
    private fun contentWords(s: String): Set<String> =
        SPLIT.split(s.lowercase()).map { it.trim('!', '?', ':', '~', '～', ',', '、', '\'') }
            .filter {
                it.length >= 2 && it !in NOISE_TOKENS && !NOISE_PATTERN.matches(it) &&
                    !it.all(Char::isDigit) && !RELEASE_INSIDE.containsMatchIn(it)
            }.toSet()

    /** Lowercase, punctuation-free form for comparing a file's title against its folder's. */
    private fun normalizeForCompare(s: String) =
        s.lowercase().filter { it.isLetterOrDigit() || it in "㐀-鿿぀-ヿ가-힯" || it.code > 0x2FFF }

    /** Every parenthetical in a folder name, longest first — see the call site for why. */
    private fun parenTitles(folder: String): List<String> =
        PAREN.findAll(folder).map { it.groupValues[1].trim() }
            .flatMap { it.split('，', ',', '、') } // `(逮捕你喲,逮捕しちゃうぞ)` holds two names, not one
            .map { it.trim() }
            // A capture that still holds an opening bracket is the outer half of a NESTED
            // parenthetical — `(1080p HEVC … softSub(chi+eng+fra)` — release words, not a name.
            .filter { it.length >= 4 && '(' !in it && '（' !in it && !isSignalless(it) }
            .sortedByDescending { it.length }
            .toList()

    private val PAREN = Regex("""[(（]([^)）]{1,60})[)）]""")

    private fun folderTitle(folder: String): String? {
        // A shelf names no work, and folder queries come FIRST — `Documentary/NHK/…` asked AniList
        // for "NHK" and put all 226 unrelated programmes on one entry.
        if (FilenameParser.isShelfFolder(folder)) return null
        // A folder whose first bracket lost its opener parses to that bracket's content and nothing
        // else — `Ghost_in_the_Shell_Stand_Alone_Complex][Solid_State_Society][BDRIP]` reads as the
        // franchise, and the bracket naming THIS work is discarded as a tag. Read it as the
        // bracketed name it is: "Solid State Society" is #1566, the franchise is #467.
        if (FilenameParser.lostOpener(folder)) {
            FilenameParser.fansubTitle(folder)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        // parse() falls back to the raw name when stripping brackets leaves nothing, so "non-blank"
        // isn't enough — a result that still carries brackets means it found no title of its own.
        val parsed = FilenameParser.parse(folder).title
        if (parsed.isNotBlank() && '[' !in parsed && ']' !in parsed) return parsed
        return FilenameParser.fansubTitle(folder)?.takeIf { it.isNotBlank() }
    }

    private fun dropRedundantAbbreviation(title: String): String? {
        val tokens = title.split(' ').filter { it.isNotBlank() }
        if (tokens.size < 2) return null
        val idx = tokens.indexOfFirst { a ->
            a.length >= 3 && tokens.any { b -> b.length > a.length && b.startsWith(a, ignoreCase = true) }
        }
        if (idx < 0) return null
        return tokens.filterIndexed { i, _ -> i != idx }.joinToString(" ").takeIf { it.isNotBlank() }
    }

    /**
     * The sibling title that spells out a one-word [short] title — `Seitokai` → `Seitokai no
     * Ichizon` from the file beside it. Latin needs the word followed by a space (so `Fate` does not
     * borrow `Fateful…`); CJK, having no spaces, needs only a longer sibling that starts with it and
     * goes on with letters rather than a number.
     */
    internal fun siblingExpansion(short: String, siblings: Collection<String>): String? {
        val s = short.trim()
        if (s.length < 2 || s.contains(' ') || siblings.isEmpty()) return null
        val cjk = s.any { it.code >= 0x2E80 }
        return siblings.asSequence().map { it.trim() }
            .filter { it.length > s.length + 1 }
            .filter { if (cjk) it.startsWith(s) && it[s.length].isLetter() else it.startsWith("$s ", ignoreCase = true) }
            .filter { !isSignalless(it) }
            .minByOrNull { it.length }
    }

    private val ROMAN_N = mapOf(2 to "ii", 3 to "iii", 4 to "iv", 5 to "v")

    /** The CJK query for the Bangumi → AniList bridge: most specific folder first, never junk. */
    fun cjkBridgeQuery(path: String, parsed: ParsedName, anime: AnimeParse.Parsed): String? {
        val full = anime.title?.takeIf { it.isNotBlank() } ?: parsed.title
        // Same shelf guard as folderTitle — a CJK shelf name (纪录片, 电影) is CJK enough to pass the
        // filter below and would then stand in as the query for everything under it.
        val showFolder = FilenameParser.animeShowFolder(path)
            ?.takeUnless(FilenameParser::isShelfFolder)?.let { FilenameParser.parse(it).title }
        val parentFolder = FilenameParser.folderName(path)
            ?.takeUnless(FilenameParser::isShelfFolder)?.let { FilenameParser.parse(it).title }
        return listOfNotNull(parentFolder, showFolder, full, parsed.title)
            .firstOrNull { it.isNotBlank() && hasCjk(it) && !isSignalless(it) }
    }

    private fun hasCjk(s: String) = s.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }
}
