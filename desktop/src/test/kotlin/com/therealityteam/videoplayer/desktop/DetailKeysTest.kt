package com.therealityteam.videoplayer.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.desktop.ui.AppState
import com.therealityteam.videoplayer.desktop.ui.AuraDesktop
import com.therealityteam.videoplayer.desktop.ui.Page
import com.therealityteam.videoplayer.desktop.ui.theme.AuraTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A series' page driven from the keyboard alone. */
@OptIn(ExperimentalTestApi::class)
class DetailKeysTest {

    @Test
    fun `arrows choose an episode, Return plays it, Esc lets go then goes back`() = runDesktopComposeUiTest(1200, 900) {
        val graph = AppGraph(Files.createTempDirectory("aura-detail-keys").toFile())
        val eps = (1..5).map { e -> Triple(1, e, "Show.S01E0$e.mkv") } + (1..2).map { e -> Triple(2, e, "Show.S02E0$e.mkv") }
        graph.media.upsertAll(eps.map { (season, e, file) ->
            MediaItem(
                id = "s${season}e$e", sourceId = "s", path = "/v/Show/$file", fileName = file, title = "Show",
                kind = MediaKind.EPISODE, season = season, episode = e, showTitle = "Show", tmdbId = 42,
            )
        })
        val key = runBlocking { withTimeout(30_000) { graph.library.groups.first { it.isNotEmpty() } } }.first().key
        val state = AppState(graph, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        setContent {
            CompositionLocalProvider(LocalStrings provides Strings.forLanguage("en")) {
                AuraTheme(true) { AuraDesktop(state = state, searchFocus = remember { FocusRequester() }, onAddFolder = {}, chrome = { it() }) }
            }
        }
        state.go(Page.Detail(key))
        waitForIdle()
        fun press(k: Key) { onRoot().performKeyInput { pressKey(k) }; waitForIdle() }

        // Nothing chosen: Space plays what the page's Play button would — the first episode.
        press(Key.Spacebar)
        assertEquals("s1e1", state.playing?.item?.id)
        state.stopPlaying()

        press(Key.DirectionDown); press(Key.DirectionDown)
        // -Daura.snapshots=<dir>: the page with the row chosen, to look at.
        System.getProperty("aura.snapshots")?.let { dir ->
            val png = org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
                .encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
            java.io.File(dir).apply { mkdirs() }.resolve("detail_keys.png").writeBytes(png)
        }
        press(Key.Enter)
        assertEquals("s1e2", state.playing?.item?.id, "the second row")
        state.stopPlaying()

        // Season 2: → moves to it, the choice to its first row.
        press(Key.DirectionRight)
        press(Key.Enter)
        assertEquals("s2e1", state.playing?.item?.id)
        state.stopPlaying()

        press(Key.Escape)
        assertEquals(Page.Detail(key), state.page, "the first Esc lets the row go")
        press(Key.Escape)
        assertEquals(Page.Wall, state.page, "the second goes back")
        assertNull(state.playing)
        // And the wall has the keyboard again: an arrow chooses its card.
        press(Key.DirectionRight)
        assertEquals(key, state.selectedKey)
    }
}
