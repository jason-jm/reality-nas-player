package com.therealityteam.videoplayer.data.subtitle

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * The code page a text not in UTF-8 was written in — a subtitle, an .nfo — read off the text itself.
 *
 * Every legacy code page reads nearly any bytes without a complaint, so the question is which reading looks like
 * writing. It used to choose among the CJK ones and Western European only: a Russian (windows-1251), Greek, Korean or
 * Thai subtitle came out as a run of CJK characters, a Polish or Turkish one with ł as ³ and ş as þ. Each candidate is
 * scored on its reading of the first 64 KB: the commonest hanzi, kana and hangul syllables; Cyrillic and Greek words
 * in lower case (a wrong Cyrillic page reads mid-word capitals); Thai runs; a Latin accent inside a word (a run of
 * accented letters is another script read as Latin); and against: what could not be read, control characters, and a
 * symbol inside a word (Polish ł read as ³). The reader's language is the tie-break and a small head start: a Czech
 * reader's old subtitles are windows-1250.
 */
object CodePage {

    /** [bytes]' code page; [language] the reader's (an ISO 639 code), Locale.getDefault()'s by default. */
    fun detect(bytes: ByteArray, language: String = Locale.getDefault().language): Charset {
        val sample = if (bytes.size > SAMPLE) bytes.copyOf(SAMPLE) else bytes
        val preferred = PREFERRED[normalize(language)].orEmpty()
        val nonAscii = sample.count { it < 0 }
        val headStart = maxOf(2, nonAscii / 25)
        return candidates().maxByOrNull { (name, charset) ->
            score(sample, charset) + (if (name in preferred) headStart * (preferred.size - preferred.indexOf(name)) / preferred.size else 0)
        }?.second ?: Charsets.UTF_8
    }

    /** The candidates this runtime has, in order of how often they are met: the first wins a tie. */
    private fun candidates(): List<Pair<String, Charset>> = available

    private val available: List<Pair<String, Charset>> by lazy {
        NAMES.mapNotNull { names -> names.firstNotNullOfOrNull { n -> runCatching { n to Charset.forName(n) }.getOrNull() } }
            .map { (asked, cs) -> canonical(asked) to cs }
    }

    /** One name a code page goes by in [PREFERRED], whichever alias this runtime knew it under. */
    private fun canonical(name: String): String = NAMES.first { name in it }.first()

    private val NAMES = listOf(
        listOf("GB18030"), listOf("GBK"), listOf("Big5-HKSCS"), listOf("Big5"),
        listOf("windows-31j", "Shift_JIS"), listOf("EUC-JP"),
        listOf("x-windows-949", "MS949", "windows-949", "EUC-KR"),
        listOf("windows-1252"), listOf("windows-1250"), listOf("windows-1251"), listOf("KOI8-R"),
        listOf("windows-1253"), listOf("windows-1254"), listOf("windows-1257"),
        listOf("x-windows-874", "windows-874", "TIS-620"),
    )

    /** The code pages a language's old files are in, most likely first. */
    private val PREFERRED: Map<String, List<String>> = mapOf(
        "zh-Hans" to listOf("GB18030", "GBK"), "zh-Hant" to listOf("Big5-HKSCS", "Big5"),
        "ja" to listOf("windows-31j", "EUC-JP"), "ko" to listOf("x-windows-949"),
        "ru" to listOf("windows-1251", "KOI8-R"), "el" to listOf("windows-1253"), "tr" to listOf("windows-1254"),
        "pl" to listOf("windows-1250"), "cs" to listOf("windows-1250"), "ro" to listOf("windows-1250"),
        "th" to listOf("x-windows-874"),
    ) + listOf("en", "de", "fr", "es", "it", "pt", "nl", "sv", "da", "nb", "fi", "ms").associateWith { listOf("windows-1252") }

    private fun normalize(language: String): String = when (val l = language.lowercase().substringBefore('-').substringBefore('_')) {
        "zh" -> if (language.contains("Hant", true) || language.uppercase().let { it.endsWith("TW") || it.endsWith("HK") || it.endsWith("MO") }) "zh-Hant" else "zh-Hans"
        "no", "nn" -> "nb"
        else -> l
    }

    internal fun score(bytes: ByteArray, charset: Charset): Int {
        val text = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith("�")
            .decode(ByteBuffer.wrap(bytes))
        var score = 0
        val accented = HashMap<Char, Int>()
        for (i in text.indices) {
            val c = text[i]
            if (c.code < 0x80) {
                if (c.code < 0x20 && c != '\n' && c != '\r' && c != '\t') score -= 6
                continue
            }
            val prev = text.getOrNull(i - 1)
            val next = text.getOrNull(i + 1)
            score += when {
                c == '�' -> -8
                c.code in 0x80..0x9F -> -6 // C1 controls: bytes the page has no letter for
                // Box drawing and blocks (KOI8-R and the DOS pages are full of them): no subtitle draws boxes.
                c.code in 0x2500..0x259F -> -6
                // A CJK character is two bytes, where a Thai or Cyrillic letter is one: worth as much as two of them.
                c in COMMON_HANZI -> 3
                c in '\u4E00'..'\u9FFF' -> 1 // a real ideograph, if not one of the commonest
                c in 'ぁ'..'ヿ' -> 2 // kana
                c in '가'..'힣' -> if (c in COMMON_HANGUL) 3 else 0
                c in 'Ѐ'..'ӿ' || c in 'Ͱ'..'Ͽ' -> alphabetic(c, prev, next)
                c in 'ก'..'๛' -> thai(c, prev)
                c in 'À'..'ɏ' && c.isLetter() -> latin(c, prev, next).also { accented.merge(c.lowercaseChar(), 1, Int::plus) }
                // Spanish ¿ ¡ open a sentence (windows-1250 reads that byte as ż, a letter).
                (c == '¿' || c == '¡') && next?.isLetter() == true && (prev == null || !prev.isLetterOrDigit()) -> 1
                // A symbol inside a word — "³" for ł, "¿" for ż: another page's letter read as this one's mark.
                !c.isLetterOrDigit() && prev?.isLetter() == true && next?.isLetter() == true -> -3
                else -> 0
            }
        }
        return score + alphabetFit(accented)
    }

