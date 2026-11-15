package com.therealityteam.videoplayer.ui.library

/**
 * The letters beside a wall in title order, each with where its first card is: `#` for titles that
 * start with a digit or a sign, then the reader's letters — A–Z by pinyin for a Chinese reader (the
 * titles' sort keys, as the wall sorts them), the language's own alphabet for every other ([TitleOrder]:
 * Å Ä Ö after Z in Swedish, А–Я, ㄱ–ㅎ, あ–わ…) — and `?` for the cards no database named, which the
 * title order puts last. Only the letters that have cards: every mark on the rail goes somewhere.
 */
object TitleIndex {
    /** The mark for the cards no database named. */
    const val UNIDENTIFIED = '?'
    const val OTHER = '#'

    /** [groups] in [LibrarySort]'s title order → (mark, index of its first card), in wall order. */
    fun of(groups: List<MediaGroup>): List<Pair<Char, Int>> {
        val out = ArrayList<Pair<Char, Int>>()
        val seen = HashSet<Char>()
        val pinyin = TitleOrder.byPinyin()
        groups.forEachIndexed { i, g ->
            val mark = when {
                !LibrarySort.identified(g) -> UNIDENTIFIED
                pinyin -> markOf(g.titleKey)
                else -> TitleOrder.mark(g.title)
            }
            if (seen.add(mark)) out += mark to i
        }
        return out
    }

    fun markOf(titleKey: String): Char {
        val c = titleKey.trimStart().firstOrNull()?.uppercaseChar() ?: return OTHER
        return if (c in 'A'..'Z') c else OTHER
    }

    /** The mark of the card at [cardIndex]: the last entry at or before it. */
    fun current(index: List<Pair<Char, Int>>, cardIndex: Int): Char? =
        index.lastOrNull { it.second <= cardIndex }?.first ?: index.firstOrNull()?.first
}
