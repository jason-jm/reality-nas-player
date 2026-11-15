package com.therealityteam.videoplayer.desktop.data

import com.therealityteam.videoplayer.data.playback.SubtitleColor
import com.therealityteam.videoplayer.data.playback.SubtitleFont
import com.therealityteam.videoplayer.data.playback.SubtitleStyle
import com.therealityteam.videoplayer.data.scrape.OmdbConfig
import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** Light, dark, or whatever the system is set to. */
enum class ThemeChoice { SYSTEM, DARK, LIGHT }

/**
 * Everything the desktop app remembers between runs. The keys the headset also has
 * (tmdbApiKey, subtitle*, librarySort, …) keep the headset's names and meanings.
 */
data class DesktopSettings(
    // Scraping
    val tmdbApiKey: String = "",
    val omdbApiKey: String = "",
    val offlineScrape: Boolean = false,
    /** Read the nfo and pictures beside a file, as the headset does. See TmdbConfig.readLocal. */
    val readLocalMeta: Boolean = true,
    /** Metadata language; "" follows the system. */
    val language: String = "",
    /** The app's own language; "" follows the system. */
    val uiLanguage: String = "",
    // Library
    val librarySort: String = "RECENT",
    val showAdult: Boolean = false,
    /** Poster width on the wall, in dp. */
    val posterWidth: Float = 168f,
    val sidebarCollapsed: Boolean = false,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    // Subtitles
    val subtitleScale: Float = 1.0f,
    val subtitleRaise: Float = 0.0f,
    val subtitleStyle: SubtitleStyle = SubtitleStyle.OUTLINE,
    val subtitleOutline: Int = 2,
    val subtitleFont: SubtitleFont = SubtitleFont.DEFAULT,
    val subtitleColor: SubtitleColor = SubtitleColor.WHITE,
    val subtitleKeepStyles: Boolean = true,
    // Playback
    val volume: Float = 100f,
    val muted: Boolean = false,
    val hardwareDecoding: Boolean = true,
    /** mpv's name for the output device; "auto" follows the system's default. */
    val audioDevice: String = "auto",
    /** Seconds the arrow keys skip. */
    val seekStep: Int = 10,
    /** Play the next file in the queue when one ends. */
    val autoPlayNext: Boolean = true,
    /** Give cards nothing identifies a poster made from a frame of the video. */
    val framePosters: Boolean = true,
    // Window
    val windowWidth: Int = 1320,
    val windowHeight: Int = 860,
    val windowX: Int = Int.MIN_VALUE,
    val windowY: Int = Int.MIN_VALUE,
    val windowMaximized: Boolean = false,
    // Sync (off unless asked for — see docs/desktop/PLAN.md)
    val syncEnabled: Boolean = false,
    /** The source id whose share root holds `.aura/`, or a folder path. */
    val syncLocation: String = "",
    val syncProgress: Boolean = true,
    val syncCorrections: Boolean = true,
    val syncLibrary: Boolean = false,
    val deviceName: String = "",
    /** A stable id for this computer in the sync folder. */
    val deviceId: String = "",
)

/** [DesktopSettings] in `aura/settings.json`, pushed into the engine on load and on every change. */
class SettingsStore(filesDir: File) {
    private val file = File(filesDir, "aura/settings.json").apply { parentFile?.mkdirs() }
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<DesktopSettings> = _settings.asStateFlow()

    init {
        if (_settings.value.deviceId.isBlank()) {
            update { it.copy(deviceId = java.util.UUID.randomUUID().toString()) }
        }
        apply(_settings.value)
    }

    val value: DesktopSettings get() = _settings.value

    @Synchronized
    fun update(change: (DesktopSettings) -> DesktopSettings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        val scrapingChanged = next.tmdbApiKey != _settings.value.tmdbApiKey ||
            next.omdbApiKey != _settings.value.omdbApiKey ||
            next.offlineScrape != _settings.value.offlineScrape ||
            next.readLocalMeta != _settings.value.readLocalMeta ||
            next.language != _settings.value.language
        _settings.value = next
        persist(next)
        if (scrapingChanged) apply(next)
    }

    /** Push the scraping settings into the engine — as the headset's settings repository does. */
    private fun apply(s: DesktopSettings) {
        TmdbConfig.offline = s.offlineScrape
        TmdbConfig.readLocal = s.readLocalMeta
        TmdbConfig.apiKey = s.tmdbApiKey.trim()
        TmdbConfig.language = s.language.ifBlank { java.util.Locale.getDefault().toLanguageTag() }
        OmdbConfig.apiKey = if (s.offlineScrape) "" else s.omdbApiKey.trim()
    }