    /**
     * How well a reading's accented letters make one language's alphabet: Czech read as windows-1252 has á í š beside
     * ì ø ù — Italian's ì with Danish's ø, no one's — where windows-1250 gives ě č ř ů, Czech's own. The best fit: its
     * letters count for, every letter outside it twice against.
     */
    private fun alphabetFit(accented: Map<Char, Int>): Int {
        if (accented.isEmpty()) return 0
        return ALPHABETS.maxOf { alphabet ->
            accented.entries.sumOf { (c, n) -> if (c in alphabet) n else -2 * n }
        }
    }

    /** The accented letters of each Latin-script language Aura reads, lower case. */
    private val ALPHABETS: List<Set<Char>> = listOf(
        "äöüß", "àâæçéèêëîïôœùûüÿ", "áéíñóúü", "àèéìíîòóùú", "áâãàçéêíóôõú", "áéëïóöü", "åäöé", "æøåé", "æøåéèóôò",
        "äöå", "ąćęłńóśźż", "áčďéěíňóřšťúůýž", "ăâîşţșț", "çğıöşüâî", "áéíóúñ",
    ).map { it.toSet() }

    /**
     * A Cyrillic or Greek letter: lower case inside a word is writing; a capital after a small letter, or a word half
     * Latin ("Czeњж" — Polish read as Cyrillic), is a wrong page.
     */
    private fun alphabetic(c: Char, prev: Char?, next: Char?): Int {
        val sameScript = { x: Char? -> x != null && x.isLetter() && Character.UnicodeScript.of(x.code) == Character.UnicodeScript.of(c.code) }
        val asciiLetter = { x: Char? -> x != null && (x in 'a'..'z' || x in 'A'..'Z') }
        return when {
            asciiLetter(prev) || asciiLetter(next) -> -3
            // Cyrillic outside Russian's alphabet (Serbian ј, Ukrainian ї…): what a CJK pair reads as in windows-1251.
            c in '\u0400'..'\u04FF' && c !in '\u0410'..'\u044F' && c != 'Ё' && c != 'ё' -> -1
            !c.isLetter() -> if (sameScript(prev) && sameScript(next)) -3 else 0
            c.isUpperCase() && prev != null && sameScript(prev) && prev.isLowerCase() -> -2
            // One byte a letter, against a CJK character's two: as much as a byte of one, no more.
            sameScript(prev) || sameScript(next) -> if (c.isLowerCase()) 1 else 0
            else -> 0
        }
    }

    /**
     * A Thai character: the common letters are writing; the rare ones (old letters, Thai digits, ฿), a vowel or tone
     * mark that follows no consonant, and Thai inside a Latin word are what CJK bytes read as Thai look like.
     */
    private fun thai(c: Char, prev: Char?): Int {
        val consonant = { x: Char? -> x != null && x in 'ก'..'ฮ' }
        val mark = { x: Char -> x == 'ั' || x in 'ิ'..'ฺ' || x in '็'..'๎' }
        return when {
            c in RARE_THAI -> -2
            mark(c) -> if (consonant(prev) || (prev != null && mark(prev))) 1 else -3
            prev != null && (prev in 'a'..'z' || prev in 'A'..'Z') -> -2
            else -> 1
        }
    }

    private val RARE_THAI: Set<Char> = "ฃฅฆฌฎฏฐฑฒฬฯ฿๎๏๚๛๐๑๒๓๔๕๖๗๘๙".toSet()

    /** An accented Latin letter: inside an ASCII word it is writing; in a run of its kind, another script read as Latin. */
    private fun latin(c: Char, prev: Char?, next: Char?): Int {
        val ascii = { x: Char? -> x != null && (x in 'a'..'z' || x in 'A'..'Z') }
        val base = if (ascii(prev) || ascii(next)) 1 else -1
        return base - (if (c in RARE_LATIN) 1 else 0)
    }

    /** Latin-1 letters no language Aura reads uses (Icelandic, Faroese): a sign of Turkish ğ ş ı read as Western. */
    private val RARE_LATIN = setOf('ð', 'þ', 'Ð', 'Þ', 'ý', 'Ý')

    /** The hangul syllables met most in dialogue (eojeol-final endings and particles first). */
    private val COMMON_HANGUL: Set<Char> = (
        "이다는의에하가고을를지리기서도사나어로한일그수시니요아대자해게것내습보면들있없주우거마라만했어요말정생" +
            "제전부무오인상여모미안잘네까데너우리저때중장소경동성원실방위계화와과로부터까지에서보다처럼같은알왜뭐좀" +
            "더많이또다시정말진짜그래요죠네요세요습니다입니다였어있어없어했어해요봐요가요와요줘요알아몰라괜찮아"
        ).toSet()

    /** The hanzi met most in film dialogue, in simplified and traditional forms (and so the commonest kanji). */
    private val COMMON_HANZI: Set<Char> get() = SubtitleParser.COMMON_HANZI

    private const val SAMPLE = 64 * 1024
}
