package com.therealityteam.videoplayer.ui.settings

import android.icu.text.RelativeDateTimeFormatter
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.LinearProgressIndicator
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.AlertDialog
import com.pico.spatial.ui.design.windows.LocalSnackbarHostState
import com.therealityteam.videoplayer.data.playback.SubtitleChoice
import com.therealityteam.videoplayer.data.playback.TrackPreference
import com.therealityteam.videoplayer.data.playback.AudioChoice
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.repository.AppSettings
import com.therealityteam.videoplayer.data.repository.APP_LANGUAGES
import com.therealityteam.videoplayer.data.repository.METADATA_LANGUAGES
import com.therealityteam.videoplayer.data.repository.SettingsRepository
import com.therealityteam.videoplayer.data.scrape.ScrapeEngine
import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import com.therealityteam.videoplayer.data.source.SourceFault
import com.therealityteam.videoplayer.data.source.faultOf
import com.therealityteam.videoplayer.data.sync.HeadsetSync
import com.therealityteam.videoplayer.data.sync.LibrarySync
import com.therealityteam.videoplayer.di.AURA_SESSION_SCOPE_ID
import com.therealityteam.videoplayer.player.Environments
import com.therealityteam.videoplayer.ui.common.ChoiceSheet
import com.therealityteam.videoplayer.ui.common.DangerRow
import com.therealityteam.videoplayer.ui.common.DetailActionRow
import com.therealityteam.videoplayer.ui.common.DetailNote
import com.therealityteam.videoplayer.ui.common.DetailPane
import com.therealityteam.videoplayer.ui.common.DetailRow
import com.therealityteam.videoplayer.ui.common.DetailSwitchRow
import com.therealityteam.videoplayer.ui.common.GroupLabel
import com.therealityteam.videoplayer.ui.common.LabelledField
import com.therealityteam.videoplayer.ui.common.MasterDetailScaffold
import com.therealityteam.videoplayer.ui.common.MasterEntry
import com.therealityteam.videoplayer.ui.common.SheetHook
import com.therealityteam.videoplayer.ui.library.LibraryTaskProgress
import com.therealityteam.videoplayer.ui.library.LibraryViewModel
import com.therealityteam.videoplayer.ui.library.fileSizeText
import com.therealityteam.videoplayer.ui.library.sourceFaultText
import com.therealityteam.videoplayer.ui.theme.dangerButtonColors
import com.therealityteam.videoplayer.ui.theme.secondaryButtonColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource

/** The settings pages, in the order they appear down the master column. */
private enum class SettingsPage(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @StringRes val blurb: Int,
) {
    SCRAPE(R.string.set_nav_scrape, R.drawable.ic_scrape, R.string.set_nav_scrape_hint),
    PLAYBACK(R.string.set_nav_play, R.drawable.ic_line_play, R.string.set_nav_play_hint),
    LIBRARY(R.string.set_nav_library, R.drawable.ic_cat_all, R.string.set_nav_library_hint),
    SYNC(R.string.set_nav_sync, R.drawable.ic_line_sync, R.string.set_nav_sync_hint),
    PRIVACY(R.string.set_nav_privacy, R.drawable.ic_privacy, R.string.set_nav_privacy_hint),
    ABOUT(R.string.set_nav_about, R.drawable.ic_line_info, R.string.set_nav_about_hint),
}

private fun pageNamed(name: String?): SettingsPage? =
    SettingsPage.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

