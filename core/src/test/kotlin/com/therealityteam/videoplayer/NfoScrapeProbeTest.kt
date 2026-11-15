package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import com.therealityteam.videoplayer.data.scrape.AniListClient
import com.therealityteam.videoplayer.data.scrape.ArtworkCache
import com.therealityteam.videoplayer.data.scrape.BangumiClient
import com.therealityteam.videoplayer.data.scrape.ImdbDatasetClient
import com.therealityteam.videoplayer.data.scrape.JikanClient
import com.therealityteam.videoplayer.data.scrape.MetaResult
import com.therealityteam.videoplayer.data.scrape.MetadataClient
import com.therealityteam.videoplayer.data.scrape.OmdbClient
import com.therealityteam.videoplayer.data.scrape.ParsedName
import com.therealityteam.videoplayer.data.scrape.ScrapeEngine
import com.therealityteam.videoplayer.data.scrape.TmdbMetadataClient
import com.therealityteam.videoplayer.data.source.LocalSource
import com.therealityteam.videoplayer.data.source.SidecarReader
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Every file in a real folder identified twice through the real engine and TMDB — once by its name
 * alone, once with the nfo and pictures beside it — and the two answers side by side.
 * `-Daura.nfo.scrape=<folder>`; skipped without it. Asks TMDB; IMDb's dataset is not fetched.
 */
class NfoScrapeProbeTest {

    /** TMDB as the app asks it, minus the IMDb id that would make the ratings step fetch a dataset. */
    private class NoImdb(private val inner: MetadataClient) : MetadataClient by inner {
        var searches = 0
        override suspend fun search(parsed: ParsedName): List<MetaResult> { searches++; return inner.search(parsed) }
        override suspend fun details(providerId: Int, kind: MediaKind): MetaResult? = inner.details(providerId, kind)?.copy(imdbId = null)
    }

    @Test fun probe(): Unit = runBlocking {
        val dir = System.getProperty("aura.nfo.scrape")
        assumeTrue(dir != null)
        val files = Files.createTempDirectory("nfo-probe").toFile()
        val tmdb = NoImdb(TmdbMetadataClient(files))
        fun engine(sidecars: SidecarReader?) = ScrapeEngine(
            tmdb, ArtworkCache(files) { _, _, _ -> null }, JsonMediaRepository(files),
            AniListClient(files), BangumiClient(files), OmdbClient(files), JikanClient(files), ImdbDatasetClient(files),
            sidecars,
        )
        val local = SidecarReader { _, path, max -> File(path).takeIf { it.isFile && it.length() <= max }?.readBytes() }
        val config = MediaSourceConfig("probe", SourceType.LOCAL, "probe", dir!!)
        val items = LocalSource().list(config).filter { it.local != null }.map { v ->
            MediaItem(MediaItem.idFor(config.id, v.path), config.id, v.path, v.fileName, title = v.fileName.substringBeforeLast('.'), local = v.local)
        }
        for (item in items.sortedBy { it.fileName }) {
            tmdb.searches = 0
            val byName = engine(null).scrape(item.copy(local = null))
            val nameSearches = tmdb.searches
            tmdb.searches = 0
            val withNfo = engine(local).scrape(item)
            println("NFO-SCRAPE ${item.fileName}")
            println("NFO-SCRAPE   by name: tmdb=${byName.tmdbId} «${byName.title}» ${byName.year} ${byName.kind} (${nameSearches} searches)")
            println("NFO-SCRAPE   by nfo:  tmdb=${withNfo.tmdbId} «${withNfo.title}» ${withNfo.year} ${withNfo.kind} (${tmdb.searches} searches) fromNfo=${withNfo.fromNfo}")
            println("NFO-SCRAPE   poster=${withNfo.posterPath?.let { File(it).name }} (${withNfo.posterPath?.let { File(it).length() }} B) backdrop=${withNfo.backdropPath?.let { File(it).name }}")
            println("NFO-SCRAPE   ratings=${withNfo.ratings.joinToString { "${it.source}:${it.value}" }} cast=${withNfo.cast.size} crew=${withNfo.crew.take(3).map { "${it.name}/${it.role}" }}")
        }
        files.deleteRecursively()
    }
}
