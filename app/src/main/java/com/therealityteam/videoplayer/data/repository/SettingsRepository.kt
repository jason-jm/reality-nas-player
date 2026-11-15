package com.therealityteam.videoplayer.data.repository

import com.therealityteam.videoplayer.data.playback.SubtitleColor
import com.therealityteam.videoplayer.data.playback.SubtitleFont
import com.therealityteam.videoplayer.data.playback.SubtitleStyle
import android.content.Context
import com.therealityteam.videoplayer.data.scrape.OmdbConfig
import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * The languages Aura speaks (its strings), as the per-app language takes them: PICO OS's own languages but Arabic.
 * Settings › 界面语言 offers them; "" there is the headset's language.
 */
val APP_LANGUAGES = listOf(
    "en", "zh-CN", "zh-TW", "ja", "ko", "de", "fr", "es", "it", "pt-BR", "ru",
    "nl", "pl", "sv", "da", "nb", "fi", "cs", "el", "ro", "tr", "th", "ms",
)

/**
 * The metadata languages Settings offers, each an IETF tag; "" is "the app's language" (the per-app one, else the
 * headset's), the default — a choice of its own, kept as itself, so it still follows when that language changes.
 */
val METADATA_LANGUAGES = listOf(
    "", "en-US", "zh-CN", "zh-TW", "ja-JP", "ko-KR", "de-DE", "fr-FR", "es-ES", "it-IT", "pt-BR", "ru-RU",
    "nl-NL", "pl-PL", "sv-SE", "da-DK", "nb-NO", "fi-FI", "cs-CZ", "el-GR", "ro-RO", "tr-TR", "th-TH", "ms-MY",
)

/**
 * The tag metadata is actually fetched in: the chosen one, or the headset's own for "".
 *
 * "" used to go straight through to [TmdbConfig.language], so picking 跟随系统 sent requests with
 * an empty `language=` — TMDB answered in its default English — and made [TmdbConfig.isChinese]
 * false, until a restart quietly turned the choice into the headset's tag for good.
 */
fun resolvedMetadataLanguage(tag: String, device: java.util.Locale = java.util.Locale.getDefault()): String =
    tag.ifBlank { device.toLanguageTag() }

