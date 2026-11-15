package com.therealityteam.videoplayer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.desktop.platform.DesktopCore
import com.therealityteam.videoplayer.desktop.player.Chapter
import com.therealityteam.videoplayer.desktop.player.MediaOpen
import com.therealityteam.videoplayer.desktop.player.PictureFormat
import com.therealityteam.videoplayer.desktop.player.PlayerController
import com.therealityteam.videoplayer.desktop.player.PlayerScreen
import com.therealityteam.videoplayer.desktop.player.SettingsPanel
import com.therealityteam.videoplayer.desktop.player.SubtitlePanel
import com.therealityteam.videoplayer.desktop.player.Track
import com.therealityteam.videoplayer.desktop.ui.AppState
import com.therealityteam.videoplayer.desktop.ui.AuraDesktop
import com.therealityteam.videoplayer.desktop.ui.Page
import com.therealityteam.videoplayer.desktop.ui.images.Images
import com.therealityteam.videoplayer.desktop.ui.library.FixMatchPage
import com.therealityteam.videoplayer.desktop.ui.library.orderedQueue
import com.therealityteam.videoplayer.desktop.ui.settings.SettingsContent
import com.therealityteam.videoplayer.desktop.ui.shell.DropHint
import com.therealityteam.videoplayer.desktop.ui.shell.DropOverlay
import com.therealityteam.videoplayer.desktop.ui.shell.OpenAddressForm
import com.therealityteam.videoplayer.desktop.ui.sources.SourcesPage
import com.therealityteam.videoplayer.desktop.ui.theme.AuraTheme
import com.therealityteam.videoplayer.ui.library.LibSection
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The desktop's screens drawn offscreen to PNG, on a real library, so the design can be looked at
 * without a window (and on a locked screen). Off unless asked for:
 *
 *   ./gradlew -p desktop test --tests '*SnapshotTest*' -Daura.snapshots=/tmp/shots \
 *       -Daura.data=/tmp/aura-data [-Daura.probe.folder=$HOME/Movies] [-Daura.shots=wall,player]
 */
class SnapshotTest {

