package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import com.therealityteam.videoplayer.desktop.platform.DesktopCore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fixing a misidentified series by hand, against the real TMDB and AniList: the whole card moves to
 * the chosen work — one card, every episode keeping its number — and a series whose name is also a
 * film's can be found at all.
 *
 *   ./gradlew -p desktop test --tests '*ManualMatchProbe*' -Daura.fixprobe=1 -i
 */
class ManualMatchProbe {

    private fun episode(id: String, folder: String, name: String, ep: Int, wrongTmdb: Int) = MediaItem(
        id = id, sourceId = "s", path = "/v/$folder/$name", fileName = name, title = "Wrong", kind = MediaKind.EPISODE,
        season = 1, episode = ep, tmdbId = wrongTmdb, scrapeStatus = ScrapeStatus.MATCHED, showTitle = "Wrong",
    )

    @Test
    fun fixWholeCards() {
        assumeTrue("set -Daura.fixprobe=1", System.getProperty("aura.fixprobe") != null)
        DesktopCore.install()
        TmdbConfig.language = "zh-CN"
        val graph = AppGraph(Files.createTempDirectory("aura-fix").toFile())
        val frieren = (1..3).map { episode("f$it", "Anime/Frieren", "[SubsPlease] Sousou no Frieren - 0$it (1080p) [ABC].mkv", it, 1399) }
        val shogun = (1..2).map { episode("s$it", "TV/Shogun", "Shogun.S01E0$it.2160p.WEB-DL.mkv", it, 20096) }
        graph.media.upsertAll(frieren + shogun)
        runBlocking {
            val before = withTimeout(30_000) { graph.library.groups.first { gs -> gs.sumOf { it.items.size } >= 5 } }
            println("FIXPROBE before: " + before.map { "${it.key}=${it.items.size}" })

            // AniList: the anime card, all three episodes, to Frieren.
            val hits = graph.library.searchAnimeMatches("Sousou no Frieren")
            println("FIXPROBE anilist hits: " + hits.take(4).map { "${it.title} (${it.year}) #${it.providerId}" })
            val pick = hits.first()
            val fixed = graph.library.applyMatch(frieren, pick, anime = true)
            fixed.forEach { println("FIXPROBE fixed ${it.fileName} -> ${it.title} ${it.collectionKey} S${it.season}E${it.episode} pinned=${it.userMatched}") }
            assertTrue(fixed.all { it.collectionKey == "anilist:${pick.providerId}" && it.userMatched })
            assertEquals(listOf(1, 2, 3), fixed.map { it.episode })

            // TMDB: a series whose name is also a film's.
            val tmdb = graph.library.searchMatches("Shogun")
            println("FIXPROBE tmdb hits: " + tmdb.take(6).map { "${it.title} (${it.year}) ${it.kind} #${it.providerId}" })
            val series = tmdb.first { it.providerId == 126308 && it.kind == MediaKind.SHOW }
            val moved = graph.library.applyMatch(shogun, series, anime = false)
            moved.forEach { println("FIXPROBE fixed ${it.fileName} -> ${it.title} tmdb=${it.tmdbId} S${it.season}E${it.episode} still=${it.stillUrl != null}") }
            assertTrue(moved.all { it.tmdbId == 126308 && it.userMatched && it.collectionKey == null })

            kotlinx.coroutines.delay(6_000)
            val after = graph.library.groups.value
            println("FIXPROBE after: " + after.map { "${it.key}=${it.items.size} '${it.title}'" })
            println("FIXPROBE repo: " + graph.media.all().map { "${it.id}:${it.collectionKey ?: it.tmdbId}:${it.showTitle}" })
            assertTrue(after.first { it.key.contains("126308") }.title.startsWith("Shōgun") || after.first { it.key.contains("126308") }.title.contains("幕府"), "the card has the show's name")
            assertEquals(3, after.first { it.key == "anilist:${pick.providerId}" }.items.size, "one Frieren card")
            assertEquals(2, after.first { it.key.contains("126308") }.items.size, "one Shōgun card")
        }
    }
}
