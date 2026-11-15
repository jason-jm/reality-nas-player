package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.WallFilter
import com.therealityteam.videoplayer.ui.library.WallFilter.Watch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 筛选: watch state × genre (by the name the page shows) × decade, every choice made must hold. */
class WallFilterTest {

    private fun item(id: String, genres: List<String>, position: Long = 0, duration: Long = 0, watched: Boolean = false) = MediaItem(
        id, "s", "/v/$id.mkv", "$id.mkv", title = id, genres = genres,
        progress = PlaybackProgress(positionMs = position, durationMs = duration, watched = watched),
    )

    private fun card(key: String, year: Int?, vararg items: MediaItem) = MediaGroup(
        key = key, title = key, year = year, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null, ratings = emptyList(), isSeries = items.size > 1, seasonal = false,
        category = LibCategory.MOVIE, count = items.size, items = items.toList(),
    )

    private val blade = card("blade", 1982, item("b", listOf("Science Fiction", "Thriller"), watched = true))
    private val arrival = card("arrival", 2016, item("a", listOf("科幻", "剧情"), position = 3_000_000, duration = 6_000_000))
    private val heat = card("heat", 1995, item("h", listOf("犯罪", "剧情")))
    private val nogenre = card("home", null, item("x", emptyList()))
    private val wall = listOf(blade, arrival, heat, nogenre)

    @Test fun `an empty filter lets everything through`() {
        assertFalse(WallFilter().isActive)
        assertEquals(wall, WallFilter().apply(wall))
    }

    @Test fun `genres match by the name the page shows`() {
        assertEquals(listOf(blade, arrival), WallFilter(genre = "科幻").apply(wall))
        val counts = WallFilter.genreCounts(wall).toMap()
        assertEquals(2, counts["科幻"])
        assertEquals(2, counts["剧情"])
    }

    @Test fun `watch state comes from how much of the card was seen`() {
        assertEquals(Watch.WATCHED, WallFilter.watchOf(blade))
        assertEquals(Watch.IN_PROGRESS, WallFilter.watchOf(arrival))
        assertEquals(Watch.UNWATCHED, WallFilter.watchOf(heat))
        assertEquals(listOf(heat, nogenre), WallFilter(watch = Watch.UNWATCHED).apply(wall))
    }

    @Test fun `every part set must hold, and a card without a year never matches a decade`() {
        val f = WallFilter(watch = Watch.UNWATCHED, genre = "剧情", decade = 1990)
        assertTrue(f.isActive)
        assertEquals(3, f.activeCount)
        assertEquals(listOf(heat), f.apply(wall))
        assertEquals(emptyList<MediaGroup>(), WallFilter(decade = 2010).apply(listOf(nogenre)))
        assertEquals(listOf(2010 to 1, 1990 to 1, 1980 to 1), WallFilter.decadeCounts(wall))
    }

    @Test fun `panoramas and 3D films by their pictures`() {
        fun clip(id: String, flags: com.therealityteam.videoplayer.data.model.VideoFlags) =
            MediaItem(id, "s", "/v/$id.mp4", "$id.mp4", title = id, video = flags)
        val pano = card("walk", null, clip("w", com.therealityteam.videoplayer.data.model.VideoFlags(
            com.therealityteam.videoplayer.data.model.StereoMode.MONO, com.therealityteam.videoplayer.data.model.ProjectionMode.EQ360, fromFile = true)))
        val vr180 = card("canyon", null, clip("c", com.therealityteam.videoplayer.data.model.VideoFlags(
            com.therealityteam.videoplayer.data.model.StereoMode.SBS, com.therealityteam.videoplayer.data.model.ProjectionMode.EQ180)))
        val avatar = card("avatar", 2009, clip("v", com.therealityteam.videoplayer.data.model.VideoFlags(
            com.therealityteam.videoplayer.data.model.StereoMode.OU, com.therealityteam.videoplayer.data.model.ProjectionMode.FLAT)))
        val all = wall + listOf(pano, vr180, avatar)
        assertEquals(listOf(pano, vr180), WallFilter(picture = WallFilter.Picture.PANORAMA).apply(all))
        assertEquals(listOf(avatar), WallFilter(picture = WallFilter.Picture.THREE_D).apply(all))
        assertEquals(mapOf(WallFilter.Picture.PANORAMA to 2, WallFilter.Picture.THREE_D to 1), WallFilter.pictureCounts(all))
        assertEquals(1, WallFilter(picture = WallFilter.Picture.PANORAMA).activeCount)
    }
}
