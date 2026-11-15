package com.therealityteam.videoplayer.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.X
import com.therealityteam.videoplayer.desktop.i18n.tr
import com.therealityteam.videoplayer.desktop.platform.Host
import com.therealityteam.videoplayer.desktop.ui.AppState
import com.therealityteam.videoplayer.desktop.ui.Page
import com.therealityteam.videoplayer.desktop.ui.components.AuraTextField
import com.therealityteam.videoplayer.desktop.ui.components.Icon
import com.therealityteam.videoplayer.desktop.ui.components.ProgressLine
import com.therealityteam.videoplayer.desktop.ui.components.Txt
import com.therealityteam.videoplayer.desktop.ui.components.clickCursor
import com.therealityteam.videoplayer.desktop.ui.components.interactionWash
import com.therealityteam.videoplayer.desktop.ui.library.icon
import com.therealityteam.videoplayer.desktop.ui.library.labelKey
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import com.therealityteam.videoplayer.desktop.ui.theme.AuraShapes
import com.therealityteam.videoplayer.ui.library.LibSection
import com.therealityteam.videoplayer.ui.library.PRIMARY_SECTIONS
import com.therealityteam.videoplayer.ui.library.SECONDARY_SECTIONS
import com.therealityteam.videoplayer.ui.library.TaskPhase
import com.therealityteam.videoplayer.ui.library.TaskProgress

/** How far the window's own buttons reach into the content on a Mac (the traffic lights). */
val TitleBarInset = if (Host.isMac) 38.dp else 0.dp

const val SidebarWidth = 236

/**
 * The sidebar: search, the shelves, your lists, and the library's own tools — with how many
 * titles each shelf holds, and the running task at the bottom where it is always visible.
 */
@Composable
fun Sidebar(
    state: AppState,
    counts: Map<LibSection, Int>,
    progress: TaskProgress?,
    searchFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val c = Aura.colors
    val showAdult = state.graph.settings.settings.value.showAdult
    Column(
        modifier
            .width(SidebarWidth.dp)
            .fillMaxHeight()
            .background(c.sidebar)
            .padding(top = TitleBarInset),
    ) {
        AuraTextField(
            value = state.query,
            onValueChange = { state.query = it; if (state.page != Page.Wall) state.show(state.section) },
            placeholder = tr("d_search_library"),
            leading = Lucide.Search,
            trailing = if (state.query.isNotEmpty()) {
                { Box(Modifier.clickable { state.query = "" }.clickCursor()) { Icon(Lucide.X, c.textTertiary, 14.dp) } }
            } else null,
            fill = c.hover,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 10.dp).focusRequester(searchFocus)
                .onPreviewKeyEvent { e ->
                    // Into the results, as a Mac's search fields do: ↓ or Return lands on the first.
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionDown || e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                        state.focusWall(selectFirst = true)
                        true
                    } else false
                },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        ) {
            Group(tr("nav_group_library"))
            PRIMARY_SECTIONS.forEach { s -> Shelf(state, s, counts[s]) }
            val secondary = SECONDARY_SECTIONS.filter { it != LibSection.ADULT || showAdult }
            if (state.moreShelves || state.section in secondary) {
                secondary.forEach { s -> Shelf(state, s, counts[s]) }
                NavRow(Lucide.ChevronUp, tr("d_show_less"), null, selected = false, muted = true) { state.moreShelves = false }
            } else {
                NavRow(Lucide.Ellipsis, tr("nav_more"), null, selected = false, muted = true) { state.moreShelves = true }
            }
            Spacer(Modifier.height(10.dp))
            Group(tr("nav_group_mine"))
            Shelf(state, LibSection.FAVORITES, counts[LibSection.FAVORITES])
            Shelf(state, LibSection.HISTORY, counts[LibSection.HISTORY])
            Spacer(Modifier.height(10.dp))
            Group(tr("nav_group_manage"))
            NavRow(Lucide.HardDrive, tr("nav_sources"), null, selected = state.page == Page.Sources) { state.go(Page.Sources) }
            NavRow(Lucide.RefreshCw, tr("nav_scrape"), null, selected = false, enabled = progress == null) { state.graph.library.rescrape() }
            NavRow(Lucide.Settings, tr("nav_settings"), null, selected = state.settingsOpen) { state.settingsOpen = true }
            Spacer(Modifier.height(12.dp))
        }
        if (progress != null) TaskCard(progress)
    }
}

@Composable
private fun Group(label: String) {
    Txt(label, Aura.type.micro, Aura.colors.textTertiary, Modifier.padding(start = 10.dp, top = 6.dp, bottom = 4.dp))
}

@Composable
private fun Shelf(state: AppState, section: LibSection, count: Int?) {
    val selected = state.page == Page.Wall && state.section == section ||
        (state.page is Page.Detail && state.section == section)
    NavRow(section.icon, tr(section.labelKey), count, selected) {
        state.show(section)
    }
}

@Composable
private fun NavRow(
    icon: ImageVector, label: String, count: Int?, selected: Boolean,
    muted: Boolean = false, enabled: Boolean = true, onClick: () -> Unit,
) {
    val c = Aura.colors
    val source = remember { MutableInteractionSource() }
    val wash = interactionWash(source, selected)
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(AuraShapes.control)
            .background(wash)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .clickCursor()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val tint = when {
            !enabled -> c.textTertiary
            selected -> c.accent
            muted -> c.textTertiary
            else -> c.textSecondary
        }
        Icon(icon, tint, 16.dp)
        Txt(label, Aura.type.callout, if (!enabled) c.textTertiary else if (muted) c.textSecondary else c.text, Modifier.weight(1f))
        if (count != null && count > 0) Txt(count.toString(), Aura.type.caption, c.textTertiary)
    }
}

@Composable
private fun TaskCard(progress: TaskProgress) {
    val c = Aura.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(AuraShapes.card)
            .background(c.surface)
            .padding(12.dp),
    ) {
        val text = when {
            progress.phase == TaskPhase.SCANNING || progress.indeterminate -> tr("d_task_scanning")
            else -> tr("d_task_matching", progress.done, progress.total)
        }
        Txt(text, Aura.type.callout, c.text)
        Spacer(Modifier.height(8.dp))
        ProgressLine(if (progress.indeterminate) null else progress.fraction)
    }
}
