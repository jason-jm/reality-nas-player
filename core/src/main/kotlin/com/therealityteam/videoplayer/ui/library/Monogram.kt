package com.therealityteam.videoplayer.ui.library

/**
 * The one character that stands in for a card with no artwork.
 *
 * The first LETTER, not the first character. Release folders are full of leading numbers —
 * `00 Footage`, `01-Pick`, `03-BROLL Extra`, `2001太空漫游` — and taking the digit gave a wall of
 * identical `0`s and `1`s where the monogram is meant to be the one thing telling the tiles apart.
 *
 * A title with no letters at all is different: there the number IS the name, and `1917` says more
 * than `1` ever could, so the whole of it is shown. It stays short by definition — anything long
 * enough to need trimming has letters in it.
 */
fun monogram(title: String): String {
    val t = title.trim().trimStart('[', '(', '【', '「', '『', '"', '\'', '《')
    t.firstOrNull(Char::isLetter)?.let { return it.toString().uppercase() }
    val digits = t.takeWhile { it.isDigit() }
    if (digits.isNotEmpty()) return digits.take(4)
    return (t.firstOrNull() ?: return "").toString().uppercase()
}
