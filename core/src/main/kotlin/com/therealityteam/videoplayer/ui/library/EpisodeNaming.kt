package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind

/**
 * Single source of truth for how one file inside a title group is named, categorized and ordered in
 * the detail view. Everything is re-derived from the FILENAME — the persisted season/episode/tag are
 * unreliable for fansub-style anime (season "S2" is missed, extras like PV03 get episode numbers,
 * Menu/CM/Program get tagged MAIN) — so the detail list stays correct without a re-scrape:
 *   • numbered episodes ("[05]", "S2E03", "EP07") → that season's episode list (deduped across versions)
 *   • OVA / OAD                                   → their own OVA section
 *   • OP/ED/NC/PV/CM/Menu/Teaser/Program/特典/…    → the 特典 bucket, always AFTER the episodes
 *   • a creditless "(NC Ver.)" of an episode      → 特典, as "Episode N (NC Ver.)" (not a duplicate row)
 *   • anything else inside a show                  → a named special (特典), never a bogus episode number
 */
object EpisodeNaming {

    enum class Cat { EPISODE, OVA, EXTRA, FEATURE }

    data class Info(
        val cat: Cat,
        val season: Int?,
        val episode: Double?,
        val version: String?,    // qualifier on a numbered episode, e.g. "NC Ver."
        val extraType: String?,  // canonical extra/OVA token (for labeling + sort rank)
        val extraNum: Int?,
        val name: String?,       // leftover descriptor for a named special / feature
    )

