package com.therealityteam.videoplayer.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PanelLeft
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.platform.Desktop
import com.therealityteam.videoplayer.desktop.ui.components.IconButton
import com.therealityteam.videoplayer.desktop.ui.components.Txt
import com.therealityteam.videoplayer.desktop.ui.components.clickCursor
import com.therealityteam.videoplayer.desktop.ui.components.menu
import com.therealityteam.videoplayer.desktop.ui.library.DetailPage
import com.therealityteam.videoplayer.desktop.ui.library.WallPage
import com.therealityteam.videoplayer.desktop.ui.library.orderedQueue
import com.therealityteam.videoplayer.desktop.ui.shell.Sidebar
import com.therealityteam.videoplayer.desktop.ui.shell.TitleBarInset
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import com.therealityteam.videoplayer.desktop.ui.theme.AuraShapes
import com.therealityteam.videoplayer.ui.library.DetailPlay
import com.therealityteam.videoplayer.ui.library.LibSection
import com.therealityteam.videoplayer.ui.library.LibSort
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import java.io.File

/**
 * The library window's content: sidebar, the page, and the thin chrome above it that carries the
 * back button (and the Mac's window buttons, drawn over it by the system).
 *
 * [chrome] wraps the top strip so the window can be dragged by it (the caller's WindowDraggableArea).
 */
