package com.therealityteam.videoplayer.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.desktop.player.PlayerController
import com.therealityteam.videoplayer.desktop.player.PlayerScreen
import com.therealityteam.videoplayer.desktop.player.SettingsPanel
import com.therealityteam.videoplayer.desktop.ui.AppState
import com.therealityteam.videoplayer.desktop.ui.AuraDesktop
import com.therealityteam.videoplayer.desktop.ui.Page
import com.therealityteam.videoplayer.desktop.ui.library.orderedQueue
import com.therealityteam.videoplayer.desktop.ui.settings.SettingsContent
import com.therealityteam.videoplayer.desktop.ui.sources.SourcesPage
import com.therealityteam.videoplayer.desktop.ui.theme.AuraTheme
import com.therealityteam.videoplayer.ui.library.LibSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What VoiceOver (or Narrator) has to say about each control: every button, switch and field on
 * the main screens must carry words — its text, or a description when it is only an icon. An
 * unnamed control is read as "button" and nothing more.
 */
@OptIn(ExperimentalTestApi::class)
class AccessibilityTest {

    @Test
    fun `every control on the main screens has a name`() = runDesktopComposeUiTest(1440, 900) {
        val graph = AppGraph(Files.createTempDirectory("aura-a11y").toFile())
        graph.media.upsertAll(
            listOf("Arrival", "Brazil", "Casablanca").mapIndexed { i, n ->
                MediaItem(
                    id = "m$i", sourceId = "s", path = "/v/$n/$n.mkv", fileName = "$n.mkv", title = n, kind = MediaKind.MOVIE,
                    year = 1980 + i, scrapeStatus = ScrapeStatus.MATCHED, overview = "A film.", runtime = 100,
                )
            } + (1..3).map { e ->
                MediaItem(
                    id = "e$e", sourceId = "s", path = "/v/Show/Show.S01E0$e.mkv", fileName = "Show.S01E0$e.mkv", title = "Show",
                    kind = MediaKind.EPISODE, season = 1, episode = e, showTitle = "Show",
                )
            },
        )
        val groups = runBlocking { withTimeout(30_000) { graph.library.groups.first { it.size >= 4 } } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val state = AppState(graph, scope)
        val screen = mutableStateOf<@Composable () -> Unit>({})
        setContent {
            CompositionLocalProvider(LocalStrings provides Strings.forLanguage("en")) {
                AuraTheme(true) { screen.value() }
            }
        }
        val problems = mutableListOf<String>()
        fun look(name: String, content: @Composable () -> Unit) {
            screen.value = content
            waitForIdle()
            problems += unnamed(name)
        }
        val desktop: @Composable () -> Unit = {
            AuraDesktop(
                state = state, searchFocus = remember { FocusRequester() }, onAddFolder = {}, chrome = { it() },
                pages = { page -> if (page == Page.Sources) SourcesPage(state, null) },
            )
        }
        state.show(LibSection.ALL)
        look("wall", desktop)
        state.go(Page.Detail(groups.first { !it.isSeries }.key))
        look("film", desktop)
        state.back()
        state.go(Page.Detail(groups.first { it.isSeries }.key))
        look("series", desktop)
        state.back()
        state.go(Page.Sources)
        look("sources", desktop)
        state.back()
        for (page in listOf("GENERAL", "SCRAPING", "PLAYBACK", "LIBRARY", "SYNC", "AI", "PRIVACY", "ABOUT")) {
            look("settings/$page") { key(page) { SettingsContent(state, page) } }
        }
        val series = groups.first { it.isSeries }
        val q = orderedQueue(series)
        val player = PlayerController(graph, scope)
        player.previewOnly(q[1], q, series.title)
        look("player") { PlayerScreen(player, fullscreen = false, onToggleFullscreen = {}, onExit = {}) }
        // The volume can be read and moved, not only clicked; the seek bar is named (nothing is
        // loaded here, so it has no length to move along yet).
        val adjustable = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).fetchSemanticsNodes().size
        assertTrue(adjustable >= 1, "the player has $adjustable adjustable controls")
        assertTrue(onAllNodes(hasContentDescription("Playback position")).fetchSemanticsNodes().size == 1, "the seek bar has a name")
        look("player settings") { Column { SettingsPanel(player, onClose = {}, onInfo = {}, onFlash = {}) } }

        problems.forEach { println("A11Y $it") }
        assertTrue(problems.isEmpty(), "${problems.size} controls without a name:\n" + problems.joinToString("\n"))
    }

    /** Controls — clickable, toggleable, editable — with no words for a screen reader. */
    private fun ComposeUiTest.unnamed(screen: String): List<String> {
        val control = SemanticsMatcher("a control") {
            it.config.getOrNull(SemanticsActions.OnClick) != null ||
                it.config.getOrNull(SemanticsActions.SetProgress) != null ||
                it.config.getOrNull(SemanticsProperties.ToggleableState) != null ||
                it.config.getOrNull(SemanticsActions.SetText) != null
        }
        return onAllNodes(control).fetchSemanticsNodes().filter { !it.named() }.map { n ->
            val role = n.config.getOrNull(SemanticsProperties.Role)
            val b = n.boundsInRoot
            "$screen: ${role ?: "?"} at (${b.left.toInt()}, ${b.top.toInt()}) ${b.width.toInt()}×${b.height.toInt()}" +
                (n.parent?.let { p -> " under ${p.words() ?: "…"}" } ?: "")
        }
    }

    private fun SemanticsNode.named(): Boolean = words() != null || config.getOrNull(SemanticsProperties.EditableText) != null

    private fun SemanticsNode.words(): String? {
        val desc = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        return listOfNotNull(desc, text).joinToString(" ").takeIf { it.isNotBlank() }
    }
}
