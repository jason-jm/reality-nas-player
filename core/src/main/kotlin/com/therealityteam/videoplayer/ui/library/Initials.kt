package com.therealityteam.videoplayer.ui.library

/**
 * What a person's avatar shows without a photo: the first letters of a Latin name's first and last
 * words ("Oleg Petrov" → "OP"), else its first character (萨姆·门德斯 → 萨, a single-word name → its
 * first letter) — as a contacts list draws them.
 */
object Initials {
    private val SEPARATORS = Regex("[\\s·・.\\-]+")

    fun of(name: String): String {
        val words = name.trim().split(SEPARATORS).filter { it.isNotEmpty() }
        val first = words.firstOrNull() ?: return ""
        val latin = first.first().isLatinLetter()
        return if (latin && words.size > 1 && words.last().first().isLatinLetter()) {
            (first.take(1) + words.last().take(1)).uppercase()
        } else {
            first.take(1).uppercase()
        }
    }

    private fun Char.isLatinLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in 'À'..'ɏ'
}