/**
 * 设置 — a master–detail page: the settings groups on the left, one group's controls on the right.
 *
 * The previous version stacked every control in the app into one column, so an API key, an
 * experimental streaming toggle and a destructive 清空媒体库 button all carried the same visual
 * weight, and the explanation for each was a long sentence set in body text. Grouping them means
 * each page can afford a proper heading, per-control descriptions, and — for the destructive ones —
 * a real confirmation dialog instead of an inline "确认清空?" row.
 *
 * There is no 保存 button any more: edits are written through as they are made, which is what lets
 * the user move between groups without losing half-entered keys. Each row of the master column
 * says what its page is set to now (离线 · 不联网, 已安装 Qwen2.5 3B, 已开启 · 5 分钟前), so the
 * state of the app reads down the column without opening anything.
 *
 * [onOpenSources] is the way to 媒体来源 from 同步 when there is no share to sync to yet; without it
 * that row is simply not offered.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    initialPage: String? = null,
    onOpenSources: (() -> Unit)? = null,
    onOpenDownloads: (() -> Unit)? = null,
) {
    val repo = remember { GlobalContext.get().get<SettingsRepository>() }
    var draft by remember { mutableStateOf(repo.get()) }

    fun edit(block: AppSettings.() -> AppSettings) {
        draft = draft.block().also { repo.update(it) }
    }

    val vm = remember { GlobalContext.get().getScope(AURA_SESSION_SCOPE_ID).get<LibraryViewModel>() }
    val sync = remember { GlobalContext.get().get<HeadsetSync>() }
    val syncStatus by sync.status.collectAsState()
    val version = appVersion()

    var page by rememberSaveable { mutableStateOf(pageNamed(initialPage) ?: SettingsPage.SCRAPE) }
    // A deep link to another page while the screen is already open moves to it.
    LaunchedEffect(initialPage) { pageNamed(initialPage)?.let { page = it } }

    MasterDetailScaffold(
        title = stringResource(R.string.set_title),
        onBack = onBack,
        master = {
            // No section heading: the window's own title already names this list. ("Aura" did not.)
            Spacer(Modifier.height(16.dp))
            SettingsPage.entries.forEach { p ->
                MasterEntry(
                    label = stringResource(p.label),
                    supporting = liveLine(p, draft, syncStatus, version),
                    icon = p.icon,
                    selected = p == page,
                    onClick = { page = p },
                )
            }
        },
        detail = {
            when (page) {
                SettingsPage.SCRAPE -> ScrapePage(draft, ::edit)
                SettingsPage.PLAYBACK -> PlaybackPage(draft, ::edit)
                SettingsPage.LIBRARY -> LibraryPage(vm, onOpenDownloads)
                SettingsPage.SYNC -> SyncPage(draft, ::edit, sync, syncStatus, onOpenSources)
                SettingsPage.PRIVACY -> PrivacyPage(onOpenScrape = { page = SettingsPage.SCRAPE })
                SettingsPage.ABOUT -> AboutPage(version)
            }
        },
    )
}

/** What a page is set to now, for its row in the master column — or what it is for, when nothing changes. */
@Composable
private fun liveLine(
    page: SettingsPage,
    s: AppSettings,
    sync: HeadsetSync.Status,
    version: String,
): String = when (page) {
    SettingsPage.SCRAPE ->
        if (s.offlineScrape) stringResource(R.string.set_live_offline) else MetadataLanguage.nameOf(s.language)
    SettingsPage.PLAYBACK -> {
        // What the reader chose (字幕 简体中文 · 夜间模式), ahead of the engine, which nobody changes;
        // two at most, in the order the page lists them.
        val chosen = listOfNotNull(
            when (s.subtitleChoice) {
                SubtitleChoice.AUTO -> null
                SubtitleChoice.OFF -> stringResource(R.string.set_pref_off)
                else -> stringResource(R.string.set_live_sub, subtitleChoiceName(s.subtitleChoice))
            },
            if (s.audioChoice != AudioChoice.DEFAULT) stringResource(R.string.set_live_audio, audioChoiceName(s.audioChoice)) else null,
            if (s.nightMode) stringResource(R.string.set_live_night) else null,
            if (!s.autoPlayNext) stringResource(R.string.set_live_no_autoplay) else null,
            if (s.autoSkipIntro) stringResource(R.string.set_live_auto_skip_intro) else null,
        )
        if (chosen.isNotEmpty()) {
            chosen.take(2).joinToString(" · ")
        } else {
            val engine = stringResource(if (s.useExoEngine) R.string.set_live_engine_exo else R.string.set_live_engine_system)
            if (s.spatialAudio) stringResource(R.string.set_live_spatial, engine) else engine
        }
    }
    SettingsPage.SYNC -> {
        val last = sync.last
        when {
            !s.syncEnabled -> stringResource(R.string.set_live_sync_off)
            sync.syncing -> stringResource(R.string.sync_syncing)
            last != null && last.error != null -> stringResource(R.string.set_live_sync_failed)
            last != null -> stringResource(R.string.set_live_sync_on_ago, syncAgo(last.at))
            else -> stringResource(R.string.set_live_sync_on)
        }
    }
    SettingsPage.ABOUT ->
        if (version.isNotBlank()) stringResource(R.string.set_live_version, version) else stringResource(page.blurb)
    else -> stringResource(page.blurb)
}

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
}

/** The build number (versionCode), which tells two installs of the same version apart. */
@Composable
private fun appBuild(): Long? {
    val context = LocalContext.current
    return remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrNull()
    }
}

@Composable
private fun ScrapePage(draft: AppSettings, edit: ((AppSettings.() -> AppSettings)) -> Unit) {
    var pickLanguage by rememberSaveable { mutableStateOf(false) }
    var pickAppLanguage by rememberSaveable { mutableStateOf(false) }
    val builtInKey = TmdbConfig.hasBuiltIn
    SheetHook("language") { pickLanguage = true }
    SheetHook("app_language") { pickAppLanguage = true }
    val context = LocalContext.current
    val appLanguage = AppLanguage.current(context)

    DetailPane(title = stringResource(R.string.set_nav_scrape), subtitle = stringResource(R.string.set_scrape_sub)) {
        // 语言 first: the two side by side, the second following the first unless set apart.
        GroupLabel(stringResource(R.string.set_group_language))
        DetailActionRow(
            title = stringResource(R.string.set_language_ui),
            description = stringResource(R.string.set_language_ui_hint),
            value = if (appLanguage.isEmpty()) stringResource(R.string.set_language_system) else AppLanguage.name(appLanguage),
            onClick = { pickAppLanguage = true },
        )
        // A value row: what it is set to beside the arrow, the choices in a sheet.
        DetailActionRow(
            title = stringResource(R.string.set_language_meta),
            description = stringResource(R.string.set_language_hint),
            value = MetadataLanguage.nameOf(draft.language),
            onClick = { pickLanguage = true },
        )

        GroupLabel(stringResource(R.string.set_group_matching))
        DetailSwitchRow(
            title = stringResource(R.string.set_offline),
            description = stringResource(R.string.set_offline_hint),
            checked = draft.offlineScrape,
            onCheckedChange = { on -> edit { copy(offlineScrape = on) } },
        )
        DetailSwitchRow(
            title = stringResource(R.string.set_local_meta),
            description = stringResource(R.string.set_local_meta_hint),
            checked = draft.readLocalMeta,
            onCheckedChange = { on -> edit { copy(readLocalMeta = on) } },
        )

        GroupLabel(stringResource(R.string.set_sources_group))
        // Keys are masked like passwords, trimmed as they are written (a pasted key often brings a
        // space or a line break with it), and off while offline scraping means they are not used.
        LabelledField(
            label = stringResource(R.string.set_tmdb_key_label),
            description = stringResource(if (builtInKey) R.string.set_tmdb_hint else R.string.set_tmdb_hint2),
            value = draft.tmdbApiKey,
            onValueChange = { v -> edit { copy(tmdbApiKey = v.trim()) } },
            placeholder = stringResource(if (builtInKey) R.string.set_tmdb_placeholder else R.string.set_tmdb_where),
            secret = true,
            enabled = !draft.offlineScrape,
        )
        LabelledField(
            label = stringResource(R.string.set_omdb_key_label),
            description = stringResource(R.string.set_omdb_hint),
            value = draft.omdbApiKey,
            onValueChange = { v -> edit { copy(omdbApiKey = v.trim()) } },
            placeholder = stringResource(R.string.set_omdb_where),
            secret = true,
            enabled = !draft.offlineScrape,
        )
        if (draft.offlineScrape) DetailNote(stringResource(R.string.set_keys_offline))
        DetailNote(stringResource(R.string.set_anime_note))
    }

    if (pickLanguage) {
        ChoiceSheet(
            title = stringResource(R.string.set_language_meta),
            choices = METADATA_LANGUAGES.map { it to MetadataLanguage.nameOf(it) },
            selected = draft.language,
            onChoose = { tag ->
                pickLanguage = false
                edit { copy(language = tag) }
            },
            onDismiss = { pickLanguage = false },
        )
    }
    if (pickAppLanguage) {
        ChoiceSheet(
            title = stringResource(R.string.set_language_ui),
            choices = listOf("" to stringResource(R.string.set_language_system)) + APP_LANGUAGES.map { it to AppLanguage.name(it) },
            selected = appLanguage,
            onChoose = { tag ->
                pickAppLanguage = false
                AppLanguage.set(context, tag)
            },
            onDismiss = { pickAppLanguage = false },
        )
    }
}

