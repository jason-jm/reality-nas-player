package com.therealityteam.videoplayer.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.desktop.ui.AppState
import com.therealityteam.videoplayer.desktop.ui.Page
import com.therealityteam.videoplayer.desktop.ui.library.WallPage
import com.therealityteam.videoplayer.desktop.ui.theme.AuraTheme
import com.therealityteam.videoplayer.ui.library.MediaGroup
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

/** The wall driven from the keyboard alone, in a 1000 × 800 window (five posters to a row). */
@OptIn(ExperimentalTestApi::class)
class WallKeysTest {

    @Test
    fun `arrows move between cards, Return opens one, Space plays one, Esc lets go`() = runDesktopComposeUiTest(1000, 800) {
        val graph = AppGraph(Files.createTempDirectory("aura-keys").toFile())
        // Seventeen unrelated films, each in a folder of its own (numbered names would be one card).
        val names = listOf(
            "Arrival", "Brazil", "Casablanca", "Dunkirk", "Election", "Fargo", "Gravity", "Heat", "Inception",
            "Jaws", "Klute", "Laura", "Metropolis", "Network", "Oldboy", "Psycho", "Rashomon",
        )
        graph.media.upsertAll(names.mapIndexed { i, n ->
            MediaItem(id = "m$i", sourceId = "s", path = "/v/$n/$n.mkv", fileName = "$n.mkv", title = n, kind = MediaKind.MOVIE, year = 1950 + i * 3)
        })
        val groups = runBlocking { withTimeout(30_000) { graph.library.groups.first { it.size >= names.size } } }
        val state = AppState(graph, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        var played: MediaGroup? = null
        setContent {
            CompositionLocalProvider(LocalStrings provides Strings.forLanguage("en")) {
                AuraTheme(true) {
                    WallPage(
                        state = state, all = groups, shown = groups, live = true, libraryEmpty = false, posterWidth = 168f,
                        cardMenu = { emptyList() }, onPlayGroup = { played = it }, onAddFolder = {},
                    )
                }
            }
        }
        waitForIdle()
        fun press(key: Key) { onRoot().performKeyInput { pressKey(key) }; waitForIdle() }
        fun at() = groups.indexOfFirst { it.key == state.selectedKey }

        press(Key.DirectionRight)
        assertEquals(0, at(), "the first key lands on the first card")
        press(Key.DirectionRight)
        assertEquals(1, at())
        press(Key.DirectionDown)
        assertEquals(6, at(), "down is one row of five")
        press(Key.DirectionUp); press(Key.DirectionUp)
        assertEquals(1, at(), "up from the first row stays")
        press(Key.MoveEnd)
        assertEquals(16, at())
        press(Key.DirectionUp); press(Key.DirectionDown)
        assertEquals(16, at())
        // From the second-to-last row's end (index 14), down has no card below: it lands on the last.
        press(Key.DirectionLeft); press(Key.DirectionLeft)
        assertEquals(14, at())
        press(Key.DirectionUp); press(Key.DirectionDown)
        assertEquals(14, at())
        press(Key.Spacebar)
        assertEquals(groups[14].key, played?.key)
        press(Key.Enter)
        assertEquals(Page.Detail(groups[14].key), state.page)
        state.back()
        waitForIdle()
        press(Key.Escape)
        assertNull(state.selectedKey)
    }

    @Test
    fun `the remembered wall shown while the library loads takes no keys`() = runDesktopComposeUiTest(1000, 800) {
        val graph = AppGraph(Files.createTempDirectory("aura-keys-cold").toFile())
        graph.media.upsertAll(listOf("Arrival", "Brazil").mapIndexed { i, n ->
            MediaItem(id = "m$i", sourceId = "s", path = "/v/$n/$n.mkv", fileName = "$n.mkv", title = n, kind = MediaKind.MOVIE)
        })
        val groups = runBlocking { withTimeout(30_000) { graph.library.groups.first { it.size >= 2 } } }
        val state = AppState(graph, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        var played: MediaGroup? = null
        setContent {
            CompositionLocalProvider(LocalStrings provides Strings.forLanguage("en")) {
                AuraTheme(true) {
                    WallPage(
                        state = state, all = groups, shown = groups, live = false, libraryEmpty = false, posterWidth = 168f,
                        cardMenu = { emptyList() }, onPlayGroup = { played = it }, onAddFolder = {},
                    )
                }
            }
        }
        waitForIdle()
        runCatching { state.wallFocus.requestFocus() }
        waitForIdle()
        for (k in listOf(Key.DirectionRight, Key.Spacebar, Key.Enter)) {
            onRoot().performKeyInput { pressKey(k) }
            waitForIdle()
        }
        assertNull(state.selectedKey, "nothing is selected")
        assertNull(played, "nothing plays")
        assertEquals(Page.Wall, state.page, "nothing opens")
        onNodeWithText("Loading your library…").assertExists()
    }
}
