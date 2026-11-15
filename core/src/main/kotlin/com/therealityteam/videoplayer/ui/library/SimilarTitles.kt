package com.therealityteam.videoplayer.ui.library

import kotlin.math.abs

/**
 * 相似作品: the cards in this library most like the one on screen, from what the library already knows
 * about both — no network, no history of anybody's taste.
 *
 * Alike means made by the same people (a shared director or writer counts most), sharing genres
 * (measured against both cards' genres together, so a card tagged with everything does not match
 * everything), then the same kind of thing (an anime beside anime) from about the same years. Not yet
 * seen breaks a near tie in its favour, and so does a good score: the row is something to watch next.
 * A card that shares nothing at all with this one is never offered, however well rated.
 */
object SimilarTitles {

    fun of(target: MediaGroup, library: List<MediaGroup>, limit: Int = 12): List<MediaGroup> {
        val genres = WallFilter.genresOf(target)
        val crew = namesOf(target, crew = true)
        val cast = namesOf(target, crew = false)
        if (genres.isEmpty() && crew.isEmpty() && cast.isEmpty()) return emptyList()
        return library.asSequence()
            .filter { it.key != target.key }
            .mapNotNull { card ->
                val cardGenres = WallFilter.genresOf(card)
                val sharedGenres = cardGenres.count { it in genres }
                val sharedCrew = namesOf(card, crew = true).count { it in crew }
                val sharedCast = namesOf(card, crew = false).count { it in cast }
                if (sharedGenres == 0 && sharedCrew == 0 && sharedCast == 0) return@mapNotNull null
                val genreOverlap = if (sharedGenres == 0) 0.0 else sharedGenres.toDouble() / (genres.size + cardGenres.size - sharedGenres)
                var score = genreOverlap * 4.0 + sharedCrew * 3.0 + minOf(sharedCast, 4) * 1.0
                if (card.category == target.category) score += 1.0
                val y = target.year
                val cy = card.year
                if (y != null && cy != null && abs(y - cy) <= 5) score += 0.5
                if (WallFilter.watchOf(card) != WallFilter.Watch.WATCHED) score += 0.5
                score += LibrarySort.ratingValue(card.ratings) / 10.0
                card to score
            }
            .filter { (_, score) -> score >= MIN_SCORE }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (card, _) -> card }
            .toList()
    }

    /**
     * Below this a card only shares one broad genre with nothing else in common — 剧情 with 剧情 — and
     * a row of those reads as random rather than similar.
     */
    private const val MIN_SCORE = 2.6

    /** The representative's credits — a show credits the same people on every episode. */
    private fun namesOf(card: MediaGroup, crew: Boolean): Set<String> =
        (if (crew) card.representative.crew else card.representative.cast)
            .mapNotNullTo(HashSet()) { p -> p.name.trim().takeIf(String::isNotEmpty) }
}