/**
 * 界面语言: the per-app language (LocaleManager) — the system keeps it, recreates the windows in it, and lists it
 * in its own settings for the app too (the generated localeConfig). "" is the headset's language.
 */
private object AppLanguage {
    fun current(context: android.content.Context): String {
        val chosen = context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales
            ?.takeUnless { it.isEmpty }?.get(0) ?: return ""
        return APP_LANGUAGES.firstOrNull { tagOf(it) == tagOf(chosen.toLanguageTag()) } ?: chosen.toLanguageTag()
    }

    fun set(context: android.content.Context, tag: String) {
        android.util.Log.i("AuraI18n", "app language → ${tag.ifEmpty { "the headset's" }}")
        context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales =
            if (tag.isEmpty()) android.os.LocaleList.getEmptyLocaleList() else android.os.LocaleList.forLanguageTags(tag)
    }

    /** A language named in itself, as pickers name them: 简体中文, 繁體中文, Deutsch, Português (Brasil)… */
    fun name(tag: String): String = when (tagOf(tag)) {
        "zh-Hans" -> "简体中文"
        "zh-Hant" -> "繁體中文"
        else -> java.util.Locale.forLanguageTag(tag).let { l ->
            (if (l.language == "pt" && l.country == "BR") l.getDisplayName(l) else l.getDisplayLanguage(l)).replaceFirstChar { it.titlecase(l) }
        }
    }

    /** One spelling for a language: zh-Hans / zh-Hant, nb for any Norwegian, else the bare language. */
    fun tagOf(tag: String): String = com.therealityteam.videoplayer.data.scrape.GenreCn.tableFor(tag)
}

/**
 * The languages metadata can be fetched in are [METADATA_LANGUAGES].
 *
 * An empty tag means "whatever the headset is set to", which is the default and the right answer
 * for almost everyone; the list is here for the reader whose device language and reading language
 * are not the same — someone abroad with an English headset who still wants Chinese titles. Each is
 * named in ITSELF, the way a language picker anywhere names its options, so it can be recognised by
 * a reader who cannot yet read the rest of the screen.
 */
private object MetadataLanguage {
    @Composable
    fun nameOf(tag: String): String = when {
        tag.isBlank() -> stringResource(R.string.set_language_follow_app)
        else -> AppLanguage.name(tag)
    }
}

@Composable
private fun PlaybackPage(draft: AppSettings, edit: ((AppSettings.() -> AppSettings)) -> Unit) {
    var pickSubtitles by rememberSaveable { mutableStateOf(false) }
    var pickSound by rememberSaveable { mutableStateOf(false) }
    SheetHook("subtitle_pref") { pickSubtitles = true }
    SheetHook("audio_pref") { pickSound = true }
    DetailPane(title = stringResource(R.string.set_nav_play), subtitle = stringResource(R.string.set_play_sub)) {
        // 观看 first: how a sitting runs is asked about far more often than which engine runs it.
        GroupLabel(stringResource(R.string.set_viewing))
        DetailSwitchRow(
            title = stringResource(R.string.set_autoplay),
            description = stringResource(if (draft.autoPlayNext) R.string.set_autoplay_on else R.string.set_autoplay_off),
            checked = draft.autoPlayNext,
            onCheckedChange = { on -> edit { copy(autoPlayNext = on) } },
        )
        DetailSwitchRow(
            title = stringResource(R.string.set_auto_skip_intro),
            description = stringResource(if (draft.autoSkipIntro) R.string.set_auto_skip_intro_on else R.string.set_auto_skip_intro_off),
            checked = draft.autoSkipIntro,
            onCheckedChange = { on -> edit { copy(autoSkipIntro = on) } },
        )
        DetailSwitchRow(
            title = stringResource(R.string.set_pano_auto),
            description = stringResource(if (draft.panoAutoImmersive) R.string.set_pano_auto_on else R.string.set_pano_auto_off),
            checked = draft.panoAutoImmersive,
            onCheckedChange = { on -> edit { copy(panoAutoImmersive = on) } },
        )
        DetailSwitchRow(
            title = stringResource(R.string.pc_night_mode),
            description = stringResource(if (draft.nightMode) R.string.set_night_on else R.string.set_night_off),
            checked = draft.nightMode,
            onCheckedChange = { on -> edit { copy(nightMode = on) } },
        )
        DetailSwitchRow(
            title = stringResource(R.string.set_cast),
            description = if (draft.castReceiver) stringResource(R.string.set_cast_on, stringResource(R.string.set_cast_name, android.os.Build.MODEL))
            else stringResource(R.string.set_cast_off),
            checked = draft.castReceiver,
            onCheckedChange = { on -> edit { copy(castReceiver = on) } },
        )

        // What a file opens with when nothing was picked for it or its show — after which the file's
        // own pick and its show's (整季沿用) take over.
        GroupLabel(stringResource(R.string.set_group_tracks))
        DetailActionRow(
            title = stringResource(R.string.set_subtitle_pref),
            description = stringResource(R.string.set_subtitle_pref_hint),
            value = subtitleChoiceName(draft.subtitleChoice),
            onClick = { pickSubtitles = true },
        )
        DetailActionRow(
            title = stringResource(R.string.set_audio_pref),
            description = stringResource(R.string.set_audio_pref_hint),
            value = audioChoiceName(draft.audioChoice),
            onClick = { pickSound = true },
        )

        GroupLabel(stringResource(R.string.set_engine))
        // Each row says what it does in the position it is in, not both positions run together.
        DetailSwitchRow(
            title = stringResource(R.string.set_enhanced_engine),
            description = stringResource(if (draft.useExoEngine) R.string.set_engine_on else R.string.set_engine_off),
            checked = draft.useExoEngine,
            onCheckedChange = { on -> edit { copy(useExoEngine = on) } },
        )
        DetailSwitchRow(
            title = stringResource(R.string.set_spatial_audio),
            description = stringResource(if (draft.spatialAudio) R.string.set_spatial_on else R.string.set_spatial_off) +
                "\n" + stringResource(R.string.set_spatial_note),
            checked = draft.spatialAudio,
            onCheckedChange = { on -> edit { copy(spatialAudio = on) } },
        )

        GroupLabel(stringResource(R.string.set_experimental))
        // The fd bridge only feeds the system player; with the enhanced engine on it does nothing,
        // so it is off-limits until that engine is.
        DetailSwitchRow(
            title = stringResource(R.string.set_fd_bridge),
            description = stringResource(if (draft.useExoEngine) R.string.set_fd_needs_system else R.string.set_fd_hint),
            checked = draft.useFdBridge,
            onCheckedChange = { on -> edit { copy(useFdBridge = on) } },
            enabled = !draft.useExoEngine,
        )
        DetailNote(stringResource(R.string.set_play_trouble))
    }
    if (pickSubtitles) {
        ChoiceSheet(
            title = stringResource(R.string.set_subtitle_pref),
            choices = readerFirst(SubtitleChoice.entries) { it.name }.map { it to subtitleChoiceName(it) },
            selected = draft.subtitleChoice,
            onChoose = { c -> pickSubtitles = false; edit { copy(subtitleChoice = c) } },
            onDismiss = { pickSubtitles = false },
        )
    }
    if (pickSound) {
        ChoiceSheet(
            title = stringResource(R.string.set_audio_pref),
            choices = readerFirst(AudioChoice.entries) { it.name }.map { it to audioChoiceName(it) },
            selected = draft.audioChoice,
            onChoose = { c -> pickSound = false; edit { copy(audioChoice = c) } },
            onDismiss = { pickSound = false },
        )
    }
}

/**
 * The track-language choices with the reader's own language straight after 自动 / 文件默认 — a German reader finds
 * Deutsch second, not twelfth.
 */
private fun <T> readerFirst(all: List<T>, name: (T) -> String): List<T> {
    val mine = TrackPreference.Lang.of(java.util.Locale.getDefault())?.name ?: return all
    val own = all.firstOrNull { name(it) == mine } ?: return all
    return listOf(all.first(), own) + all.drop(1).filter { it != own }
}

/** A language named in itself (as a language picker names them); the choices about no language in the reader's. */
@Composable
private fun subtitleChoiceName(c: SubtitleChoice): String = when (c) {
    SubtitleChoice.AUTO -> stringResource(R.string.set_pref_auto)
    SubtitleChoice.SIMPLIFIED -> "简体中文"
    SubtitleChoice.TRADITIONAL -> "繁體中文"
    SubtitleChoice.ENGLISH -> "English"
    SubtitleChoice.JAPANESE -> "日本語"
    SubtitleChoice.OFF -> stringResource(R.string.set_pref_off)
    else -> AppLanguage.name(TrackPreference.Lang.valueOf(c.name).code)
}

@Composable
private fun audioChoiceName(c: AudioChoice): String = when (c) {
    AudioChoice.DEFAULT -> stringResource(R.string.set_pref_file_default)
    AudioChoice.JAPANESE -> "日本語"
    AudioChoice.CHINESE -> "中文"
    AudioChoice.ENGLISH -> "English"
    else -> AppLanguage.name(TrackPreference.Lang.valueOf(c.name).code)
}

@Composable
private fun LibraryPage(vm: LibraryViewModel, onOpenDownloads: (() -> Unit)? = null) {
    val scrape = remember { GlobalContext.get().get<ScrapeEngine>() }
    val taskProgress by vm.taskProgress.collectAsState()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var confirmClearDownloads by rememberSaveable { mutableStateOf(false) }
    SheetHook("clear") { confirmClear = true }
    SheetHook("clear_downloads") { confirmClearDownloads = true }
    // One library task at a time: while a scan or scrape runs, neither of these would start.
    val busy = taskProgress != null || clearing

    DetailPane(title = stringResource(R.string.set_nav_library), subtitle = stringResource(R.string.set_rebuild)) {
        taskProgress?.let { progress ->
            GroupLabel(stringResource(R.string.sources_status))
            LibraryTaskProgress(progress, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }

        GroupLabel(stringResource(R.string.set_group_upkeep))
        val settingsRepo = remember { GlobalContext.get().get<com.therealityteam.videoplayer.data.repository.SettingsRepository>() }
        val appSettings by settingsRepo.settings.collectAsState()
        DetailSwitchRow(
            title = stringResource(R.string.set_auto_scan),
            description = stringResource(if (appSettings.autoScan) R.string.set_auto_scan_on else R.string.set_auto_scan_off),
            checked = appSettings.autoScan,
            onCheckedChange = { on -> settingsRepo.update(settingsRepo.get().copy(autoScan = on)) },
            icon = R.drawable.ic_line_nas,
        )
        DetailRow(
            title = stringResource(R.string.set_rescrape),
            description = stringResource(R.string.set_rescrape_hint),
            icon = R.drawable.ic_line_refresh,
            trailing = {
                Button(
                    onClick = {
                        vm.rescrape()
                        scope.launch { snackbar.show(message = context.getString(R.string.set_rescrape_started)) }
                    },
                    enabled = !busy,
                    size = ButtonDefaults.Small,
                ) { Text(stringResource(R.string.set_rescrape_start)) }
            },
        )
        if (taskProgress != null) DetailNote(stringResource(R.string.set_task_busy))

        // 下载到本机: what the copies take, where to see them — and, below, all of them gone at once.
        val downloader = remember { GlobalContext.get().get<com.therealityteam.videoplayer.data.download.Downloader>() }
        val downloads by downloader.store.all.collectAsState()
        val copies = downloads.filter { it.isDone }
        GroupLabel(stringResource(R.string.nav_downloads))
        DetailRow(
            title = stringResource(R.string.set_downloads),
            description = if (copies.isEmpty()) stringResource(R.string.set_downloads_none)
            else pluralStringResource(
                R.plurals.set_downloads_n, copies.size, copies.size,
                android.text.format.Formatter.formatShortFileSize(context, copies.sumOf { it.totalBytes }),
            ),
            icon = R.drawable.ic_line_download,
            trailing = onOpenDownloads?.let { open ->
                {
                    Button(onClick = open, size = ButtonDefaults.Small, colors = secondaryButtonColors()) {
                        Text(stringResource(R.string.set_downloads_open))
                    }
                }
            },
        )
        DetailSwitchRow(
            title = stringResource(R.string.set_delete_watched),
            description = stringResource(R.string.set_delete_watched_hint),
            checked = appSettings.deleteWatchedCopies,
            onCheckedChange = { on -> settingsRepo.update(settingsRepo.get().copy(deleteWatchedCopies = on)) },
            icon = R.drawable.ic_det_watched,
        )

        GroupLabel(stringResource(R.string.set_danger))
        if (downloads.isNotEmpty()) {
            DangerRow(
                title = stringResource(R.string.set_downloads_clear),
                description = stringResource(R.string.set_downloads_clear_hint),
                action = stringResource(R.string.set_downloads_clear_action),
                onClick = { confirmClearDownloads = true },
            )
        }
        DangerRow(
            title = stringResource(R.string.set_clear_library),
            description = stringResource(R.string.set_clear_hint),
            action = stringResource(R.string.set_clear_action),
            onClick = { confirmClear = true },
            enabled = !busy,
            busy = clearing,
        )
        DetailNote(stringResource(R.string.set_clear_note))
    }

    if (confirmClearDownloads) {
        val downloader = remember { GlobalContext.get().get<com.therealityteam.videoplayer.data.download.Downloader>() }
        AlertDialog(
            onDismissRequest = { confirmClearDownloads = false },
            title = { Text(stringResource(R.string.set_downloads_clear_q)) },
            content = { Text(stringResource(R.string.set_downloads_clear_body)) },
            buttons = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { confirmClearDownloads = false }, colors = secondaryButtonColors()) { Text(stringResource(R.string.cancel)) }
                    Button(
                        onClick = {
                            confirmClearDownloads = false
                            val all = downloader.store.all.value
                            all.forEach { downloader.remove(it.itemId) }
                            scope.launch { snackbar.show(message = context.getString(R.string.set_downloads_cleared)) }
                        },
                        colors = dangerButtonColors(),
                    ) { Text(stringResource(R.string.set_downloads_clear_confirm)) }
                }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.set_clear_q)) },
            content = { Text(stringResource(R.string.set_clear_body)) },
            buttons = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { confirmClear = false },
                        colors = secondaryButtonColors(),
                    ) { Text(stringResource(R.string.cancel)) }
                    Button(
                        onClick = {
                            confirmClear = false
                            clearing = true
                            scope.launch {
                                val ok = runCatching { scrape.clearLibrary() }.isSuccess
                                clearing = false
                                snackbar.show(message = context.getString(if (ok) R.string.set_cleared else R.string.set_clear_failed))
                            }
                        },
                        colors = dangerButtonColors(),
                    ) { Text(stringResource(R.string.set_clear)) }
                }
            },
        )
    }
}

