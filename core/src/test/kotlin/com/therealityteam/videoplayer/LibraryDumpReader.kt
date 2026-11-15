package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import java.io.File

/**
 * The flat TSV the evaluation harness extracts from a device's `library.json`, as MediaItems.
 *
 * Shared by every offline dev tool that replays real library data — the card dump, the grouping
 * bench, the reconciliation probe — so they all read the same columns the same way.
 */
object LibraryDumpReader {

    /** Reads the flat TSV the evaluation harness extracts from a device's `library.json`. */
    fun read(f: File): List<MediaItem> {
        val lines = f.readLines()
        val head = lines.first().split('\t')
        return lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val c = line.split('\t')
            fun s(n: String): String = head.indexOf(n).let { if (it < 0) "" else c.getOrNull(it).orEmpty() }
            fun sn(n: String): String? = s(n).ifBlank { null }
            MediaItem(
                id = s("id"),
                sourceId = s("sourceId").ifBlank { "s" },
                path = s("path"),
                fileName = s("fileName"),
                title = s("title"),
                originalTitle = sn("originalTitle"),
                aliases = s("aliases").split('|').filter { it.isNotBlank() },
                year = sn("year")?.toIntOrNull(),
                kind = runCatching { MediaKind.valueOf(s("kind")) }.getOrDefault(MediaKind.UNKNOWN),
                season = sn("season")?.toIntOrNull(),
                episode = sn("episode")?.toIntOrNull(),
                showTitle = sn("showTitle"),
                collectionKey = sn("collectionKey"),
                contentTag = sn("contentTag")?.let { runCatching { ContentKind.valueOf(it) }.getOrNull() },
                tmdbId = sn("tmdbId")?.toIntOrNull(),
                scrapeStatus = runCatching { ScrapeStatus.valueOf(s("scrapeStatus")) }
                    .getOrDefault(ScrapeStatus.PENDING),
                posterPath = sn("posterPath"),
                genres = s("genres").split('|').filter { it.isNotBlank() },
                favorite = s("favorite") == "true",
                addedAt = sn("addedAt")?.toLongOrNull() ?: 0L,
            )
        }
    }
}
