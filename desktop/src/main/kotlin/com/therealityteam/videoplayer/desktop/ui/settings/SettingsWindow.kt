package com.therealityteam.videoplayer.desktop.ui.settings

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Library
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MonitorPlay
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.ScanSearch
import com.composables.icons.lucide.ShieldCheck
import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import com.therealityteam.videoplayer.desktop.data.ThemeChoice
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.platform.CrashLog
import com.therealityteam.videoplayer.desktop.platform.Desktop
import com.therealityteam.videoplayer.desktop.platform.Host
import com.therealityteam.videoplayer.desktop.platform.LogFile
import com.therealityteam.videoplayer.desktop.ui.AppState
import com.therealityteam.videoplayer.desktop.ui.components.AuraButton
import com.therealityteam.videoplayer.desktop.ui.components.AuraSwitch
import com.therealityteam.videoplayer.desktop.ui.components.AuraTextField
import com.therealityteam.videoplayer.desktop.ui.components.ButtonKind
import com.therealityteam.videoplayer.desktop.ui.components.Divider
import com.therealityteam.videoplayer.desktop.ui.components.DropdownMenu
import com.therealityteam.videoplayer.desktop.ui.components.Icon
import com.therealityteam.videoplayer.desktop.ui.components.Segmented
import com.therealityteam.videoplayer.desktop.ui.components.SettingRow
import com.therealityteam.videoplayer.desktop.ui.components.Txt
import com.therealityteam.videoplayer.desktop.ui.components.clickCursor
import com.therealityteam.videoplayer.desktop.ui.components.interactionWash
import com.therealityteam.videoplayer.desktop.ui.components.menu
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import com.therealityteam.videoplayer.desktop.ui.theme.AuraShapes
import kotlinx.coroutines.launch
import java.util.Locale

private enum class SettingsPage(val titleKey: String, val icon: ImageVector) {
    GENERAL("d_set_general", Lucide.Palette),
    SCRAPING("set_nav_scrape", Lucide.ScanSearch),
    PLAYBACK("set_nav_play", Lucide.MonitorPlay),
    LIBRARY("set_nav_library", Lucide.Library),
    SYNC("d_set_sync", Lucide.RefreshCw),
    PRIVACY("set_nav_privacy", Lucide.ShieldCheck),
    ABOUT("set_nav_about", Lucide.Info),
}

/** The metadata languages offered, each named in itself; "" follows the system. */
private val METADATA_LANGUAGES = listOf(
    "", "en-US", "zh-CN", "zh-TW", "ja-JP", "ko-KR", "de-DE", "fr-FR", "es-ES", "it-IT", "pt-BR", "ru-RU",
    "nl-NL", "pl-PL", "sv-SE", "da-DK", "nb-NO", "fi-FI", "cs-CZ", "el-GR", "ro-RO", "tr-TR", "th-TH", "ms-MY",
)

/** The languages the desktop speaks — the headset's (its strings are the headset's, i18n/Strings.kt). */
private val APP_LANGUAGES = listOf(
    "", "en", "zh-CN", "zh-TW", "ja", "ko", "de", "fr", "es", "it", "pt-BR", "ru",
    "nl", "pl", "sv", "da", "nb", "fi", "cs", "el", "ro", "tr", "th", "ms",
)

/** Settings, in a window of its own as a desktop app has them (⌘, / Ctrl+,). */
@Composable
fun SettingsWindow(state: AppState, onClose: () -> Unit) {
    val s = LocalStrings.current
    val windowState = rememberWindowState(size = DpSize(820.dp, 600.dp))
    Window(onCloseRequest = onClose, state = windowState, title = s["set_title"], resizable = true) {
        CompositionLocalProvider(LocalSettingsWindow provides window) { SettingsContent(state) }
    }
}

/** The settings window, for the dialogs its pages open (a folder chooser) to sit on. */
internal val LocalSettingsWindow = staticCompositionLocalOf<java.awt.Frame?> { null }