/** User settings: scraping key, privacy (offline), language. */
data class AppSettings(
    val tmdbApiKey: String = "",
    /** OMDb key (omdbapi.com) — enables IMDb / 烂番茄 / Metacritic ratings for movies. */
    val omdbApiKey: String = "",
    val offlineScrape: Boolean = false,
    /** Read the nfo and pictures beside a file (本地 NFO 与海报). See [TmdbConfig.readLocal]. */
    val readLocalMeta: Boolean = true,
    /** Metadata language: "" follows the headset; see [METADATA_LANGUAGES], [resolvedMetadataLanguage]. */
    val language: String = "",
    /** Network playback bridge: false = loopback HTTP proxy, true = native fd (experimental). */
    val useFdBridge: Boolean = false,
    /**
     * Playback engine: true = Media3/ExoPlayer via VideoComponent (tracks, subtitles, broad
     * format support); false = the SDK's CypressMediaPlayer, kept as a fallback switch.
     */
    val useExoEngine: Boolean = true,
    /** Subtitle text size multiplier (0.6–2.0), adjusted from the player's subtitle menu. */
    val subtitleScale: Float = 1.0f,
    /** How far to raise subtitles off the bottom, as a fraction of panel height (0–0.35). */
    val subtitleRaise: Float = 0.0f,
    /**
     * Let the OS spatializer (PICO 空间音频) process our audio. Off plays the film's mix as
     * authored (SPATIALIZATION_BEHAVIOR_NEVER). Measured on a PICO 4 Ultra: the spatializer path
     * showed intermittent AudioTrack underruns (audible dropouts) on 4K + TrueHD 7.1 while the
     * plain mixer path showed none — so off is the reliable default.
     */
    val spatialAudio: Boolean = false,
    /** 夜间模式: loud scenes evened out and quiet lines lifted (see Loudness); kept, like the subtitle look. */
    val nightMode: Boolean = false,
    /** 自动播放下一集: the next item counts down and starts by itself; off, its card waits to be asked. */
    val autoPlayNext: Boolean = true,
    /** 自动跳过片头: an opening the file's chapters name (OP, Opening, 片头…) is skipped as it is reached. */
    val autoSkipIntro: Boolean = false,
    /** 全景视频自动沉浸: a 360°/180° film opens in 沉浸空间 by itself, round the viewer. */
    val panoAutoImmersive: Boolean = true,
    /** Keep the panorama remote at eye level across films when the viewer raises it. */
    val panoRemoteAtEyeLevel: Boolean = false,
    /** 首选字幕 / 首选音轨: what a file opens with when nothing was picked for it or its show. */
    val subtitleChoice: com.therealityteam.videoplayer.data.playback.SubtitleChoice = com.therealityteam.videoplayer.data.playback.SubtitleChoice.AUTO,
    val audioChoice: com.therealityteam.videoplayer.data.playback.AudioChoice = com.therealityteam.videoplayer.data.playback.AudioChoice.DEFAULT,
    /** Subtitle look: bare text (default), text on a translucent box, or outlined text (描边). */
    val subtitleStyle: SubtitleStyle = SubtitleStyle.NONE,
    /** Outline thickness in dp (1–4) when the look is 描边. */
    val subtitleOutline: Int = 2,
    val subtitleFont: SubtitleFont = SubtitleFont.DEFAULT,
    /** Colour of subtitle text that carries none of its own. */
    val subtitleColor: SubtitleColor = SubtitleColor.WHITE,
    /** Honour the colours an ASS file styles its lines with; off, everything takes [subtitleColor]. */
    val subtitleKeepStyles: Boolean = true,
    /** ASS drawn as its author typeset it (libass) — false: as plain lines in the reader's own look. */
    val subtitleTypeset: Boolean = true,
    /** What the bar's time shows after the position: "total", "left" (−MM:SS) or "ends" (the clock it ends at). */
    val barTime: String = "total",
    /** 接收投屏: found by phones on the network as a DLNA renderer. Off until turned on — it lets them play on the headset. */
    val castReceiver: Boolean = false,
    /** 自动更新媒体库: the sources are scanned for new files as the app opens, if not in [AUTO_SCAN_EVERY_MS]. */
    val autoScan: Boolean = true,
    /** 看完后删除下载: a copy watched to the end leaves the headset by itself (off unless asked for). */
    val deleteWatchedCopies: Boolean = false,
    /** When that scan last ran (epoch ms). */
    val lastAutoScanAt: Long = 0L,
    /** The poster wall's order — a `LibSort` name (RECENT / TITLE / RATING / YEAR / WATCHED). */
    val librarySort: String = "RECENT",
    /**
     * Whether the adult shelf is on show at all.
     *
     * Off unless asked for. A headset is handed to other people more readily than a laptop is, and
     * the shelf exists so that what a database marks as adult is kept off the other shelves — which
     * only works if it is not the first thing somebody scrolls past.
     */
    val showAdult: Boolean = false,
    /** The environment 虚拟环境 opens with: the one chosen last (an id of `Environments`). */
    val lastEnvironment: String = "private_theater",
    /** A non-central chair last chosen in each room. Older settings open on the centre chair. */
    val environmentSeats: Map<String, String> = emptyMap(),
    /** 观影灯光: an environment dims while a film plays in it, as a cinema's lights go down. */
    val dimEnvironment: Boolean = true,
    /** House-light level during automatic dimming, as a percentage of the normal lighting. */
    val viewingLightPercent: Int = 0,
    /** Light from the film on the screen frame and nearby surfaces. */
    val screenGlow: Boolean = true,
    /** 在环境的屏幕上播放: play on the room's own screen. */
    val screenPlay: Boolean = true,
    /** 同步: progress, watched state and favourites kept in step through the NAS. Off until asked for. */
    val syncEnabled: Boolean = false,
    /** Where the `.aura` folder lives — `smb://host/share` — or "" for the share holding most of the library. */
    val syncLocation: String = "",
    /** This headset's id in the sync folder, made once and kept. */
    val deviceId: String = "",
    /** What the other devices call this one; "" is the headset's own name. */
    val deviceName: String = "",
)

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    fun get(): AppSettings
    fun update(value: AppSettings)

    /** The app's language changed (界面语言, or the headset's): a metadata language that follows it follows. */
    fun languageChanged() {}
}