    // ── Recognizers (all delimiter-aware so a token never fires inside a word) ──
    private val SXXEXX = Regex("""(?i)\bS(\d{1,2})E(\d{1,3})\b""")
    /** At most three digits: a Blu-ray's own stream files are 00001.m2ts, and they are no episodes. */
    private val STEM_NUMBER = Regex("""(\d{1,3})""")
    private val N_OF_M = Regex("""(?i)(?<![0-9a-z])0*(\d{1,2})\s*of\s*\d{1,2}(?![0-9])""")
    /** "the.west.wing.1x01.pilot": season x episode — never a 1920x1080 or a 16x9. */
    private val NXNN = Regex("""(?i)(?<![0-9a-z])(\d{1,2})x(\d{2,3})(?![0-9a-z])""")
    private val SEASON = listOf(
        Regex("""(?i)\bseason\s*0*(\d{1,2})"""),
        Regex("""(?i)\b(\d{1,2})(?:nd|rd|th|st)\s+season"""),
        Regex("""(?:^|[\s._\-\[(])S(\d{1,2})(?=[\s._\-\])])"""), // a bare " S2 " token
        Regex("""第\s*(\d{1,2})\s*[季期]"""),
    )
    private val SEASON_CN = Regex("""第\s*([一二三四五六七八九十]+)\s*[季期]""")
    private val CN_NUM = mapOf("一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10)

    // A bracket that is ONLY a number (+ optional .5 and a "(qualifier)"): [01], [04.5], [02(NC Ver.)]
    // — a re-release of one, [12v2] — and the last episode as the groups mark it: [103Fin], [12END],
    // [24 END], [26完].
    private val BRACKET_EP = Regex("""(?i)\[\s*(\d{1,3}(?:\.5)?)(?:v\d)?\s*(?:_?(?:END|Fin|Final)|完结?|完結|終|终)?\s*(?:\(([^)]*)\))?\s*\]""")
    /** The same last episode outside brackets: "Show - 12 END", "Show - 13 Fin", "Show 26完" (not 完全版). */
    private val FINAL_EP = Regex("""(?i)(?:^|[\s._\-])0*(\d{1,3})\s*_?(?:END|Fin)(?=[\s._\-()\[\]]|$)|(?:^|[\s._\-])0*(\d{1,3})\s*(?:完结?|完結|終|终)(?!\p{L})""")
    // Not the 第 of a season: "好汉两个半第1季01" had read as episode 1 — every file of season 1 alike,
    // so all but one went under 特典 as copies of it.
    // …nor the 第 of a promo, a piece or a volume (第1弹特報, ソナタ第2番, DVD第1巻) or a span (第1~3回).
    private val EP_WORD = Regex("""(?i)(?:\bep|\bepisode|第)\s*0*(\d{1,3})(?!\d)(?!\s*[季期弹弾彈番巻卷~～])\s*[话話集]?""")
    /** "第1季01", "第四季01", "第2季第03集": the season, then the episode straight after it. */
    private val SEASON_EP_CN = Regex("""第\s*(\d{1,2}|[一二三四五六七八九十]+)\s*[季期]\s*(?:第\s*)?0*(\d{1,3})(?!\d)\s*[话話集]?""")
    /** Marks that, beside an OVA's, say it is a special — still the OVA, not the disc's extra. */
    private val OVA_SPECIALS = setOf("SP", "SPECIAL")

    private val OVA = Regex("""(?i)(?:^|[\s._\-\[])(OVA|OAV|OAD)\s*\.?\s*(\d{1,3})?(?=[\s._\-\]]|$)""")
    // A recap / 総集編 compilation ("[S2 EP01-12 Digest]") — NOT a real episode, even though it
    // carries a season + an EPnn range. Caught before EP_WORD so it never mints a bogus "第 N 集".
    /**
     * "IV" alone is four — Star.Wars.Episode.IV, "(FF IV)", Apocalypse…War.IV — and an interview
     * carries its number: [IV01], "- IV02", [IV02_1]. Read as interviews, Star Wars IV's two versions
     * were both extras and its card read "2 个文件".
     */
    private fun romanFour(m: MatchResult): Boolean = m.groupValues[1].equals("IV", ignoreCase = true) && m.groupValues[2].isEmpty()

    /** Whether [fileName] says in a bracket of its own that it is the main feature: "[MAIN_MOVIE]", "【本編】". */
    fun marksMain(fileName: String): Boolean = MAIN_MARK.containsMatchIn(fileName)

    private val MAIN_MARK = Regex("""(?i)[\[【]\s*(?:main[\s_.\-]?(?:movie|feature)|本編|本篇|正片)\s*[\]】]""")

    /** A disc's catch-all words for what is on it besides the film: "Extras1", "cor.extras.…", "Deleted.Scenes". */
    private val GENERIC_EXTRA = Regex("""(?i)(?:^|[\s._\-\[(（【])(extras|deleted[\s._\-]?scenes?)\s*\.?\s*(\d{1,3})?(?=[\s._\-\]()）】]|$)""")

    /**
     * What follows a generic extra's word, as its name — "Anatomy of a Scene" of "Memento.Extras1.
     * Anatomy.of.a.Scene-Grym" — without the release's tags, checksum or group; null when nothing does.
     */
    private fun tailName(rest: String): String? {
        val words = rest.replace(BRACKETED, " ").trim().replace(GROUP_SUFFIX, "").replace(WORD_SEPARATORS, " ")
        return words.trim(' ', '-', '·').ifBlank { null }
    }

    private val BRACKETED = Regex("""\[[^\]]*]""")
    /** A release group at the very end of a name: "-Grym", "-Grym@BTNET". */
    private val GROUP_SUFFIX = Regex("""-[A-Za-z0-9@]+$""")
    private val WORD_SEPARATORS = Regex("""[._\s]+""")

    /** A name that is nothing but a web address: "RARBG.com", "www.YTS.MX". */
    private val WEB_ADDRESS = Regex("""(?i)(?:www\.)?[a-z0-9][a-z0-9-]*\.(?:com|net|org|to|mx|am|lt|ag|cc|tv)""")
    private val DIGEST = Regex("""(?i)(?:^|[\s._\-\[])(?:digest|recap)(?=[\s._\-\]()]|$)|総集編|総集篇|总集篇|总集编""")
    // "(Sample)" and "【PV】" set a token apart as surely as a space does: Spider-Verse's sample, named
    // "…-MgB (Sample).mkv", played as the film.
    private val EXTRA = Regex(
        """(?i)(?:^|[\s._\-\[(（【])(FEATURETTES|TRAILERS|TEASERS|INTERVIEWS|SPECIALS|""" +
            """NCOP|NCED|OPED|MENU|COLLECTION|TRAILER|TEASER|PREVIEW|FEATURETTE|""" +
            """INTERVIEW|MAKINGOF|MAKING|DOCUMENTARY|BOOKLET|SPECIAL|BONUS|SAMPLE|PROGRAM|ANNOUNCEMENT|CONCERT|""" +
            """GREETING|MANUAL|TALK|TVCM|PV|CM|IV|IB|SP|NC|YOKOKU)\s*\.?\s*(\d{1,3})?(?=[\s._\-\]()）】]|$)""",
    )
    /**
     * An extra's token alone in its own brackets, ahead of the number after it: "[Menu][01]" is the
     * first menu, not episode 1 — read as one, it was what 播放 played for 奈叶 The MOVIE 2nd A's.
     */
    private val BRACKETED_EXTRA = Regex(
        """(?i)\[(NCOP|NCED|MENU|TRAILER|TEASER|PREVIEW|SPECIAL|BONUS|SAMPLE|INTERVIEW|MAKING|TVCM|PV|CM|SP)\s*(\d{1,3})?\](?:\[(\d{1,3})\])?""",
    )
    /**
     * The words Japanese and Chinese discs and groups name their extras with — no spaces around them to
     * find a token by, so they are read as they stand: トップをねらえ2's 映像特典「BD CF集」 and the
     * 制作花絮 of 二次大战启示录 had played as the films.
     */
    private val CJK_EXTRAS = listOf(
        "映像特典" to "BONUS", "特典映像" to "BONUS", "ノンテロップ" to "NC", "本予告" to "TRAILER", "予告編" to "TRAILER",
        "予告" to "TRAILER", "预告片" to "TRAILER", "预告" to "TRAILER", "預告" to "TRAILER", "特報" to "TRAILER",
        "特报" to "TRAILER", "花絮" to "MAKING", "メイキング" to "MAKING", "CM集" to "CM", "CF集" to "CM", "番宣" to "CM",
        "メニュー" to "MENU", "特典" to "BONUS",
    )
    // Brackets that are pure release noise (skipped when picking a descriptive name for a special).
    private val NOISE_BRACKET = Regex(
        """(?i)\d{3,4}p|x26[45]|h\.?26[45]|hevc|avc|av1|vp9|xvid|flac|aac|ac3|eac3|opus|truehd|""" +
            """ddp?5?|10bit|8bit|hi10p|ma10p|hi444|dovi|hdr|sdr|bluray|web|remux|&""",
    )

    private val EXTRA_ORDER = listOf(
        "DIGEST", "SPECIAL", "SP", "NCOP", "NCED", "OPED", "NC", "MENU", "COLLECTION",
        "PV", "IV", "IB", "CM", "TRAILER", "TEASER", "PREVIEW", "PROGRAM", "ANNOUNCEMENT",
        "MAKING", "DOCUMENTARY", "INTERVIEW", "TALK", "GREETING", "CONCERT", "FEATURETTE", "DELETED", "BONUS",
    )
    /**
     * The extra types that are NAMED for the reader, in their language — the only translated thing
     * about a file's name (each app words them: its ep_t_* strings). Everything above is vocabulary
     * matched against FILE NAMES (第N話, 総集編, OVA, NCOP…) and must stay in the language the files
     * are written in, whatever language the reader has chosen. A token that reads the same
     * everywhere (PV, CM, SP, OVA, OAD…) is absent here and shown verbatim.
     */
    val NAMED_TYPES: Set<String> = setOf(
        "NCOP", "NCED", "OPED", "NC", "MENU", "COLLECTION", "TRAILER", "TEASER", "PREVIEW", "PROGRAM",
        "ANNOUNCEMENT", "FEATURETTE", "INTERVIEW", "MAKING", "DOCUMENTARY", "TALK", "GREETING",
        "CONCERT", "BOOKLET", "SPECIAL", "BONUS", "SAMPLE", "DIGEST", "MANUAL", "AD", "DELETED",
    )

    /** Fullwidth digits ０-９ → ASCII so episode numbers parse (第２話) — ART's parseInt is ASCII-only. */
    private fun normalizeDigits(s: String): String =
        if (s.none { it in '０'..'９' }) s else s.map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("")

    /** Re-derive a file's identity from its name (persisted fields are unreliable — see class doc). */
    /**
     * A bare instalment number, read only for a card the wall has judged a NUMBERED RUN no database
     * named (激流中国's `.01.喉舌与职责`, Rick Steves' `01of11`, `毛泽东-01.反潮流`): anywhere else a
     * bare number is a sequel (Terminator 2) or part of the name, and stays out of the episode list.
     */
    private val BARE_NUMBER = Regex("""(?<=[\s.\u3000\-\]】)）])([0-9]{1,3})(?=[\s.\u3000\-]|of[0-9]|$)""")

    /**
     * Whether a series' files are a run of instalments by their names alone: none holds a number the
     * scan read, and most hold a bare one — 激流中国's `[激流中国].01.喉舌与职责`, Rick Steves'
     * `Rick.Steves.Europe.12.Austria…`, `StarshipOperators-01-CountDown`. A database named the show and
     * none of its files; read without this, every file was a named special and the whole show was
     * listed under 特典, with no episodes at all.
     */
    fun numberedByNames(items: List<MediaItem>): Boolean {
        val mains = items.filterNot { it.contentTag == ContentKind.EXTRA }.ifEmpty { items }
        if (mains.size < 2 || mains.any { it.episode != null }) return false
        val numbers = mains.mapNotNull { BARE_NUMBER.find(normalizeDigits(stripExt(it.fileName)))?.groupValues?.get(1)?.toIntOrNull() }
        // …numbers that run: one number every file holds is a release's, not an instalment's — the
        // 5 of `AAC.5.1ch` on both of 攻殻機動隊 S.A.C.'s OVA films.
        return numbers.size >= 2 && numbers.size * 2 >= mains.size && numbers.distinct().size >= 2
    }

    /**
     * What each instalment of a numbered run calls itself, by item id: the words after its number
     * (`.04.民告官.土地攻防战` → 民告官·土地攻防战), or, for one with no number, after the words the run
     * opens with (`[激流中国].(引子).富人与穷人` → 引子·富人与穷人) — up to the release's own words (720p,
     * XviD, a group's tag) and without the words every instalment ends with: Queen Victoria's Empire
     * closes each with `festering.leper`, Jane Eyre's `1 of 4 DaRmEtH` says nothing of its own.
     */
    fun instalmentNames(items: List<MediaItem>): Map<String, String> {
        val stems = items.associate { it.id to normalizeDigits(stripExt(it.fileName)) }
        val numbered = stems.mapNotNull { (id, s) -> BARE_NUMBER.find(s)?.let { id to it } }.toMap()
        if (numbered.size < 2) return emptyMap()
        val opening = numbered.map { (id, m) -> stems.getValue(id).substring(0, m.range.first) }
            .reduce { a, b -> a.commonPrefixWith(b) }
        val tails = stems.mapNotNull { (id, s) ->
            val m = numbered[id]
            val tail = when {
                m != null -> s.substring(m.range.last + 1)
                opening.isNotBlank() && s.startsWith(opening) -> s.substring(opening.length)
                else -> null
            } ?: return@mapNotNull null
            nameWords(tail).takeIf { it.isNotEmpty() }?.let { id to it }
        }
        // Words every instalment ends with are the release's, not the instalments'.
        val shared = if (tails.size < 2) 0 else {
            var k = 0
            while (tails.all { (_, w) -> w.size > k } &&
                tails.map { (_, w) -> w[w.size - 1 - k].lowercase() }.distinct().size == 1
            ) k++
            k
        }
        return tails.mapNotNull { (id, w) -> joinName(w.dropLast(shared))?.let { id to it } }.toMap()
    }

    /** A run's tail as words: its tags gone, cut where the release's own words begin. */
    private fun nameWords(tail: String): List<String> {
        val bare = tail
            .replace(BRACKETED, " ").replace(CJK_BRACKETED, " ")
            // A group's tag in parentheses, "(anime-mx)", "(BDRip 1920x1080 x264 FLAC)"; a CJK
            // parenthesis is part of the name — "(最终回)", "(引子)".
            .replace(LATIN_PARENS) { m -> if (SearchKeys.hasCjk(m.value)) " " + m.value.trim('(', ')', '（', '）') + " " else " " }
            .replace(LEADING_SPAN, " ")
        val words = bare.split(NAME_GAPS).filter { it.isNotEmpty() }
        val cut = words.indexOfFirst { RELEASE_WORD.matches(it) }
        // A name written in one word, `TrafalgarCrisis`, `GreatEscape-PartII`, is read in its words.
        return (if (cut >= 0) words.take(cut) else words).flatMap { w ->
            if (w.all { it.isLetterOrDigit() && it.code < 128 }) w.split(CAMEL_GAP) else listOf(w)
        }
    }

    /** Words joined as the script reads: 民告官·土地攻防战, Austria Salzburg; a lower-case title capitalised. */
    private fun joinName(words: List<String>): String? {
        if (words.isEmpty()) return null
        val out = StringBuilder(words.first())
        for (i in 1 until words.size) {
            // A middle dot where either side is written in Han or kana — 病人的长龙·13亿人的医疗 —
            // a space between Latin words.
            val cjk = SearchKeys.hasCjk(words[i - 1].last().toString()) || SearchKeys.hasCjk(words[i].first().toString())
            out.append(if (cjk) "·" else " ").append(words[i])
        }
        val name = out.toString().trim()
        if (name.isEmpty() || name.all { it.isDigit() || it.isWhitespace() }) return null
        if (name.any { it.isUpperCase() } || SearchKeys.hasCjk(name)) return name
        return name.split(' ').mapIndexed { i, w ->
            if (i > 0 && w in SMALL_WORDS) w else w.replaceFirstChar { it.uppercaseChar() }
        }.joinToString(" ")
    }

    /** Where a word written as one (`TrafalgarCrisis`, `PartII`) turns to its next: a capital after a small letter. */
    private val CAMEL_GAP = Regex("(?<=[a-z])(?=[A-Z])")
    private val CJK_BRACKETED = Regex("""[【『「][^】』」]*[】』」]""")
    private val LATIN_PARENS = Regex("""[(（][^)）]*[)）]""")
    /** What a number's tail may open with that is not a name: "of 4" of "1 of 4", "and 2" of "1 and 2". */
    private val LEADING_SPAN = Regex("""(?i)^[\s.\-~]*(?:of|and|&|\+|-|~)\s*\d{1,3}(?![0-9])""")
    private val NAME_GAPS = Regex("""[\s._~\-–—]+""")
    /** Where a release's own words begin: a resolution, a source, a codec. */
    private val RELEASE_WORD = Regex(
        """(?i)\d{3,4}[pi]|\d{3,4}x\d{3,4}|xvid|divx\d?|x26[45]|h26[45]|hevc|avc|aac\d?|e?ac3|dts|flac|mp3|""" +
            """bd|bdrip|bluray|dvd|dvdrip|hdtv|hdrip|tvrip|webrip|web|webdl|10bit|8bit|hi10p|ma10p|proper|repack|fixed|internal|rerip""",
    )
    private val SMALL_WORDS = setOf("a", "an", "the", "of", "to", "for", "and", "or", "in", "on", "at", "by", "with")

    fun parse(item: MediaItem, numberedRun: Boolean = false): Info {
        val key = ParseKey(
            item.fileName, item.season, item.episode, item.kind, item.contentTag,
            item.title, item.originalTitle, item.showTitle, numberedRun,
        )
        parsed[key]?.let { return it }
        val info = parseUncached(item, numberedRun)
        if (parsed.size >= MAX_PARSED) parsed.clear()
        parsed[key] = info
        return info
    }

    /**
     * What [parse] reads of a file, and so what its answer is a function of. The wall's grouping, every
     * card's count and every detail page's order parse the same files again and again — a 9,120-file
     * library spent half its 5–7 s regrouping on the B3110 in these regular expressions, at each step of
     * a scrape — so an answer is kept for as long as these are what they were.
     */
    private data class ParseKey(
        val fileName: String, val season: Int?, val episode: Int?, val kind: MediaKind, val tag: ContentKind?,
        val title: String, val originalTitle: String?, val showTitle: String?, val numberedRun: Boolean,
    )

    private val parsed = java.util.concurrent.ConcurrentHashMap<ParseKey, Info>()

    /** A library's files twice over (each read alone and as one of a numbered run), and room to change. */
    private const val MAX_PARSED = 60_000

    private fun parseUncached(item: MediaItem, numberedRun: Boolean): Info {
        val name = normalizeDigits(stripExt(item.fileName))
        // "[MAIN_MOVIE]", "[本編]": the disc says which file is the film.
        if (marksMain(item.fileName)) return Info(Cat.FEATURE, item.season, null, null, null, null, null)
        if (numberedRun) {
            // A run's specials, creditless openings and OVAs stay what they are: 最后大魔王's
            // `~ Special 01 ~` and `~ Special Content 01 ~` read as a second and third episode 1.
            val plain = parse(item, numberedRun = false)
            if (plain.cat == Cat.EXTRA || plain.cat == Cat.OVA) return plain
            BARE_NUMBER.find(name)?.let {
                return Info(Cat.EPISODE, item.season, it.groupValues[1].toDoubleOrNull() ?: 0.0, null, null, null, null)
            }
        }
        SXXEXX.find(name)?.let {
            return Info(Cat.EPISODE, it.groupValues[1].toIntOrNull() ?: 1, it.groupValues[2].toDoubleOrNull() ?: 0.0, null, null, null, null)
        }
        // "1.rmvb", "09.avi": a file named by its number alone is that episode — unless it is a film
        // the scan knows by that name (300, 21).
        STEM_NUMBER.matchEntire(name.trim())?.takeIf { item.kind != MediaKind.MOVIE }?.let {
            return Info(Cat.EPISODE, item.season, it.groupValues[1].toDoubleOrNull() ?: 0.0, null, null, null, null)
        }
        // "Empires.Napoleon.1of4.to.destiny", "Jane Eyre [2006] 1 of 4": part 1 of 4. Whatever kind
        // the scan gave each part — it called three of Napoleon's four films and one unknown.
        N_OF_M.find(name)?.let {
            return Info(Cat.EPISODE, detectSeason(name) ?: item.season, it.groupValues[1].toDoubleOrNull() ?: 0.0, null, null, null, null)
        }
        NXNN.find(name)?.let {
            return Info(Cat.EPISODE, it.groupValues[1].toIntOrNull() ?: 1, it.groupValues[2].toDoubleOrNull() ?: 0.0, null, null, null, null)
        }
        SEASON_EP_CN.find(name)?.let {
            val s = it.groupValues[1].let { n -> n.toIntOrNull() ?: CN_NUM[n] } ?: 1
            return Info(Cat.EPISODE, s, it.groupValues[2].toDoubleOrNull() ?: 0.0, null, null, null, null)
        }
        val season = detectSeason(name) ?: item.season
        OVA.find(name)?.let {
            // The disc's menu, its creditless openings, its trailers, filed with the OVAs and named for
            // them — `[Patlabor_OVA][EARLY_DAYS][BDRip][1080P][MENU]` — are the release's extras, not
            // an OVA: read as one, the menu sorted first, and was what 播放 played.
            // A special is not one of these: `[SP08] OVA [Bridge]` is RahXephon's OVA, numbered among
            // the release's specials.
            BRACKETED_EXTRA.findAll(name).firstOrNull { m ->
                m.groupValues[1].uppercase() !in OVA_SPECIALS && m.groupValues[1].lowercase() !in titleWords(item)
            }?.let { m ->
                val n = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull()
                return Info(Cat.EXTRA, season, null, null, extraType(m.groupValues[1]), n, null)
            }
            // …and an OVA whose mark carries no number is numbered as the scan read the file:
            // `[Patlabor_OVA]…[03]` is OVA 3, not whichever place it took in the list.
            val n = it.groupValues[2].toIntOrNull()
                ?: item.episode?.takeIf { e -> e !in titleNumbers(item) && holdsNumber(name, e) }
            return Info(Cat.OVA, season, null, null, it.groupValues[1].uppercase(), n, null)
        }
        if (DIGEST.containsMatchIn(name)) {
            return Info(Cat.EXTRA, season, null, null, "DIGEST", null, epRange(name))
        }
        // "RARBG.com.mp4": the site's advert, shipped beside the film it came with. Named by its
        // web address alone it read as a second feature — and sorting ahead of "Spotlight.2015…",
        // it was what 播放 played.
        if (WEB_ADDRESS.matches(name.trim()) && titleText(item).none { it.equals(name.trim(), ignoreCase = true) }) {
            return Info(Cat.EXTRA, season, null, null, "AD", null, null)
        }
        val title = titleWords(item)
        BRACKETED_EXTRA.findAll(name).firstOrNull { it.groupValues[1].lowercase() !in title }?.let {
            val n = (it.groupValues[2].ifEmpty { it.groupValues[3] }).toIntOrNull()
            return Info(Cat.EXTRA, season, null, null, extraType(it.groupValues[1]), n, null)
        }
        bracketEp(name)?.let { (num, qual) ->
            return Info(Cat.EPISODE, season, num, qual?.trim()?.trimEnd('.')?.ifBlank { null }, null, null, null)
        }
        FINAL_EP.find(name)?.let { m ->
            (m.groups[1] ?: m.groups[2])?.value?.toDoubleOrNull()?.let { return Info(Cat.EPISODE, season, it, null, null, null, null) }
        }
        // …but not a word of the work's own name: "The.Menu.2016" is the film called The Menu.
        EXTRA.findAll(name).firstOrNull { it.groupValues[1].lowercase() !in title && !romanFour(it) }?.let {
            // "EP20 15sec. Next EP Yokoku": 予告 written in romaji, the preview that follows episode 20 —
            // numbered by that episode, so twenty-five of them sort in order and read apart.
            val n = it.groupValues[2].toIntOrNull()
                ?: if (it.groupValues[1].equals("yokoku", ignoreCase = true)) EP_WORD.find(name)?.groupValues?.get(1)?.toIntOrNull() else null
            return Info(Cat.EXTRA, season, null, null, extraType(it.groupValues[1]), n, null)
        }
        // The generic words, only when nothing more particular was said: "Memento.Extras1.Anatomy.of.a.
        // Scene" is an extra by that name, "Deleted.Scenes-Grym" the film's deleted scenes — each had
        // counted as a second film.
        GENERIC_EXTRA.findAll(name).firstOrNull { it.groupValues[1].lowercase() !in title }?.let {
            val type = if (it.groupValues[1].startsWith("d", ignoreCase = true)) "DELETED" else "BONUS"
            return Info(Cat.EXTRA, season, null, null, type, it.groupValues[2].toIntOrNull(), tailName(name.substring(it.range.last + 1)))
        }
        CJK_EXTRAS.firstOrNull { (word, _) -> word in name && titleText(item).none { word in it } }?.let { (_, type) ->
            return Info(Cat.EXTRA, season, null, null, type, null, null)
        }
        EP_WORD.find(name)?.let {
            return Info(Cat.EPISODE, season, it.groupValues[1].toDoubleOrNull() ?: 0.0, null, null, null, null)
        }
        // Nothing above read a number — but the scan did: "[Coalgirls]_Code_Geass_R2_01_(…)",
        // "[Zero-Raws] Show - 01 (BD …)", "Show.DVDRip.05", "Show 01[1080P]". Without it those
        // episodes were all named specials, a whole series under 特典 with one episode left in its
        // list. Only for a file the scan took for an episode and did not file as an extra; only for
        // a number the name holds on its own (not the 9 of "16x9"); and not the title's own number:
        // the scan read Gundam SEED C.E.73's menus and extras as "episode 73".
        if (item.kind == MediaKind.EPISODE && item.contentTag != ContentKind.EXTRA) {
            item.episode?.takeIf { it !in titleNumbers(item) && holdsNumber(name, it) }?.let {
                return Info(Cat.EPISODE, season, it.toDouble(), null, null, null, null)
            }
        }
        // Unrecognized: a movie's feature (flat list sorts it first) or a named special inside a show.
        return Info(Cat.FEATURE, season, null, null, null, null, descriptor(name))
    }

    /**
     * Global order: the feature first (movies), then episodes by season+episode, then OVA, then 特典
     * grouped by type. Used by the flat file list and by group sorting; section views refine it.
     */
    fun sortKey(item: MediaItem, numberedRun: Boolean = false): Double {
        val i = parse(item, numberedRun)
        return when (i.cat) {
            // A real feature (no descriptive name) leads a movie's flat list; a *named* leftover is
            // junk/a special and sorts with the extras, never ahead of the feature.
            Cat.FEATURE -> if (i.name.isNullOrBlank()) -1.0 else 650_000.0
            Cat.EPISODE -> (i.season ?: 1) * 1_000.0 + (i.episode ?: 0.0)
            Cat.OVA -> 500_000.0 + (i.extraNum ?: 0)
            Cat.EXTRA -> {
                val rank = EXTRA_ORDER.indexOf(i.extraType).let { if (it < 0) 99 else it }
                600_000.0 + rank * 1_000 + (i.extraNum ?: 0)
            }
        }
    }

    private fun detectSeason(name: String): Int? {
        for (p in SEASON) p.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        SEASON_CN.find(name)?.groupValues?.get(1)?.let { cn -> CN_NUM[cn]?.let { return it } }
        return null
    }

    private val NAME_TOKENS = Regex("""[^\p{L}\p{N}]+""")

    /** The numbers that stand alone in the work's names — 73 of "C.E.73", 86 of "86 -エイティシックス-". */
    /**
     * What a file calls itself once its release brackets are gone — `Mobile Suit Information`,
     * `Making_of_Part_2` as `Making of Part 2` — or null when nothing but brackets was there.
     */
    fun ownName(item: MediaItem): String? {
        val stem = stripExt(item.fileName)
        val bare = stem.replace(Regex("""\[[^\]]*]|【[^】]*】"""), " ")
        val spaced = if (' ' in bare.trim()) bare else bare.replace('_', ' ').replace('.', ' ')
        return spaced.replace(Regex("""\s+"""), " ").trim().trim('-', '_', '.').trim().ifEmpty { null }
    }

    /** A matched extra token as its kind: a TV spot is a CM, a making-of a MAKING. */
    private fun extraType(token: String): String = when (val t = token.uppercase()) {
        "MAKINGOF" -> "MAKING"
        "TVCM" -> "CM"
        // 予告 in romaji, as Japanese releases spell it.
        "YOKOKU" -> "TRAILER"
        // A disc's plurals — "Featurettes (Play All)", "BD-BOX Trailers" — are its kind of extra.
        "FEATURETTES", "TRAILERS", "TEASERS", "INTERVIEWS", "SPECIALS" -> t.dropLast(1)
        else -> t
    }

    /**
     * The WORK's names as a database gave them — the show's and the original. Not the file's own title:
     * an extra nobody matched is titled by its name ("Theatrical Trailer"), and that would have shielded
     * every word of it.
     */
    private fun titleText(item: MediaItem): List<String> = listOfNotNull(item.showTitle, item.originalTitle)

    /** The words of [titleText], lowercased: a token in here is the name, not a kind of extra. */
    private fun titleWords(item: MediaItem): Set<String> =
        titleText(item).flatMap { it.lowercase().split(NAME_TOKENS) }.filterTo(HashSet()) { it.isNotEmpty() }

    private fun titleNumbers(item: MediaItem): Set<Int> =
        listOfNotNull(item.showTitle, item.title, item.originalTitle)
            .flatMap { it.split(NAME_TOKENS) }
            .mapNotNull { t -> t.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull() }
            .toSet()

    private val VERSION_TAIL = Regex("""v\d$""")

    /** Whether [n] stands in [name] as a number of its own: "_01_", " - 03 ", ".05", "E01", "[02v2]". */
    private fun holdsNumber(name: String, n: Int): Boolean =
        name.lowercase().split(NAME_TOKENS).any { t ->
            val digits = t.removePrefix("episode").removePrefix("ep").removePrefix("e").replace(VERSION_TAIL, "")
            digits.isNotEmpty() && digits.all(Char::isDigit) && digits.toIntOrNull() == n
        }

    /** The first non-numeric, non-noise bracket (e.g. "Survival Camp"), else the cleaned stem. */
    private fun descriptor(name: String): String? {
        // Brackets side by side are one run, and a run names its parts in order: who released it,
        // the work, then the part — `Sound Horizon [POPGO][Linked_Horizon_Luxendarc_Kikou]` read
        // "POPGO", the group, and a concert's `[Disc2][LIVE DOCUMENT]` read "Disc2" for each of the
        // disc's four parts. So the LAST bracket of a run that could be a name is the name.
        val runs = mutableListOf<MutableList<MatchResult>>()
        BRACKET.findAll(name).forEach { m ->
            val run = runs.lastOrNull()
            if (run != null && run.last().range.last + 1 == m.range.first) run += m else runs += mutableListOf(m)
        }
        for (run in runs) {
            // A leading bracket is whoever released the file or where it came from — [Kamigami],
            // [OZC], [VCB-Studio], [NHK], [アニメ BD] — and a special read "Kamigami" or "NHK". The
            // special's own name, if it has one, comes after it.
            val named = run.filterNot { it.range.first == 0 && it.range.last < name.length - 1 }
                .lastOrNull { names(it.groupValues[1].trim()) } ?: continue
            // Written with underscores for spaces, as fansub brackets are.
            return named.groupValues[1].trim().replace('_', ' ').trim()
        }
        return null
    }

    /**
     * Whether a bracket's text could be a name — not a number, a codec, a checksum, a source, the
     * subtitles' languages (`[GB_JP]`, `[Jp_Cn]`) or a form word that names nothing but the film
     * itself (`[Ghost in the Shell][Movie]` is the film, not a cut called "Movie").
     */
    private fun names(inner: String): Boolean =
        inner.length > 1 && !inner.first().isDigit() && !NOISE_BRACKET.containsMatchIn(inner) &&
            !CRC32.matches(inner) && !SOURCE_TAG.containsMatchIn(inner) && !RIP.containsMatchIn(inner) &&
            !inner.split('_', '&', '+', ' ', '-').filter { it.isNotBlank() }.all { it.lowercase() in LANGUAGE_TAGS } &&
            inner.lowercase().replace('_', ' ').trim() !in FORM_WORDS

    /**
     * What a release says of itself after the name, which a run's last bracket often is: a source
     * anywhere in it (`[Full_HD-BDRIP]`), the subtitles' or audio's format and languages (`[PGS]`,
     * `[Multi Sub]`, `[DTS-HDMA+DTSx2]`, `[内封中日字幕]`, `[国日粤三语]`), a raw, a region code (`[R10]`),
     * a release's own version (`[Voice_ver2]`), its container (`[MP4]`).
     */
    private val RIP = Regex(
        """(?i)(?:bd|br|dvd|hd|tv|web)[-_]?rip|\b(?:pgs|sup|dts|hdma|raw)\b|(?:multi|dual)[\s_-]?(?:audio|subs?)|""" +
            """^r\d{1,2}$|ver\d+\b|字幕|(?:[双雙三四多]|国|國|粤|粵|日|英|中)[语語]$|^(?:mp4|mkv|avi|rmvb|wmv|flv|mov|m2ts|ts)$""",
    )

    private val LANGUAGE_TAGS = setOf(
        "gb", "big5", "gbk", "jp", "jpn", "jap", "chs", "cht", "sc", "tc", "cn", "chi", "en", "eng", "kor", "简", "繁", "日",
    )

    private val FORM_WORDS = setOf("movie", "the movie", "film", "main", "feature", "本編", "本篇", "正片", "剧场版", "劇場版")

    private val BRACKET = Regex("""\[([^\]]+)\]""")

    /** A release's checksum, "[F0F7F4E7]" — not a name. */
    private val CRC32 = Regex("""^[0-9A-Fa-f]{8}$""")

    /** Where a file came from or what kind of programme it is — "[BD]", "[DVDRip]", "[纪录片]" — not its name. */
    private val SOURCE_TAG = Regex("""(?i)^(?:bd|dvd|tv|web|hdtv)(?:-?rip|box|mv)?$|^(?:纪录片|紀錄片|记录片|ドキュメンタリー)$""")

    /** The episode span a digest covers, e.g. "[S2 EP01-12 Digest]" → "01-12" (null if unstated). */
    private fun epRange(name: String): String? =
        Regex("""(?i)\bE(?:P|PISODE)?\s*0*(\d{1,3})\s*[-~–]\s*0*(\d{1,3})""").find(name)
            ?.let { "%02d-%02d".format(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

    private fun bracketEp(name: String): Pair<Double, String?>? =
        BRACKET_EP.findAll(name)
            .mapNotNull { m -> m.groupValues[1].toDoubleOrNull()?.let { it to m.groupValues.getOrNull(2) } }
            .firstOrNull()

    private fun stripExt(fn: String): String = fn.substringBeforeLast('.', fn)

    /** An episode number as it is read: 5, not 5.0; 4.5 stays 4.5. */
    fun fmt(n: Double): String = if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()
}