/** A failure as a sentence, outside composition (a Snackbar's message is written in a coroutine). */
private fun faultMessage(context: Context, e: Throwable): String = when (faultOf(e)) {
    SourceFault.AUTH -> context.getString(R.string.fault_auth)
    SourceFault.NO_SHARE -> context.getString(R.string.fault_no_share)
    SourceFault.UNREACHABLE -> context.getString(R.string.fault_unreachable)
    else -> e.message?.trim()?.takeIf { it.isNotEmpty() }?.take(120) ?: context.getString(R.string.fault_unknown)
}

/**
 * 同步: off until asked for; then which share holds the folder, what this headset is called there,
 * how the last sync went, who else is in it, and a way to take it all off the NAS — the desktop's
 * Settings › Sync, page for page.
 */
@Composable
private fun SyncPage(
    draft: AppSettings,
    edit: ((AppSettings.() -> AppSettings)) -> Unit,
    sync: HeadsetSync,
    status: HeadsetSync.Status,
    onOpenSources: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val colors = PicoTheme.colorScheme
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    var candidates by remember { mutableStateOf<List<HeadsetSync.Location>?>(null) }
    LaunchedEffect(Unit) { candidates = withContext(Dispatchers.IO) { sync.candidates() } }
    // The check follows the choice the moment it is made; the location the last sync used only
    // stands in until the list of shares has been read.
    val chosen = candidates?.let { list ->
        list.firstOrNull { it.id.equals(draft.syncLocation, ignoreCase = true) }
            ?: list.firstOrNull().takeIf { draft.syncLocation.isBlank() }
    } ?: status.location
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val noShare = candidates?.isEmpty() == true
    SheetHook("remove") { if (chosen != null) confirmRemove = true }

    DetailPane(title = stringResource(R.string.set_nav_sync), subtitle = stringResource(R.string.sync_sub)) {
        // Turning it on needs a share to sync to; turning it off never needs anything.
        DetailSwitchRow(
            title = stringResource(R.string.sync_enable),
            description = stringResource(R.string.sync_enable_hint),
            checked = draft.syncEnabled,
            onCheckedChange = { on -> edit { copy(syncEnabled = on, syncLocation = syncLocation.ifBlank { chosen?.id.orEmpty() }) } },
            enabled = draft.syncEnabled || candidates?.isNotEmpty() == true,
        )

        GroupLabel(stringResource(R.string.sync_location))
        when {
            candidates == null -> DetailRow(title = stringResource(R.string.sync_location_loading), busy = true)
            noShare -> {
                DetailNote(stringResource(R.string.sync_no_location))
                if (onOpenSources != null) {
                    DetailActionRow(
                        title = stringResource(R.string.sync_open_sources),
                        description = stringResource(R.string.sync_open_sources_hint),
                        icon = R.drawable.ic_line_nas,
                        onClick = onOpenSources,
                    )
                }
            }
            else -> {
                candidates.orEmpty().forEach { loc ->
                    DetailActionRow(
                        title = loc.share,
                        description = loc.host,
                        icon = R.drawable.ic_line_nas,
                        selected = loc.id == chosen?.id,
                        onClick = { edit { copy(syncLocation = loc.id) } },
                    )
                }
                DetailNote(stringResource(R.string.sync_location_hint))
            }
        }

        GroupLabel(stringResource(R.string.sync_this_device))
        LabelledField(
            label = stringResource(R.string.sync_device_name),
            value = draft.deviceName,
            onValueChange = { v -> edit { copy(deviceName = v.take(60)) } },
            description = stringResource(R.string.sync_device_name_hint),
            placeholder = sync.defaultDeviceName,
        )

        if (draft.syncEnabled && chosen != null) {
            GroupLabel(stringResource(R.string.sync_status))
            val last = status.last
            val error = last?.error
            val lastLine = when {
                status.syncing -> stringResource(R.string.sync_syncing)
                last == null -> stringResource(R.string.sync_never)
                // The sentence for a cause we recognise; the library's own words for one we do not.
                error != null -> faultOf(java.io.IOException(error)).let { fault ->
                    stringResource(
                        R.string.sync_failed,
                        if (fault == SourceFault.UNKNOWN) error.take(120) else sourceFaultText(fault, error),
                    )
                }
                last.applied > 0 -> androidx.compose.ui.res.pluralStringResource(R.plurals.sync_took, last.applied, syncAgo(last.at), last.applied)
                else -> stringResource(R.string.sync_took_none, syncAgo(last.at))
            }
            DetailRow(
                title = stringResource(R.string.sync_last),
                description = lastLine,
                icon = if (error != null) R.drawable.ic_line_warning else R.drawable.ic_line_sync,
                iconTint = if (error != null) colors.error else null,
                busy = status.syncing,
                trailing = {
                    Button(
                        onClick = { scope.launch { sync.syncNow() } },
                        enabled = !status.syncing && !removing,
                        size = ButtonDefaults.Small,
                        colors = secondaryButtonColors(),
                    ) { Text(stringResource(R.string.sync_now)) }
                },
            )
            if (error == null && status.writable == false) {
                DetailRow(
                    title = stringResource(R.string.sync_readonly_title),
                    description = stringResource(R.string.sync_readonly),
                    icon = R.drawable.ic_line_warning,
                    iconTint = colors.alert,
                )
            }
            val where = "smb://${chosen.host}/${chosen.share}/${LibrarySync.FOLDER}"
            DetailRow(
                title = stringResource(R.string.sync_folder),
                description = last?.takeIf { it.error == null && it.bytes > 0 }
                    ?.let { stringResource(R.string.md_pair, where, fileSizeText(it.bytes)) } ?: where,
                icon = R.drawable.ic_line_folder,
            )

            GroupLabel(stringResource(R.string.sync_devices))
            val devices = last?.devices.orEmpty()
            if (devices.isEmpty()) {
                DetailNote(stringResource(R.string.sync_devices_none))
            } else {
                devices.forEach { d ->
                    DetailRow(
                        title = d.name,
                        description = stringResource(
                            R.string.sync_seen,
                            d.platform.ifBlank { stringResource(R.string.md_unknown) },
                            if (d.lastSeen > 0) syncAgo(d.lastSeen) else stringResource(R.string.md_unknown),
                        ),
                    )
                }
            }
        }

        GroupLabel(stringResource(R.string.sync_what))
        DetailNote(stringResource(R.string.sync_what_body))
        DetailNote(stringResource(R.string.sync_nas_note))

        if (chosen != null && (draft.syncEnabled || status.last != null)) {
            GroupLabel(stringResource(R.string.set_danger))
            DangerRow(
                title = stringResource(R.string.sync_remove),
                description = stringResource(R.string.sync_remove_hint),
                action = stringResource(R.string.sync_remove_button),
                onClick = { confirmRemove = true },
                enabled = !status.syncing,
                busy = removing,
            )
        }
    }

    if (confirmRemove && chosen != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.sync_remove_q)) },
            content = { Text(stringResource(R.string.sync_remove_body_at, "smb://${chosen.host}/${chosen.share}")) },
            buttons = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { confirmRemove = false },
                        colors = secondaryButtonColors(),
                    ) { Text(stringResource(R.string.cancel)) }
                    Button(
                        onClick = {
                            confirmRemove = false
                            removing = true
                            scope.launch {
                                val result = sync.remove()
                                removing = false
                                val failure = result.exceptionOrNull()
                                if (failure == null) {
                                    // Off too: left on, the next sync would write this headset's part back.
                                    edit { copy(syncEnabled = false) }
                                    snackbar.show(message = context.getString(R.string.sync_removed))
                                } else {
                                    snackbar.show(message = context.getString(R.string.sync_remove_failed, faultMessage(context, failure)))
                                }
                            }
                        },
                        colors = dangerButtonColors(),
                    ) { Text(stringResource(R.string.sync_remove_confirm)) }
                }
            },
        )
    }
}

