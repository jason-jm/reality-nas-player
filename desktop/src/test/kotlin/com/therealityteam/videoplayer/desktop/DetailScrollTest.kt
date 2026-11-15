package com.therealityteam.videoplayer.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
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

/** A card's page keeps its place while a video plays over it, and starts at the top when opened afresh. */
@OptIn(ExperimentalTestApi::class)
class DetailScrollTest {

    @Test
    fun `the way back from the player lands where the page was left`() = runDesktopComposeUiTest(1200, 800) {
        val graph = AppGraph(Files.createTempDirectory("aura-detail-scroll").toFile())
        graph.media.upsertAll((1..30).map { e ->
            MediaItem(
                id = "e$e", sourceId = "s", path = "/v/Show/Show.S01E%02d.mkv".format(e), fileName = "Show.S01E%02d.mkv".format(e),
                title = "Show", kind = MediaKind.EPISODE, season = 1, episode = e, showTitle = "Show",
            )
        })
        val key = runBlocking { withTimeout(30_000) { graph.library.groups.first { it.isNotEmpty() } } }.first().key
        val state = AppState(graph, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        var playing by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalStrings provides Strings.forLanguage("en")) {
                AuraTheme(true) {
                    // As in the app: while a video plays, the library is not drawn at all.
                    if (playing) Box {} else AuraDesktop(state = state, searchFocus = remember { FocusRequester() }, onAddFolder = {}, chrome = { it() })
                }
            }
        }
        state.go(Page.Detail(key))
        waitForIdle()
        runBlocking { state.detailScroll(key).scrollTo(900) }
        waitForIdle()

        playing = true
        waitForIdle()
        playing = false
        waitForIdle()
        assertEquals(900, state.detailScroll(key).value, "back from the player, the page is where it was")

        state.back()
        waitForIdle()
        state.go(Page.Detail(key))
        waitForIdle()
        assertEquals(0, state.detailScroll(key).value, "opened afresh from the wall, it starts at the top")
    }
}
