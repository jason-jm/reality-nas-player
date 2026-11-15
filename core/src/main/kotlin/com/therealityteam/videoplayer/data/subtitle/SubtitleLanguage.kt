package com.therealityteam.videoplayer.data.subtitle

import com.therealityteam.videoplayer.data.playback.TrackPreference
import com.therealityteam.videoplayer.data.playback.TrackPreference.Lang

/**
 * What language a subtitle file is in — from its name tag when it has one ("chs&eng"), else from its
 * lines. Many have none: `1917.2019.1080p.Bluray.Atmos.TrueHD.7.1.x264-EVO.ass` beside the film of
 * that name is its Chinese–English subtitle, and only the lines say so. Read over enough of them to
 * outvote a sign, a song or a credit: Han characters, and among them the forms only one script
 * writes (们 / 們, 这 / 這…); kana, which only Japanese writes; English's commonest words. Pure, so
 * it is unit-tested.
 */
object SubtitleLanguage {

    /** The languages a name tag spells, Chinese first: "chs&eng" → Simplified, English. */
    fun ofTag(tag: String?): List<Lang> =
        if (tag.isNullOrBlank()) emptyList() else ordered(TrackPreference.langsOfText(tag))

    /**
     * The languages [lines] are written in, Chinese first, then Japanese, then English — the order
     * of a bilingual release's own lines. Empty when they are too few to tell, or none of these.
     */
    fun of(lines: List<String>): List<Lang> {
        val sample = lines.asSequence()
            .flatMap { it.split('\n').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_LINES)
            .toList()
        if (sample.size < MIN_LINES) return emptyList()
        // Lines of Han with no kana — Chinese ones — and lines with kana — Japanese ones.
        var chinese = 0
        var kana = 0
        var english = 0
        // Lines in an alphabet of their own say their language outright (Cyrillic read as Russian, the language of
        // nearly every such subtitle a reader of Aura's meets).
        val byScript = HashMap<Lang, Int>()
        var simplified = 0
        var traditional = 0
        for (line in sample) {
            var lineHan = 0
            var lineKana = 0
            var lineSimplified = 0
            var lineTraditional = 0
            for (ch in line) {
                when {
                    isHan(ch) -> {
                        lineHan++
                        if (ch in SIMPLIFIED) lineSimplified++ else if (ch in TRADITIONAL) lineTraditional++
                    }
                    ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FA' -> lineKana++
                }
            }
            scriptOf(line)?.let { byScript.merge(it, 1, Int::plus) }
            if (lineKana >= 1) kana++
            else if (lineHan >= 2) {
                chinese++
                // Japanese writes 来 and 国 as simplified Chinese does: only Chinese lines tell the scripts apart.
                simplified += lineSimplified
                traditional += lineTraditional
            }
            if (isEnglish(line)) english++
        }
        val enough = (sample.size * SHARE).coerceAtLeast(MIN_HITS.toDouble())
        val out = mutableSetOf<Lang>()
        if (kana >= enough) out += Lang.JAPANESE
        // Japanese writes kanji too, a name or a short line without kana now and then — so Chinese
        // lines count beside kana ones only when they are at least half as many: a Chinese–Japanese
        // release's (Kamigami's Fate/Zero) are as many, one over the other in every cue.
        if (chinese >= enough && chinese * 2 >= kana) {
            out += when {
                simplified > traditional * 2 && simplified >= MIN_SCRIPT_HITS -> Lang.SIMPLIFIED
                traditional > simplified * 2 && traditional >= MIN_SCRIPT_HITS -> Lang.TRADITIONAL
                else -> Lang.CHINESE
            }
        }
        if (english >= enough) out += Lang.ENGLISH
        byScript.forEach { (lang, n) -> if (n >= enough) out += lang }
        return ordered(out)
    }

    /**
     * The languages of a subtitle file of which only [head] was read — [complete] if that is the whole
     * file. A part is cut after its last whole line first: a character split at the cut turned UTF-8
     * into "not UTF-8", and the whole head was then read as GBK.
     */
    fun ofHead(head: ByteArray, fileName: String, complete: Boolean): List<Lang> {
        val bytes = if (complete) head else wholeLines(head)
        return of(runCatching { SubtitleParser.parse(bytes, fileName).cues.map { it.text } }.getOrDefault(emptyList()))
    }

    /** [head] up to and with its last line break — in the two-byte encodings, a whole two-byte one. */
    fun wholeLines(head: ByteArray): ByteArray {
        val le = head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()
        val be = head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()
        for (i in head.size - 1 downTo 1) {
            val end = when {
                le -> i % 2 == 1 && head[i] == ZERO && head[i - 1] == LF
                be -> i % 2 == 1 && head[i] == LF && head[i - 1] == ZERO
                else -> head[i] == LF
            }
            if (end) return head.copyOf(i + 1)
        }
        return head
    }

    private const val LF: Byte = 0x0A
    private const val ZERO: Byte = 0

    /** The name tag these languages would carry, as [ofTag] and TrackPreference read tags: "chs&eng". */
    fun tag(langs: List<Lang>): String? = langs.mapNotNull { TAGS[it] }.distinct().joinToString("&").ifEmpty { null }

