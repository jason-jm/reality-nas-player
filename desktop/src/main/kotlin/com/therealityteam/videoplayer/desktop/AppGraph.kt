package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.playback.LocalStreamServer
import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import com.therealityteam.videoplayer.data.repository.JsonSourceRepository
import com.therealityteam.videoplayer.data.repository.MediaRepository
import com.therealityteam.videoplayer.data.repository.SourceRepository
import com.therealityteam.videoplayer.data.scrape.AniListClient
import com.therealityteam.videoplayer.data.scrape.ArtworkCache
import com.therealityteam.videoplayer.data.scrape.BangumiClient
import com.therealityteam.videoplayer.data.scrape.ImdbDatasetClient
import com.therealityteam.videoplayer.data.scrape.JikanClient
import com.therealityteam.videoplayer.data.scrape.OmdbClient
import com.therealityteam.videoplayer.data.scrape.ScrapeEngine
import com.therealityteam.videoplayer.data.scrape.TmdbMetadataClient
import com.therealityteam.videoplayer.data.source.DlnaSource
import com.therealityteam.videoplayer.data.source.JellyfinClient
import com.therealityteam.videoplayer.data.source.JellyfinSource
import com.therealityteam.videoplayer.data.source.LocalSource
import com.therealityteam.videoplayer.data.source.PlexClient
import com.therealityteam.videoplayer.data.source.PlexSource
import com.therealityteam.videoplayer.data.source.SmbDiscovery
import com.therealityteam.videoplayer.data.source.SmbSource
import com.therealityteam.videoplayer.data.source.SourceScanner
import com.therealityteam.videoplayer.data.source.WebDavSource
import com.therealityteam.videoplayer.desktop.data.SettingsStore
import com.therealityteam.videoplayer.desktop.platform.DesktopLibraryPlatform
import com.therealityteam.videoplayer.desktop.platform.SkiaSampler
import com.therealityteam.videoplayer.desktop.sync.SyncService
import com.therealityteam.videoplayer.ui.library.LibraryViewModel
import java.io.File

/**
 * The app's objects, made once and wired by hand — the headset's Koin module (AppModule), for a
 * program with one process and one window.
 */
class AppGraph(val filesDir: File) {
    val settings = SettingsStore(filesDir)

    val media: MediaRepository = JsonMediaRepository(filesDir)
    val sources: SourceRepository = JsonSourceRepository(filesDir)

    val artwork = ArtworkCache(filesDir, SkiaSampler)
    private val tmdb = TmdbMetadataClient(filesDir)
    val scrape = ScrapeEngine(
        client = tmdb,
        artwork = artwork,
        repo = media,
        anilist = AniListClient(filesDir),
        bangumi = BangumiClient(filesDir),
        omdb = OmdbClient(filesDir),
        jikan = JikanClient(filesDir),
        imdb = ImdbDatasetClient(filesDir),
        sidecars = com.therealityteam.videoplayer.data.source.SourceSidecarReader(sources),
        publicMovies = com.therealityteam.videoplayer.data.scrape.PublicMovieCatalog(filesDir),
    )

    val smb = SmbSource()
    val smbDiscovery = SmbDiscovery()
    val jellyfin = JellyfinSource(JellyfinClient(), artwork)
    val plex = PlexSource(PlexClient(), artwork)
    val scanner = SourceScanner(
        media, sources, scrape,
        listOf(
            LocalSource(), WebDavSource(), smb, DlnaSource(),
            com.therealityteam.videoplayer.data.source.RemoteSource(com.therealityteam.videoplayer.data.model.SourceType.FTP),
            com.therealityteam.videoplayer.data.source.RemoteSource(com.therealityteam.videoplayer.data.model.SourceType.SFTP),
            com.therealityteam.videoplayer.data.source.RemoteSource(com.therealityteam.videoplayer.data.model.SourceType.NFS),
        ),
        jellyfin = jellyfin,
        plex = plex,
    )

    val library = LibraryViewModel(media, scrape, scanner, sources, DesktopLibraryPlatform(filesDir))

    /** Streams SMB files to mpv over loopback HTTP with Range support (as on the headset). */
    val streamServer = LocalStreamServer()

    /** Progress and favourites shared with the user's other devices — only when they turn it on. */
    val sync = SyncService(this)

    /** Posters from the videos themselves, for cards nothing identifies. */
    val framePosters = com.therealityteam.videoplayer.desktop.player.FramePosters(this)
    val frameStills by lazy { com.therealityteam.videoplayer.desktop.player.FrameStills(this) }
}
