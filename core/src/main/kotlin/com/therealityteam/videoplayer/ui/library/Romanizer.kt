package com.therealityteam.videoplayer.ui.library

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * CJK text → Latin, by the ICU transliterators the platform ships: pinyin for Han, romaji for
 * kana, romanised Hangul. One transliterator per script rather than "Any-Latin", whose script
 * detection over every rule set made each call an order of magnitude slower — on the emulator
 * the first grouping of a 9.6k-file library spent 46 seconds in it. Memoised: grouping asks the
 * same few thousand names on every regroup. Returns null where the platform cannot (JVM tests).
 */
object Romanizer {
    /**
     * An ICU transliterator by its id ("Han-Latin"), as a function over text — Android's built-in
     * ICU on the headset, ICU4J on the desktop. Set once at startup, before the first [romanize];
     * left unset, nothing is romanised.
     */
    @Volatile
    var transliterators: (id: String) -> ((String) -> String)? = { null }

    private val cache = ConcurrentHashMap<String, String>()
    private val HAN = Regex("""\p{IsHan}""")
    private val KANA = Regex("""[\p{IsHiragana}\p{IsKatakana}ー]""")
    private val HANGUL = Regex("""\p{IsHangul}""")
    private val CYRILLIC = Regex("""\p{IsCyrillic}""")
    private val GREEK = Regex("""\p{IsGreek}""")
    private val THAI = Regex("""\p{IsThai}""")

    private fun icu(id: String): ((String) -> String)? = runCatching { transliterators(id) }.getOrNull()

    private val han by lazy { icu("Han-Latin") }
    private val kana by lazy { icu("Hiragana-Latin; Katakana-Latin") }
    private val hangul by lazy { icu("Hangul-Latin") }
    // Начало typed as "nachalo", Ψυχώ as "psycho", ฟ้าทะลายโจร as "fa thalai chon": a Latin keyboard finds them.
    private val cyrillic by lazy { icu("Cyrillic-Latin") }
    private val greek by lazy { icu("Greek-Latin") }
    private val thai by lazy { icu("Thai-Latin") }

    /** How much time the transliterators have taken, for the AuraPerf log. */
    val spentNanos = AtomicLong(0)
    val calls = AtomicLong(0)

    /** The texts romanised so far (an empty answer: nothing to romanise) — for TextMemo to keep across launches. */
    fun soFar(): Map<String, String> = HashMap(cache)

    /** Answers from an earlier launch (TextMemo), taken as given. */
    fun prime(answers: Map<String, String>) = cache.putAll(answers)

    fun romanize(text: String): String? {
        if (!SearchKeys.needsRomanizing(text)) return null
        cache[text]?.let { return it.ifEmpty { null } }
        val t0 = System.nanoTime()
        var out = text
        runCatching {
            if (HAN.containsMatchIn(out)) han?.let { out = it(out) }
            if (KANA.containsMatchIn(out)) kana?.let { out = it(out) }
            if (HANGUL.containsMatchIn(out)) hangul?.let { out = it(out) }
            if (CYRILLIC.containsMatchIn(out)) cyrillic?.let { out = it(out) }
            if (GREEK.containsMatchIn(out)) greek?.let { out = it(out) }
            if (THAI.containsMatchIn(out)) thai?.let { out = it(out) }
        }
        val result = if (out == text || SearchKeys.needsRomanizing(out) && out.length == text.length) "" else out
        spentNanos.addAndGet(System.nanoTime() - t0)
        calls.incrementAndGet()
        if (cache.size > 32_768) cache.clear()
        cache[text] = result
        return result.ifEmpty { null }
    }
}
