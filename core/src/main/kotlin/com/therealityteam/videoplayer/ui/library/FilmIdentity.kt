package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.scrape.StringSimilarity
import kotlin.math.abs

/**
 * Which film a file is, as the databases say — for the files whose names cannot say it: one film
 * filed twice, a 2160p release in 下载 named in Chinese and a BDRip on the anime shelf named in
 * English, identified once by TMDB and once by AniList. Pure.
 */
object FilmIdentity {

    /** The scraped identity a file carries: an AniList collectionKey, else a TMDB id. */
    fun key(item: MediaItem): String? = item.collectionKey ?: item.tmdbId?.let { "id:$it" }

    fun isTmdb(key: String): Boolean = key.startsWith("id:")

    fun isAniList(key: String): Boolean = key.startsWith(ANILIST)

    /**
     * The film's own name as its database gives it, folded for comparing: TMDB's original title and
     * AniList's native title are the same Japanese, letter for letter, where the films are one.
     */
    fun name(item: MediaItem): String? =
        item.originalTitle?.let { StringSimilarity.normalizeTitle(it).filterNot(Char::isWhitespace) }?.ifBlank { null }

    /** One year, a year apart at most: a festival premiere and a release are both "the year". */
    fun sameYear(a: Int, b: Int): Boolean = abs(a - b) <= 1

    /** Whether [a] and [b] are copies of one film that the two databases each identified. */
    fun oneFilmTwoDatabases(a: MediaItem, b: MediaItem): Boolean {
        val ka = key(a) ?: return false
        val kb = key(b) ?: return false
        if (!(isTmdb(ka) && isAniList(kb) || isAniList(ka) && isTmdb(kb))) return false
        val name = name(a) ?: return false
        val ya = a.year ?: return false
        val yb = b.year ?: return false
        return name == name(b) && sameYear(ya, yb)
    }

    private const val ANILIST = "anilist:"
}