@Composable
fun AuraDesktop(
    state: AppState,
    searchFocus: FocusRequester,
    onAddFolder: () -> Unit,
    chrome: @Composable (content: @Composable () -> Unit) -> Unit,
    pages: @Composable (Page) -> Unit = {},
) {
    val c = Aura.colors
    val lib = state.graph.library
    val settings by state.graph.settings.settings.collectAsState()
    val snapshot by lib.wallSnapshot.collectAsState()
    val groups by lib.groups.collectAsState()
    val searchable by lib.searchableGroups.collectAsState()
    val live by lib.wallLive.collectAsState()
    val items by lib.items.collectAsState()
    val progress by lib.taskProgress.collectAsState()

    val wallGroups = when {
        !live -> snapshot
        searchable.size == groups.size -> searchable
        else -> groups
    }
    val sort = LibSort.entries.firstOrNull { it.name == settings.librarySort } ?: LibSort.RECENT
    val shown = remember(wallGroups, state.section, state.query, sort, settings.showAdult) {
        LibraryGrouping.filter(wallGroups, state.section, state.query, sort, settings.showAdult)
    }
    val counts = remember(wallGroups, settings.showAdult) {
        LibSection.entries.associateWith { LibraryGrouping.filter(wallGroups, it, "", LibSort.RECENT, settings.showAdult).size }
    }
    val s = LocalStrings.current

    Box(Modifier.fillMaxSize().background(c.background)) {
        Row(Modifier.fillMaxSize()) {
            if (!settings.sidebarCollapsed) {
                Sidebar(state, counts, progress, searchFocus)
                Box(Modifier.width(1.dp).fillMaxSize().background(c.stroke))
            }
            Column(Modifier.weight(1f).fillMaxSize()) {
                chrome {
                    Row(
                        Modifier.fillMaxWidth().height(TitleBarInset.coerceAtLeast(10.dp) + 8.dp)
                            .padding(start = if (settings.sidebarCollapsed) sidebarGap() else 12.dp, end = 12.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            Lucide.PanelLeft, s[if (settings.sidebarCollapsed) "d_show_sidebar" else "d_hide_sidebar"],
                            { state.graph.settings.update { it.copy(sidebarCollapsed = !it.sidebarCollapsed) } },
                            size = 28.dp, iconSize = 16.dp, tint = c.textSecondary,
                        )
                        if (state.canGoBack) {
                            Spacer(Modifier.width(4.dp))
                            IconButton(Lucide.ArrowLeft, s["d_go_back"], { state.back() }, size = 28.dp, iconSize = 16.dp, tint = c.textSecondary)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val page = state.page) {
                        Page.Wall -> WallPage(
                            state = state,
                            all = wallGroups,
                            shown = shown,
                            live = live,
                            libraryEmpty = items.isEmpty() && live,
                            posterWidth = settings.posterWidth,
                            cardMenu = { g -> cardMenu(state, s, g) },
                            onPlayGroup = { g -> playGroup(state, g) },
                            onAddFolder = onAddFolder,
                        )
                        is Page.Detail -> {
                            val g = wallGroups.firstOrNull { it.key == page.key }
                            if (g != null) DetailPage(state, g) else pages(page)
                        }
                        else -> pages(page)
                    }
                }
            }
        }
        ToastHost(state)
    }
}

/** Room for the Mac's window buttons when the sidebar that normally sits under them is hidden. */
private fun sidebarGap() = if (com.therealityteam.videoplayer.desktop.platform.Host.isMac) 78.dp else 12.dp

/** Play what a card's page would play: the part-watched file, else the next one, else the first. */
fun playGroup(state: AppState, g: MediaGroup) {
    val numberedRun = g.isSeries && g.key.startsWith("dir:")
    val ordered = orderedQueue(g)
    val choice = DetailPlay.choose(ordered, numberedRun) ?: return
    state.play(choice.item, ordered, title = g.title)
}

private fun cardMenu(state: AppState, s: com.therealityteam.videoplayer.desktop.i18n.Strings, g: MediaGroup) = menu {
    val lib = state.graph.library
    val ids = g.items.map { it.id }
    val resume = g.items.any { it.progress.started && !it.progress.watched }
    item(s[if (resume) "d_resume" else "d_play"], Lucide.Play) { playGroup(state, g) }
    item(s["d_show_details"], Lucide.Info) { state.go(Page.Detail(g.key)) }
    separator()
    val allWatched = g.items.all { it.progress.watched }
    item(s[if (allWatched) "det_mark_all_unwatched" else "det_mark_all_watched"]) {
        lib.setWatched(ids, !allWatched)
        state.toast(s[if (allWatched) "d_marked_unwatched" else "d_marked_watched"], s["d_undo"]) { lib.setWatched(ids, allWatched) }
    }
    val fav = g.items.any { it.favorite }
    item(s[if (fav) "d_remove_favorite" else "d_add_favorite"], Lucide.Heart) { lib.setGroupFavorite(ids, !fav) }
    if (!g.key.startsWith("jellyfin") && !g.key.startsWith("plex") && !g.key.startsWith("emby")) {
        item(s["det_fix_match"], Lucide.Pencil) { state.go(Page.FixMatch(g.key)) }
    }
    val path = g.representative.path
    separator()
    if (Desktop.canReveal(path)) item(Desktop.revealLabel(s)) { Desktop.reveal(path) }
    item(s["d_copy_path"]) { Desktop.copy(path); state.toast(s["d_copied"]) }
}

/** Add [folder] as a local source and scan it. */
fun addFolder(state: AppState, folder: File) {
    val path = folder.absolutePath
    val existing = state.graph.sources.sources.value.firstOrNull { it.type == SourceType.LOCAL && it.uri == path }
    val config = existing ?: MediaSourceConfig(
        id = "local:$path", type = SourceType.LOCAL, name = folder.name.ifBlank { path }, uri = path,
        addedAt = System.currentTimeMillis(),
    )
    state.graph.library.addSource(config)
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ToastHost(state: AppState) {
    val c = Aura.colors
    val t = state.toast
    AnimatedVisibility(
        visible = t != null,
        enter = fadeIn(tween(150)) + slideInVertically(tween(180)) { it / 2 },
        exit = fadeOut(tween(150)),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
    ) {
        val toast = t ?: return@AnimatedVisibility
        Row(
            Modifier
                .shadow(16.dp, AuraShapes.pill)
                .clip(AuraShapes.pill)
                .background(c.raised)
                .border(1.dp, c.stroke, AuraShapes.pill)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(toast.text, Aura.type.callout, c.text)
            if (toast.action != null) {
                Spacer(Modifier.width(16.dp))
                Txt(
                    toast.action, Aura.type.callout.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), c.accent,
                    Modifier.clickable { toast.onAction?.invoke(); state.dismissToast() }.clickCursor(),
                )
            }
        }
    }
}