/**
 * "now", "5 minutes ago", "yesterday", "3 days ago", then the date — in the reader's language, words and plural
 * forms from ICU (hand-made "%d days ago" strings had one form for every number).
 */
@Composable
private fun syncAgo(at: Long, now: Long = System.currentTimeMillis()): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val f = RelativeDateTimeFormatter.getInstance(locale)
    val min = (now - at).coerceAtLeast(0) / 60_000
    val ago = RelativeDateTimeFormatter.Direction.LAST
    return when {
        min < 1 -> f.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
        min < 60 -> f.format(min.toDouble(), ago, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
        min < 24 * 60 -> f.format((min / 60).toDouble(), ago, RelativeDateTimeFormatter.RelativeUnit.HOURS)
        min < 48 * 60 -> f.format(ago, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
        min < 30 * 24 * 60 -> f.format((min / (24 * 60)).toDouble(), ago, RelativeDateTimeFormatter.RelativeUnit.DAYS)
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).format(java.util.Date(at))
    }
}

/** In-app summary of docs/privacy-policy.md, with a shortcut to offline title identification. */
@Composable
private fun PrivacyPage(onOpenScrape: () -> Unit) {
    DetailPane(title = stringResource(R.string.set_nav_privacy), subtitle = stringResource(R.string.set_privacy_sub)) {
        GroupLabel(stringResource(R.string.set_no_collect))
        DetailNote(stringResource(R.string.set_no_collect_body))

        GroupLabel(stringResource(R.string.set_when_network))
        DetailRow(
            title = stringResource(R.string.set_scrape_metadata),
            description = stringResource(R.string.set_scrape_metadata_body),
            icon = R.drawable.ic_scrape,
        )
        DetailRow(
            title = stringResource(R.string.set_you_asked),
            description = stringResource(R.string.set_you_asked_body),
            icon = R.drawable.ic_line_download,
        )
        DetailRow(
            title = stringResource(R.string.set_cast_privacy),
            description = stringResource(R.string.set_cast_privacy_body),
            icon = R.drawable.ic_line_cast,
        )
        DetailActionRow(
            title = stringResource(R.string.set_privacy_go_scrape),
            description = stringResource(R.string.set_offline_note),
            icon = R.drawable.ic_privacy,
            onClick = onOpenScrape,
        )

        GroupLabel(stringResource(R.string.set_stored_local))
        DetailRow(
            title = stringResource(R.string.set_credentials),
            description = stringResource(R.string.set_credentials_body),
            icon = R.drawable.ic_line_nas,
        )
        DetailRow(
            title = stringResource(R.string.set_library_cache),
            description = stringResource(R.string.set_library_cache_body),
            icon = R.drawable.ic_cat_all,
        )
        DetailRow(
            title = stringResource(R.string.set_downloads_privacy),
            description = stringResource(R.string.set_downloads_privacy_body),
            icon = R.drawable.ic_line_download,
        )
    }
}

