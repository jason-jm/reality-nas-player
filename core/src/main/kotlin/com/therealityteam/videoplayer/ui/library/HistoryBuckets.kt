package com.therealityteam.videoplayer.ui.library

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 历史 in the order it was lived: what was watched today, yesterday, earlier this week, this month,
 * and before — by calendar day where the viewer is, from the last time any of a card's files played.
 * The cards keep the order they came in (latest first) within each bucket; an empty bucket is left out.
 */
object HistoryBuckets {

    enum class Bucket { TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH, EARLIER }

    fun of(
        groups: List<MediaGroup>,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Pair<Bucket, List<MediaGroup>>> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return groups.groupBy { bucketOf(lastWatched(it), today, zone) }
            .toList()
            .sortedBy { (bucket, _) -> bucket.ordinal }
    }

    /** When any of the card's files last played; 0 for never. */
    fun lastWatched(group: MediaGroup): Long = group.items.maxOfOrNull { it.progress.updatedAt } ?: 0L

    fun bucketOf(atMs: Long, today: LocalDate, zone: ZoneId): Bucket {
        if (atMs <= 0L) return Bucket.EARLIER
        val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
        val daysAgo = ChronoUnit.DAYS.between(day, today)
        return when {
            daysAgo <= 0L -> Bucket.TODAY
            daysAgo == 1L -> Bucket.YESTERDAY
            daysAgo < 7L -> Bucket.THIS_WEEK
            daysAgo < 30L -> Bucket.THIS_MONTH
            else -> Bucket.EARLIER
        }
    }
}
