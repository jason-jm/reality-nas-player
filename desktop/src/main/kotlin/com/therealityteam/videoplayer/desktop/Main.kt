package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.util.PRODUCT_NAME
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.therealityteam.videoplayer.data.playback.PlaylistMode
import com.therealityteam.videoplayer.data.playback.SleepMode
import com.therealityteam.videoplayer.desktop.data.ThemeChoice
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.desktop.platform.Desktop
import com.therealityteam.videoplayer.desktop.platform.DesktopCore
import com.therealityteam.videoplayer.desktop.platform.DockMenu
import com.therealityteam.videoplayer.desktop.platform.DockProgress
import com.therealityteam.videoplayer.desktop.platform.Host
import com.therealityteam.videoplayer.desktop.platform.LogFile
import com.therealityteam.videoplayer.desktop.platform.Pickers
import com.therealityteam.videoplayer.desktop.player.Aspect
import com.therealityteam.videoplayer.desktop.player.MediaOpen
import com.therealityteam.videoplayer.desktop.player.PictureFormat
import com.therealityteam.videoplayer.desktop.player.PlayerController
import com.therealityteam.videoplayer.desktop.player.PlayerScreen
import com.therealityteam.videoplayer.desktop.player.aspectLabel
import com.therealityteam.videoplayer.desktop.player.fmtSpeed
import com.therealityteam.videoplayer.desktop.player.trackName
import com.therealityteam.videoplayer.desktop.ui.AppState
import com.therealityteam.videoplayer.desktop.ui.AuraDesktop
import com.therealityteam.videoplayer.desktop.ui.Page
import com.therealityteam.videoplayer.desktop.ui.addFolder
import com.therealityteam.videoplayer.desktop.ui.images.Images
import com.therealityteam.videoplayer.desktop.ui.library.FixMatchPage
import com.therealityteam.videoplayer.desktop.ui.library.episodeLabel
import com.therealityteam.videoplayer.desktop.ui.library.labelKey
import com.therealityteam.videoplayer.desktop.ui.library.orderedQueue
import com.therealityteam.videoplayer.desktop.ui.settings.SettingsWindow
import com.therealityteam.videoplayer.desktop.ui.shell.DropHint
import com.therealityteam.videoplayer.desktop.ui.shell.Dropped
import com.therealityteam.videoplayer.desktop.ui.shell.FileDropArea
import com.therealityteam.videoplayer.desktop.ui.shell.OpenAddressDialog
import com.therealityteam.videoplayer.desktop.ui.sources.SourcesPage
import com.therealityteam.videoplayer.desktop.ui.theme.AuraTheme
import com.therealityteam.videoplayer.ui.library.ContinueWatching
import com.therealityteam.videoplayer.ui.library.LibSection
import com.therealityteam.videoplayer.util.Log
import java.awt.Dimension
import java.io.File
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--self-test") {
        DesktopCore.install()
        kotlin.system.exitProcess(SelfTest.run(args.drop(1)))
    }
    // The Mac's own menu bar and appearance, before AWT starts.
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.name", PRODUCT_NAME)
    System.setProperty("apple.awt.application.appearance", "system")
    DesktopCore.install()
    Log.i("Aura", "starting · data ${Host.dataDir} · log ${LogFile.file}")
    val graph = AppGraph(Host.dataDir)
    Images.artwork = graph.artwork
    Images.frameStills = { item -> graph.frameStills.still(item) }
    graph.sync.start()
    // Frame posters once the wall is up, and again whenever a scan or scrape has finished.
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
        graph.library.wallLive.first { it }
        runCatching { graph.framePosters.fill() }
        var busy = false
        graph.library.taskProgress.collect { task ->
            if (busy && task == null) runCatching { graph.framePosters.fill() }
            busy = task != null
        }
    }
    val opened = args.map(::File).filter { it.exists() }
    // An address given on the command line (`Aura https://…/film.mkv`) plays as File › Open Address… would.
    val addresses = args.filter { "://" in it && !File(it).exists() }

    application {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(graph, scope) }
        val player = remember { PlayerController(graph, scope) }
        val settings by graph.settings.settings.collectAsState()
        val strings = remember(settings.uiLanguage) { Strings.forLanguage(settings.uiLanguage) }
        val windowState = rememberWindowState(
            placement = if (settings.windowMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = if (settings.windowX != Int.MIN_VALUE) WindowPosition(settings.windowX.dp, settings.windowY.dp) else WindowPosition.PlatformDefault,
            size = DpSize(settings.windowWidth.dp, settings.windowHeight.dp),
        )
        var beforeFullscreen by remember { mutableStateOf(WindowPlacement.Floating) }
        fun toggleFullscreen() {
            if (windowState.placement == WindowPlacement.Fullscreen) {
                windowState.placement = beforeFullscreen
            } else {
                beforeFullscreen = windowState.placement
                windowState.placement = WindowPlacement.Fullscreen
            }
        }
        fun exitPlayer() {
            player.stop()
            state.stopPlaying()
            if (windowState.placement == WindowPlacement.Fullscreen) windowState.placement = beforeFullscreen
            graph.sync.nudge(minGapMs = 0)
        }
        /** Every way out — Quit, ⌘Q, the window's close button off the Mac — saves the place and syncs it. */
        fun quit() {
            player.dispose()
            graph.sync.flush()
            exitApplication()
        }
        /**
         * The window's close button and ⌘W. A Mac app outlives its window: what is playing stops
         * where it is (its place saved, as on the way back to the library) and the window goes;
         * a scan or a match carries on, the Dock icon brings the window back, ⌘Q quits. Elsewhere,
         * closing the window is quitting.
         */
        fun closeWindow() {
            if (!Host.isMac) return quit()
            if (state.playing != null) exitPlayer()
            state.windowShown = false
        }
        fun playFiles(files: List<File>) {
            val items = files.filter { it.isFile }.sortedBy { it.name.lowercase() }.map { MediaOpen.adHocItem(it.absolutePath) }
            items.firstOrNull()?.let { state.play(it, items, title = it.title) }
            files.firstOrNull { it.isDirectory }?.let { addFolder(state, it) }
        }
        // An address: a file on this computer plays as one; anything else as itself, with the
        // credentials of a share or server it lies under, when one is set up.
        fun playAddress(address: String) {
            val local = File(address.removePrefix("file://"))
            if (local.isFile) return playFiles(listOf(local))
            val item = MediaOpen.adHocItem(address, MediaOpen.owner(address, graph.sources.all()))
            state.play(item, listOf(item), title = item.title)
        }
        LaunchedEffect(Unit) {
            AppIcon.installInDock()
            if (opened.isNotEmpty()) playFiles(opened) else addresses.firstOrNull()?.let(::playAddress)
            installAppHandlers(state, ::playFiles, onQuit = { player.dispose(); graph.sync.flush() })
        }
        // Anything asked of the window while it is away brings it back.
        LaunchedEffect(state.settingsOpen, state.addressOpen) {
            if (state.settingsOpen || state.addressOpen) state.windowShown = true
        }
        // The player follows the request.
        LaunchedEffect(state.playing) {
            val r = state.playing ?: return@LaunchedEffect
            player.open(r.item, r.queue, r.startMs, r.title)
        }

        Window(
            onCloseRequest = ::closeWindow,
            state = windowState,
            visible = state.windowShown,
            title = PRODUCT_NAME,
            icon = AppIcon.painter,
        ) {
            WindowSetup(windowState, graph)
            LaunchedEffect(state.windowShown) { if (state.windowShown) window.toFront() }
            AwayMenuBar(state, strings, onOpenFile = { playFiles(Pickers.chooseFiles(window, strings["d_open_file"])) })
            WindowProbe(state, ::closeWindow)
            DockMenuSetup(state, strings, onOpenFile = { playFiles(Pickers.chooseFiles(window, strings["d_open_file"])) })
            val dark = when (settings.theme) {
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                ThemeChoice.DARK -> true
                ThemeChoice.LIGHT -> false
            }
            val searchFocus = remember { FocusRequester() }
            CompositionLocalProvider(LocalStrings provides strings) {
                AuraTheme(dark) {
                    AuraMenus(state, player, strings, windowState, searchFocus, ::toggleFullscreen, ::playFiles, ::quit, ::closeWindow)
                    FileDropArea(
                        hint = { d -> dropHint(d, playing = state.playing != null) },
                        onDrop = { d -> dropped(d, state, player, strings, ::playFiles) },
                        modifier = Modifier.fillMaxSize().backButton { if (state.playing == null) state.back() },
                    ) {
                    if (state.playing != null) {
                        PlayerScreen(
                            player = player,
                            fullscreen = windowState.placement == WindowPlacement.Fullscreen,
                            onToggleFullscreen = ::toggleFullscreen,
                            onExit = ::exitPlayer,
                        )
                    } else {
                        AuraDesktop(
                            state = state,
                            searchFocus = searchFocus,
                            onAddFolder = { chooseAndAddFolder(state, window, strings) },
                            chrome = { content -> WindowDraggableArea { content() } },
                            pages = { page ->
                                when (page) {
                                    Page.Sources -> SourcesPage(state, window)
                                    is Page.FixMatch -> FixMatchPage(state, page.key)
                                    else -> {}
                                }
                            },
                        )
                    }
                    }
                }
                if (state.settingsOpen) {
                    AuraTheme(dark) { SettingsWindow(state, onClose = { state.settingsOpen = false }) }
                }
                if (state.addressOpen) {
                    AuraTheme(dark) { OpenAddressDialog(onOpen = ::playAddress, onClose = { state.addressOpen = false }) }
                }
            }
        }
    }
}