/**
 * The page opens on the app itself — its icon, the round badge PICO shows under the window, beside
 * its name and version — where it used to open on a generic ⓘ row set like any other setting.
 */
@Composable
private fun AppIdentity(version: String, build: Long?) {
    val res = LocalContext.current.resources
    // The launcher art is a 1424 px square: a quarter of it is ample for 72 dp, and it is decoded
    // unscaled (it sits in the density-less drawable folder, which would otherwise be blown up).
    val icon = remember(res) {
        runCatching {
            BitmapFactory.decodeResource(
                res, R.drawable.ic_launcher_foreground,
                BitmapFactory.Options().apply { inSampleSize = 4; inScaled = false },
            )?.asImageBitmap()
        }.getOrNull()
    }
    Row(
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(72.dp).clip(CircleShape))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.app_name), style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary)
            Text(
                when {
                    version.isBlank() -> stringResource(R.string.md_unknown)
                    build != null -> stringResource(R.string.set_version_build, version, build.toString())
                    else -> stringResource(R.string.set_live_version, version)
                },
                style = PicoTheme.typography.bodyMedium,
                color = PicoTheme.colorScheme.labelSecondary,
            )
        }
    }
}

@Composable
private fun AboutPage(version: String) {
    val context = LocalContext.current
    var showLicences by remember { mutableStateOf(false) }
    SheetHook("licences") { showLicences = true }
    if (showLicences) LicencesSheet(onDismiss = { showLicences = false })

    DetailPane(title = stringResource(R.string.set_nav_about), subtitle = stringResource(R.string.set_about_line)) {
        AppIdentity(version, appBuild())
        DetailNote(stringResource(R.string.set_about_body))
        // TMDB's terms require this attribution wherever their data is used.
        GroupLabel(stringResource(R.string.set_data_sources))
        DetailRow(
            title = stringResource(R.string.set_tmdb_name),
            description = stringResource(R.string.set_tmdb_notice),
            trailing = {
                Image(painterResource(R.drawable.tmdb_logo), contentDescription = null,
                    modifier = Modifier.size(width = 108.dp, height = 14.dp))
            },
        )
        DetailRow(
            title = stringResource(R.string.set_anime_dbs),
            description = stringResource(R.string.set_anime_dbs_body),
        )
        // The LGPL wants the notice and the trademark line wherever FFmpeg ships.
        DetailRow(
            title = stringResource(R.string.set_ffmpeg_name),
            description = stringResource(R.string.set_ffmpeg) + "\n" + stringResource(R.string.set_ffmpeg_tm),
        )
        // …and mpv's, with the libraries it is built with (app/natives/build-mpv.sh), where it ships.
        if (remember(context) { com.therealityteam.videoplayer.data.playback.SwitchingEngine.mpvPackaged(context) }) {
            DetailRow(title = stringResource(R.string.set_mpv_name), description = stringResource(R.string.set_mpv))
        }
        // Every component's licence, the LGPL libraries' source and our change to FFmpeg, in full
        // (assets/licenses.txt, written by tools/dev/gen-licenses.py from the files the components ship).
        DetailActionRow(
            title = stringResource(R.string.set_licences),
            description = stringResource(R.string.set_licences_hint),
            onClick = { showLicences = true },
        )
        // CC BY asks for the title, the author, the source, the licence and a note of what was
        // changed, wherever the work is used; the environments are used here. Our own places owe no one
        // (their scans are CC0): with none credited, the group is not shown.
        val credited = Environments.all.filter { it.credit != null }
        if (credited.isNotEmpty()) {
            GroupLabel(stringResource(R.string.set_environments))
            DetailNote(stringResource(R.string.set_environments_note))
        }
        credited.forEach { env ->
            val credit = env.credit ?: return@forEach
            DetailRow(
                title = stringResource(R.string.set_env_credit, stringResource(env.label), credit.title, credit.author),
                description = stringResource(R.string.md_pair, credit.licence, credit.url),
            )
        }

        val lastCrash = remember(context) {
            runCatching { com.therealityteam.videoplayer.platform.CrashLog.latest(context) }.getOrNull()
        }
        if (lastCrash != null) {
            GroupLabel(stringResource(R.string.set_diagnostics))
            DetailRow(
                title = stringResource(R.string.set_last_crash),
                description = stringResource(
                    R.string.md_pair,
                    lastCrash.name.removePrefix("crash-").removeSuffix(".txt"),
                    stringResource(R.string.set_crash_where, lastCrash.parentFile?.name ?: ""),
                ),
                icon = R.drawable.ic_line_warning,
                iconTint = PicoTheme.colorScheme.alert,
            )
        }
    }
}