    /** The code the engine matches the reader's own language against — the main language's: zh-Hans, en… */
    fun code(langs: List<Lang>): String? = langs.firstOrNull()?.let { CODES[it] }

    private fun ordered(langs: Set<Lang>): List<Lang> = ORDER.filter { it in langs } + langs.filter { it !in ORDER }

    /** A line's language by its alphabet, where the alphabet is one language's: Hangul, Cyrillic, Greek, Thai. */
    private fun scriptOf(line: String): Lang? {
        var hangul = 0; var cyrillic = 0; var greek = 0; var thai = 0
        for (ch in line) when (ch) {
            in '\uAC00'..'\uD7A3' -> hangul++
            in '\u0400'..'\u04FF' -> cyrillic++
            in '\u0370'..'\u03FF' -> greek++
            in '\u0E01'..'\u0E5B' -> thai++
        }
        val (lang, n) = listOf(Lang.KOREAN to hangul, Lang.RUSSIAN to cyrillic, Lang.GREEK to greek, Lang.THAI to thai).maxBy { it.second }
        return lang.takeIf { n >= 2 }
    }

    private fun isHan(ch: Char) = ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF'

    /** A line of English: two words or more, at least a quarter of them its commonest. */
    private fun isEnglish(line: String): Boolean {
        val words = WORD.findAll(line.lowercase()).map { it.value }.toList()
        if (words.size < 2) return false
        return words.count { it in COMMON_ENGLISH } * 4 >= words.size
    }

    private val ORDER = listOf(Lang.SIMPLIFIED, Lang.TRADITIONAL, Lang.CHINESE, Lang.JAPANESE, Lang.ENGLISH)

    private val TAGS = mapOf(
        Lang.SIMPLIFIED to "chs", Lang.TRADITIONAL to "cht", Lang.CHINESE to "chi",
        Lang.JAPANESE to "jpn", Lang.ENGLISH to "eng", Lang.KOREAN to "kor", Lang.RUSSIAN to "rus",
        Lang.GREEK to "gre", Lang.THAI to "tha",
    )

    /** The engine's code for each: the language's own (zh-Hans, en, ko, ru…). */
    private val CODES: Map<Lang, String> = Lang.entries.associateWith { it.code }

    /** Lines read: a film's first few minutes of dialogue are plenty. */
    private const val MAX_LINES = 400
    private const val MIN_LINES = 8

    /** A language counts when this share of the lines (and at least [MIN_HITS]) is written in it. */
    private const val SHARE = 0.15
    private const val MIN_HITS = 4

    /** Characters that only one script writes, before a Chinese file is called one or the other. */
    private const val MIN_SCRIPT_HITS = 5

    private val WORD = Regex("[a-z]+(?:'[a-z]+)?")

    private val COMMON_ENGLISH = setOf(
        "the", "a", "an", "and", "or", "but", "to", "of", "in", "on", "at", "for", "with", "from", "is", "are",
        "was", "were", "be", "been", "am", "it", "it's", "that", "this", "what", "you", "you're", "your", "i",
        "i'm", "me", "my", "we", "our", "he", "she", "they", "him", "her", "them", "his", "not", "no", "don't",
        "do", "did", "have", "has", "had", "can", "can't", "will", "would", "just", "so", "if", "there", "here",
        "all", "get", "got", "go", "know", "like", "up", "out", "about", "how", "why", "who", "where", "when",
        "yes", "yeah", "come", "let's", "right", "now", "okay", "well", "see", "look", "think", "want", "one",
    )

    /** The commonest characters the two scripts write differently: the simplified forms… */
    private const val SIMPLIFIED_ONLY = "们这个来说时为会对还过没里后现样发开经问关见让给听动东两从实头点话长电车门间学爱谁吗该应机无买卖难钱认识记帮欢乐刚当号边进远运场报种战军国万与业亲体几变叶吓员坏处备将尔师带干广张弹归总恋担么杀医华饭气热尽节结统绝继续线练组细终红级约纪罗贝负贵费资赶轻较辆输选递遗邮钟铁错闹阳阴陆队际险随隐雾静页顶项须顾预领题风飞馆马驾验鱼鸟鸡龙岁宝虽谢请谈论讲试诉误"

    /** …and the traditional ones, in the same order, then the variants 裏, 麽 and 説. */
    private const val TRADITIONAL_ONLY = "們這個來說時為會對還過沒裡後現樣發開經問關見讓給聽動東兩從實頭點話長電車門間學愛誰嗎該應機無買賣難錢認識記幫歡樂剛當號邊進遠運場報種戰軍國萬與業親體幾變葉嚇員壞處備將爾師帶幹廣張彈歸總戀擔麼殺醫華飯氣熱盡節結統絕繼續線練組細終紅級約紀羅貝負貴費資趕輕較輛輸選遞遺郵鐘鐵錯鬧陽陰陸隊際險隨隱霧靜頁頂項須顧預領題風飛館馬駕驗魚鳥雞龍歲寶雖謝請談論講試訴誤裏麽説"

    private val SIMPLIFIED = SIMPLIFIED_ONLY.toSet()
    private val TRADITIONAL = TRADITIONAL_ONLY.toSet()
}
