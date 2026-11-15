package com.therealityteam.videoplayer.desktop.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowUpDown
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.FolderPlus
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Image as ImageIcon
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.SearchX
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.i18n.tr
import com.therealityteam.videoplayer.desktop.ui.AppState
import com.therealityteam.videoplayer.desktop.ui.Page
import com.therealityteam.videoplayer.desktop.ui.components.AuraButton
import com.therealityteam.videoplayer.desktop.ui.components.AuraSlider
import com.therealityteam.videoplayer.desktop.ui.components.ButtonKind
import com.therealityteam.videoplayer.desktop.ui.components.DropdownMenu
import com.therealityteam.videoplayer.desktop.ui.components.Icon
import com.therealityteam.videoplayer.desktop.ui.components.MenuEntry
import com.therealityteam.videoplayer.desktop.ui.components.PagedRow
import com.therealityteam.videoplayer.desktop.ui.components.ProgressLine
import com.therealityteam.videoplayer.desktop.ui.components.Spinner
import com.therealityteam.videoplayer.desktop.ui.components.Tooltip
import com.therealityteam.videoplayer.desktop.ui.components.Txt
import com.therealityteam.videoplayer.desktop.ui.components.clickCursor
import com.therealityteam.videoplayer.desktop.ui.components.interactionWash
import com.therealityteam.videoplayer.desktop.ui.components.menu
import com.therealityteam.videoplayer.desktop.ui.images.Images
import com.therealityteam.videoplayer.desktop.ui.images.rememberImage
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import com.therealityteam.videoplayer.desktop.ui.theme.AuraShapes
import com.therealityteam.videoplayer.ui.library.ContinueWatching
import com.therealityteam.videoplayer.ui.library.LibSection
import com.therealityteam.videoplayer.ui.library.LibSort
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.TaskPhase
import kotlinx.coroutines.launch

/** The poster wall of the current shelf or search, with "Continue watching" above it on All. */
@Composable
fun WallPage(
    state: AppState,
    all: List<MediaGroup>,
    shown: List<MediaGroup>,
    live: Boolean,
    libraryEmpty: Boolean,
    posterWidth: Float,
    cardMenu: (MediaGroup) -> List<MenuEntry>,
    onPlayGroup: (MediaGroup) -> Unit,
    onAddFolder: () -> Unit,
) {
    val s = LocalStrings.current
    val grid = state.wallState(state.section)
    val resume = if (live && state.section == LibSection.ALL && state.query.isBlank()) ContinueWatching.of(all) else emptyList()
    LaunchedEffect(resume.isNotEmpty()) {
        if (resume.isNotEmpty() && grid.firstVisibleItemIndex <= 1 && grid.firstVisibleItemScrollOffset < 400) grid.scrollToItem(0)
    }
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val offset = if (resume.isNotEmpty()) 1 else 0
    // Keyboard: the wall takes focus when it appears (unless the search field has it), and ↓ from
    // the search field lands on the first result.
    LaunchedEffect(Unit) { runCatching { state.wallFocus.requestFocus() } }
    LaunchedEffect(focused, state.selectFirstOnFocus, shown) {
        if (focused && state.selectFirstOnFocus && shown.isNotEmpty()) {
            state.selectFirstOnFocus = false
            state.selectedKey = shown.first().key
            grid.scrollToItem(offset)
        }
    }
    Column(Modifier.fillMaxSize()) {
        WallHeader(state, shown.size, posterWidth, live)
        Box(
            Modifier.fillMaxSize()
                .focusRequester(state.wallFocus)
                .onFocusChanged { focused = it.hasFocus }
                .focusable()
                .onPreviewKeyEvent { e ->
                    // The remembered wall shown while the library loads is a picture: nothing on it
                    // opens or plays yet, by key or by click.
                    e.type == KeyEventType.KeyDown && live && shown.isNotEmpty() &&
                        wallKey(e, state, shown, grid, offset, onPlayGroup) { target -> scope.launch { reveal(grid, target + offset) } }
                },
        ) {
            if (shown.isEmpty()) {
                EmptyWall(state, live, libraryEmpty, onAddFolder)
            } else {
                LazyVerticalGrid(
                    state = grid,
                    columns = GridCells.Adaptive(posterWidth.dp),
                    contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 6.dp, bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalArrangement = Arrangement.spacedBy(26.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (resume.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                            ContinueRow(resume) { e -> state.play(e.item, orderedQueue(e.group), title = e.group.title) }
                        }
                    }
                    items(shown, key = { it.key }) { g ->
                        PosterCard(
                            group = g,
                            live = live,
                            onOpen = { state.selectedKey = g.key; state.go(Page.Detail(g.key)) },
                            onPlay = { state.selectedKey = g.key; onPlayGroup(g) },
                            menu = { cardMenu(g) },
                            selected = focused && g.key == state.selectedKey,
                        )
                    }
                }
                VerticalScrollbar(
                    rememberScrollbarAdapter(grid),
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 3.dp),
                )
            }
        }
    }
}

