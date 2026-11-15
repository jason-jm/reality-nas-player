package com.therealityteam.videoplayer.data.scrape

/** Structured result of cleaning a messy video filename. */
data class ParsedName(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val isEpisode: Boolean get() = season != null || episode != null
}

/**
 * Step ① of the scraping pipeline: strip release noise from a filename and recover the
 * core title + year (+ season/episode for TV).
 *
 * e.g. `Interstellar.2014.2160p.BluRay.x264.8bit.DoVi.DTS-HD.MA.5.1-BiG.mkv`
 *      -> ParsedName(title="Interstellar", year=2014)
 *      `The.Bear.S01E05.1080p.WEB-DL.mkv`
 *      -> ParsedName(title="The Bear", season=1, episode=5)
 */
object FilenameParser {

    private val NOISE: Set<String> = setOf(
        "2160p", "1440p", "1080p", "1080i", "720p", "480p", "576p", "4k", "8k", "uhd", "fhd",
        "bluray", "blu-ray", "bdrip", "brrip", "bdremux", "remux", "webrip", "web-dl", "webdl",
        "web", "hdtv", "hdrip", "dvdrip", "dvd", "hdcam", "cam", "ts", "telesync",
        "x264", "x265", "h264", "h265", "hevc", "avc", "av1", "vp9", "xvid", "divx",
        "10bit", "8bit", "10bits", "8bits", "hdr", "hdr10", "hdr10+", "dovi", "dv", "sdr", "hlg",
        "aac", "ac3", "eac3", "dts", "dts-hd", "dtshd", "truehd", "atmos", "ddp", "dd", "ddp5",
        "flac", "mp3", "opus", "5.1", "7.1", "2.0", "dual", "multi",
        "proper", "repack", "extended", "unrated", "remastered", "limited", "internal",
        "imax", "dc", "se", "bdre", "oar", "rm", "open-matte", "redux",
        "subs", "sub", "subbed", "dubbed", "cc", "hc",
        "3d", "hsbs", "fsbs", "sbs", "hou", "fou", "ou", "tab", "half-sbs", "half-ou",
        "vr", "vr180", "180", "180vr", "360", "360vr", "fisheye", "mkx200", "rf52", "eac",
        // The languages a release is in, as the scenes of each country tag them: Der.Pate.1972.GER.DL…,
        // Amelie.TRUEFRENCH.VFF…, …Castellano / Latino, …Lektor.PL, …CZ.Dabing, …TR.Dublaj. Only the scenes' own
        // words and codes: a language's NAME is a title's as often (The Italian Job, The English Patient, The
        // Danish Girl, The Russian Prison), and "dan", "fin", "it", "es" or "pol" may be a name's own.
        "deutsch", "ger", "dl", "truefrench", "vff", "vfq", "vfi", "vf2", "vostfr", "vost", "subfrench",
        "ita", "castellano", "latino", "esp", "spa", "vose", "subesp", "eng",
        "rus", "lektor", "napisy", "dubbing", "pldub", "plsub", "cze", "dabing", "titulky",
        "swesub", "nordic", "nlsub", "nlsubs", "dublaj", "altyazi", "kor", "jpn",
        "multisub", "multisubs", "multi-subs", "dual-audio", "dubbed-audio",
    )

    private val SEASON_EP = Regex("""[sS](\d{1,2})[ ._-]*[eE](\d{1,3})""")
    // Letter-guarded as well as digit-guarded: `AC3x2` / `FLACx2` in a fansub's codec bracket read as
    // season 3 episode 2, and a season the path never named sent 26 episodes of S.A.C. to the third
    // entry of its franchise by air date.
    private val SEASON_EP_X = Regex("""(?<![0-9A-Za-z])(\d{1,2})x(\d{1,3})(?![0-9A-Za-z])""")
    private val EP_ONLY = Regex("""(?:\bEpisode\s*|\b[eE][pP]?\.?\s?|第)\s*(\d{1,3})\s*集?""", RegexOption.IGNORE_CASE)
    /**
     * An episode named in words: Folge 3, Épisode 3, Episodio 3, Aflevering 3, Avsnitt 3, Afsnit 3, Jakso 3,
     * Odcinek 3, Díl 3, Epizoda 3, Επεισόδιο 3, Episodul 3, Bölüm 3, Серия 3, Эпизод 3, ตอนที่ 3, Episod 3 —
     * and Korean's number first, 3화 / 3회.
     */
    private val EP_WORD = Regex(
        """(?iu)(?<!\p{L})(?:folge|épisode|episodio|epis[oó]dio|aflevering|afl\.|avsnitt|afsnit|jakso|odcinek|díl|epizoda|επεισόδιο|episodul|bölüm|серия|эпизод|ตอนที่|episod)\s*(\d{1,3})(?!\d)|(?<![\p{L}\d])(\d{1,3})\s*[화회](?!\p{L})""",
    )
    // Fansub/anime bracket episode: a bracket whose content is ONLY a number (+optional .5 and a
    // parenthetical), e.g. [60], [04.5], [02(NC Ver.)]. Years/resolutions ([2024], [1080p]) are
    // 4-digit or non-numeric so they never match. Checked before brackets are stripped.
    private val BRACKET_EP = Regex("""\[\s*(\d{1,3})(?:\.5)?\s*(?:\([^)]*\))?\s*]""")

    /**
     * `Some Show - 07`, `Mugen no Ryvius - 26 END` — a spaced dash then the episode number, with
     * nothing after it but an end marker. This is the dominant fansub convention worldwide, and
     * nothing here recognised it: neither season nor episode came out, and the number stayed glued
     * to the title, so every episode asked the database a different question. 160 of 581 works in
     * the reference library fragmented this way.
     *
     * The number may be followed by another dash rather than the end of the name:
     * `… SEED Destiny HD Remaster - 03 - [AVC_10bit_1080P …] - kewenyu` puts the release tags
     * after it, and anchoring at the end alone left `- 03 - - kewenyu` in the query.
     *
     * A bare single digit is excluded on purpose. `Kill Bill - 2` and `Rocky - 3` are sequel
     * numbering, which is almost always a lone digit; episode numbering is almost always
     * zero-padded or already past nine.
     */
    /**
     * `Code Geass 01`, `Legend of Galactice Heroes 002` — the same episode marker with only a
     * space in front of it. Riskier than the dashed form, because plenty of real titles end in a
     * number, so it carries three guards that were checked against the reference library rather
     * than guessed:
     *
     *  - never a lone digit: `寒战2`, `沙丘2`, `Toy Story 3`, `Iron Man 3` are sequel numbering;
     *  - never `00`: `机动战士高达00` is a work, and its 30 files were already asking correctly;
     *  - never when only ONE word precedes the number. Titles that genuinely end in a number are
     *    almost all of the shape `<one word> <number>` — Apollo 13, Ocean's 11, Catch 22, Area 88,
     *    Fahrenheit 451 — while a series title long enough to be searched is not.
     *
     * In the reference library every query matching this pattern was an episode, and none of the
     * 20 ground-truth titles that end in a number matched it.
     */
    /**
     * `鎌倉殿の１３人「根拠なき自信」` — Japanese broadcast names the programme, then quotes that
     * episode's own subtitle. The quoted part is never part of the work's name, so leaving it in
     * asks the database a different question for every single episode: 190 files across two
     * recorded series each had a query of their own.
     *
     * Only cut when something precedes the quote — a name that IS a quote (`「彼女と彼女の猫」`)
     * has nothing in front of it and must be kept whole.
     */
    private val QUOTED_SUBTITLE = Regex("""(?<=\S)\s*[「『｢].*$""")

    private val CONTENTS_LIST = Regex(
        """(?i)(?<=\S)[\s\-–—]*(?:BD-?BOX[\s\-–—]*)?(?:(?:TV|SP|SPs|OVA|OAD|ONA|Movie|Movies|Specials?|All|OST|BK|Scans?|CDs?|Extras?|Bonus|Menu|NC(?:OP|ED)?)(?:\s+SPs?)?\s*\+\s*)+""" +
            """(?:TV|SP|SPs|OVA|OAD|ONA|Movie|Movies|Specials?|All|OST|BK|Scans?|CDs?|Extras?|Bonus|Menu|NC(?:OP|ED)?)(?![A-Za-z]).*$|""" +
            """(?<=\S)\s*全\s*\d{1,3}\s*[話话集](?:\s*\+\s*\S+)*\s*$""",
    )

    /**
     * `青天を衝け 青天を衝け紀行` — a recording that repeats the programme name before its
     * segment title. Keep the first copy; the rest is that segment, not the work.
     */
    private fun collapseRepeatedName(t: String): String {
        val i = t.indexOf(' ')
        if (i <= 2) return t
        val head = t.substring(0, i)
        val rest = t.substring(i + 1)
        if (!rest.startsWith(head)) return t
        // Only a CJK programme name. A Latin word said twice IS the title — Tick, Tick… Boom!,
        // Boom Boom Room, Liar Liar, Duran Duran — and reading it as a repeat left "Tick" to search.
        return if (head.any { it.code in 0x3040..0x9FFF }) head else t
    }