/** The app's icon for window title bars and task bars (a packaged Mac app takes its bundle's). */
private object AppIcon {
    private val image by lazy {
        runCatching { javax.imageio.ImageIO.read(AppIcon::class.java.getResourceAsStream("/icons/aura.png")) }.getOrNull()
    }
    val painter: androidx.compose.ui.graphics.painter.Painter? by lazy {
        image?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it.toComposeImageBitmap()) }
    }

    /** The Dock icon when run from Gradle rather than from the bundle. */
    fun installInDock() {
        val img = image ?: return
        runCatching { if (java.awt.Taskbar.isTaskbarSupported()) java.awt.Taskbar.getTaskbar().iconImage = img }
    }
}

/**
 * The Dock icon's menu, kept current: up to five titles to carry on with, as the wall's Continue
 * watching row has them (the window comes forward and plays), then Open File and Open Address.
 */
@OptIn(FlowPreview::class)
@Composable
private fun FrameWindowScope.DockMenuSetup(state: AppState, s: Strings, onOpenFile: () -> Unit) {
    LaunchedEffect(s) {
        state.graph.library.groups.debounce(1_500).collect { groups ->
            val resume = ContinueWatching.of(groups, limit = 5).map { e ->
                val label = e.group.title + if (e.item.episode != null || e.group.items.size > 1) " · " + episodeLabel(s, e.item) else ""
                DockMenu.Entry(label) {
                    state.play(e.item, orderedQueue(e.group), title = e.group.title)
                }
            }
            DockMenu.set(
                listOf(
                    if (resume.isEmpty()) emptyList() else listOf(DockMenu.Entry(s["cw_title"], null)) + resume,
                    listOf(
                        DockMenu.Entry(s["d_open_file"]) { state.windowShown = true; onOpenFile() },
                        DockMenu.Entry(s["d_open_url"]) { state.addressOpen = true },
                    ),
                ),
            )
        }
    }
}