    @Test
    fun screens() {
        val out = System.getProperty("aura.snapshots")?.let(::File)
        val data = System.getProperty("aura.data")?.let(::File)
        assumeTrue("set -Daura.snapshots and -Daura.data", out != null && data != null)
        out!!.mkdirs()
        val only = System.getProperty("aura.shots")?.split(',')?.map { it.trim() }?.toSet()
        fun wanted(name: String) = only == null || name in only
        DesktopCore.install()
        val graph = AppGraph(data!!)
        Images.artwork = graph.artwork
        Images.frameStills = { item -> graph.frameStills.still(item) }
        runBlocking {
            graph.media.loaded.first { it }
            val folder = System.getProperty("aura.probe.folder")
            if (graph.media.all().isEmpty() && folder != null) {
                val config = MediaSourceConfig("local:$folder", SourceType.LOCAL, File(folder).name, folder, addedAt = System.currentTimeMillis())
                graph.sources.upsert(config)
                graph.scanner.scan(config) { _, _ -> }
            }
            withTimeoutOrNull(30_000) { graph.library.wallLive.first { it } }
            withTimeoutOrNull(30_000) { graph.library.groups.first { it.isNotEmpty() } }
            withTimeoutOrNull(30_000) { graph.library.searchableGroups.first { it.isNotEmpty() } }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val state = AppState(graph, scope)
        val groups = graph.library.groups.value

        if (wanted("wall")) for (lang in listOf("zh-CN", "en")) for (dark in listOf(true, false)) {
            state.show(LibSection.ALL)
            shoot(File(out, "wall_${lang}_${if (dark) "dark" else "light"}.png"), lang, dark, state)
        }
        if (wanted("detail")) {
            listOfNotNull(groups.firstOrNull { it.isSeries }?.key, groups.firstOrNull { !it.isSeries && it.posterPath != null }?.key)
                .forEachIndexed { i, key ->
                    state.go(Page.Detail(key))
                    shoot(File(out, "detail${i}_zh-CN_dark.png"), "zh-CN", true, state)
                    shoot(File(out, "detail${i}_en_light.png"), "en", false, state)
                    state.back()
                }
        }
        if (wanted("rowhover")) groups.firstOrNull { !it.isSeries && it.posterPath != null }?.let { g ->
            // The cast row under the pointer, then its right arrow clicked.
            state.go(Page.Detail(g.key))
            val row = Offset(900f, 720f)
            shoot(File(out, "rowhover_zh-CN_dark.png"), "zh-CN", true, state, pointer = listOf(row to false))
            shoot(File(out, "rowpaged_zh-CN_dark.png"), "zh-CN", true, state, pointer = listOf(row to false, Offset(1411f, 723f) to true))
            state.back()
        }
        if (wanted("address")) for (lang in listOf("zh-CN", "en")) for (dark in listOf(true, false)) {
            shootContent(File(out, "address_${lang}_${if (dark) "dark" else "light"}.png"), lang, dark, 540, 172) {
                OpenAddressForm("https://example.com/films/My%20Film.mkv", onOpen = {}, onCancel = {})
            }
        }
        if (wanted("subtitlepanel")) {
            // Three sidecar files and an embedded track: the shared name dimmed, the rest strong.
            val player = PlayerController(graph, scope)
            val film = "[Aura] Frieren - 01 [WebRip 1080p HEVC-10bit AAC]"
            player.subtitleTracks = listOf(
                Track("1", null, "chi", "ass", 0, 0, true, "/v/$film.chs.ass", true, false, false, false),
                Track("2", null, "chi", "ass", 0, 0, true, "/v/$film.cht.ass", false, false, false, false),
                Track("3", null, "jpn", "subrip", 0, 0, true, "/v/$film.jpn.srt", false, false, false, false),
                Track("4", "Signs & Songs", "eng", "ass", 0, 0, false, null, false, false, true, false),
            )
            for (lang in listOf("zh-CN", "en")) shootContent(File(out, "subtitles_panel_$lang.png"), lang, true, 400, 520) {
                AuraTheme(true) {
                    Column(Modifier.fillMaxSize().background(Color(0xFF24272F)).padding(vertical = 8.dp)) {
                        SubtitlePanel(player, onClose = {})
                    }
                }
            }
        }
        if (wanted("search")) {
            state.query = "dune"
            shoot(File(out, "search_zh-CN_dark.png"), "zh-CN", true, state)
            state.query = ""
        }
        if (wanted("sources")) {
            state.go(Page.Sources)
            shoot(File(out, "sources_zh-CN_dark.png"), "zh-CN", true, state)
            state.back()
        }
        if (wanted("fixmatch")) groups.firstOrNull { !it.isSeries }?.let { g ->
            state.go(Page.FixMatch(g.key))
            shoot(File(out, "fixmatch_zh-CN_dark.png"), "zh-CN", true, state, settleMs = 6_000)
            state.back()
        }
        if (wanted("settings")) for (page in listOf("GENERAL", "SCRAPING", "PLAYBACK", "LIBRARY", "AI", "ABOUT")) {
            shootContent(File(out, "settings_${page.lowercase()}_zh-CN_dark.png"), "zh-CN", true, 820, 600) {
                SettingsContent(state, page)
            }
        }
        if (wanted("sync")) {
            shootContent(File(out, "settings_sync_off_zh-CN_dark.png"), "zh-CN", true, 820, 640) { SettingsContent(state, "SYNC") }
            // On, in a folder another device already writes to.
            val nas = Files.createTempDirectory("aura-shot-nas").toFile()
            File(nas, ".aura/devices/pico-1").mkdirs()
            File(nas, ".aura/devices/pico-1/device.json").writeText(
                """{"v":1,"id":"pico-1","name":"PICO 4 Ultra","platform":"PICO","app":"Aura 1.0.0","at":${System.currentTimeMillis() - 2 * 3_600_000}}""",
            )
            val before = graph.settings.value
            graph.settings.update { it.copy(syncEnabled = true, syncLocation = "folder:${nas.absolutePath}") }
            runBlocking { graph.sync.syncNow() }
            for (lang in listOf("zh-CN", "en")) for (dark in listOf(true, false)) {
                shootContent(File(out, "settings_sync_on_${lang}_${if (dark) "dark" else "light"}.png"), lang, dark, 820, 900) { SettingsContent(state, "SYNC") }
            }
            graph.settings.update { before }
            nas.deleteRecursively()
        }
        if (wanted("drop")) {
            state.show(LibSection.ALL)
            shootContent(File(out, "drop_zh-CN_dark.png"), "zh-CN", true, 1440, 900) {
                Box {
                    AuraDesktop(state = state, searchFocus = remember { FocusRequester() }, onAddFolder = {}, chrome = { it() })
                    DropOverlay(DropHint.Play)
                }
            }
        }
        if (wanted("playerformat")) {
            // The player's settings panel, for a 360° file, a fisheye's (its lens), and a flat one.
            val fisheye = com.therealityteam.videoplayer.desktop.player.PanoChoice(com.therealityteam.videoplayer.data.model.ProjectionMode.FISHEYE, 190)
            for ((name, format) in listOf("360" to PictureFormat.EQ360, "fisheye" to PictureFormat.EQ180, "2d" to PictureFormat.FLAT_2D)) {
                val player = PlayerController(graph, scope)
                val item = MediaOpen.adHocItem("/tmp/Preview.mkv")
                player.previewOnly(item, listOf(item), "Preview")
                player.changeFormat(format)
                if (name == "fisheye") player.changePano(fisheye)
                player.chapters = listOf(Chapter("Opening", 0), Chapter(null, 90_000), Chapter("Part B", 720_000), Chapter("Ending", 1_320_000))
                for (lang in listOf("zh-CN", "en")) shootContent(File(out, "player_format_${name}_$lang.png"), lang, true, 360, 1100) {
                    AuraTheme(true) {
                        Column(Modifier.fillMaxSize().background(Color(0xFF1B1B21)).padding(vertical = 8.dp)) {
                            SettingsPanel(player, onClose = {}, onInfo = {}, onFlash = {})
                        }
                    }
                }
            }
        }
        if (wanted("playerguess")) {
            // The question a picture that looks like a panorama asks (ProjectionGuess), over a black picture.
            val player = PlayerController(graph, scope)
            val item = MediaOpen.adHocItem("/tmp/VID_0042.mp4")
            player.previewOnly(item, listOf(item), "VID_0042")
            player.panoGuess = com.therealityteam.videoplayer.data.playback.ProjectionGuess.Guess(
                com.therealityteam.videoplayer.data.model.ProjectionMode.EQ360, com.therealityteam.videoplayer.data.model.StereoMode.MONO,
            )
            for (lang in listOf("zh-CN", "en")) shootContent(File(out, "player_guess_$lang.png"), lang, true, 1440, 900) {
                PlayerScreen(player, fullscreen = false, onToggleFullscreen = {}, onExit = {})
            }
        }
        if (wanted("player")) groups.firstOrNull { it.isSeries }?.let { g ->
            // The controls over a black picture: nothing is opened offscreen.
            val player = PlayerController(graph, scope)
            val q = orderedQueue(g)
            player.previewOnly(q[1.coerceAtMost(q.lastIndex)], q, g.title)
            shootContent(File(out, "player_zh-CN.png"), "zh-CN", true, 1440, 900) {
                PlayerScreen(player, fullscreen = false, onToggleFullscreen = {}, onExit = {})
            }
            // The end of a file: the up-next card, with the next episode's picture.
            player.upNextIn = 4
            shootContent(File(out, "player_upnext_zh-CN.png"), "zh-CN", true, 1440, 900, settleMs = 3_000) {
                PlayerScreen(player, fullscreen = false, onToggleFullscreen = {}, onExit = {})
            }
            player.upNextIn = null
        }
        println("SNAPSHOTS in $out")
    }

    private fun shoot(
        file: File, lang: String, dark: Boolean, state: AppState, width: Int = 1440, height: Int = 900, settleMs: Long = 0,
        pointer: List<Pair<Offset, Boolean>> = emptyList(),
    ) {
        shootContent(file, lang, dark, width, height, settleMs, pointer) {
            AuraDesktop(
                state = state,
                searchFocus = remember { FocusRequester() },
                onAddFolder = {},
                chrome = { it() },
                pages = { page ->
                    when (page) {
                        Page.Sources -> SourcesPage(state, null)
                        is Page.FixMatch -> FixMatchPage(state, page.key)
                        else -> {}
                    }
                },
            )
        }
    }

    private fun shootContent(
        file: File, lang: String, dark: Boolean, width: Int, height: Int, settleMs: Long = 0,
        pointer: List<Pair<Offset, Boolean>> = emptyList(),
        content: @Composable () -> Unit,
    ) {
        val scene = ImageComposeScene(width * 2, height * 2, Density(2f)) {
            val strings = remember(lang) { Strings.forLanguage(lang) }
            CompositionLocalProvider(LocalStrings provides strings) { AuraTheme(dark) { content() } }
        }
        try {
            // Let the posters decode: render, wait, render again until a frame settles.
            var image = scene.render()
            repeat(14) { i ->
                Thread.sleep(if (i < 4) 150 else 350)
                image = scene.render(System.nanoTime())
            }
            // The pointer moved to each point (in dp), clicking where asked, the scene let run after each.
            for ((at, click) in pointer) {
                val px = Offset(at.x * 2, at.y * 2)
                scene.sendPointerEvent(PointerEventType.Move, px)
                if (click) {
                    scene.sendPointerEvent(PointerEventType.Press, px, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, px, button = PointerButton.Primary)
                }
                repeat(8) {
                    Thread.sleep(120)
                    image = scene.render(System.nanoTime())
                }
            }
            if (settleMs > 0) {
                Thread.sleep(settleMs)
                repeat(4) {
                    Thread.sleep(250)
                    image = scene.render(System.nanoTime())
                }
            }
            val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
            file.writeBytes(png.bytes)
        } finally {
            scene.close()
        }
    }
}
