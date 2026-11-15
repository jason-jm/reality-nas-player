package com.therealityteam.videoplayer.data.scrape

/**
 * The name a Chinese reader knows a work by, when the database's Chinese translation has a
 * synopsis but no name and so answers with the original: Andor, Obi-Wan Kenobi, Top Gun: Maverick
 * on a Chinese wall. The work's alternative titles carry the names each region uses — 星球大战：安多,
 * 欧比旺·克诺比, 壮志凌云2：独行侠 — and the reader's own region's come first. Pure.
 */
object ChineseTitle {

    /** Whether [s] holds Chinese characters (or Japanese kanji — a name already in the reader's script). */
    fun hasHan(s: String): Boolean = s.any { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }

    /**
     * Whether a title a Chinese reader is shown wants a Chinese name: written in letters (Latin, kana,
     * Hangul…) and no Chinese characters. A title of digits alone reads the same in every language —
     * the film is 1917 in China too, not the 一九一七 a region lists.
     */
    fun wantsChineseName(title: String): Boolean = title.any { it.isLetter() } && !hasHan(title)

    /**
     * The first name in Chinese characters among [regional] (region code → title, in the database's
     * order), the reader's regions first: mainland and Singapore for a simplified reader, Taiwan,
     * Hong Kong and Macau for a traditional one — the other script only when theirs has none. Null
     * when no region names it in Chinese.
     */
    fun pick(regional: List<Pair<String, String>>, language: String): String? {
        val order = if (traditional(language)) TRADITIONAL + SIMPLIFIED else SIMPLIFIED + TRADITIONAL
        for (region in order) {
            regional.firstOrNull { (r, title) -> r.equals(region, ignoreCase = true) && hasHan(title) }?.let { return it.second.trim() }
        }
        return null
    }

    private fun traditional(language: String): Boolean {
        val tag = language.lowercase()
        return "hant" in tag || tag.endsWith("-tw") || tag.endsWith("-hk") || tag.endsWith("-mo")
    }

    private val SIMPLIFIED = listOf("CN", "SG")
    private val TRADITIONAL = listOf("TW", "HK", "MO")
}
