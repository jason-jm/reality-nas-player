package com.therealityteam.videoplayer.ui.library

import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A→Z as the reader's language orders it, and the rail's mark for each title.
 *
 * A Chinese reader's wall is in pinyin, CJK and Latin titles together (their [MediaGroup.titleKey]s), A–Z down the
 * rail, as it always was. Every other reader's is in their own collation — Swedish puts Å Ä Ö after Z, Czech Č after C,
 * Polish Ł after L, Korean ㄱ ㄴ ㄷ, Japanese kana by あかさたな, Thai by consonant past its leading vowel — where it
 * was by code point, Cyrillic, Greek and Thai titles after z and all under "#". The rail's marks are the letters
 * that order uses, so a mark is always where its cards begin.
 */
object TitleOrder {

    /** Whether [locale]'s wall is in pinyin (Chinese, simplified or traditional). */
    fun byPinyin(locale: Locale = Locale.getDefault()): Boolean = locale.language == "zh"

    private val collators = ConcurrentHashMap<Locale, Collator>()

    /** [locale]'s collation, case and accents as tie-breaks only where the language says so. */
    fun collator(locale: Locale = Locale.getDefault()): Collator =
        collators.getOrPut(locale) { Collator.getInstance(locale).apply { strength = Collator.SECONDARY } }

    /** The name a card sorts by: its title, past a leading quote or bracket (「…」, "…", (…)). */
    fun sortName(title: String): String = title.trimStart { !it.isLetterOrDigit() }

    /** A mark for the titles in a script the reader's alphabet does not have (Han in a German wall). */
    const val REST = '…'

    /** The rail's mark for [title] in [locale]'s order. */
    fun mark(title: String, locale: Locale = Locale.getDefault()): Char {
        val c = sortName(title).firstOrNull() ?: return TitleIndex.OTHER
        if (c.isDigit()) return TitleIndex.OTHER
        return when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.LATIN -> latin(c, locale.language)
            Character.UnicodeScript.CYRILLIC -> c.uppercaseChar().let { if (it == 'Ё') 'Е' else it }
            Character.UnicodeScript.GREEK -> bare(c.uppercaseChar())
            Character.UnicodeScript.HANGUL -> initial(c)
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kanaRow(c)
            Character.UnicodeScript.THAI -> thaiConsonant(sortName(title))
            else -> REST
        }
    }

    /** A Latin initial: the language's own letters as themselves (Å in Swedish, Ł in Polish), the rest by their base. */
    private fun latin(c: Char, language: String): Char {
        val up = c.uppercaseChar().let { if (language == "ro") ROMANIAN_COMMA[it] ?: it else it }
        if (up in OWN_LETTERS[language].orEmpty()) return up
        val base = KEYBOARD[up] ?: bare(up)
        return if (base in 'A'..'Z') base else REST
    }

    private fun bare(c: Char): Char = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).first()

    private val KEYBOARD = mapOf('Ø' to 'O', 'Æ' to 'A', 'Œ' to 'O', 'Ł' to 'L', 'Đ' to 'D', 'Þ' to 'T', 'ß' to 'S')

    /** Latin letters a language alphabetises apart (after their base, as its collation does). */
    private val OWN_LETTERS: Map<String, Set<Char>> = mapOf(
        "sv" to "ÅÄÖ", "fi" to "ÅÄÖ", "da" to "ÆØÅ", "nb" to "ÆØÅ", "no" to "ÆØÅ",
        "pl" to "ĄĆĘŁŃÓŚŹŻ", "cs" to "ČŘŠŽ", "tr" to "ÇĞİÖŞÜ", "ro" to "ĂÂÎȘȚ", "es" to "Ñ",
    ).mapValues { it.value.toSet() }

    /** Old Romanian text writes ş ţ with a cedilla; the alphabet's letters are the comma-below ones. */
    private val ROMANIAN_COMMA = mapOf('Ş' to 'Ș', 'Ţ' to 'Ț')

    /** A hangul syllable's initial consonant, doubled ones under their single (ㄲ under ㄱ), as Korean indexes do. */
    private fun initial(c: Char): Char {
        if (c !in '가'..'힣') return c
        val lead = (c.code - 0xAC00) / (21 * 28)
        return "ㄱㄱㄴㄷㄷㄹㅁㅂㅂㅅㅅㅇㅈㅈㅊㅋㅌㅍㅎ"[lead]
    }

    /** A kana's row of the gojūon (あかさたなはまやらわ), katakana read as hiragana. */
    private fun kanaRow(c: Char): Char {
        val h = if (c in 'ァ'..'ヶ') (c.code - 0x60).toChar() else c
        return when (h.code) {
            in 0x3041..0x304A, 0x3094 -> 'あ'
            in 0x304B..0x3054, 0x3095, 0x3096 -> 'か'
            in 0x3055..0x305E -> 'さ'
            in 0x305F..0x3069 -> 'た'
            in 0x306A..0x306E -> 'な'
            in 0x306F..0x307D -> 'は'
            in 0x307E..0x3082 -> 'ま'
            in 0x3083..0x3088 -> 'や'
            in 0x3089..0x308D -> 'ら'
            in 0x308E..0x3093 -> 'わ'
            else -> REST
        }
    }

    /** A Thai title's consonant: the first one, past a leading vowel (เ แ โ ใ ไ), as Thai dictionaries order. */
    private fun thaiConsonant(name: String): Char =
        name.firstOrNull { it in 'ก'..'ฮ' } ?: REST
}