class JsonSettingsRepository(context: Context) : SettingsRepository {
    private val file = File(context.filesDir, "aura/settings.json").apply { parentFile?.mkdirs() }
    private val _settings = MutableStateFlow(load())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        // The sync folder knows each device by an id that must outlive renames; made once, here.
        if (_settings.value.deviceId.isBlank()) {
            _settings.value = _settings.value.copy(deviceId = java.util.UUID.randomUUID().toString())
            persist()
        }
        apply(_settings.value)
    }

    override fun get(): AppSettings = _settings.value

    override fun languageChanged() {
        val before = TmdbConfig.language
        apply(_settings.value)
        if (TmdbConfig.language != before) android.util.Log.i("AuraScan", "metadata language follows the app: $before → ${TmdbConfig.language}")
    }

    override fun update(value: AppSettings) {
        _settings.value = value
        persist()
        apply(value)
    }

    /** Push settings into the scraping layer. Offline mode disables online scraping. */
    private fun apply(s: AppSettings) {
        // Offline is its own switch on TmdbConfig: blanking the user's key here would let the
        // BUILT-IN key take over and keep calling out, which is the opposite of 离线刮削.
        TmdbConfig.offline = s.offlineScrape
        TmdbConfig.readLocal = s.readLocalMeta
        TmdbConfig.apiKey = s.tmdbApiKey.trim()
        TmdbConfig.language = resolvedMetadataLanguage(s.language)
        // OMDb stays user-key-only: its free tier is a per-KEY 1000/day quota, so a built-in key
        // really would be one pool shared by every install — the thing TMDB's per-IP model avoids.
        OmdbConfig.apiKey = if (s.offlineScrape) "" else s.omdbApiKey.trim()
    }

    private fun load(): AppSettings = runCatching {
        // Damaged → set aside and the last whole one read (DurableFile), never defaults saved over it.
        val text = com.therealityteam.videoplayer.util.DurableFile.read(file) { JSONObject(it); true } ?: return AppSettings()
        val o = JSONObject(text)
        AppSettings(
            tmdbApiKey = o.optString("tmdbApiKey"),
            omdbApiKey = o.optString("omdbApiKey"),
            offlineScrape = o.optBoolean("offlineScrape", false),
            readLocalMeta = o.optBoolean("readLocalMeta", true),
            language = o.optString("language").let { stored ->
                // Before 跟随系统 was kept as itself it was saved as the headset's own tag; a tag the
                // list does not offer that is exactly the headset's can only have meant that.
                if (stored !in METADATA_LANGUAGES && stored == java.util.Locale.getDefault().toLanguageTag()) "" else stored
            },
            useFdBridge = o.optBoolean("useFdBridge", false),
            useExoEngine = o.optBoolean("useExoEngine", true),
            subtitleScale = o.optDouble("subtitleScale", 1.0).toFloat(),
            subtitleRaise = o.optDouble("subtitleRaise", 0.0).toFloat(),
            spatialAudio = o.optBoolean("spatialAudio", false),
            nightMode = o.optBoolean("nightMode", false),
            autoPlayNext = o.optBoolean("autoPlayNext", true),
            autoSkipIntro = o.optBoolean("autoSkipIntro", false),
            panoAutoImmersive = o.optBoolean("panoAutoImmersive", true),
            panoRemoteAtEyeLevel = o.optBoolean("panoRemoteAtEyeLevel", false),
            subtitleChoice = com.therealityteam.videoplayer.data.playback.SubtitleChoice.entries.firstOrNull { it.name == o.optString("subtitleChoice") }
                ?: com.therealityteam.videoplayer.data.playback.SubtitleChoice.AUTO,
            audioChoice = com.therealityteam.videoplayer.data.playback.AudioChoice.entries.firstOrNull { it.name == o.optString("audioChoice") }
                ?: com.therealityteam.videoplayer.data.playback.AudioChoice.DEFAULT,
            subtitleStyle = SubtitleStyle.entries.firstOrNull { it.name == o.optString("subtitleStyle") } ?: SubtitleStyle.NONE,
            subtitleOutline = o.optInt("subtitleOutline", 2).coerceIn(1, 4),
            subtitleFont = SubtitleFont.entries.firstOrNull { it.name == o.optString("subtitleFont") } ?: SubtitleFont.DEFAULT,
            subtitleColor = SubtitleColor.entries.firstOrNull { it.name == o.optString("subtitleColor") } ?: SubtitleColor.WHITE,
            subtitleKeepStyles = o.optBoolean("subtitleKeepStyles", true),
            subtitleTypeset = o.optBoolean("subtitleTypeset", true),
            barTime = o.optString("barTime", "total").ifBlank { "total" },
            castReceiver = o.optBoolean("castReceiver", false),
            autoScan = o.optBoolean("autoScan", true),
            deleteWatchedCopies = o.optBoolean("deleteWatchedCopies", false),
            lastAutoScanAt = o.optLong("lastAutoScanAt", 0L),
            librarySort = o.optString("librarySort", "RECENT"),
            showAdult = o.optBoolean("showAdult", false),
            lastEnvironment = o.optString("lastEnvironment", "private_theater").ifBlank { "private_theater" },
            environmentSeats = o.optJSONObject("environmentSeats")?.let { seats ->
                seats.keys().asSequence().associateWith { seats.optString(it) }
            }.orEmpty(),
            dimEnvironment = o.optBoolean("dimEnvironment", true),
            viewingLightPercent = o.optInt("viewingLightPercent", 0).coerceIn(0, 100),
            screenGlow = o.optBoolean("screenGlow", true),
            screenPlay = o.optBoolean("screenPlay", true),
            syncEnabled = o.optBoolean("syncEnabled", false),
            syncLocation = o.optString("syncLocation", ""),
            deviceId = o.optString("deviceId", ""),
            deviceName = o.optString("deviceName", ""),
        )
    }.getOrDefault(AppSettings())

    private fun persist() {
        val o = JSONObject()
            .put("tmdbApiKey", _settings.value.tmdbApiKey)
            .put("omdbApiKey", _settings.value.omdbApiKey)
            .put("offlineScrape", _settings.value.offlineScrape)
            .put("readLocalMeta", _settings.value.readLocalMeta)
            .put("language", _settings.value.language)
            .put("useFdBridge", _settings.value.useFdBridge)
            .put("useExoEngine", _settings.value.useExoEngine)
            .put("subtitleScale", _settings.value.subtitleScale.toDouble())
            .put("subtitleRaise", _settings.value.subtitleRaise.toDouble())
            .put("spatialAudio", _settings.value.spatialAudio)
            .put("nightMode", _settings.value.nightMode)
            .put("autoPlayNext", _settings.value.autoPlayNext)
            .put("autoSkipIntro", _settings.value.autoSkipIntro)
            .put("panoAutoImmersive", _settings.value.panoAutoImmersive)
            .put("panoRemoteAtEyeLevel", _settings.value.panoRemoteAtEyeLevel)
            .put("subtitleChoice", _settings.value.subtitleChoice.name)
            .put("audioChoice", _settings.value.audioChoice.name)
            .put("subtitleStyle", _settings.value.subtitleStyle.name)
            .put("subtitleOutline", _settings.value.subtitleOutline)
            .put("subtitleFont", _settings.value.subtitleFont.name)
            .put("subtitleColor", _settings.value.subtitleColor.name)
            .put("subtitleKeepStyles", _settings.value.subtitleKeepStyles)
            .put("subtitleTypeset", _settings.value.subtitleTypeset)
            .put("barTime", _settings.value.barTime)
            .put("castReceiver", _settings.value.castReceiver)
            .put("autoScan", _settings.value.autoScan)
            .put("deleteWatchedCopies", _settings.value.deleteWatchedCopies)
            .put("lastAutoScanAt", _settings.value.lastAutoScanAt)
            .put("librarySort", _settings.value.librarySort)
            .put("showAdult", _settings.value.showAdult)
            .put("lastEnvironment", _settings.value.lastEnvironment)
            .put("environmentSeats", JSONObject(_settings.value.environmentSeats))
            .put("dimEnvironment", _settings.value.dimEnvironment)
            .put("viewingLightPercent", _settings.value.viewingLightPercent)
            .put("screenGlow", _settings.value.screenGlow)
            .put("screenPlay", _settings.value.screenPlay)
            .put("syncEnabled", _settings.value.syncEnabled)
            .put("syncLocation", _settings.value.syncLocation)
            .put("deviceId", _settings.value.deviceId)
            .put("deviceName", _settings.value.deviceName)
        com.therealityteam.videoplayer.util.DurableFile.write(file, o.toString())
    }
}

/** How long the library goes before an opening of the app looks for new files again: 6 hours. */
const val AUTO_SCAN_EVERY_MS = 6 * 60 * 60 * 1000L