/** The Mac's full-height window (content under the title bar), a minimum size, remembered geometry. */
@OptIn(FlowPreview::class)
@Composable
private fun FrameWindowScope.WindowSetup(windowState: WindowState, graph: AppGraph) {
    DisposableEffect(Unit) {
        if (Host.isMac) {
            window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
        window.minimumSize = Dimension(960, 620)
        // Back at the window after a while elsewhere — perhaps on another device: catch up.
        val focus = object : java.awt.event.WindowAdapter() {
            override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = graph.sync.nudge()
        }
        window.addWindowFocusListener(focus)
        onDispose { window.removeWindowFocusListener(focus) }
    }
    // A long task on the Dock icon / taskbar button: a model downloading, else a scan or a match.
    // -Daura.dumpMenus=<seconds>: the menu bar as built — titles, keys, what is enabled — printed
    // that long after start, then quit: the menus checked where nothing can be looked at.
    System.getProperty("aura.dumpMenus")?.toLongOrNull()?.let { after ->
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(after * 1000)
            fun dump(item: javax.swing.JMenuItem, depth: Int) {
                val key = item.accelerator?.let { " [${java.awt.event.KeyEvent.getModifiersExText(it.modifiers)}+${java.awt.event.KeyEvent.getKeyText(it.keyCode)}]" } ?: ""
                val mark = when (item) { is javax.swing.JRadioButtonMenuItem, is javax.swing.JCheckBoxMenuItem -> if (item.isSelected) "(•) " else "( ) "; else -> "" }
                println("MENU " + "  ".repeat(depth) + mark + item.text + key + if (item.isEnabled) "" else "  — disabled")
                if (item is javax.swing.JMenu) for (i in 0 until item.itemCount) item.getItem(i)?.let { dump(it, depth + 1) } ?: println("MENU " + "  ".repeat(depth + 1) + "———")
            }
            window.jMenuBar?.let { bar -> for (i in 0 until bar.menuCount) dump(bar.getMenu(i), 0) } ?: println("MENU none")
            DockMenu.describe().forEach { println("DOCKMENU $it") }
            System.exit(0)
        }
    }
    LaunchedEffect(Unit) {
        graph.library.taskProgress.collect { task ->
            if (task != null) DockProgress.show(window, if (task.indeterminate) null else task.fraction, running = true)
            else DockProgress.show(window, null)
        }
    }
    LaunchedEffect(windowState) {
        snapshotFlow { Triple(windowState.size, windowState.position, windowState.placement) }
            .debounce(600)
            .collect { (size, pos, placement) ->
                if (placement == WindowPlacement.Fullscreen) return@collect
                graph.settings.update {
                    it.copy(
                        windowMaximized = placement == WindowPlacement.Maximized,
                        windowWidth = if (placement == WindowPlacement.Floating) size.width.value.toInt() else it.windowWidth,
                        windowHeight = if (placement == WindowPlacement.Floating) size.height.value.toInt() else it.windowHeight,
                        windowX = if (placement == WindowPlacement.Floating && pos is WindowPosition.Absolute) pos.x.value.toInt() else it.windowX,
                        windowY = if (placement == WindowPlacement.Floating && pos is WindowPosition.Absolute) pos.y.value.toInt() else it.windowY,
                    )
                }
            }
    }
}