    private fun load(): DesktopSettings = runCatching {
        if (!file.exists()) return DesktopSettings()
        val o = JSONObject(file.readText())
        val d = DesktopSettings()
        DesktopSettings(
            tmdbApiKey = o.optString("tmdbApiKey", d.tmdbApiKey),
            omdbApiKey = o.optString("omdbApiKey", d.omdbApiKey),
            offlineScrape = o.optBoolean("offlineScrape", d.offlineScrape),
            readLocalMeta = o.optBoolean("readLocalMeta", d.readLocalMeta),
            language = o.optString("language", d.language),
            uiLanguage = o.optString("uiLanguage", d.uiLanguage),
            librarySort = o.optString("librarySort", d.librarySort),
            showAdult = o.optBoolean("showAdult", d.showAdult),
            posterWidth = o.optDouble("posterWidth", d.posterWidth.toDouble()).toFloat().coerceIn(120f, 280f),
            sidebarCollapsed = o.optBoolean("sidebarCollapsed", d.sidebarCollapsed),
            theme = enumOr(o.optString("theme"), d.theme),
            subtitleScale = o.optDouble("subtitleScale", d.subtitleScale.toDouble()).toFloat().coerceIn(0.6f, 2.0f),
            subtitleRaise = o.optDouble("subtitleRaise", d.subtitleRaise.toDouble()).toFloat().coerceIn(0f, 0.35f),
            subtitleStyle = enumOr(o.optString("subtitleStyle"), d.subtitleStyle),
            subtitleOutline = o.optInt("subtitleOutline", d.subtitleOutline).coerceIn(1, 4),
            subtitleFont = enumOr(o.optString("subtitleFont"), d.subtitleFont),
            subtitleColor = enumOr(o.optString("subtitleColor"), d.subtitleColor),
            subtitleKeepStyles = o.optBoolean("subtitleKeepStyles", d.subtitleKeepStyles),
            volume = o.optDouble("volume", d.volume.toDouble()).toFloat().coerceIn(0f, 150f),
            muted = o.optBoolean("muted", d.muted),
            hardwareDecoding = o.optBoolean("hardwareDecoding", d.hardwareDecoding),
            audioDevice = o.optString("audioDevice", d.audioDevice).ifBlank { "auto" },
            seekStep = o.optInt("seekStep", d.seekStep).coerceIn(2, 60),
            autoPlayNext = o.optBoolean("autoPlayNext", d.autoPlayNext),
            framePosters = o.optBoolean("framePosters", d.framePosters),
            windowWidth = o.optInt("windowWidth", d.windowWidth),
            windowHeight = o.optInt("windowHeight", d.windowHeight),
            windowX = o.optInt("windowX", d.windowX),
            windowY = o.optInt("windowY", d.windowY),
            windowMaximized = o.optBoolean("windowMaximized", d.windowMaximized),
            syncEnabled = o.optBoolean("syncEnabled", d.syncEnabled),
            syncLocation = o.optString("syncLocation", d.syncLocation),
            syncProgress = o.optBoolean("syncProgress", d.syncProgress),
            syncCorrections = o.optBoolean("syncCorrections", d.syncCorrections),
            syncLibrary = o.optBoolean("syncLibrary", d.syncLibrary),
            deviceName = o.optString("deviceName", d.deviceName),
            deviceId = o.optString("deviceId", d.deviceId),
        )
    }.getOrDefault(DesktopSettings())

    private fun persist(s: DesktopSettings) {
        val o = JSONObject()
            .put("tmdbApiKey", s.tmdbApiKey)
            .put("omdbApiKey", s.omdbApiKey)
            .put("offlineScrape", s.offlineScrape)
            .put("readLocalMeta", s.readLocalMeta)
            .put("language", s.language)
            .put("uiLanguage", s.uiLanguage)
            .put("librarySort", s.librarySort)
            .put("showAdult", s.showAdult)
            .put("posterWidth", s.posterWidth.toDouble())
            .put("sidebarCollapsed", s.sidebarCollapsed)
            .put("theme", s.theme.name)
            .put("subtitleScale", s.subtitleScale.toDouble())
            .put("subtitleRaise", s.subtitleRaise.toDouble())
            .put("subtitleStyle", s.subtitleStyle.name)
            .put("subtitleOutline", s.subtitleOutline)
            .put("subtitleFont", s.subtitleFont.name)
            .put("subtitleColor", s.subtitleColor.name)
            .put("subtitleKeepStyles", s.subtitleKeepStyles)
            .put("volume", s.volume.toDouble())
            .put("muted", s.muted)
            .put("hardwareDecoding", s.hardwareDecoding)
            .put("audioDevice", s.audioDevice)
            .put("seekStep", s.seekStep)
            .put("autoPlayNext", s.autoPlayNext)
            .put("framePosters", s.framePosters)
            .put("windowWidth", s.windowWidth)
            .put("windowHeight", s.windowHeight)
            .put("windowX", s.windowX)
            .put("windowY", s.windowY)
            .put("windowMaximized", s.windowMaximized)
            .put("syncEnabled", s.syncEnabled)
            .put("syncLocation", s.syncLocation)
            .put("syncProgress", s.syncProgress)
            .put("syncCorrections", s.syncCorrections)
            .put("syncLibrary", s.syncLibrary)
            .put("deviceName", s.deviceName)
            .put("deviceId", s.deviceId)
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString(2))
            if (!tmp.renameTo(file)) { file.writeText(o.toString(2)); tmp.delete() }
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