/**
 * 开源许可: assets/licenses.txt in a sheet — the components by licence, the LGPL libraries' source and
 * relinking, then every licence text. Its sections are three blank lines apart, each led by its title line.
 */
@Composable
private fun LicencesSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sections by androidx.compose.runtime.produceState(initialValue = emptyList<Pair<String, String>>()) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
                .split("\n\n\n").map { it.trim('\n') }.filter { it.isNotEmpty() }
                .map { s ->
                    // A heading line (■ …, ━ …), a licence's own title over its text (one line, then a blank
                    // one), or a list read as it is.
                    val head = s.substringBefore("\n\n")
                    when {
                        '\n' !in s -> s to ""
                        '\n' !in head -> head to s.substringAfter("\n\n").trim('\n')
                        else -> "" to s
                    }
                }
        }
    }
    com.pico.spatial.ui.design.windows.Sheet(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_licences)) },
    ) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.widthIn(min = 560.dp, max = 760.dp).heightIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(sections.size) { i ->
                val (title, body) = sections[i]
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (title.isNotEmpty()) Text(title, style = PicoTheme.typography.titleSmall, color = PicoTheme.colorScheme.labelPrimary)
                    if (body.isNotEmpty()) {
                        Text(body, style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
                    }
                }
            }
        }
    }
}