    private val SPACE_EP = Regex(
        """\s(0[1-9]|[1-9]\d|\d{3})(?:\s*(?:END|FIN|完|v\d+))?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val DASH_EP = Regex(
        """\s[-–—]\s*(0\d{1,2}|\d{2,3})(?:\.5)?(?:\s*(?:END|FIN|完|v\d+))?\s*(?:[-–—]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val SEASON_CN = Regex("""第\s*(\d{1,2})\s*季""")
    private val SEASON_CN_CH = Regex("""第\s*([一二三四五六七八九十两]{1,3})\s*季""")
    /**
     * A season named in words, in the languages Aura reads: Season 2, Staffel 2, Saison 2, Temporada 2, Stagione 2,
     * Seizoen 2, Säsong 2, Sæson 2, Sesong 2, Kausi 2, Sezon 2, Sezona 2, Sezonul 2, Σεζόν 2, Сезон 2, シーズン2,
     * 시즌2, Musim 2 — and number first, as German, Spanish, Portuguese and Russian also write it: 2. Staffel,
     * 2ª Temporada, 2 сезон.
     */
    private val SEASON_WORD = Regex(
        """(?iu)(?<!\p{L})(?:seasons?|staffel|saison|temporada|stagione|seizoen|s[äa]song|s[æa]son|sesong|kausi|sezona|sezonul|sezon|σεζόν|сезон|シーズン|시즌|musim)\s*(\d{1,2})(?!\d)""",
    )
    private val SEASON_WORD_AFTER = Regex(
        """(?iu)(?<![\p{L}\d])(\d{1,2})\s*(?:\.|ª|º|-?я|-?й)?\s*(?:staffel|temporada|saison|stagione|сезон|sezon)(?!\p{L})""",
    )
    // A release year is a STANDALONE token — letter-adjacent digits (a group tag like "lsqh2002",
    // "DivXNL") are not a year, so they can't hijack the real one.
    private val YEAR = Regex("""(?<![A-Za-z0-9])(19\d{2}|20\d{2})(?![A-Za-z0-9])""")
    private val BRACKET_YEAR = Regex("""[\[(（【]\s*(19\d{2}|20\d{2})\s*[\])）】]""")
    private val GLUED_RES = Regex("""(?i)(?<=[a-z])([1-9]\d{2,3}[pi])(?![a-z0-9])""")
    private val PART_LIST = Regex("""(?i)(?<=\s)(?:I{1,3}|IV|V|[1-5])\s*[&+]\s*(?:I{1,3}|IV|V|[1-5])(?=\s|$)""")
    // Each bracket type closed by its OWN delimiter: a mixed character class ends the match at the
    // first close of ANY type, so `[1080p x265 Sub(Eng,Jap)]` used to strip only up to the `)` and
    // leave a stray `]` glued to the title.
    private val BRACKETS = Regex("""\[[^\[\]]*\]|【[^【】]*】|\([^()]*\)|（[^（）]*）""")
    /** Subtitle stamps Mp4Ba-style releases glue into the middle of a title. */
    private val SUB_STAMP = Regex("""特效中英字幕|中英双字幕|中文字幕|双语字幕|简繁中字|中英字幕|特效字幕""")
    private val SEPARATORS = Regex("""[._]+""")
    private val STRAY_BRACKET = Regex("""[\[\]【】]""")

    /**
     * A short decimal that stands alone (`1.11`, `2.0`, `0.515`) is part of a NAME — Evangelion's
     * rebuild films, Ghost in the Shell 2.0, Code Geass's picture dramas — and splitting it to
     * "1 11" produced a query no database answers, so the right entry never got asked. Standing
     * alone is the whole test: either side touching a digit, letter or another dot means scene
     * separators (`2049.1080p`) or an IP in an SMB host, which must keep splitting.
     */
    private val NAME_DECIMAL = Regex("""(?<![0-9A-Za-z.])([0-9]{1,3})\.([0-9]{1,3})(?![0-9A-Za-z.])""")

    /** A dot straight after a letter/bracket is a scene SEPARATOR (`Evangelion.1.11`, `MP4.640`) —
     *  spaced out first, so the decimal test above sees the digits standing alone. */
    private val LETTER_DOT = Regex("""(?<=[A-Za-z)\]])\.(?=[0-9A-Za-z])|(?<=[0-9])\.(?=[A-Za-z])""")
    private const val DOT_MARK = '\uF8FF' // private-use scapegoat; restored after separator cleanup
    private val MULTISPACE = Regex("""\s{2,}""")
    // Broadcaster tag prefixed to a title (safe set — none start a real movie title).
    private val LEADING_NETWORK = Regex("""(?i)^(bbc|itv|nhk|pbs|hbo)\s+""")

    /** Media/subtitle container extensions — the only trailing `.xxx` that is not part of a name. */
    private val EXTENSION = Regex(
        """(?i)\.(mkv|mp4|m4v|avi|rmvb|rm|wmv|mov|m2ts|mts|ts|iso|flv|webm|mpg|mpeg|vob|3gp|ogm|""" +
            """divx|asf|f4v|srt|ass|ssa|sub|idx|sup|nfo|txt)$""",
    )

    /**
     * Strip a real extension, and only that. `substringBeforeLast('.')` also amputates the last
     * word of any dotted name that has no extension at all — which every scene FOLDER is:
     * `2001.A.Space.Odyssey` became "2001 A Space", TMDB was asked for a 2001 film by that name,
     * and 2001: A Space Odyssey came back as 2001: A Space Travesty.
     */
    private fun dropExtension(name: String): String =
        EXTENSION.find(name)?.let { name.substring(0, it.range.first) }?.ifBlank { name } ?: name

    // parse() and animeShowRoot() are pure but regex-heavy, and grouping calls them per item
    // on every recompute — memoizing turned a 9609-item group() from ~1.2s to sub-second on
    // JVM (5x that on device). Bounded: a library is ~10k names; clear on overflow.
    private val parseCache = java.util.concurrent.ConcurrentHashMap<String, ParsedName>()

    /** Start from cold. Only a benchmark wants this — the memo is otherwise the whole point. */
    fun forgetParses() = parseCache.clear()

    /** The names read so far and what they were read as — for [NameMemo] to keep across launches. */
    fun parsesSoFar(): Map<String, ParsedName> = HashMap(parseCache)

    /** Names read in an earlier launch of this same build ([NameMemo]), taken as read. */
    fun primeParses(read: Map<String, ParsedName>) = parseCache.putAll(read)
    private val rootCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ── The programme a file belongs to, where its name marks the instalment ─────────────────
    /** Where a programme's own name ends and the instalment begins: `（5）`, `第七回`, `最终回`, `1of4`. */
    val EPISODE_MARK = Regex(
        """[（(]\s*[0-9０-９]{1,3}\s*[)）]|第\s*[0-9０-９一二三四五六七八九十]{1,3}\s*[回話话集]|""" +
            """最終回|最终回|序章|序[編编]|序幕|引子|前言|[前後后上下]篇|[前後后上下][編编]|""" +
            // `[激流中国].01.喉舌与职责`, `毛泽东-01.反潮流` — a bare number between separators (a dash
            // before it too) is the instalment; `Blade-2` at the end of a name is a sequel and is not.
            """(?<=[\s.\u3000\-])[0-9０-９]{1,3}(?=[\s.\u3000])|""" +
            // `Rick.Steves.Europe.10of11.Spain`, `Empires.Napoleon.1of4` — "N of M" is the instalment.
            """(?i)(?<![0-9a-z])[0-9]{1,3}of[0-9]{1,3}(?![0-9a-z])""",
    )

    /** An episode's own title in Japanese corner brackets, after the programme's name. */
    val QUOTED_EPISODE = Regex("""(?<=\S)\s*[「『][^「」『』]{2,}[」』]""")

    private val PROGRAMME_HEAD_BRACKET = Regex("""^[\[【]([^\[\]【】]+)[\]】]""")
    /** A bracket that labels the source rather than naming the work. */
    private val PROGRAMME_SITE_MARK = Regex("""(?i)www|\.(com|net|cn|cc|org|tv)|字幕|发布|压制|论坛""")

    /**
     * On a SHELF, a lone CJK head bracket is the programme's name (`[PBS.拿破仑传奇].Empires.Napoleon
     * .1of4`); returns it and where the file name continues. TWO label brackets are not a name
     * (`[NHK][纪录片]`), a Latin one is a release group, a site tag is neither.
     */
    fun shelfBracket(fileName: String, parentFolderName: String): Pair<String, Int>? {
        if (!isShelfFolder(parentFolderName)) return null
        val m = PROGRAMME_HEAD_BRACKET.find(fileName) ?: return null
        if (fileName.getOrNull(m.range.last + 1) in listOf('[', '【')) return null
        var inner = m.groupValues[1].trim()
        inner.split('.', '_', ' ', '-').firstOrNull()
            ?.takeIf { isShelfFolder(it) }
            ?.let { inner = inner.removePrefix(it).trimStart('.', '_', ' ', '-') }
        if (inner.none { it.code in 0x3040..0x9FFF } || PROGRAMME_SITE_MARK.containsMatchIn(inner)) return null
        return inner.takeUnless { isJunkTitle(it) }?.let { it to m.range.last + 1 }
    }

    /**
     * The PROGRAMME a file name belongs to, where the name marks its instalment: the whole name
     * before the mark, parsed, 系列 dropped. `沸腾都市（3）达卡` → 沸腾都市; `Empires.Napoleon.1of4` →
     * Empires Napoleon. Null when nothing marks an instalment or what precedes it is junk.
     */
    fun programmeOf(name: String): String? {
        val head = EPISODE_MARK.find(name)
            ?.let { name.take(it.range.first) }
            ?.trimEnd(' ', '　', '.', '·', '-', '－', '_', '(', '（', '[', '【', '~', '～', ':', '：')
            ?: return null
        return parse(head).title
            .takeUnless { isJunkTitle(it) }
            ?.trimEnd(' ', '　', '.', '·', '-', '－', '~', '～', ':', '：')
            ?.removeSuffix("系列")
            ?.ifBlank { null }
    }

    /**
     * What to ask a database for when a file's own name finds nothing: the programme its name
     * belongs to. A shelf's label bracket names it when the remainder is only the instalment
     * (`[NHK.映像的世纪].第01集`); otherwise the name before the instalment mark, with any label
     * bracket dropped (`[PBS.里奇·史蒂夫斯游欧洲].Rick.Steves.Europe.10of11` → Rick Steves Europe).
     */
    fun programmeTitle(fileName: String, parentFolderName: String): String? {
        val noExt = dropExtension(fileName)
        shelfBracket(noExt, parentFolderName)?.let { (label, end) ->
            return programmeOf(noExt.substring(end).trimStart('.', ' ', '_', '-', '　')) ?: label
        }
        return programmeOf(noExt)
    }

    fun parse(rawFileName: String): ParsedName {
        if (parseCache.size > 32_768) parseCache.clear()
        return parseCache.getOrPut(rawFileName) { parseUncached(rawFileName) }
    }

    /**
     * The title as the name writes it, both scripts kept: what a folder nothing identified is called
     * on its card. [parse] prefers the Latin run of a mixed name, which is what a database answers
     * to — but as a NAME that read `Sound Horizon - Roman～僕達が繋がる物語～` as "Sound Horizon",
     * the same as the folder beside it, `Sound Horizon - 其他`.
     */
    fun writtenTitle(rawFileName: String): String {
        if (writtenCache.size > 4_096) writtenCache.clear()
        return writtenCache.getOrPut(rawFileName) { parseUncached(rawFileName, preferLatin = false).title }
    }

    private val writtenCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun parseUncached(rawFileName: String, preferLatin: Boolean = true): ParsedName {
        // Backtick / fullwidth apostrophes stand in for a real apostrophe in many filenames
        // (`Knockin`_on_Heaven`s_Door`) — fold them so the title matches DB entries.
        val noExt = dropExtension(rawFileName)
            .replace('`', '\'').replace('’', '\'')

        // Normalize ._ separators to spaces. Unwrap a bracketed year "(2008)"/"[2006]" → " 2008 " so
        // it's positioned like a bare year (and truncates the title there), then drop other brackets.
        val base = noExt.replace(LETTER_DOT, " ")
            .replace(NAME_DECIMAL) { m -> m.groupValues[1] + DOT_MARK + m.groupValues[2] }
            .replace(SUB_STAMP, " ").replace(SEPARATORS, " ").replace(DOT_MARK, '.')
        // Nothing usable outside the brackets means the title is INSIDE them, and the blank-title
        // fallback below would hand the whole soup to the DB. Rebuild it as "<title> <code>".
        // "Usable" and not merely non-blank: `[YYeTs][How.I.Met.Your.Mother][S01E01][CN][DVDRip]V2`
        // leaves a version stub outside, which is no more a title than a bare tag would be.
        val flat = if (fansubCand(base.replace(BRACKETS, " ")) == null) sceneTitle(noExt) else null
        // Brackets are stripped innermost-first, so nesting takes another pass: `(1080p … softSub(chi+eng)
        // chap)` left ` chap)` glued to the title after one.
        var work = stripBrackets((flat ?: base).replace(BRACKET_YEAR, " $1 "))
            // `The Last Samurai720p` — a resolution glued to the title's last word is still noise.
            .replace(GLUED_RES, " $1")
            // `Red Cliff I&II`, `Kill Bill 1&2` — a combined cut is named for its parts; the work is
            // the name before them, which is what a database answers to.
            .replace(PART_LIST, " ")
        // `[激流中国].01.喉舌与职责.avi` — the bracket holds the SERIES, not the release group. What
        // is left outside opens with a bare episode number, which no title does, so the name is the
        // one thing that was thrown away, and twelve parts of one NHK series drew twelve cards.
        if (flat == null) bracketSeries(noExt, work)?.let { work = "$it $work" }

        var cut = work.length
        var season: Int? = null
        var episode: Int? = null

        SEASON_EP.find(work)?.let { m ->
            season = m.groupValues[1].toIntOrNull()
            episode = m.groupValues[2].toIntOrNull()
            cut = minOf(cut, m.range.first)
        }
        if (season == null) SEASON_EP_X.find(work)?.let { m ->
            season = m.groupValues[1].toIntOrNull()
            episode = m.groupValues[2].toIntOrNull()
            cut = minOf(cut, m.range.first)
        }
        SEASON_CN.find(work)?.let { m ->
            season = season ?: m.groupValues[1].toIntOrNull()
            cut = minOf(cut, m.range.first)
        }
        if (season == null) SEASON_CN_CH.find(work)?.let { m ->
            season = cnNumber(m.groupValues[1])
            if (season != null) cut = minOf(cut, m.range.first)
        }
        if (season == null) (SEASON_WORD.find(work) ?: SEASON_WORD_AFTER.find(work))?.let { m ->
            season = m.groupValues[1].toIntOrNull()
            cut = minOf(cut, m.range.first)
        }
        // An episode in words, in another language (Folge 3, Серия 3, 3화) — after the season's words as often as not.
        if (episode == null) EP_WORD.find(work)?.let { m ->
            episode = (m.groups[1] ?: m.groups[2])?.value?.toIntOrNull()
            cut = minOf(cut, m.range.first)
        }
        // Anime "[60]" bracket episode — read from the bracketed original (the title is taken from
        // the bracket-stripped `work`, so this only recovers the episode number).
        if (episode == null) BRACKET_EP.find(noExt)?.let { m ->
            episode = m.groupValues[1].toIntOrNull()
        }

        // Year detection. The release year is the FIRST plausible standalone year that isn't the
        // leading token: the title comes first, then its year, then release junk — so the first
        // non-leading year is the film's ("Terminator.2.1991.Lionsgate.2015" → 1991, not the 2015
        // re-release tag). A leading year IS the title ("1917", "2012"), so it's skipped in favour of
        // the trailing release year. Plausible rejects a future number like "Blade Runner 2049".
        val maxYear = java.time.Year.now().value + 1
        val plausible = { y: Int -> y in 1900..maxYear }
        val bareYears = YEAR.findAll(work).map { it.range to it.value.toInt() }
            .filter { plausible(it.second) }.toList()
        // A BRACKETED year is a release year wherever it sits — `[2008] Some Show`. A bare LEADING
        // year is the title, and nothing else: `2001.A.Space.Odyssey` has no release year in it, and
        // taking 2001 as one asked TMDB for a 2001 film called "2001 A Space Odyssey" — of which
        // there is none, since Kubrick's is 1968.
        val bracketYear = BRACKET_YEAR.find(noExt)?.groupValues?.get(1)?.toIntOrNull()?.takeIf(plausible)
        // Two years side by side — `The.Legend.of.1900.1998`, `Death.Race.2000.1975` — are a title
        // that ends in a number and then the release year: the earlier one belongs to the name.
        val candidates = bareYears.filter { work.substring(0, it.first.first).isNotBlank() }
        val yearMatch = candidates.firstOrNull { y ->
            val next = bareYears.firstOrNull { it.first.first > y.first.last }
            // (a hyphen between them is a span — `Collection.2005-2012` — and the first year stays)
            next == null || work.substring(y.first.last + 1, next.first.first).any { !it.isWhitespace() && it !in "._" }
        } ?: candidates.lastOrNull()
        val year: Int? = bracketYear ?: yearMatch?.second
        if (yearMatch != null) cut = minOf(cut, yearMatch.first.first)

        // First release-noise token: title ends before it.
        cut = minOf(cut, firstNoiseCharIndex(work))

        var title = work.substring(0, cut.coerceIn(0, work.length))

        // A bare episode marker (e.g. "EP05", "第5集") still glued to the title.
        if (episode == null) EP_ONLY.find(title)?.let { m ->
            episode = m.groupValues[1].toIntOrNull()
            title = title.substring(0, m.range.first)
        }
        // …and the spaced-dash form the fansub world actually uses: `Eureka Seven - 39`.
        if (episode == null) DASH_EP.find(title)?.let { m ->
            episode = m.groupValues[1].toIntOrNull()
            title = title.substring(0, m.range.first)
        }
        // …and the bare-space form, but only where at least two words come before the number.
        if (episode == null) SPACE_EP.find(title)?.let { m ->
            val before = title.substring(0, m.range.first).trim()
            val words = before.split(' ', '\u3000').count { it.isNotBlank() }
            val cjk = before.count { it.code in 0x3040..0x9FFF }
            if (words >= 2 || cjk >= 4) {
                episode = m.groupValues[1].toIntOrNull()
                title = before
            }
        }

        // A quoted episode subtitle is that episode's, not the programme's.
        QUOTED_SUBTITLE.find(title)?.let { title = title.substring(0, it.range.first) }
        // A box lists its contents after the name — `交响诗篇 - TV + SP`, `翼神传说 BD-BOX - TV +
        // Movie + OVA`, `GUNBUSTER&DIEBUSTER&合体劇場版 全12話+劇場版x2+特典+Scans`. Two or more forms
        // joined by "+" (or an episode count) is a list, not a title; a single "- Movie" is kept,
        // since it is the one word that says which entry of a franchise is meant.
        title = title.replace(CONTENTS_LIST, "").trim()
        title = collapseRepeatedName(title.trim())

        // A bracket left unpaired by a truncated name (`Ghost_in_the_Shell_Stand_Alone_Complex]
        // [Solid_State_Society][BDRIP]` — a folder that lost its leading `[POPGO][`) is not part of
        // the title, and a title carrying one was refused as a query outright.
        title = title.replace(STRAY_BRACKET, " ")
        title = title.replace(MULTISPACE, " ").trim().trim('-', '–', '—', ' ').trim()
        if (title.isBlank()) title = noExt.replace(SEPARATORS, " ").replace(MULTISPACE, " ").trim()

        // A leading broadcaster tag isn't part of the title ("BBC Jane Eyre" → "Jane Eyre").
        title = title.replace(LEADING_NETWORK, "")

        // The year that OPENS the title is part of it, not a release year — even when the name
        // repeats it in a position that reads like one (`2001：太空漫游.2001.A.Space.Odyssey`), which
        // sent TMDB looking for a 2001 film called 2001 A Space Odyssey. Kubrick's is 1968.
        val yearOwned = year != null && title.startsWith(year.toString())

        // Mp4Ba/fansub releases bury an English title inside an (often obfuscated) Chinese one:
        // "使徒行者.电影版.Line.Walker.2016", "美国狙击手.特效中英字幕.american.sniper". The Latin run matches
        // TMDB far better than the localized name, so prefer it when the title is otherwise CJK.
        // …but only a run that names something. `魔法少女奈叶 The Movie 2nd A's` has a Latin run of
        // release words that searched on its own found a random film; `读或死R.O.D` leaves "O D".
        if (preferLatin && CJK.containsMatchIn(title)) embeddedLatin(title)?.takeIf(::latinHasSignal)?.let { title = it }

        return ParsedName(title = title, year = if (yearOwned) null else year, season = season, episode = episode)
    }

    /**
     * A year the title OPENS with belongs to the title. `parseFromPath` inherits a year from the
     * file when the folder has none, and that is how `2001.A.Space.Odyssey` still went to TMDB as a
     * 2001 film — the filename repeats the title, so its second "2001" reads like a release year.
     */
    private fun dropOwnedYear(p: ParsedName): ParsedName =
        if (p.year != null && p.title.startsWith(p.year.toString())) p.copy(year = null) else p

    /**
     * The longest run of consecutive non-CJK tokens (≥2 tokens) — an embedded romanized/English
     * title. Tokens only: splitting a CJK title from a Latin one GLUED to it (`逃离德黑兰Argo`) reads
     * well on that name and badly on `機動戦士ガンダムSEED C.E.73` (→ "SEED C"), where the Japanese
     * title is the real one and the Latin fragment is a subtitle. Measured on the library: 1 file
     * better, 15 worse.
     */
    private fun embeddedLatin(title: String): String? = latinRun(title, requireEdge = true)

    /** Function words and form words that never carry a title by themselves. */
    private val LATIN_FILLER = setOf(
        "the", "a", "an", "of", "and", "no", "to", "in", "movie", "film", "season", "part", "vol",
        "1st", "2nd", "3rd", "4th", "5th", "ii", "iii", "iv", "special", "edition", "version",
    )

    /** Does a Latin run hold at least one real word — two or more letters, not filler, not a tag? */
    private fun latinHasSignal(run: String): Boolean =
        run.split(' ', '+', '&', '/').any { tok ->
            val k = tok.lowercase().removeSuffix("'s").trim('-', '.', ',', '!', '?')
            k.length >= 2 && k.any(Char::isLetter) && k !in LATIN_FILLER && k !in NOISE &&
                k !in FANSUB_TAG && !isJunkTitle(k)
        }

    /**
     * Longest run of ≥2 consecutive non-CJK tokens. Splitting a Latin title GLUED to a CJK one
     * (`白色严冬Into.the.White` → "the White" → matched The White Lions) is NOT worth it: measured
     * over the whole library twice, at any threshold it fixes that one file and breaks dozens
     * (`Downton Abbey Behind The Drama` picks up "Chi Eng HDT", `機動戦士ガンダムSEED` becomes
     * "SEED C.E.73", `[Raws』Ichiban Ushiro no Daimaou` keeps the group bracket).
     */
    private fun latinRun(title: String, min: Int = 2, requireEdge: Boolean = false): String? {
        val toks = title.split(' ').filter { it.isNotBlank() }
        var best = emptyList<String>()
        var bestSandwiched = false
        var start = -1
        fun close(end: Int) {
            if (start < 0) return
            // A separator at either end belongs to what the run was cut from: `Sound Horizon -`
            // stood for `Sound Horizon - Roman～僕達が繋がる物語～`, `- Concert Tour` for `松隆子 -
            // Concert Tour`.
            val run = toks.subList(start, end).dropWhile(::isMark).dropLastWhile(::isMark)
            // With a CJK name on both sides the run is a phrase inside it — an episode's subtitle
            // (`世界市场.财富攻防战.第二回：made.in.china.中国的战略`, which searched as "in china" and
            // found the film Made in China), a series' own words (`機動警察パトレイバー ON TELEVISION
            // 映像特典…`) — not the English name a release puts before or after one. A name, not a
            // stamp: four CJK letters a side, a release group's signature (`720X400-YYeTs人人影视`) not
            // counted, and a short group tag before a romaji title (`星海城 - Hoshi no Koe 星之声`) not
            // enough.
            val sandwiched = cjkLetters(toks.subList(0, start)) >= 4 && cjkLetters(toks.subList(end, toks.size)) >= 4
            if (run.size > best.size) { best = run; bestSandwiched = sandwiched }
            start = -1
        }
        toks.forEachIndexed { i, tok ->
            if (CJK.containsMatchIn(tok)) close(i) else if (start < 0) start = i
        }
        close(toks.size)
        if (requireEdge && bestSandwiched) return null
        return best.takeIf { it.size >= min }?.joinToString(" ")
    }

    private fun isMark(token: String) = token.none { it.isLetterOrDigit() }

    /** CJK letters in [toks], a release group's signature left out. */
    private fun cjkLetters(toks: List<String>): Int =
        toks.filterNot { SEG_GROUP.containsMatchIn(it) }.sumOf { t -> t.count { CJK.matches(it.toString()) } }

    private val GENERIC = setOf(
        "video", "movie", "film", "index", "title", "untitled", "sample", "trailer",
        "clip", "output", "vid", "new", "temp", "playback", "stream", "fullmovie", "hd",
    )
    // …plus the disc-furniture stubs a BD rip leaves behind (`menu01`, `ncop1`), which name no work
    // and, left alone, become the search query for a whole folder of a show's extras.
    private val GENERIC_PATTERN = Regex(
        """^(vts\d+|title\d+|video\d+|movie\d+|s\d+e\d+|part\d+|cd\d+|disc\d+|menu\d*|nc(op|ed)\d*|(op|ed|pv|cm|sp)\d+)$""",
    )

    /** CJK (Han / Kana / Hangul) — a localized title that won't match TMDB's romanized search well. */
    private val CJK = Regex("""[㐀-鿿぀-ヿ가-힯]""")
    /** Forum / site stamps fansub uploaders glue onto filenames (影视帝国(bbs.cnxp.com), www.x.cn@…). */
    private val SITE_STAMP = Regex("""(?i)\b(?:www|bbs)\.[a-z0-9.-]+|@[a-z0-9-]+\.(?:com|cn|net|org)|影视帝国""")
    /** A scene release-group stub prefix: `tlf-enemy…`, `cbgb-sherlock…`, `japhson-alatriste`. */
    private val GROUP_STUB = Regex("""^[a-z0-9]{2,8}-[a-z]""")

    /** A parsed title carries little signal (generic/short/numeric) — prefer the folder. */
    private fun ParsedName.isWeak(): Boolean {
        val t = title.trim().lowercase()
        val compact = t.replace(" ", "")
        // A leftover bracket means the name defeated the parser (unbalanced/nested delimiters); the
        // remains are never a searchable title, so the folder is the better bet.
        return t.length < 2 || compact.all { it.isDigit() } || t.any { it in "[]【】" } ||
            compact in GENERIC || GENERIC_PATTERN.matches(compact)
    }

    /** A title that's a single long mashed token or a release-group stub — unmatchable as-is. */
    private fun ParsedName.isCryptic(): Boolean {
        val t = title.trim().lowercase()
        return GROUP_STUB.containsMatchIn(t) || (!t.contains(' ') && t.replace("-", "").length >= 9)
    }

    /**
     * Whether a movie's FILE name can be trusted over its folder. Scene layouts put the clean release
     * name on the FOLDER (`Cloud.Atlas.2012.BluRay-AiHD/`) and a garbled stub in the file
     * (`cloudatlas-x264.mkv`, `美国狙击手.特效中英字幕.american.sniper.2014…`, `www.site@科洛弗档案.rmvb`).
     * A file is trustworthy only when its title is real, not a stub, not localized CJK, and not a site
     * stamp — so a clean `Inception.mkv` is kept (and a boxset parent folder can't hijack it).
     */
    /**
     * Can the FILE's own title be trusted, or should the release folder supply it?
     *
     * A Chinese title used to count as untrustworthy, because Mp4Ba-style releases bury the real
     * (English) title inside a Chinese one. But [embeddedLatin] already digs that English title
     * out, so the rule only ever fired on names whose title genuinely IS Chinese — and then handed
     * the title to the folder. In `Documentary/NHK/秋叶原物语.avi` that folder is a BROADCASTER, so
     * all 226 documentaries in it parsed as "NHK" and 211 of them matched 第73回NHK紅白歌合戦.
     * A site-stamped or cryptic name is still distrusted, which is what actually catches the
     * Mp4Ba / 影视帝国 releases.
     */
    private fun trustworthyFile(p: ParsedName, raw: String): Boolean =
        !p.isWeak() && !p.isCryptic() && !SITE_STAMP.containsMatchIn(raw)

    /** Parent folder name of a file path (handles both / and \\ separators). */
    fun folderName(path: String): String? {
        val norm = path.replace('\\', '/').trimEnd('/')
        val parent = norm.substringBeforeLast('/', "")
        return parent.substringAfterLast('/').ifBlank { null }
    }

    /** Folder names that designate an ANIME library root — everything under one is treated as anime. */
    private val ANIME_ROOTS = setOf(
        "anime", "animes", "anime movies", "动漫", "動漫", "动画", "動畫", "动画片",
        "番剧", "番劇", "新番", "アニメ", "动漫剧", "国漫",
    )

    /** Library roots that mean "documentary" — the same argument as [ANIME_ROOTS]: the user filed
     *  it there, and that is a judgement no metadata lookup can override. */
    private val DOC_ROOTS = setOf(
        "documentary", "documentaries", "纪录片", "紀錄片", "记录片", "纪录", "docs", "doc",
    )

    /** True when [path] sits under a documentary library root. */
    fun isUnderDocRoot(path: String): Boolean =
        path.replace('\\', '/').split('/').any { it.trim().lowercase() in DOC_ROOTS }

    /**
     * Library roots that mean "music" — concerts, live sets, MVs, a guitar lesson.
     *
     * The same argument as [ANIME_ROOTS]: a folder is a judgement the person made about their own
     * files, and no metadata lookup overrides it. It carries more weight here than usual, because
     * the databases are actively misleading about music — TMDB puts its Music genre on 爆裂鼓手 and
     * 波西米亚狂想曲, which are films about music, while a recording of a concert is usually in no
     * database at all.
     */
    private val MUSIC_ROOTS = setOf(
        "music", "musik", "musique", "音乐", "音樂", "ミュージック", "음악",
        "concert", "concerts", "konzert", "live", "lives", "演唱会", "演唱會", "音乐会", "音樂會",
        "ライブ", "コンサート", "콘서트", "mv", "mvs", "music video", "music videos", "音乐视频",
    )

    fun isUnderMusicRoot(path: String): Boolean = underRoot(path, MUSIC_ROOTS)

    /**
     * Whether the folder a file sits in is a category of a music shelf — right under the shelf itself
     * (`Music/Guitar/`, `Music/ACG/`, `Music/Drum & Bass/`), sorting clips by kind and naming no work.
     * Asked of a database, such a name finds a work called that: 56 guitar clips became a film called
     * Guitar, 39 drum clips a drum'n'bass documentary. A concert's own folder sits a level down
     * (`Music/ACG/NANA MIZUKI LIVE FORMULA at SAITAMA SUPER ARENA/`), and the outermost root counts,
     * so `Music/Live/<a concert>/` is a concert's folder, `Music/Live/` a category.
     */
    fun inMusicCategoryFolder(path: String): Boolean {
        val folders = path.replace('\\', '/').trimEnd('/').split('/').filter { it.isNotBlank() }.dropLast(1)
        val root = folders.indexOfFirst { it.trim().lowercase() in MUSIC_ROOTS }
        if (root < 0) return false
        val own = folders.indices.lastOrNull { i ->
            folders[i].lowercase() !in CONTAINER_FOLDERS && !isSeasonFolder(folders[i])
        } ?: return false
        return own == root + 1
    }

    /**
     * Library roots that mean "game" — a recorded playthrough, a trailer, an esports match.
     *
     * Nothing else can say so. No metadata database indexes game footage, so unlike every other
     * category this one has the folder and the file name and nothing else; a recording called
     * `08.avi` is otherwise indistinguishable from an episode of anything.
     */
    /** Library roots that mean "for children". */
    private val KIDS_ROOTS = setOf(
        "kids", "kid", "children", "child", "family", "儿童", "兒童", "少儿", "少兒", "亲子", "親子",
        "キッズ", "こども", "子供", "어린이", "키즈",
    )

    fun isUnderKidsRoot(path: String): Boolean = underRoot(path, KIDS_ROOTS)

    private val GAME_ROOTS = setOf(
        "game", "games", "gaming", "游戏", "遊戲", "ゲーム", "게임", "esports", "e-sports", "电竞", "電競",
    )

    fun isUnderGameRoot(path: String): Boolean = underRoot(path, GAME_ROOTS)

    /**
     * Library roots that mean "mine" — what the person recorded or kept themselves.
     *
     * Worth more than a shelf. A clip off a camera has a name like `C1284.MP4`, which a database
     * will happily match to something: on a real library `C1284.MP4` came back as a 1947 work
     * called "C1284" and a folder of phone video became a film. Knowing the folder is personal is
     * what stops the question being asked at all.
     */
    private val PERSONAL_ROOTS = setOf(
        "my", "mine", "personal", "private", "home video", "home videos", "homevideos", "camera",
        "dcim", "我的", "个人", "個人", "家庭", "家庭录像", "家庭錄像", "自拍", "录像", "錄像",
        "マイビデオ", "ホームビデオ", "개인", "홈비디오",
    )

    fun isUnderPersonalRoot(path: String): Boolean = underRoot(path, PERSONAL_ROOTS)

    /** A recording somebody made themselves, by where it is filed or what the camera called it. */
    fun isOwnRecording(path: String, fileName: String): Boolean =
        isUnderPersonalRoot(path) || looksLikeCameraFile(fileName)

    /** A recording of somebody playing, a trailer, an esports match. */
    fun isGameFootage(path: String, fileName: String, title: String): Boolean =
        isUnderGameRoot(path) || GAME_TITLE.containsMatchIn(title) || GAME_TITLE.containsMatchIn(fileName)

    /** A recording of somebody playing, rather than a work anybody published. */
    private val GAME_TITLE = Regex(
        "(?i)\\bgameplay\\b|\\bwalkthrough\\b|\\bplaythrough\\b|\\blet's play\\b|\\bspeedrun\\b|" +
            "\\bno commentary\\b|实况|實況|攻略|通关|通關|游戏视频|遊戲影片|" +
            // Esports: the league is the title, and no film database has ever heard of any of them.
            "\\b(osl|gsl|spl|lck|lpl|lec|lcs|msi|dota ?2|csgo|owl)\\b|电子竞技|電子競技|" +
            // The industry's own events, which nothing else is called. Measured against a real
            // shelf these add four files and misfire on none — worth having and no more than that.
            //
            // What is deliberately NOT here: platform names (Switch, Steam), `E3`, and `State of
            // Play`, which is Sony's showcase and also a 2003 BBC serial and a 2009 film. Adding
            // that set caught seven more and mislabelled the film, which is the wrong trade: a
            // recording wrongly left alone merely keeps a plain name, while a film wrongly left
            // alone is never identified and nothing ever retries it.
            "\\bblizzcon\\b|\\bgamescom\\b|\\bchinajoy\\b|\\bnintendo direct\\b|" +
            "\\btokyo game show\\b|東京ゲームショウ|\\bsummer game fest\\b|\\bgame awards\\b|游戏展",
    )

    /**
     * True when no database anywhere could have an entry for this file, so there is nothing to ask.
     *
     * Two kinds of thing: what somebody recorded themselves, and footage of a game. Neither was ever
     * published as a work, which is not a gap in TMDB's coverage — there is nothing for it to cover.
     *
     * Asking anyway costs twice. It costs the time: on a real library these are 1,623 of 9,597
     * files, a sixth of every pass spent on questions with no answer. And it costs the answers,
     * because a search always returns SOMETHING — `C1284.MP4` off a camera came back as a 1947 work
     * called "C1284", and a folder of guitar covers came back as a 2021 film called 鹿角.
     */
    fun nothingToLookUp(path: String, fileName: String, title: String): Boolean =
        isOwnRecording(path, fileName) || isGameFootage(path, fileName, title)

    private fun underRoot(path: String, roots: Set<String>): Boolean =
        path.replace('\\', '/').split('/').any { it.trim().lowercase() in roots }

    /**
     * A name a camera gave a file, rather than a name anybody chose.
     *
     * `C1284.MP4`, `DSC_0031.JPG`, `IMG_4410.MOV`, `VID_20250903_101500.mp4`, `GOPR0142.MP4`,
     * `P1030512.MP4`, `20250903_101500.mp4`. A file called this was not published by anyone, so
     * there is nothing for a database to have an entry for — and the search that would otherwise be
     * run against it is the one that produces the worst matches of all.
     */
    private val CAMERA_NAME = Regex(
        "(?i)^(" +
            // A prefix and then digits — possibly in groups, as a camera writes a date and a time:
            // DSC_0031, IMG_4410, VID_20250903_101500.
            "(dsc|dscf|dscn|img|vid|mov|mvi|pict|gopr|gh0\\d|p10|p\\d{2})[ _-]?\\d{3,}([ _-]\\d{2,})*|" +
            "c\\d{3,}|" +
            "\\d{8}[ _-]\\d{6}|" +
            "\\d{4}[-_]\\d{2}[-_]\\d{2}[ _]\\d{2}[-_.]\\d{2}[-_.]\\d{2}" +
            ")$",
    )

    /** True when [fileName] is what a camera called it. Extension already stripped or not. */
    fun looksLikeCameraFile(fileName: String): Boolean =
        CAMERA_NAME.matches(dropExtension(fileName).trim())

    /** Library roots that mean "series", the TV counterpart of [ANIME_ROOTS]. */
    private val TV_ROOTS = setOf(
        "tv", "tvshow", "tvshows", "tv shows", "series", "show", "shows", "电视剧", "剧集", "美剧",
        "日剧", "韩剧", "国产剧", "连续剧",
    )

    /**
     * True when [path] sits under a TV library root. A file there is an episode of something even
     * when its own name says nothing — `TV/John Adams/tpz-johnadams1.avi` searched MOVIES, because
     * nothing in the name marks a season, and matched a concert film called "John Adams: El Niño".
     */
    fun isUnderTvRoot(path: String): Boolean =
        path.replace('\\', '/').split('/').any { it.trim().lowercase() in TV_ROOTS }

    /**
     * Library roots that mean "film", the counterpart of [TV_ROOTS].
     *
     * Weaker evidence than the others, and consulted last for that reason: a documentary or a
     * concert filed under `Movies/` is still a documentary or a concert. What it settles is only
     * the question nothing else could answer — a file nobody identified, whose name carries no
     * episode number. Somebody put it on the film shelf; that is more than we otherwise know.
     */
    private val MOVIE_ROOTS = setOf(
        "movie", "movies", "film", "films", "filme", "cinema", "电影", "電影", "影片", "映画",
        "영화", "大片", "蓝光电影", "藍光電影", "高清电影", "剧场版", "劇場版",
    )

    /**
     * An EDITION of a release, which is not part of the work's name.
     *
     * `Mobile Suit Gundam SEED Destiny HD Remaster` returns NOTHING from AniList; the same name
     * without those two words returns the series as its first hit. 88 files went to the base
     * series' TMDB entry over that, and the whole folder was named after the wrong show.
     *
     * Deliberately NOT in [NOISE]. That set marks where a title is CUT, and cutting at an edition
     * costs more than it wins — `… SEED Destiny HD Remaster - 03 - [AVC…]` carries its episode
     * number AFTER the edition, so the cut takes the number with it. Here the words are lifted out
     * of the middle and the rest closed up, which is only ever used to ASK a database something.
     */
    private val EDITION = Regex(
        "(?i)\\b(?:hd[\\s._-]+)?remaster(?:ed)?\\b|\\brestored\\b|\\buncut\\b|\\buncensored\\b|" +
            "\\bbd[\\s._-]?box\\b|\\bdigital[\\s._-]+remaster(?:ed)?\\b",
    )

    /**
     * [title] with any edition marker lifted out, or null when it carries none — or when taking it
     * out would leave nothing worth asking about.
     */
    fun withoutEdition(title: String): String? {
        if (!EDITION.containsMatchIn(title)) return null
        val out = Regex("""\s{2,}""").replace(EDITION.replace(title, " "), " ").trim().trim('-', '–', '—', '.')
        return out.takeIf { it.length >= 2 && it != title.trim() }
    }

    /** A folder that says the work is live-action — 実写, 真人, "Live Action" — anywhere on the path. */
    private val LIVE_ACTION = Regex("""(?i)実写|實寫|实写|真人(?:版|电影|剧|劇)?|live[\s._-]?action""")

    fun saysLiveAction(path: String): Boolean =
        path.replace('\\', '/').split('/').dropLast(1).any { LIVE_ACTION.containsMatchIn(it) }

    /** True when [path] sits under a film library root. */
    fun isUnderMovieRoot(path: String): Boolean = underRoot(path, MOVIE_ROOTS)

    /**
     * True when the name carries a mark only published material gets — a resolution, a source, a
     * codec, an audio format, a cut.
     *
     * Not "is this a film": it is the far weaker claim that somebody encoded and released this,
     * which is the one thing separating `Alien.1979.1080p.BluRay.mkv` from `00063.m2ts`. Deliberately
     * generous, because the cost is asymmetric — a false yes leaves a file exactly where it is today,
     * a false no moves it off the shelf it belongs on.
     */
    fun looksPublished(fileName: String): Boolean =
        dropExtension(fileName)
            // Every separator a release name is written with. Splitting MORE can only find more
            // tags, and a found tag leaves a file exactly where it already is, so this errs wide on
            // purpose: `[1080p,BluRay,x264,DTS]` is one token to a reader that does not know commas.
            .split(' ', '.', '_', '[', ']', '(', ')', '{', '}', ',', '，', '、', '+', '~', '～', ';', '；', '\t')
            .any { t ->
                val w = t.trim().lowercase()
                // Whole token first, because the vocabulary itself contains hyphens — `web-dl`,
                // `blu-ray`, `dts-hd`. Only if that fails is the token taken apart, for the names
                // that glue two tags together: `[1944][BluRay-720P.MKV]` is one token and two tags.
                w in NOISE || (w.contains('-') && w.split('-').any { it in NOISE })
            }

    /** True when any folder in [path] marks an anime library root (`smb://nas/video/Anime/…`). */
    fun isUnderAnimeRoot(path: String): Boolean =
        path.replace('\\', '/').split('/').any { it.trim().lowercase() in ANIME_ROOTS }

    /**
     * A title with no usable signal for a DB search — a bare episode number ("01", "08"), a one-char
     * token, or a generic release word ("Final", "OVA", "Movie"). These fuzzy-match a random popular
     * title, so they must never be used as a search query, and a file whose only name is one (a game
     * screen-recording's `08.avi`/`Final.rmvb`) is treated as unidentifiable.
     */
    private val JUNK_TITLE = setOf(
        "final", "fin", "end", "ending", "opening", "op", "ed", "ova", "oad", "oav", "ona", "sp",
        "special", "specials", "movie", "gekijouban", "preview", "trailer", "sample", "menu", "pv",
        "cm", "ncop", "nced", "ex", "extra", "extras", "complete", "tv", "disc", "cd", "bonus",
    )
    fun isJunkTitle(s: String?): Boolean {
        val t = (s ?: "").trim().lowercase()
        if (t.length < 2) return true
        if (t.replace(" ", "").all { it.isDigit() }) return true
        return t in JUNK_TITLE
    }

    /**
     * A name whose FIRST bracket lost its opener. `Ghost_in_the_Shell_Stand_Alone_Complex][Solid_
     * State_Society][BDRIP][1080P]` is a copy of a fully bracketed name truncated at the front, and
     * read as written its leading run is plain text — so the work's own bracket was taken for a tag
     * and thrown away, leaving the franchise alone as the query. A leading run that ENDS in `]`
     * says the opener was cut; a name that merely carries tags after a plain title does not.
     */
    private fun repairLostOpener(name: String): String =
        if (lostOpener(name)) "[$name" else name

    /** Was this name's first bracket cut off at the front? See [repairLostOpener]. */
    fun lostOpener(name: String): Boolean {
        if (name.startsWith("[") || name.startsWith("【")) return false
        val head = name.substringBefore('[')
        return head.length < name.length && head.trimEnd().endsWith("]")
    }

    private val BRACKET_SEG = Regex("""[\[【]\s*([^\[\]【】]*?)\s*[\]】]""")
    private val HEX8 = Regex("""(?i)^[0-9a-f]{8}$""")
    // Source / codec / quality / language tokens that fill fansub brackets — a segment made only of
    // these (or numbers) is never the title.
    private val FANSUB_TAG = setOf(
        "tvrip", "dvdrip", "bdrip", "brrip", "webrip", "hdtv", "hdtvrip", "bd", "dvd", "vcd", "hd", "sd", "full",
        "tv", "raw", "web", "rv10", "rmvb", "xvid", "divx", "hi10p", "ma10p", "ova", "oad", "oav", "sp",
        "menu", "ncop", "nced", "op", "ed", "pv", "cm", "all", "ost", "bk", "booklet", "scans", "movie", "sps",
        "gb", "big5", "jis", "chs", "cht", "jp", "jpn", "en", "eng", "cn", "chi", "sc", "tc", "gbk",
        "jap", "can", "man", "kor", "fre", "ger", "ass", "srt", "sub", "subs", "cc",
        // `[GB_MP4]` — a language and a container are two tags, not a two-word title.
        "mp4", "mkv", "avi", "flv", "wmv", "mov", "m2ts", "ts", "webm", "iso", "mpg", "mpeg", "vob",
    )

    /** `52movieba`, `4kfans` — digits then letters is how a site signs, never how a title is spelt. */
    private val SITE_TOKEN = Regex("""(?i)^\d{1,4}[a-z]{3,}\d*$""")

    private val PARENS = Regex("""[(（][^)）]*[)）]""")
    private val COUNT_SUFFIX = Regex("""[×✕]\s*\d+""")

    // ── bracket segments that are release metadata, never a title ─────────────────────────────
    /** A site stamp the uploader signs with: `圣城家园dream2008.cn`, `www.某站.com`. */
    private val SEG_SITE = Regex("""(?i)\b[a-z0-9-]+\.(?:cn|com|net|org|tv|cc|me|info)\b""")
    /** Subtitle / audio stamps: 中英双字幕, 中文字幕, 简繁中字, 内嵌, 外挂, Chi_Eng. */
    private val SEG_LANG = Regex("""(?i)^(?:[中英日简繁粤双多文语]{0,6}(?:字幕|双字|中字)|内[嵌封]|外挂|chi[ _]?eng|chs[ _]?jap)$""")
    /** A resolution stamp: `624X352`. */
    private val SEG_RES = Regex("""(?i)^\d{3,4}\s?[x×]\s?\d{3,4}$""")
    /** How the Chinese scene signs the LAST bracket: 人人影视, 诸神字幕组, XX压制. */
    private val SEG_GROUP = Regex("""影视|字幕组|字幕社|压制|发布组|论坛""")
    /** A whole bracket of quality/format words: `高清蓝光720P版BD-RMVB.中英双字`. Deliberately excludes
     *  words that also END real titles (加长版, 剧场版 keep their 加长/剧场 out of the vocabulary). */
    private val SEG_QUALITY = Regex(
        """(?i)^(?:高清|超清|蓝光|标清|国语|粤语|国粤|中字|双字|中英双字|原盘|完整|版|[-\s]|""" +
            """\d{3,4}[pi]|bd|hd|sd|rmvb|mkv|mp4|avi|dvd|web|rip|x26[45]|h\.?26[45]|aac|dts|ac3|""" +
            """remux|bluray|webdl)+$""",
    )

    private fun isReleaseMeta(seg: String): Boolean {
        val s = seg.trim()
        return SEG_SITE.containsMatchIn(s) || SEG_LANG.matches(s) || SEG_RES.matches(s) ||
            SEG_GROUP.containsMatchIn(s) || SEG_QUALITY.matches(s.replace(SEPARATORS, " "))
    }

    /** The segment carries a season/episode code — `S05E01`, `1x01`, `第五季`. */
    private fun hasCode(seg: String): Boolean {
        val s = seg.replace(SEPARATORS, " ")
        return SEASON_EP.containsMatchIn(s) || SEASON_EP_X.containsMatchIn(s) ||
            SEASON_CN.containsMatchIn(s) || SEASON_CN_CH.containsMatchIn(s)
    }

    /** No CJK and it reads like a whole name rather than an abbreviation ("Friends", not "EVA"). */
    private fun fullLatinTitle(s: String) =
        !CJK.containsMatchIn(s) && (s.contains(' ') || s.length >= 6)

    /**
     * Title of an ALL-bracket name — one where stripping the brackets would leave nothing at all,
     * so the normal parse falls back to the whole soup and a site stamp becomes the search query.
     *
     * Both scene conventions land here and they sign their work at OPPOSITE ends: Japanese fansubs
     * lead with the group (`[SumiSora][Munto][01][GB]` → Munto), the Chinese scene trails it
     * (`[好汉两个半.Two.And.A.Half.Men][S05E01][CN][624X352][YYeTs人人影视]`). The episode code tells
     * them apart — a bracket carrying one is the Chinese layout's signature, and there the leading
     * bracket is the title and must be kept.
     */
    private fun sceneTitle(noExt: String): String? {
        val segs = BRACKET_SEG.findAll(noExt).map { it.groupValues[1] }.toList()
        if (segs.size < 2) return null
        val coded = segs.any(::hasCode)
        val cands = segs.filterNot(::isReleaseMeta).mapNotNull(::fansubCand)
        val pool = if (!coded && cands.size >= 2) cands.drop(1) else cands
        // Nested title brackets read as one here as well (see fansubTitle): a FOLDER named
        // `[POPGO][Ghost_in_the_Shell_Stand_Alone_Complex][Solid_State_Society][BDRIP]` is the film,
        // not the series whose bracket it starts with.
        val joined = pool.zipWithNext().filter { (a, b) -> joinable(a) && joinable(b) }.map { (a, b) -> "$a $b" }
        // Prefer a Latin title: TMDB indexes those far better than a localized alias — the same
        // preference `embeddedLatin` applies WITHIN a segment, applied across them.
        val chosen = (pool + joined).maxWithOrNull(
            compareBy({ if (fullLatinTitle(it)) 1 else 0 }, { it.split(' ').size }, { it.length }),
        ) ?: return null
        // Keep the coded segments so the season/episode still parse out of the rebuilt name.
        return (listOf(chosen) + segs.filter(::hasCode).map { fansubCand(it) ?: it })
            .distinct().joinToString(" ")
    }

    /** A re-release marker (`V2`, `v3`) some groups append — never part of a title. */
    private val VERSION_STUB = Regex("""(?i)^v\d{1,2}$""")

    /** A bracket holding nothing but the episode code — `[S05E01]`, `[第五季]` — names no work. */
    private val CODE_ONLY = Regex(
        """(?i)^(?:s\d{1,2}\s?e\d{1,3}(?:\s?-\s?e?\d{1,3})?|\d{1,2}x\d{1,3}|""" +
            """第\s*[0-9一二三四五六七八九十两]{1,3}\s*[季集话話部]|ep?\d{1,3})$""",
    )

    /** Do [seg] and [folder] name the same thing — do they share a word that isn't a release tag? */
    private fun echoesFolder(seg: String, folder: String?): Boolean {
        if (folder == null) return false
        val words = { s: String ->
            s.replace(SEPARATORS, " ").replace(BRACKETS, " ").split(' ', '(', ')', '（', '）')
                .map { it.trim().lowercase() }
                .filter { it.length >= 3 && it !in FANSUB_TAG && it !in NOISE }
                .toSet()
        }
        return words(seg).any { it in words(folder) }
    }

    /** Clean a candidate segment and reject it if it's only tags / a number / a CRC hash / a code. */
    /** No title opens with a bare episode number — what does is a title that was thrown away. */
    private val OPENS_WITH_EPISODE = Regex("""^[0-9０-９]{1,3}(?:[ ._\-]|${'$'})""")

    /** A bracket segment that names a release group rather than a work. */
    private val GROUP_MARK = Regex("""(?i)字幕组|字幕社|压制|发布组|subs?\b|raws?\b|studio|team|works""")

    private fun bracketSeries(noExt: String, outside: String): String? {
        if (!OPENS_WITH_EPISODE.containsMatchIn(outside.trim())) return null
        val first = BRACKET_SEG.find(noExt)?.groupValues?.get(1)?.trim() ?: return null
        // Only a CJK name, and only one that does not announce itself as a group: a Latin bracket
        // at the head of a file is a release group far more often than it is a title.
        if (first.none { it.code in 0x3040..0x9FFF } || GROUP_MARK.containsMatchIn(first)) return null
        return first.takeUnless { isJunkTitle(it) || it.lowercase() in FANSUB_TAG }
    }

    private val EP_RANGE_HEAD = Regex("""^\s*\d{1,3}\s*[-~～]\s*\d{1,3}(?![0-9])""")

    /** Every bracket pair, nested ones included, replaced by a space. */
    private fun stripBrackets(s: String): String {
        var x = s
        repeat(3) {
            val y = x.replace(BRACKETS, " ")
            if (y == x) return y
            x = y
        }
        return x
    }

    private fun fansubCand(raw: String): String? {
        // A standalone decimal is a NAME (`Ghost in the Shell 2.0`, `Evangelion 2.22`) — the same
        // rule parse() applies; split, "2 22" is a query no database answers.
        val norm = raw.replace(PARENS, " ").replace(PARENS, " ").replace(COUNT_SUFFIX, " ").replace(STRAY_BRACKET, " ")
            .replace(NAME_DECIMAL) { m -> m.groupValues[1] + DOT_MARK + m.groupValues[2] }
            .replace('_', ' ').replace(SEPARATORS, " ").replace(DOT_MARK, '.')
            .replace(MULTISPACE, " ").trim()
        if (norm.isBlank() || HEX8.matches(norm.replace(" ", "")) || CODE_ONLY.matches(norm)) return null
        // `01-24TV全集+特典映像`, `01-13 END` — a bracket that OPENS with an episode range is the
        // release's contents list, never its name.
        if (EP_RANGE_HEAD.containsMatchIn(norm)) return null
        val toks = norm.split(' ').filter { it.isNotBlank() }
        // `Full_HD-BDRIP` is three tags, not a two-word title: read a hyphenated token part by part.
        fun isTag(t: String) = t.isEmpty() || t.all(Char::isDigit) || VERSION_STUB.matches(t) ||
            t.lowercase() in FANSUB_TAG || t.lowercase() in NOISE || SITE_TOKEN.matches(t)
        if (toks.all { tok -> tok.split('-', '+', '&').all(::isTag) }) return null
        return norm
    }

    /**
     * Title from a fansub name that Anitomy mis-reads — handles BOTH layouts:
     *  - all-bracket: `[POPGO][FREEWIND][TALES_OF_PHANTASIA][DVDRIP][01]` → "TALES OF PHANTASIA"
     *  - bracketed group + bare title: `[Kamigami] Laputa Castle in the Sky [BD…]` → "Laputa Castle in the Sky"
     * Picks the richest candidate (most words, then chars) among the bracket segments (minus the leading
     * group) and the text OUTSIDE the brackets, after dropping pure tags / numbers / CRC hashes.
     */
    /**
     * The release group a scene name closes with, after its tags and a dash —
     * `…[1080p,BluRay,x264,DTS]_-_THORA-UTW` — which a fansub name puts in front instead, and so every
     * reader of names here looked for in front only. Only after a closing bracket: what follows a
     * dash anywhere else is the name's own — a song (`B&T - Nirvana`), a part (`Kizumonogatari -
     * Tekketsu-hen`), an episode (`Show - 01`).
     */
    fun trailingGroup(stem: String): String? = TRAILING_GROUP.find(stem)?.groupValues?.get(1)

    /** [stem] without the [trailingGroup] it closes with. */
    fun withoutTrailingGroup(stem: String): String =
        TRAILING_GROUP.find(stem)?.let { stem.substring(0, it.range.first) } ?: stem

    private val TRAILING_GROUP = Regex("""(?<=[\])])[\s_]*-[\s_]+((?=[A-Za-z0-9.@&-]*[A-Za-z])[A-Za-z0-9][A-Za-z0-9.@&-]{1,24})$""")

    fun fansubTitle(fileName: String, folder: String? = null): String? {
        // Left on, the scene group was read as part of the title: "The Borrowers Arrietty - THORA-UTW".
        val noExt = withoutTrailingGroup(repairLostOpener(dropExtension(fileName)))
        val brackets = BRACKET_SEG.findAll(noExt).map { it.groupValues[1] }.toList()
        if (brackets.isEmpty()) return null // no fansub signal — let the normal parser handle it
        val bracketCands = brackets.filterNot(::isReleaseMeta).mapNotNull(::fansubCand)
        // The bare text between the brackets is the title in the `[Group] Title [tags]` layout.
        val outside = fansubCand(noExt.replace(BRACKET_SEG, " "))
        // The first non-tag bracket is the release GROUP ([SumiSora][Munto]…) — drop it when another
        // bracket title follows, else a long group ("SumiSora") would beat a short title ("Munto").
        // Drop it just the same when a bare title sits outside the brackets: `[Kamigami] 追逐繁星的孩子
        // [BD…]` is unambiguous, yet a CJK title has no spaces and so lost the "more words, then
        // longer" comparison to the group name — and the group name became the search query.
        val bracketPool = when {
            // …but the group is at the END in the Chinese layout, whose signature is a bracket
            // carrying the episode code; dropping the leading bracket there loses the title.
            brackets.any(::hasCode) -> bracketCands
            // …and it is no group at all when the enclosing FOLDER says the same thing. A group
            // name rarely echoes the folder; a title always does. `[Patlabor_OVA][NEW_OVA][BDRip]`
            // under `…Patlabor_機動警察(…)_OVA…` lost "Patlabor OVA" and searched "NEW OVA", which
            // AniList answers with 恶魔高校 D×D NEW; its sibling searched "EARLY DAYS" and got
            // The Place Promised in Our Early Days.
            bracketCands.size >= 2 && echoesFolder(bracketCands.first(), folder) -> bracketCands
            bracketCands.size >= 2 -> bracketCands.drop(1)
            outside != null -> emptyList()
            else -> bracketCands
        }
        // Nested brackets name the franchise THEN the work — `[POPGO][Stand_Alone_Complex]
        // [Solid_State_Society][Main_Movie]`. Each is a title on its own, and the franchise won the
        // "most words, then longest" contest, so the film searched as its series and landed on it.
        // Two adjacent plain-word titles read as one; a bracket carrying a tag or a number (`BOX1 SP`,
        // `S.A.C. 2nd GIG`) is filing, not a name, and is never joined.
        val joined = bracketPool.zipWithNext()
            .filter { (a, b) -> joinable(a) && joinable(b) }
            .map { (a, b) -> "$a $b" }
        val pool = bracketPool + joined + listOfNotNull(outside)
        // Anything but a collaboration first. Two groups sharing a release join their names with an
        // `&` — `[manmiV9&HinA]`, `[Nekomoe kissaten&VCB-Studio]` — and nothing else in a filename
        // does, while "most words, then longest" is exactly the contest a doubled group name wins:
        // `[双恋2].[manmiV9&HinA][Futakoi2]…` searched for manmiV9&HinA, and Futakoi2 — which AniList
        // answers with the right entry — was never asked at all.
        return pool.maxWithOrNull(compareBy({ if ('&' in it) 0 else 1 }, { it.split(' ').size }, { it.length }))
    }

    /** `2nd`, `3rd`, `4th` — an ordinal is part of a name, not a disc number. */
    private val ORDINAL_WORD = Regex("""(?i)[1-9](?:st|nd|rd|th)""")

    /** Words that make a bracket a label rather than a name, for the join above. */
    private val JOIN_STOP = setOf(
        "main", "movie", "ver", "version", "box", "vol", "disc", "special", "trailer", "preview",
        "making", "cg", "part", "menu", "ncop", "nced", "op", "ed", "pv", "cm", "sp", "extra",
        "extras", "session", "bonus", "full",
    )

    private fun joinable(seg: String): Boolean {
        // `[Movie_I_Earth_Light]`, `[The_Movie-The_Wings_of_Goodbye]` — a film's bracket opens with the
        // word that says so; what follows is its name.
        val words = seg.split(' ').filter { it.isNotBlank() }
            .let { w -> if (w.firstOrNull()?.lowercase() == "the") w.drop(1) else w }
            .let { w -> if (w.firstOrNull()?.lowercase() in setOf("movie", "film")) w.drop(1) else w }
        return words.size >= 2 && words.none { w ->
            // A digit marks filing — BOX1, Vol 2, disc 3 — except as an ORDINAL inside a longer
            // name: `S.A.C. 2nd GIG` and `2nd Raid` are what the work is called, and refusing to
            // join them left the franchise bracket alone as the query.
            (w.any(Char::isDigit) && !ORDINAL_WORD.matches(w)) || w.split('-').any { part ->
                val k = part.lowercase()
                k in FANSUB_TAG || k in JOIN_STOP || k in NOISE
            }
        }
    }

    /**
     * The show folder directly under the anime root — its clean franchise title source
     * (`…/Anime/银河英雄传说/Legend…002.mkv` → "银河英雄传说"). Null if the file sits at the root.
     */
    /**
     * The folders between the anime root and the file, INNERMOST first.
     *
     * A box is often three deep — `Anime/逮捕令_逮捕しちゃうぞ_OVA.TV.MOVIE/[1996-1997] …_TV/DVDRip…/file`
     * — and the folder that says what THIS work is sits in the middle. Reading only the innermost
     * and the outermost skips it.
     */
    fun folderChain(path: String): List<String> {
        val segs = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
        val idx = segs.indexOfLast { it.trim().lowercase() in ANIME_ROOTS }
        if (idx < 0 || idx + 1 >= segs.lastIndex) return emptyList()
        return segs.subList(idx + 1, segs.lastIndex).reversed()
    }

    fun animeShowFolder(path: String): String? {
        val segs = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
        val idx = segs.indexOfLast { it.trim().lowercase() in ANIME_ROOTS }
        if (idx < 0) return null
        return segs.getOrNull(idx + 1)?.takeIf { idx + 1 < segs.lastIndex } // a folder, not the file
    }

    /**
     * Full path up to (and including) the show folder under the anime root — a stable grouping key so
     * every file of one franchise collapses into one card even when it's spread over nested sub-folders
     * (`…/Anime/Remember11/中文影像版/悟篇/01.avi` → `…/Anime/Remember11`). Null if not under an anime root
     * or the file sits directly at the root (no show folder).
     */
    fun animeShowRoot(path: String): String? {
        if (rootCache.size > 32_768) rootCache.clear()
        // ConcurrentHashMap can't hold nulls; encode "no root" as "".
        return rootCache.getOrPut(path) { animeShowRootUncached(path) ?: "" }.ifEmpty { null }
    }

    private fun animeShowRootUncached(path: String): String? {
        val norm = path.replace('\\', '/').trimEnd('/')
        val segs = norm.split('/')
        val idx = segs.indexOfLast { it.trim().lowercase() in ANIME_ROOTS }
        if (idx < 0 || idx + 1 >= segs.lastIndex) return null // need a show folder above the file
        return segs.subList(0, idx + 2).joinToString("/")
    }

    /**
     * Parse using the filename, falling back to the parent folder when the filename is weak
     * (Plex/Infuse-style): a generic file like `video1.mkv` inside `Interstellar (2014)/` still
     * resolves correctly. Also borrows a year from the folder when the file lacks one but the
     * folder names the same title.
     */
    fun parseBest(fileName: String, folderName: String?): ParsedName {
        val fromFile = parse(fileName)
        if (folderName.isNullOrBlank()) return fromFile
        val fromFolder = parse(folderName)
        return when {
            fromFile.isWeak() && !fromFolder.isWeak() -> fromFolder.copy(
                season = fromFile.season ?: fromFolder.season,
                episode = fromFile.episode ?: fromFolder.episode,
            )
            fromFile.year == null && fromFolder.year != null && !fromFolder.isWeak() &&
                StringSimilarity.titleSimilarity(fromFile.title, fromFolder.title) > 0.6 ->
                fromFile.copy(year = fromFolder.year)
            else -> fromFile
        }
    }

    /** Container folders that name a location, not a title — never used as the show name. */
    /** An address's scheme as the path splits it ("smb:" before "//host"): not a folder of the film's. */
    private val URI_SCHEMES = setOf("smb:", "http:", "https:", "ftp:", "sftp:", "nfs:")

    private val CONTAINER_FOLDERS = setOf(
        "video", "videos", "movie", "movies", "film", "films", "tv", "tvshow", "tvshows",
        "show", "shows", "series", "media", "download", "downloads", "下载", "迅雷", "电影",
        "电视剧", "动漫", "番剧", "剧集", "anime", "share", "shared", "public", "home", "homes",
        "nas", "music", "photo", "photos", "新建文件夹", "新片", "合集",
        "bdmv", "stream", "video_ts", "disc", "disc1", "disc2",
        // A broadcaster folder is a shelf too: `Documentary/NHK/` holds ~170 unrelated programmes,
        // and letting it name them made 226 files scrape to 第73回NHK紅白歌合戦 — one poster for the
        // whole shelf. Same idea as LEADING_NETWORK, applied to folders.
        "nhk", "bbc", "pbs", "hbo", "itv", "cctv", "央视", "discovery", "natgeo",
        "national geographic", "arte", "探索频道", "纪录片", "documentary", "documentaries",
    )

    /** A folder that only marks a season / specials bucket (climb past it to find the show name). */
    private val SEASON_FOLDER = Regex(
        // `\d{1,2}` alone covers the bare-number season directories a lot of TV rips use
        // (`Two and a Half Men/01/…`, `/02/…`); two digits max so a film folder named for a year
        // ("1917", "2012") is never mistaken for a season.
        """(?i)^(season\s*\d{1,2}|s\d{1,2}|\d{1,2}|第\s*[0-9一二三四五六七八九十两]{1,3}\s*[季部]|""" +
            """specials?|sps?|extras?|特典|花絮|scans?|cds?|ova|ovas|oad|oads|menu|pv|cm|""" +
            // 総集編 (recap compilation) subfolders: NHK-style rips file a show's recap cuts under a
            // bare 総集編/ dir. Left unskipped it becomes the file's nearest folder, the recap's own
            // chapter numbers become the query, and four kamakura recaps matched SSSS.GRIDMAN.
            // A rip-revision dir (`Version 2/`, `v2/`) holds the same work re-encoded — climb past
            // it, or its name becomes both the query ("Version 2") and a phantom season number.
            """総集編|总集编|總集編|soushuuhen|v(?:ersion)?[\s._-]*\d{1,2})$""",
    )

    fun isSeasonFolder(name: String): Boolean = SEASON_FOLDER.matches(name.trim())

    /**
     * Resolve a title from the whole path (Plex/Infuse "path ladder"): the episode number comes
     * from the filename, but the show name and season are recovered by climbing the folders —
     * `Show/Season 3/ep.mkv`, `Show 第三季/ep.mkv`, or seasons scattered under `video/` all
     * resolve to the same show. Container folders (download, video, the NAS share…) are skipped.
     */
    /**
     * A box named for its OWNER or its span rather than for a work — `宮崎駿全集 Miyazaki Hayao All
     * Movies`, `今敏 Kon Satoshi All Movies`, `新海诚（Makoto Shinkai）全集`, `The.Dark.Knight.Collection
     * .2005-2012`. It shelves several works, so it is a shelf: its name is no query (the director's
     * name in its parenthetical was searched and matched his most popular film), and it anchors and
     * adopts nothing. A lone "Collection" is not enough — a work can be called that — so it needs a
     * year span beside it; and `01-24TV全集` is one work's contents list, so 全集 only counts when
     * nothing Latin or numeric runs into it.
     */
    private val COLLECTION_FOLDER = Regex(
        """(?i)(?<![0-9A-Za-z])全集|作品集|合集|全作品|\ball\s+(?:movies|films|works)\b|\bcomplete\s+works\b|\bfilmography\b|""" +
            """\bcollection\b.*\b(?:19|20)\d{2}\s*[-–~]\s*(?:19|20)\d{2}\b|\b(?:19|20)\d{2}\s*[-–~]\s*(?:19|20)\d{2}\b.*\bcollection\b""",
    )

    /** A folder that shelves works rather than naming one — a library root, a broadcaster, a disc
     *  dir, or a director's / franchise box (see [COLLECTION_FOLDER]). */
    fun isShelfFolder(name: String): Boolean {
        shelfFolders[name]?.let { return it }
        val shelf = name.trim().lowercase() in CONTAINER_FOLDERS || COLLECTION_FOLDER.containsMatchIn(name)
        // The wall's grouping asks this of the same few hundred folder names for every file, at every
        // regroup: answered from memory, as the parser's other per-name readings are.
        if (shelfFolders.size >= 8_192) shelfFolders.clear()
        shelfFolders[name] = shelf
        return shelf
    }

    private val shelfFolders = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * The title of the release/show folder a file sits in — the other half of its name. Skips the
     * library shelf itself ([CONTAINER_FOLDERS]) and season/extras subfolders, so
     * `TV/Two and a Half Men/01/[YYsoR]好汉两个半第1季01…` answers "Two and a Half Men" and a file
     * loose at a shelf root answers nothing.
     */
    fun showFolderTitle(path: String): String? {
        val segments = path.replace('\\', '/').trimEnd('/').split('/')
            .filter { it.isNotBlank() && it !in URI_SCHEMES }
        segments.dropLast(1).asReversed().take(4).forEach { folder ->
            if (folder.lowercase() in CONTAINER_FOLDERS || isSeasonFolder(folder)) return@forEach
            val p = parse(folder)
            if (!p.isWeak()) return p.title
        }
        return null
    }

    fun parseFromPath(path: String): ParsedName {
        val norm = path.replace('\\', '/').trimEnd('/')
        val segments = norm.split('/')
            .filter { it.isNotBlank() && it !in URI_SCHEMES }
        if (segments.isEmpty()) return ParsedName("")
        val fileName = segments.last()
        val folders = segments.dropLast(1).asReversed() // nearest parent first

        val fromFile = parse(fileName)

        // Movies: the release FOLDER is the authoritative title on scene layouts — only the file is
        // garbled (`…/Cloud.Atlas.2012.BluRay-AiHD/cloudatlas-x264.mkv`, `…/Cloverfield/www.x@科洛弗档案`).
        // When the file title can't be trusted, adopt the nearest informative folder's title + year.
        // A clean, year-bearing release folder also wins over a year-less file so a boxset parent can't.
        if (!fromFile.isEpisode && !trustworthyFile(fromFile, fileName)) {
            for ((i, folder) in folders.withIndex()) {
                if (i >= 4) break
                if (folder.lowercase() in CONTAINER_FOLDERS || isSeasonFolder(folder)) continue
                val p = parse(folder)
                if (!p.isWeak()) return dropOwnedYear(ParsedName(p.title, p.year ?: fromFile.year))
            }
        }

        var title = fromFile.title
        var weak = fromFile.isWeak()
        var season = fromFile.season
        var episode = fromFile.episode
        var year = fromFile.year

        for ((i, folder) in folders.withIndex()) {
            if (i >= 4) break // don't climb into the NAS root
            if (folder.lowercase() in CONTAINER_FOLDERS) continue
            val p = parse(folder)
            if (season == null && p.season != null) season = p.season
            if (year == null && p.year != null) year = p.year
            if (weak && !isSeasonFolder(folder) && !p.isWeak()) {
                title = p.title
                year = year ?: p.year
                weak = false
            }
            if (!weak && season != null) break
        }
        return dropOwnedYear(ParsedName(title = title, year = year, season = season, episode = episode))
    }

    private val CN_DIGITS = mapOf(
        '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    /** Small Chinese-numeral reader for season numbers (一..二十九). */
    private fun cnNumber(s: String): Int? {
        if (s.isBlank()) return null
        s.toIntOrNull()?.let { return it }
        val ten = s.indexOf('十')
        return when {
            ten < 0 -> CN_DIGITS[s.first()]
            s == "十" -> 10
            ten == 0 -> 10 + (CN_DIGITS[s.getOrNull(1)] ?: 0) // 十一..十九
            else -> (CN_DIGITS[s[0]] ?: 0) * 10 + (CN_DIGITS[s.getOrNull(ten + 1)] ?: 0) // 二十..
        }
    }

    /** Audio channel layouts — release tags only once the release part of the name has begun. */
    private val AUDIO_LAYOUT = setOf("2.0", "5.1", "7.1")

    private fun firstNoiseCharIndex(work: String): Int {
        var idx = 0
        var prevNoise = false
        work.split(' ').forEachIndexed { i, token ->
            val clean = token.lowercase().trim('-', '–', '—', '.', ',')
            // Never cut at the very first token: a real title may legitimately open with a word that
            // is also a release tag (`DC League`, `SE7EN`), but "DC"/"IMAX" mid-name IS a tag.
            // …and `2.0` straight after title words is a NAME (`Ghost in the Shell 2.0`); it is the
            // DTS 2.0 audio tag only once a quality/codec word or the year has gone before it.
            // `DVD-RMVB-人人影视-bddiudiu` is one token whose first part says everything: a hyphenated
            // token that OPENS with a release word is noise. Only the first part — Spider-Man,
            // X-Men, Wall-E and Blu-Ray open with a name, and only a whole match cut them before.
            val inNoise = clean.isNotEmpty() && (clean in NOISE || clean.substringBefore('-') in NOISE)
            if (i > 0 && inNoise && (clean !in AUDIO_LAYOUT || prevNoise)) return idx
            prevNoise = inNoise || YEAR.matches(clean)
            idx += token.length + 1
        }
        return work.length
    }
}