/** The menu bar: the Mac's across the top of the screen, the window's on Windows. */
@Composable
private fun FrameWindowScope.AuraMenus(
    state: AppState,
    player: PlayerController,
    s: Strings,
    windowState: WindowState,
    searchFocus: FocusRequester,
    toggleFullscreen: () -> Unit,
    playFiles: (List<File>) -> Unit,
    quit: () -> Unit,
    closeWindow: () -> Unit,
) {
    val cmd = !Host.isMac
    fun key(k: Key, shift: Boolean = false, alt: Boolean = false) =
        if (Host.isMac) KeyShortcut(k, meta = true, shift = shift, alt = alt) else KeyShortcut(k, ctrl = true, shift = shift, alt = alt)
    val playing = state.playing != null
    MenuBar {
        Menu(s["d_menu_file"]) {
            Item(s["d_open_file"], shortcut = key(Key.O)) { playFiles(Pickers.chooseFiles(window, s["d_open_file"])) }
            Item(s["d_open_url"], shortcut = key(Key.L)) { state.addressOpen = true }
            Item(s["d_add_folder"], shortcut = key(Key.O, shift = true)) { chooseAndAddFolder(state, window, s) }
            Item(s["d_add_source"]) { if (playing) return@Item; state.go(Page.Sources) }
            if (!Host.isMac) {
                Separator()
                Item(s["d_preferences"], shortcut = key(Key.Comma)) { state.settingsOpen = true }
                Separator()
                Item(s["d_quit"], shortcut = key(Key.Q)) { quit() }
            }
        }
        Menu(s["d_menu_edit"]) {
            Item(s["d_find"], shortcut = key(Key.F), enabled = !playing) { runCatching { searchFocus.requestFocus() } }
        }
        Menu(s["d_menu_view"]) {
            Item(
                s["d_go_back"],
                shortcut = if (Host.isMac) KeyShortcut(Key.LeftBracket, meta = true) else KeyShortcut(Key.DirectionLeft, alt = true),
                enabled = !playing && state.canGoBack,
            ) { state.back() }
            Separator()
            Item(s["d_toggle_sidebar"], shortcut = key(Key.S, alt = true), enabled = !playing) {
                state.graph.settings.update { it.copy(sidebarCollapsed = !it.sidebarCollapsed) }
            }
            Item(s["d_bigger_posters"], shortcut = key(Key.Equals), enabled = !playing) {
                state.graph.settings.update { it.copy(posterWidth = (it.posterWidth + 16f).coerceAtMost(280f)) }
            }
            Item(s["d_smaller_posters"], shortcut = key(Key.Minus), enabled = !playing) {
                state.graph.settings.update { it.copy(posterWidth = (it.posterWidth - 16f).coerceAtLeast(120f)) }
            }
            Separator()
            Menu(s["d_picture_format"], enabled = playing) {
                for (f in PictureFormat.entries) RadioButtonItem(s["d_fmt_" + f.name.lowercase()], selected = player.format == f) { player.changeFormat(f) }
            }
            Menu(s["pc_pano_format"], enabled = playing && player.spherical) {
                val pano = player.pano
                if (pano != null) for (c in com.therealityteam.videoplayer.desktop.player.PanoChoice.of(pano.projection)) {
                    val label = s[com.therealityteam.videoplayer.desktop.player.PanoChoice.labelKey(c.projection)] + if (c.lens > 0) " ${c.lens}°" else ""
                    RadioButtonItem(label, selected = c.matches(pano)) { player.changePano(c) }
                }
            }
            Menu(s["pc_pano_direction"], enabled = playing && player.spherical) {
                Item(s["pc_pano_tilt_up"]) { player.nudgeLevel(player.LEVEL_STEP, 0f) }
                Item(s["pc_pano_tilt_down"]) { player.nudgeLevel(-player.LEVEL_STEP, 0f) }
                Separator()
                Item(s["pc_pano_roll_left"]) { player.nudgeLevel(0f, -player.LEVEL_STEP) }
                Item(s["pc_pano_level"]) { player.resetLevel() }
                Item(s["pc_pano_roll_right"]) { player.nudgeLevel(0f, player.LEVEL_STEP) }
            }
            Menu(s["pc_aspect"], enabled = playing && player.format == PictureFormat.FLAT_2D) {
                for (a in Aspect.entries) RadioButtonItem(aspectLabel(a, s), selected = player.aspect == a) { player.changeAspect(a) }
            }
            Separator()
            Item(
                s[if (windowState.placement == WindowPlacement.Fullscreen) "d_exit_fullscreen" else "d_fullscreen"],
                shortcut = if (Host.isMac) KeyShortcut(Key.F, meta = true, ctrl = true) else KeyShortcut(Key.F11),
                onClick = toggleFullscreen,
            )
        }
        Menu(s["d_menu_library"]) {
            listOf(LibSection.ALL, LibSection.MOVIE, LibSection.TV, LibSection.ANIME, LibSection.DOC).forEachIndexed { i, sec ->
                Item(s[sec.labelKey], shortcut = key(Key(Key.One.keyCode + i)), enabled = !playing) {
                    state.show(sec)
                }
            }
            Separator()
            Item(s["d_rescan_all"], shortcut = key(Key.R)) { state.graph.library.scanAllSources() }
            Item(s["d_rescrape"], shortcut = key(Key.R, shift = true)) { state.graph.library.rescrape() }
            Item(s["nav_sources"], enabled = !playing) { state.go(Page.Sources) }
        }
        // The player's commands, where a Mac keeps them: what the panels offer, for the menu bar's
        // reader and for keys of their own. Plain keys stay the player's (Space, arrows, M, Z/X…).
        val isPlaying = if (playing) player.player.state.collectAsState().value.isPlaying else false
        val settings by state.graph.settings.settings.collectAsState()
        LaunchedEffect(playing) { if (playing) player.refreshAudioDevices() }
        Menu(s["d_menu_playback"]) {
            Item(s[if (isPlaying) "pc_pause" else "pc_play"], enabled = playing) { player.togglePause() }
            Separator()
            Item(s["pc_previous"], shortcut = key(Key.DirectionLeft), enabled = playing) { player.prev() }
            Item(s["pc_next"], shortcut = key(Key.DirectionRight), enabled = playing && player.hasNext) { player.next() }
            Item(s["d_prev_chapter"], enabled = playing && player.chapters.size > 1) { player.prevChapter() }
            Item(s["d_next_chapter"], enabled = playing && player.chapters.size > 1) { player.nextChapter() }
            // 逐帧 (in the player: , and .).
            Item(s["pc_frame_back"], enabled = playing) { player.stepFrame(forward = false) }
            Item(s["pc_frame_forward"], enabled = playing) { player.stepFrame(forward = true) }
            Separator()
            Menu(s["pc_speed"], enabled = playing) {
                for (v in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) {
                    RadioButtonItem(fmtSpeed(v) + "×", selected = player.speed == v) { player.changeSpeed(v) }
                }
            }
            Menu(s["pc_repeat_mode"], enabled = playing) {
                for (m in PlaylistMode.entries) RadioButtonItem(s["d_mode_" + m.name.lowercase()], selected = player.mode == m) { player.mode = m }
            }
            Item(
                s["pc_ab_repeat"] + " · " + when {
                    player.abA == null -> s["pc_set_a"]
                    player.abB == null -> s["pc_set_b"]
                    else -> s["pc_clear_loop"]
                },
                enabled = playing,
            ) { player.cycleAb() }
            Menu(s["pc_sleep"], enabled = playing) {
                for (m in SleepMode.entries) {
                    val label = when (m) {
                        SleepMode.OFF -> s["pc_off"]
                        SleepMode.END_OF_ITEM -> s["d_sleep_end_row"]
                        else -> "${m.minutes}′"
                    }
                    RadioButtonItem(label, selected = player.sleep == m) { player.changeSleep(m) }
                }
            }
            Separator()
            Item(s["pc_screenshot"], shortcut = key(Key.S, shift = true), enabled = playing) {
                state.scope.launch {
                    val shot = player.screenshot()
                    if (shot != null) Desktop.reveal(shot.absolutePath) else player.tell(s["d_screenshot_failed"])
                }
            }
        }
        Menu(s["d_menu_audio"]) {
            if (player.audioTracks.isEmpty()) Item(s["pc_default_track"], enabled = false) {}
            for (t in player.audioTracks) {
                RadioButtonItem(trackName(t, s), selected = t.selected, enabled = playing) { player.selectAudio(t.id) }
            }
            Separator()
            Item(s["d_volume_up"], shortcut = key(Key.DirectionUp), enabled = playing) { player.setVolume(player.volume() + 5f) }
            Item(s["d_volume_down"], shortcut = key(Key.DirectionDown), enabled = playing) { player.setVolume(player.volume() - 5f) }
            CheckboxItem(s["d_mute"], checked = settings.muted, enabled = playing) { player.toggleMute() }
            if (player.audioDevices.isNotEmpty()) {
                Menu(s["d_audio_output"], enabled = playing) {
                    val chosen = settings.audioDevice
                    for ((name, description) in player.audioDevices) {
                        RadioButtonItem(if (name == "auto") s["d_audio_default"] else description.ifBlank { name }, selected = chosen == name) {
                            player.chooseAudioDevice(name)
                        }
                    }
                }
            }
        }
        Menu(s["d_menu_subtitles"]) {
            RadioButtonItem(s["pc_off"], selected = player.subtitleTracks.none { it.selected }, enabled = playing) { player.selectSubtitle(null) }
            for (t in player.subtitleTracks) {
                RadioButtonItem(trackName(t, s), selected = t.selected, enabled = playing) { player.selectSubtitle(t.id) }
            }
            Separator()
            Item(s["d_add_subtitle_file"], enabled = playing) {
                Pickers.chooseFiles(window, s["d_add_subtitle_file"]).firstOrNull()?.let { player.addSubtitleFile(it) }
            }
            Separator()
            Item(s["d_sub_earlier"], enabled = playing) { player.setSubtitleDelay(player.subtitleDelayMs - 100) }
            Item(s["d_sub_later"], enabled = playing) { player.setSubtitleDelay(player.subtitleDelayMs + 100) }
            Item(s["d_sub_delay_reset"], enabled = playing && player.subtitleDelayMs != 0L) { player.setSubtitleDelay(0) }
        }
        Menu(s["d_menu_window"]) {
            if (Host.isMac) Item(s["d_close_window"], shortcut = key(Key.W)) { closeWindow() }
            Item(s["d_minimize"], shortcut = key(Key.M)) { windowState.isMinimized = true }
            Item(s["d_zoom"]) {
                windowState.placement = if (windowState.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
            }
        }
        Menu(s["d_menu_help"]) {
            Item(s["d_show_logs"]) { Desktop.reveal(LogFile.file.absolutePath) }
        }
    }
}

fun chooseAndAddFolder(state: AppState, window: java.awt.Frame?, s: Strings) {
    Pickers.chooseFolder(window, s["d_add_folder"])?.let {
        addFolder(state, it)
        state.toast(s["d_scan_started", it.name])
    }
}

/** The Mac application menu's About / Settings / Quit, and files opened from Finder. */
private fun installAppHandlers(state: AppState, playFiles: (List<File>) -> Unit, onQuit: () -> Unit) {
    runCatching {
        if (!java.awt.Desktop.isDesktopSupported()) return
        val d = java.awt.Desktop.getDesktop()
        if (d.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER)) {
            d.setQuitHandler { _, response ->
                runCatching(onQuit)
                response.performQuit()
            }
        }
        if (d.isSupported(java.awt.Desktop.Action.APP_PREFERENCES)) d.setPreferencesHandler { state.settingsOpen = true }
        if (d.isSupported(java.awt.Desktop.Action.APP_ABOUT)) d.setAboutHandler { state.settingsOpen = true }
        if (d.isSupported(java.awt.Desktop.Action.APP_OPEN_FILE)) d.setOpenFileHandler { e -> playFiles(e.files) }
        // A click on the Dock icon while the window is put away brings it back.
        if (Host.isMac) d.addAppEventListener(java.awt.desktop.AppReopenedListener { state.windowShown = true })
    }.onFailure { Log.w("Aura", "app handlers unavailable", it) }
}

