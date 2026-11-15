package com.therealityteam.videoplayer.di

import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import com.therealityteam.videoplayer.data.repository.JsonSourceRepository
import com.therealityteam.videoplayer.data.repository.MediaRepository
import com.therealityteam.videoplayer.data.repository.JsonSettingsRepository
import com.therealityteam.videoplayer.data.repository.SettingsRepository
import com.therealityteam.videoplayer.data.repository.SourceRepository
import com.therealityteam.videoplayer.data.scrape.ArtworkCache
import com.therealityteam.videoplayer.data.scrape.MetadataClient
import com.therealityteam.videoplayer.data.scrape.ScrapeEngine
import com.therealityteam.videoplayer.data.scrape.TmdbMetadataClient
import com.therealityteam.videoplayer.data.source.LocalSource
import com.therealityteam.videoplayer.data.source.SourceScanner
import com.therealityteam.videoplayer.platform.AndroidLibraryPlatform
import com.therealityteam.videoplayer.ui.library.LibraryViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Long-lived session scope hosting shared ViewModels / the single CypressMediaPlayer,
 * so the Shared-Space poster wall and the Full-Space immersive scene drive one player.
 * (Mirrors the official Spatial Video sample's Koin session scope.)
 */
const val AURA_SESSION_SCOPE_ID = "aura_session_scope"

val appModule = module {
    single<MediaRepository> { JsonMediaRepository(androidContext().filesDir) }
    single<SourceRepository> { JsonSourceRepository(androidContext().filesDir) }
    single<SettingsRepository> { JsonSettingsRepository(androidContext()) }
    single<MetadataClient> { TmdbMetadataClient(androidContext().filesDir) }
    single { ArtworkCache(androidContext()) }
    single { com.therealityteam.videoplayer.data.playback.LocalStreamServer() }
    single { com.therealityteam.videoplayer.data.playback.SmbFdBridge(androidContext()) }
    single { com.therealityteam.videoplayer.data.scrape.AniListClient(androidContext().filesDir) }
    single { com.therealityteam.videoplayer.data.scrape.BangumiClient(androidContext().filesDir) }
    single { com.therealityteam.videoplayer.data.scrape.OmdbClient(androidContext().filesDir) }
    single { com.therealityteam.videoplayer.data.scrape.JikanClient(androidContext().filesDir) }
    single { com.therealityteam.videoplayer.data.scrape.ImdbDatasetClient(androidContext().filesDir) }
    single { com.therealityteam.videoplayer.data.scrape.FramePoster(androidContext(), get(), get(), get()) }
    // 接收投屏: one receiver for the process, started and stopped by the setting (AuraApp).
    single {
        com.therealityteam.videoplayer.platform.CastReceiver(
            androidContext(),
            com.therealityteam.videoplayer.platform.AppCastPlayer(androidContext()) { com.therealityteam.videoplayer.platform.AppCastPlayer.sessionVm() },
        )
    }
    single {
        ScrapeEngine(
            get(), get(), get(), get(), get(), get(), get(), get(),
            sidecars = com.therealityteam.videoplayer.data.source.SourceSidecarReader(get()),
        )
    }
    single { com.therealityteam.videoplayer.data.sync.HeadsetSync(androidContext(), get(), get(), get(), engine = get()) }
    single { com.therealityteam.videoplayer.data.repository.BookmarkStore(androidContext().filesDir) }
    single { com.therealityteam.videoplayer.data.repository.SkipStore(androidContext().filesDir) }
    single { com.therealityteam.videoplayer.data.repository.CropStore(androidContext().filesDir) }
    single { com.therealityteam.videoplayer.data.repository.TrackPickStore(androidContext().filesDir) }
    single { com.therealityteam.videoplayer.data.repository.ContinueHiddenStore(androidContext().filesDir) }
    // 下载到本机: the list, and what works through it.
    single { com.therealityteam.videoplayer.data.download.DownloadStore(androidContext().filesDir) }
    single { com.therealityteam.videoplayer.data.download.Downloader(androidContext(), get(), get(), get(), get()) }
    single { LocalSource() }
    single { com.therealityteam.videoplayer.data.source.WebDavSource() }
    single { com.therealityteam.videoplayer.data.source.SmbSource() }
    single { com.therealityteam.videoplayer.data.source.SmbDiscovery() }
    single { com.therealityteam.videoplayer.data.source.JellyfinClient() }
    single { com.therealityteam.videoplayer.data.source.JellyfinSource(get(), get()) }
    single { com.therealityteam.videoplayer.data.source.PlexClient() }
    single { com.therealityteam.videoplayer.data.source.PlexSource(get(), get()) }
    single { com.therealityteam.videoplayer.data.source.DlnaSource() }
    single {
        SourceScanner(
            get(), get(), get(),
            listOf(
                get<LocalSource>(),
                get<com.therealityteam.videoplayer.data.source.WebDavSource>(),
                get<com.therealityteam.videoplayer.data.source.SmbSource>(),
                get<com.therealityteam.videoplayer.data.source.DlnaSource>(),
                com.therealityteam.videoplayer.data.source.RemoteSource(com.therealityteam.videoplayer.data.model.SourceType.FTP),
                com.therealityteam.videoplayer.data.source.RemoteSource(com.therealityteam.videoplayer.data.model.SourceType.SFTP),
                com.therealityteam.videoplayer.data.source.RemoteSource(com.therealityteam.videoplayer.data.model.SourceType.NFS),
            ),
            jellyfin = get(),
            plex = get(),
            onAdopted = { ids ->
                get<com.therealityteam.videoplayer.data.download.DownloadStore>().renamed(ids)
                get<com.therealityteam.videoplayer.data.repository.BookmarkStore>().renamed(ids)
                get<com.therealityteam.videoplayer.data.repository.CropStore>().renamed(ids)
            },
        )
    }

    // Session-scoped shared state (poster wall + immersive scene share one instance).
    scope(named(AURA_SESSION_SCOPE_ID)) {
        scoped { LibraryViewModel(get(), get(), get(), get(), AndroidLibraryPlatform(androidContext())) }
        scoped { com.therealityteam.videoplayer.ui.player.PlaybackViewModel(get(), get(), get(), get(), get(), androidContext(), sync = get(), bookmarkStore = get(), skipStore = get(), trackPicks = get(), scrape = get(), cropStore = get(), downloads = get(), onStreaming = get<com.therealityteam.videoplayer.data.download.Downloader>().let { dl -> { on: Boolean -> dl.streaming = on } }) }
        scoped { com.therealityteam.videoplayer.ui.library.LibraryNavState() }
    }
}