/**
 * The wall's keys, as a Mac's icon views have them: arrows move between cards (row by row
 * vertically), Home/End and Page Up/Down jump, Return opens the card's page, Space plays it,
 * Esc lets go. [reveal] scrolls a card into view.
 */
private fun wallKey(
    e: KeyEvent,
    state: AppState,
    shown: List<MediaGroup>,
    grid: LazyGridState,
    offset: Int,
    onPlay: (MediaGroup) -> Unit,
    reveal: (Int) -> Unit,
): Boolean {
    val info = grid.layoutInfo
    val cards = info.visibleItemsInfo.filter { it.key != "continue" }
    val cols = (cards.maxOfOrNull { it.column } ?: 0) + 1
    val rows = cards.map { it.row }.distinct().size.coerceAtLeast(1)
    val idx = shown.indexOfFirst { it.key == state.selectedKey }
    val firstVisible = ((cards.minOfOrNull { it.index } ?: offset) - offset).coerceIn(0, shown.lastIndex)
    val current = shown.getOrNull(idx)
    val next: Int = when (e.key) {
        Key.Enter, Key.NumPadEnter -> { current?.let { state.go(Page.Detail(it.key)) }; return current != null }
        Key.Spacebar -> { current?.let(onPlay); return current != null }
        Key.Escape -> { if (idx < 0) return false; state.selectedKey = null; return true }
        Key.DirectionRight, Key.DirectionLeft, Key.DirectionDown, Key.DirectionUp, Key.MoveHome, Key.MoveEnd,
        Key.PageDown, Key.PageUp -> if (idx < 0) firstVisible else when (e.key) {
            Key.DirectionRight -> idx + 1
            Key.DirectionLeft -> idx - 1
            // Down from the last full row lands on the last card, as Finder does; up from the first stays.
            Key.DirectionDown -> if (idx + cols <= shown.lastIndex) idx + cols else if (idx / cols < shown.lastIndex / cols) shown.lastIndex else idx
            Key.DirectionUp -> if (idx - cols >= 0) idx - cols else { if (offset > 0) reveal(-offset); idx }
            Key.MoveHome -> 0
            Key.MoveEnd -> shown.lastIndex
            Key.PageDown -> idx + cols * (rows - 1).coerceAtLeast(1)
            else -> idx - cols * (rows - 1).coerceAtLeast(1)
        }
        else -> return false
    }.coerceIn(0, shown.lastIndex)
    state.selectedKey = shown[next].key
    reveal(next)
    return true
}

/** Scroll the grid just enough that item [index] is wholly in view. */
private suspend fun reveal(grid: LazyGridState, index: Int) {
    if (index <= 0) { grid.animateScrollToItem(0); return }
    val info = grid.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) { grid.animateScrollToItem(index); return }
    val margin = 24
    val top = item.offset.y
    val bottom = top + item.size.height
    when {
        top < info.viewportStartOffset + margin -> grid.animateScrollBy((top - info.viewportStartOffset - margin).toFloat())
        bottom > info.viewportEndOffset - margin -> grid.animateScrollBy((bottom - info.viewportEndOffset + margin).toFloat())
    }
}

