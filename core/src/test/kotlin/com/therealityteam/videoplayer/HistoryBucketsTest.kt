package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.ui.library.HistoryBuckets
import com.therealityteam.videoplayer.ui.library.HistoryBuckets.Bucket
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaGroup
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 历史 by calendar day where the viewer is: today, yesterday, this week, this month, earlier. */
class HistoryBucketsTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 2, 9, 30).atZone(zone).toInstant().toEpochMilli()
    private fun at(day: Int, hour: Int, month: Int = 10) = LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun card(key: String, vararg watchedAt: Long) = MediaGroup(
        key = key, title = key, year = null, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null, ratings = emptyList(), isSeries = watchedAt.size > 1, seasonal = false,
        category = LibCategory.MOVIE, count = watchedAt.size,
        items = watchedAt.mapIndexed { i, t ->
            MediaItem("$key$i", "s", "/v/$key$i.mkv", "$key$i.mkv", title = key, progress = PlaybackProgress(1, 10, false, t))
        },
    )

    @Test fun `cards fall into calendar days, a show by its latest episode`() {
        val buckets = HistoryBuckets.of(
            listOf(
                card("early-today", at(2, 1)),
                card("late-last-night", at(1, 23)),
                card("show", at(20, 20, month = 9), at(29, 21, month = 9)),
                card("week-ago", at(25, 12, month = 9)),
                card("summer", at(15, 12, month = 7)),
            ),
            nowMs = now, zone = zone,
        )
        assertEquals(
            listOf(
                Bucket.TODAY to listOf("early-today"),
                Bucket.YESTERDAY to listOf("late-last-night"),
                Bucket.THIS_WEEK to listOf("show"),
                Bucket.THIS_MONTH to listOf("week-ago"),
                Bucket.EARLIER to listOf("summer"),
            ),
            buckets.map { (b, cards) -> b to cards.map { it.key } },
        )
    }

    @Test fun `empty buckets are left out and order within a bucket is kept`() {
        val buckets = HistoryBuckets.of(listOf(card("b", at(2, 8)), card("a", at(2, 2))), nowMs = now, zone = zone)
        assertEquals(listOf(Bucket.TODAY to listOf("b", "a")), buckets.map { (b, cards) -> b to cards.map { it.key } })
    }
}