/** The settings themselves: pages on the left, the page on the right. [initial] is a page's name. */
@Composable
fun SettingsContent(state: AppState, initial: String? = null) {
    val s = LocalStrings.current
    val c = Aura.colors
    var page by remember { mutableStateOf(SettingsPage.entries.firstOrNull { it.name == initial } ?: SettingsPage.GENERAL) }
    Row(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.width(210.dp).fillMaxHeight().background(c.sidebar).padding(horizontal = 10.dp, vertical = 16.dp)) {
            SettingsPage.entries.forEach { p ->
                val source = remember(p) { MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().height(32.dp).clip(AuraShapes.control)
                        .background(interactionWash(source, page == p))
                        .clickable(interactionSource = source, indication = null) { page = p }.clickCursor()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(p.icon, if (page == p) c.accent else c.textSecondary, 16.dp)
                    Spacer(Modifier.width(10.dp))
                    Txt(s[p.titleKey], Aura.type.callout, c.text)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 32.dp, vertical = 24.dp).widthIn(max = 640.dp)) {
                Txt(s[page.titleKey], Aura.type.title1, c.text)
                Spacer(Modifier.height(16.dp))
                when (page) {
                    SettingsPage.GENERAL -> General(state)
                    SettingsPage.SCRAPING -> Scraping(state)
                    SettingsPage.PLAYBACK -> Playback(state)
                    SettingsPage.LIBRARY -> LibraryPage(state)
                    SettingsPage.SYNC -> SyncPage(state)
                    SettingsPage.PRIVACY -> Privacy()
                    SettingsPage.ABOUT -> About()
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
internal fun Group(title: String?, content: @Composable () -> Unit) {
    val c = Aura.colors
    if (title != null) Txt(title, Aura.type.micro, c.textTertiary, Modifier.padding(bottom = 6.dp, top = 4.dp))
    Column(Modifier.fillMaxWidth().clip(AuraShapes.card).background(c.surface).border(1.dp, c.stroke, AuraShapes.card).padding(horizontal = 16.dp, vertical = 4.dp)) {
        content()
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun General(state: AppState) {
    val s = LocalStrings.current
    val st by state.graph.settings.settings.collectAsState()
    Group(null) {
        SettingRow(s["d_appearance"]) {
            Segmented(
                listOf(ThemeChoice.SYSTEM to s["d_theme_system"], ThemeChoice.DARK to s["d_theme_dark"], ThemeChoice.LIGHT to s["d_theme_light"]),
                st.theme, { v -> state.graph.settings.update { it.copy(theme = v) } },
            )
        }
        Divider()
        SettingRow(s["d_app_language"], s["d_app_language_hint"]) {
            LanguagePicker(st.uiLanguage, APP_LANGUAGES, s["set_language_system"]) { v -> state.graph.settings.update { it.copy(uiLanguage = v) } }
        }
    }
    Group(s["set_nav_library"]) {
        SettingRow(s["settings_show_adult"], s["settings_show_adult_hint"]) {
            AuraSwitch(st.showAdult, { v -> state.graph.settings.update { it.copy(showAdult = v) } })
        }
    }
}

@Composable
private fun Scraping(state: AppState) {
    val s = LocalStrings.current
    val c = Aura.colors
    val st by state.graph.settings.settings.collectAsState()
    Txt(s["set_scrape_sub"], Aura.type.callout, c.textSecondary)
    Spacer(Modifier.height(16.dp))
    Group(null) {
        SettingRow(s["set_offline"], s["set_offline_hint"]) {
            AuraSwitch(st.offlineScrape, { v -> state.graph.settings.update { it.copy(offlineScrape = v) } })
        }
        Divider()
        SettingRow(s["set_local_meta"], s["set_local_meta_hint"]) {
            AuraSwitch(st.readLocalMeta, { v -> state.graph.settings.update { it.copy(readLocalMeta = v) } })
        }
        Divider()
        SettingRow(s["set_language"], s["set_language_hint"]) {
            LanguagePicker(st.language, METADATA_LANGUAGES, s["set_language_system"]) { v -> state.graph.settings.update { it.copy(language = v) } }
        }
    }
    Group(s["set_sources_group"]) {
        val keyHint = if (TmdbConfig.hasBuiltIn) "set_tmdb_hint" else "set_tmdb_hint2"
        val placeholder = if (TmdbConfig.hasBuiltIn) "set_tmdb_placeholder" else "set_tmdb_where"
        KeyField("TMDB API Key", st.tmdbApiKey, s[placeholder], s[keyHint]) { v ->
            state.graph.settings.update { it.copy(tmdbApiKey = v) }
        }
        Divider()
        KeyField("OMDb API Key", st.omdbApiKey, "", s["set_omdb_hint"]) { v -> state.graph.settings.update { it.copy(omdbApiKey = v) } }
    }
    Txt(s["set_anime_note"], Aura.type.caption, c.textTertiary, maxLines = 3)
}

@Composable
private fun KeyField(label: String, value: String, placeholder: String, hint: String, onChange: (String) -> Unit) {
    val c = Aura.colors
    Column(Modifier.padding(vertical = 10.dp)) {
        Txt(label, Aura.type.bodyStrong, c.text)
        Spacer(Modifier.height(6.dp))
        AuraTextField(value, onChange, Modifier.fillMaxWidth(), placeholder = placeholder, password = value.isNotEmpty())
        Spacer(Modifier.height(4.dp))
        Txt(hint, Aura.type.caption, c.textTertiary, maxLines = 3)
    }
}

@Composable
private fun Playback(state: AppState) {
    val s = LocalStrings.current
    val st by state.graph.settings.settings.collectAsState()
    Group(null) {
        SettingRow(s["d_hwdec"], s["d_hwdec_hint"]) {
            AuraSwitch(st.hardwareDecoding, { v -> state.graph.settings.update { it.copy(hardwareDecoding = v) } })
        }
        Divider()
        SettingRow(s["d_seek_step"], s["d_seek_step_hint"]) {
            Segmented(listOf(5, 10, 15, 30).map { it to "$it s" }, st.seekStep, { v -> state.graph.settings.update { it.copy(seekStep = v) } })
        }
        Divider()
        SettingRow(s["d_autoplay_next"], s["d_autoplay_next_hint"]) {
            AuraSwitch(st.autoPlayNext, { v -> state.graph.settings.update { it.copy(autoPlayNext = v) } })
        }
    }
    Group(s["d_shortcuts"]) {
        listOf(
            "Space / K" to s["d_key_play"],
            "← → / J L" to s["d_key_seek", st.seekStep],
            "⇧ ← →" to s["d_key_seek", 60],
            "↑ ↓" to s["d_key_volume"],
            "M" to s["d_mute"],
            "F" to s["d_fullscreen"],
            "N / P" to s["d_key_next_prev"],
            "Page Up / Down" to s["d_key_chapters"],
            "[ ]  ⌫" to s["d_key_speed"],
            "S / A" to s["d_key_tracks"],
            "Z / X" to s["d_key_sub_delay"],
            "B" to s["pc_ab_repeat"],
            "Esc" to s["d_key_escape"],
        ).forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Txt(k, Aura.type.callout, Aura.colors.textSecondary, Modifier.width(150.dp))
                Txt(v, Aura.type.callout, Aura.colors.text)
            }
        }
    }
}

@Composable
private fun LibraryPage(state: AppState) {
    val s = LocalStrings.current
    val c = Aura.colors
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    val st by state.graph.settings.settings.collectAsState()
    Group(null) {
        SettingRow(s["d_frame_posters"], s["d_frame_posters_hint"]) {
            AuraSwitch(st.framePosters, { v ->
                state.graph.settings.update { it.copy(framePosters = v) }
                if (v) state.scope.launch { state.graph.framePosters.fill() }
            })
        }
    }
    Group(s["set_rebuild"]) {
        SettingRow(s["d_rescrape"], s["d_rescrape_hint"]) {
            AuraButton(s["d_rescrape"], { state.graph.library.rescrape() })
        }
    }
    Group(s["set_danger"]) {
        if (!confirm) {
            SettingRow(s["set_clear_library"], s["set_clear_hint"]) {
                AuraButton(s["set_clear"], { confirm = true }, kind = ButtonKind.Danger)
            }
        } else {
            Column(Modifier.padding(vertical = 10.dp)) {
                Txt(s["set_clear_q"], Aura.type.bodyStrong, c.text)
                Spacer(Modifier.height(4.dp))
                Txt(s["set_clear_body"], Aura.type.caption, c.textSecondary, maxLines = 4)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuraButton(s["set_clear"], {
                        confirm = false
                        scope.launch { state.graph.scrape.clearLibrary() }
                    }, kind = ButtonKind.Danger)
                    AuraButton(s["cancel"], { confirm = false })
                }
            }
        }
    }
    Txt(s["set_clear_note"], Aura.type.caption, c.textTertiary, maxLines = 4)
}

@Composable
private fun Privacy() {
    val s = LocalStrings.current
    val c = Aura.colors
    Txt(s["set_privacy_sub"], Aura.type.callout, c.textSecondary)
    Spacer(Modifier.height(16.dp))
    Group(s["set_no_collect"]) { Para(s["set_no_collect_body"]) }
    Group(s["set_when_network"]) {
        Para(s["set_scrape_metadata"], s["set_scrape_metadata_body"])
        Divider()
        Para(null, s["set_offline_note"])
    }
    Group(s["set_stored_local"]) {
        Para(s["set_credentials"], s["set_credentials_body"])
        Divider()
        Para(s["set_library_cache"], s["set_library_cache_body"])
    }
}

@Composable
internal fun Para(title: String?, body: String? = null) {
    Column(Modifier.padding(vertical = 10.dp)) {
        if (title != null) Txt(title, Aura.type.bodyStrong, Aura.colors.text, maxLines = 3)
        if (body != null) {
            if (title != null) Spacer(Modifier.height(3.dp))
            Txt(body, Aura.type.callout, Aura.colors.textSecondary, maxLines = 12)
        }
    }
}

@Composable
private fun About() {
    val s = LocalStrings.current
    val c = Aura.colors
    Txt(s["set_about_line"], Aura.type.title2, c.text)
    Spacer(Modifier.height(4.dp))
    Txt("${s["set_version"]} ${Host.appVersion}", Aura.type.callout, c.textSecondary)
    Spacer(Modifier.height(14.dp))
    Txt(s["set_about_body"], Aura.type.body, c.textSecondary, maxLines = 8)
    Spacer(Modifier.height(20.dp))
    Group(s["set_data_sources"]) {
        Para(null, s["set_tmdb_notice"])
        Divider()
        Para(s["set_anime_dbs"], s["set_anime_dbs_body"])
    }
    Group(s["d_open_source_parts"]) {
        Para(null, s["d_mpv_notice"])
    }
    Group(s["set_diagnostics"]) {
        SettingRow(s["d_data_folder"], Host.dataDir.absolutePath) {
            AuraButton(s["d_show"], { Desktop.reveal(Host.dataDir.absolutePath) })
        }
        Divider()
        SettingRow(s["d_show_logs"], LogFile.file.absolutePath) {
            AuraButton(s["d_show"], { Desktop.reveal(LogFile.file.absolutePath) })
        }
        // Only when there has been one: when, and the file to attach to a report.
        val crash = remember { CrashLog.latest() }
        if (crash != null) {
            Divider()
            val at = java.time.Instant.ofEpochMilli(crash.lastModified()).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM))
            SettingRow(s["set_last_crash"], at + "\n" + s["set_crash_where", CrashLog.dir.absolutePath]) {
                AuraButton(s["d_show"], { Desktop.reveal(crash.absolutePath) })
            }
        }
    }
}

@Composable
private fun LanguagePicker(value: String, options: List<String>, systemLabel: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun name(tag: String) = if (tag.isBlank()) systemLabel else languageName(tag)
    Box {
        AuraButton(name(value), { open = true })
        DropdownMenu(open, { open = false }, alignEnd = true, entries = menu {
            options.forEach { tag -> check(name(tag), tag == value) { onPick(tag) } }
        })
    }
}

/** A language named in itself, as pickers name them: 简体中文, 繁體中文, Deutsch, Português (Brasil)… */
private fun languageName(tag: String): String {
    val l = Locale.forLanguageTag(tag)
    if (l.language == "zh") {
        val hant = l.script.equals("Hant", true) || l.country.uppercase() in setOf("TW", "HK", "MO")
        return if (hant) "繁體中文" else "简体中文"
    }
    val name = if (l.language == "pt" && l.country == "BR") l.getDisplayName(l) else l.getDisplayLanguage(l)
    return name.replaceFirstChar { it.titlecase(l) }
}
