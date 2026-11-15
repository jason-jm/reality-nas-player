package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.model.StereoMode
import com.therealityteam.videoplayer.data.scrape.GenreCn

/**
 * The wall's 筛选: narrow a shelf — or a search — by how far it has been watched, by genre, by
 * decade, and by the picture (全景 for 360°/180°, 3D for a flat stereo film). Each is one choice or
 * none, and every choice made must hold.
 *
 * Genres are compared by the name the page shows them under ([GenreCn]): one card's "Sci-Fi" and
 * another's 科幻 are one genre to the reader, so they are one chip and one filter.
 */
data class WallFilter(
    val watch: Watch = Watch.ANY,
    val genre: String? = null,
    val decade: Int? = null,
    val picture: Picture = Picture.ANY,
) {
    enum class Watch { ANY, UNWATCHED, IN_PROGRESS, WATCHED }

    /** What the picture is: round the viewer (360°/180°, any projection), or a flat film in stereo. */
    enum class Picture { ANY, PANORAMA, THREE_D }

    val isActive: Boolean get() = watch != Watch.ANY || genre != null || decade != null || picture != Picture.ANY

    /** How many of the four parts are set — the number the 筛选 chip shows. */
    val activeCount: Int get() = listOf(watch != Watch.ANY, genre != null, decade != null, picture != Picture.ANY).count { it }

    fun apply(groups: List<MediaGroup>): List<MediaGroup> = if (!isActive) groups else groups.filter(::matches)

    fun matches(group: MediaGroup): Boolean =
        (watch == Watch.ANY || watchOf(group) == watch) &&
            (genre == null || genre in genresOf(group)) &&
            (decade == null || group.year?.let(::decadeOf) == decade) &&
            (picture == Picture.ANY || picture in picturesOf(group))

    companion object {
        /** Seen to the end, begun, or not touched — by the share of the card's files watched. */
        fun watchOf(group: MediaGroup): Watch {
            val seen = group.watchedFraction
            return when {
                seen >= 1f -> Watch.WATCHED
                seen > 0f -> Watch.IN_PROGRESS
                else -> Watch.UNWATCHED
            }
        }

        fun genresOf(group: MediaGroup): Set<String> =
            group.representative.genres.flatMap(GenreCn::zhAll).mapNotNullTo(LinkedHashSet()) { it.trim().takeIf(String::isNotEmpty) }

        fun decadeOf(year: Int): Int = year / 10 * 10

        /**
         * What the card's files are as pictures, by their flags — the file's own word once played or
         * looked at, the viewer's choice, else the name: a panorama when any of them goes round the
         * viewer, 3D when any is a flat stereo film.
         */
        fun picturesOf(group: MediaGroup): Set<Picture> {
            val out = LinkedHashSet<Picture>()
            for (item in group.items) {
                val v = item.video
                if (v.isImmersive) out += Picture.PANORAMA
                else if (v.stereo == StereoMode.SBS || v.stereo == StereoMode.OU) out += Picture.THREE_D
            }
            return out
        }

        /** How many cards hold a panorama, and how many a 3D film. */
        fun pictureCounts(groups: List<MediaGroup>): Map<Picture, Int> =
            groups.flatMap { picturesOf(it) }.groupingBy { it }.eachCount()

        /** The genres on [groups] and how many cards carry each, most common first. */
        fun genreCounts(groups: List<MediaGroup>): List<Pair<String, Int>> =
            groups.flatMap { genresOf(it) }.groupingBy { it }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key to it.value }

        /** The decades [groups] were made in and how many cards each, newest first. */
        fun decadeCounts(groups: List<MediaGroup>): List<Pair<Int, Int>> =
            groups.mapNotNull { g -> g.year?.let(::decadeOf) }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.key }
                .map { it.key to it.value }

        fun watchCounts(groups: List<MediaGroup>): Map<Watch, Int> =
            groups.groupingBy(::watchOf).eachCount()
    }
}