@Composable
private fun WallHeader(state: AppState, count: Int, posterWidth: Float, live: Boolean) {
    val c = Aura.colors
    val s = LocalStrings.current
    val settings by state.graph.settings.settings.collectAsState()
    val task by state.graph.library.taskProgress.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 4.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val title = if (state.query.isNotBlank()) s["d_results_for", state.query.trim()] else s[state.section.labelKey]
        // Title and count take the room the controls leave; the controls sit at the right edge.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, Aura.type.title1, c.text, Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(12.dp))
            if (!live) {
                // The remembered wall, until the library is read.
                Spinner(12.dp, c.textTertiary, 1.5.dp)
                Spacer(Modifier.width(7.dp))
                Txt(s["library_loading"], Aura.type.callout, c.textTertiary)
            } else {
                Txt(if (count == 1) s["d_title_one"] else s["d_titles_n", count], Aura.type.callout, c.textTertiary)
            }
        }
        // With the sidebar hidden, its task card is too: the task shows here instead.
        val t = task
        if (settings.sidebarCollapsed && t != null) {
            Spacer(Modifier.width(12.dp))
            Column(Modifier.width(180.dp)) {
                Txt(
                    if (t.phase == TaskPhase.SCANNING || t.indeterminate) s["d_task_scanning"] else s["d_task_matching", t.done, t.total],
                    Aura.type.caption, c.textSecondary, maxLines = 1,
                )
                Spacer(Modifier.height(5.dp))
                ProgressLine(if (t.indeterminate) null else t.fraction)
            }
        }
        Spacer(Modifier.width(12.dp))
        SortButton(state)
        Spacer(Modifier.width(14.dp))
        Tooltip(s["d_poster_size"]) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.ImageIcon, c.textTertiary, 13.dp)
                AuraSlider(
                    value = posterWidth,
                    onChange = { v -> state.graph.settings.update { it.copy(posterWidth = v) } },
                    range = 120f..280f,
                    modifier = Modifier.width(96.dp).padding(horizontal = 8.dp),
                    trackHeight = 3.dp,
                    knobSize = 12.dp,
                    label = s["d_poster_size"],
                )
                Icon(Lucide.ImageIcon, c.textTertiary, 17.dp)
            }
        }
    }
}

@Composable
private fun SortButton(state: AppState) {
    val c = Aura.colors
    val s = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val wash = interactionWash(source, open)
    Box {
        Row(
            Modifier
                .height(30.dp)
                .clip(AuraShapes.control)
                .background(wash)
                .clickable(interactionSource = source, indication = null) { open = true }
                .clickCursor()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.ArrowUpDown, c.textSecondary, 14.dp)
            Spacer(Modifier.width(7.dp))
            Txt(s[state.sort.labelKey], Aura.type.callout, c.text)
            Spacer(Modifier.width(4.dp))
            Icon(Lucide.ChevronDown, c.textTertiary, 13.dp)
        }
        DropdownMenu(
            expanded = open,
            onDismiss = { open = false },
            alignEnd = true,
            entries = menu {
                header(s["d_sort_by"])
                LibSort.entries.forEach { sort ->
                    check(s[sort.labelKey], state.sort == sort, detail = s[sort.hintKey]) { state.setSort(sort) }
                }
            },
        )
    }
}

@Composable
private fun EmptyWall(state: AppState, live: Boolean, libraryEmpty: Boolean, onAddFolder: () -> Unit) {
    val c = Aura.colors
    val s = LocalStrings.current
    Column(
        Modifier.fillMaxSize().padding(bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            !live -> {
                Spinner(28.dp)
                Spacer(Modifier.height(16.dp))
                Txt(s["library_loading"], Aura.type.body, c.textSecondary)
            }
            libraryEmpty && state.query.isBlank() -> {
                Icon(Lucide.FolderPlus, c.accent, 44.dp)
                Spacer(Modifier.height(18.dp))
                Txt(s["d_empty_title"], Aura.type.title1, c.text)
                Spacer(Modifier.height(8.dp))
                Txt(s["d_empty_body"], Aura.type.body, c.textSecondary, Modifier.widthIn(max = 460.dp), maxLines = 4, align = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AuraButton(s["d_add_folder"], onAddFolder, kind = ButtonKind.Primary, icon = Lucide.FolderPlus)
                    AuraButton(s["d_other_sources"], { state.go(Page.Sources) }, icon = Lucide.HardDrive)
                }
            }
            else -> {
                Icon(Lucide.SearchX, c.textTertiary, 38.dp)
                Spacer(Modifier.height(14.dp))
                val msg = when {
                    state.query.isNotBlank() -> s["lib_empty_search", state.query.trim()]
                    state.section == LibSection.FAVORITES -> s["lib_empty_favorites"]
                    state.section == LibSection.HISTORY -> s["lib_empty_history"]
                    else -> s["library_empty_in", s[state.section.labelKey]]
                }
                Txt(msg, Aura.type.body, c.textSecondary, Modifier.widthIn(max = 460.dp), maxLines = 3, align = TextAlign.Center)
                if (state.query.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Txt(s["d_no_match_body"], Aura.type.callout, c.textTertiary)
                }
            }
        }
    }
}

