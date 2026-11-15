package com.therealityteam.videoplayer.data.scrape

/**
 * A synopsis as a reader reads it. bgm.tv's often ends with its source — "[简介原文] 34歳・無職…", the
 * whole Japanese text again — which is cut off; and an episode's is often the Japanese only (bgm.tv
 * copies the official site's), which a Chinese reader is better without: the caller shows the
 * show's own, or nothing. Pure, so it is tested.
 */
object Synopsis {

    /** [text] for a reader of [language] ("zh", "zh-CN", "en"…), or null when there is nothing for them. */
    fun forReader(text: String?, language: String): String? {
        var t = paragraphs(unescape(text?.trim().orEmpty()))
        ORIGINAL.find(t)?.let { m -> if (m.range.first > 0) t = t.substring(0, m.range.first).trim() }
        if (t.isEmpty()) return null
        if (language.lowercase().startsWith("zh") && isJapanese(t)) return null
        return t
    }

    /**
     * HTML's character references as the characters: AniList's descriptions come with them written out
     * — "a new weapon in the war on crime &ndash; advanced robots", "Q&amp;A" — 109 of them over a
     * real library. The named ones its text uses, and any by number.
     */
    fun unescape(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { m ->
            val name = m.groupValues[1]
            when {
                name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)
                name.startsWith("#") -> name.substring(1).toIntOrNull()
                else -> NAMED[name]
            }?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
        }
    }

    /**
     * Chinese sites indent each paragraph with two ideographic spaces; with the line breaks lost they stood in the
     * middle of the text as a wide gap ("…事迹改编。　　20世纪90年代中期…" — 130 synopses of a 9,648-item library).
     * Such an indent is a paragraph break, and no paragraph keeps one.
     */
    fun paragraphs(text: String): String {
        if ('\u3000' !in text) return text
        return INDENT.replace(text, "\n").lines().joinToString("\n") { it.trim(' ', '\u3000') }.trim()
    }

    /** Written in Japanese: kana among its CJK, more than a name's worth — Chinese writes none. */
    fun isJapanese(text: String): Boolean {
        var han = 0
        var kana = 0
        for (c in text) {
            when (c) {
                in '぀'..'ゟ', in '゠'..'ヺ' -> kana++
                in '一'..'鿿' -> han++
            }
        }
        return kana >= MIN_KANA && kana * 100 >= (han + kana) * KANA_PERCENT
    }

    /** Where bgm.tv's translation hands over to its source. */
    private val ORIGINAL = Regex("""[\[【〔(（]?\s*(?:简介原文|簡介原文|原文简介|原文簡介)\s*[\]】〕)）]?\s*[:：]?""")
    private val INDENT = Regex("""\s*\u3000{2,}\s*""")
    private val ENTITY = Regex("""&(#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);""")
    private val NAMED = mapOf(
        "amp" to '&'.code, "lt" to '<'.code, "gt" to '>'.code, "quot" to '"'.code, "apos" to '\''.code,
        "nbsp" to 0xA0, "ndash" to 0x2013, "mdash" to 0x2014, "hellip" to 0x2026,
        "lsquo" to 0x2018, "rsquo" to 0x2019, "ldquo" to 0x201C, "rdquo" to 0x201D,
    )
    private const val MIN_KANA = 5
    private const val KANA_PERCENT = 15
}