/**
 * The Mac's menu bar while no Aura window is up: a way back in — the window, a file, an address —
 * beside the application menu's Settings and Quit. With the window up its own bar shows instead.
 */
@Composable
private fun FrameWindowScope.AwayMenuBar(state: AppState, s: Strings, onOpenFile: () -> Unit) {
    if (!Host.isMac) return
    LaunchedEffect(s) {
        runCatching {
            val d = java.awt.Desktop.getDesktop()
            if (!d.isSupported(java.awt.Desktop.Action.APP_MENU_BAR)) return@runCatching
            val meta = java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
            fun item(label: String, key: Int, run: () -> Unit) = javax.swing.JMenuItem(label).apply {
                accelerator = javax.swing.KeyStroke.getKeyStroke(key, meta)
                addActionListener { run() }
            }
            val bar = javax.swing.JMenuBar()
            bar.add(javax.swing.JMenu(s["d_menu_file"]).apply {
                add(item(s["d_open_file"], java.awt.event.KeyEvent.VK_O) { state.windowShown = true; onOpenFile() })
                add(item(s["d_open_url"], java.awt.event.KeyEvent.VK_L) { state.addressOpen = true })
            })
            bar.add(javax.swing.JMenu(s["d_menu_window"]).apply {
                add(item(s["d_show_window"], java.awt.event.KeyEvent.VK_0) { state.windowShown = true })
            })
            d.setDefaultMenuBar(bar)
        }.onFailure { Log.w("Aura", "no menu bar for when the window is away", it) }
    }
}