/** The files of a card in the order the detail page lists them — the queue a play gets. */
fun orderedQueue(g: MediaGroup): List<MediaItem> {
    val numberedRun = g.isSeries && g.key.startsWith("dir:")
    return g.items.sortedWith(
        compareBy({ com.therealityteam.videoplayer.ui.library.EpisodeNaming.sortKey(it, numberedRun) }, { it.fileName.lowercase() }),
    )
}

@Composable
private fun ContinueRow(entries: List<ContinueWatching.Entry>, onPlay: (ContinueWatching.Entry) -> Unit) {
    val c = Aura.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Txt(tr("cw_title"), Aura.type.title2, c.text, Modifier.padding(bottom = 12.dp))
        // The pictures are 300 dp wide at 16:9; the arrows sit at their middle.
        PagedRow(entries, key = { it.group.key + "/" + it.item.id }, gutter = 28.dp, arrowCenter = 84.dp) { e -> ContinueCard(e) { onPlay(e) } }
    }
}

@Composable
private fun ContinueCard(entry: ContinueWatching.Entry, onClick: () -> Unit) {
    val c = Aura.colors
    val s = LocalStrings.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(Modifier.width(300.dp).hoverable(source)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .shadow(if (hovered) 16.dp else 5.dp, AuraShapes.card)
                .clip(AuraShapes.card)
                .background(c.surface)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .clickCursor(),
        ) {
            val px = with(LocalDensity.current) { maxWidth.roundToPx() }
            // A frame of the video only when there is no other picture: a film's backdrop is better.
            val bare = entry.group.backdropPath == null && entry.item.backdropPath == null && entry.group.posterPath == null
            val still by produceState<ImageBitmap?>(null, entry.item.id) { value = Images.still(entry.item, px, frames = bare) }
            val backdrop by rememberImage(entry.group.backdropPath ?: entry.item.backdropPath, px)
            val poster by rememberImage(entry.group.posterPath, px)
            val img = still ?: backdrop ?: poster
            if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else TitleTile(entry.group.title)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xB0000000))))
            if (hovered) {
                Box(
                    Modifier.align(Alignment.Center).padding(bottom = 6.dp).clip(AuraShapes.pill).background(Color(0xE6FFFFFF))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.Play, Color(0xFF111111), 14.dp)
                        Spacer(Modifier.width(6.dp))
                        Txt(s[if (entry.nextUp) "d_play" else "d_resume"], Aura.type.callout, Color(0xFF111111))
                    }
                }
            }
            val p = entry.item.progress
            if (!entry.nextUp && p.fraction > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp).height(3.dp).clip(AuraShapes.pill).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxWidth(p.fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
                }
            }
        }
        Txt(entry.group.title, Aura.type.bodyStrong, c.text, Modifier.padding(top = 8.dp, start = 2.dp))
        Txt(continueSubtitle(s, entry), Aura.type.caption, c.textTertiary, Modifier.padding(start = 2.dp, top = 1.dp))
    }
}

/** "Episode 5 · 42 min left", "Next up · Episode 6" — the headset's ContinueWatching.subtitle. */
private fun continueSubtitle(s: com.therealityteam.videoplayer.desktop.i18n.Strings, entry: ContinueWatching.Entry): String {
    val sub = ContinueWatching.sub(entry)
    val numberedRun = entry.group.isSeries && entry.group.key.startsWith("dir:")
    val where = sub.episode?.let { episodeLabel(s, it, numberedRun) }
    val tail = when {
        sub.nextUp -> null
        sub.minutesLeft != null -> s.plural("cw_left_min", sub.minutesLeft!!)
        else -> s["cw_seen_pct", sub.percent ?: 0]
    }
    val head = if (sub.nextUp) s["cw_next_up"] else null
    return listOfNotNull(head, where, tail).joinToString(" · ")
}