/**
 * -Daura.windowProbe=<seconds>: the close button and the Dock's way back, exercised on the real
 * window where nothing can be looked at — that long after start the window is closed as its
 * button would, then brought back as a Dock click would; what the window and the app did is
 * printed, then the app quits.
 */
@Composable
private fun FrameWindowScope.WindowProbe(state: AppState, closeWindow: () -> Unit) {
    val after = System.getProperty("aura.windowProbe")?.toLongOrNull() ?: return
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(after * 1000)
        val bar = runCatching {
            java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_MENU_BAR)
        }.getOrDefault(false)
        println("WINDOW before visible=${window.isVisible} shown=${state.windowShown} playing=${state.playing != null} awayMenuBar=$bar")
        closeWindow()
        kotlinx.coroutines.delay(800)
        println("WINDOW closed visible=${window.isVisible} shown=${state.windowShown} playing=${state.playing != null} appAlive=true")
        state.windowShown = true // what the Dock click's AppReopenedListener does
        kotlinx.coroutines.delay(800)
        println("WINDOW reopened visible=${window.isVisible} shown=${state.windowShown} focused=${window.isFocused}")
        System.exit(0)
    }
}

/** What letting go of [d] will do, for the overlay; null contents (unknown yet) get the general hint. */
private fun dropHint(d: Dropped?, playing: Boolean): DropHint = when {
    d == null -> DropHint.Generic
    d.videos.isNotEmpty() -> DropHint.Play
    d.subtitles.isNotEmpty() && playing -> DropHint.AddSubtitles
    d.folders.isNotEmpty() -> DropHint.AddToLibrary
    else -> DropHint.Generic
}

/**
 * Files let go of over the window: videos play (a folder dropped with them is still added),
 * subtitles join the video that is playing, folders join the library.
 */
private fun dropped(d: Dropped, state: AppState, player: PlayerController, s: Strings, playFiles: (List<File>) -> Unit) {
    when {
        d.videos.isNotEmpty() -> playFiles(d.videos)
        d.subtitles.isNotEmpty() && state.playing != null -> {
            d.subtitles.forEach(player::addSubtitleFile)
            state.toast(s["d_subtitle_added", d.subtitles.joinToString(" · ") { it.name }])
        }
        d.subtitles.isNotEmpty() && d.folders.isEmpty() -> state.toast(s["d_subtitle_needs_video"])
    }
    if (d.folders.isNotEmpty()) {
        d.folders.forEach { addFolder(state, it) }
        if (d.videos.isEmpty()) state.toast(s["d_scan_started", d.folders.joinToString(" · ") { it.name }])
    }
}

/** The mouse's back button (the side one), as browsers and file managers treat it. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun Modifier.backButton(onBack: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { e ->
        if (e.button == PointerButton.Back) onBack()
    }
