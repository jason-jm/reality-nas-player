package com.therealityteam.videoplayer.desktop.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Undo2
import com.therealityteam.videoplayer.data.model.CrewRoles
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.scrape.GenreCn
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.desktop.platform.Desktop
import com.therealityteam.videoplayer.desktop.ui.AppState
import com.therealityteam.videoplayer.desktop.ui.Page
import com.therealityteam.videoplayer.desktop.ui.components.AuraButton
import com.therealityteam.videoplayer.desktop.ui.components.ButtonKind
import com.therealityteam.videoplayer.desktop.ui.components.DropdownMenu
import com.therealityteam.videoplayer.desktop.ui.components.Icon
import com.therealityteam.videoplayer.desktop.ui.components.IconButton
import com.therealityteam.videoplayer.desktop.ui.components.PagedRow
import com.therealityteam.videoplayer.desktop.ui.components.Segmented
import com.therealityteam.videoplayer.desktop.ui.components.Txt
import com.therealityteam.videoplayer.desktop.ui.components.WithContextMenu
import com.therealityteam.videoplayer.desktop.ui.components.clickCursor
import com.therealityteam.videoplayer.desktop.ui.components.interactionWash
import com.therealityteam.videoplayer.desktop.ui.components.menu
import com.therealityteam.videoplayer.desktop.ui.images.Images
import com.therealityteam.videoplayer.desktop.ui.images.rememberImage
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import com.therealityteam.videoplayer.desktop.ui.theme.AuraShapes
import com.therealityteam.videoplayer.ui.library.DetailPlay
import com.therealityteam.videoplayer.ui.library.DetailSections
import com.therealityteam.videoplayer.ui.library.MediaGroup
import kotlinx.coroutines.launch

/**
 * A title's page: the backdrop across the top, the poster and what is known about the work, the
 * play button that knows where you are, cast and crew, and every file — by season for a show.
 */
@Composable
fun DetailPage(state: AppState, group: MediaGroup) {
    val c = Aura.colors
    val s = LocalStrings.current
    val numberedRun = group.isSeries && group.key.startsWith("dir:")
    val ordered = remember(group) { group.items.sortedWith(DetailSections.order(numberedRun)) }
    val rep = group.representative
    val scroll = remember(group.key) { state.detailScroll(group.key) }
    val sections = remember(group) { detailSections(group, ordered, numberedRun) }
    // Open on the section with something part-watched, as the headset does.
    val keys = remember(group.key) {
        DetailKeys(sections.indexOfFirst { sec -> sec.items.any { it.progress.started && !it.progress.watched } }.coerceAtLeast(0))
    }
    val focus = remember { FocusRequester() }
    var pageFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(group.key) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onFocusChanged { pageFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                detailKey(e, keys, sections, playsOnSpace = pageFocused || keys.row >= 0, onTop = { scope.launch { scroll.animateScrollTo(0) } },
                    play = { item -> state.play(item ?: DetailPlay.choose(ordered, numberedRun)?.item ?: return@detailKey false, ordered, title = group.title); true },
                    back = { state.back() })
            },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Hero(state, group, ordered, numberedRun)
            Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp)) {
                val overview = rep.overview
                if (!overview.isNullOrBlank()) {
                    Overview(overview)
                    Spacer(Modifier.height(28.dp))
                }
                val people = remember(rep) { rep.crew.take(8) + rep.cast.take(16) }
                if (people.isNotEmpty()) {
                    Txt(s["det_credits"], Aura.type.title2, c.text)
                    Spacer(Modifier.height(14.dp))
                    PagedRow(people, key = { it.name + "/" + it.role }, gutter = 40.dp, arrowCenter = 38.dp) { p -> PersonChip(p) }
                    Spacer(Modifier.height(30.dp))
                }
                Files(state, group, ordered, numberedRun, sections, keys)
                Spacer(Modifier.height(48.dp))
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(3.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(state: AppState, group: MediaGroup, ordered: List<MediaItem>, numberedRun: Boolean) {
    val c = Aura.colors
    val s = LocalStrings.current
    val rep = group.representative
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 440.dp)) {
        val px = with(LocalDensity.current) { maxWidth.roundToPx() }
        val backdrop by rememberImage(group.backdropPath ?: rep.backdropPath, px)
        val b = backdrop
        if (b != null) {
            Image(b, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.matchParentSize())
        } else {
            val tint = group.dominantColor?.let { Color(it) } ?: c.accent
            Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(tint.copy(alpha = 0.55f), c.background))))
        }
        // Darken towards the text on the left, and fade into the page at the bottom.
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to c.background.copy(alpha = 0.92f), 0.55f to c.background.copy(alpha = 0.55f), 1f to c.background.copy(alpha = 0.15f))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to c.background)))
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 28.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.width(210.dp).aspectRatio(2f / 3f).shadow(24.dp, AuraShapes.card).clip(AuraShapes.card).background(c.surface)) {
                val poster by rememberImage(group.posterPath, with(LocalDensity.current) { 210.dp.roundToPx() })
                val p = poster
                if (p != null) Image(p, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) else TitleTile(group.title)
            }
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f).widthIn(max = 760.dp)) {
                Txt(group.title, Aura.type.display, c.text, maxLines = 3)
                val original = rep.originalTitle
                if (!original.isNullOrBlank() && original != group.title) {
                    Spacer(Modifier.height(2.dp))
                    Txt(original, Aura.type.body, c.textSecondary)
                }
                Spacer(Modifier.height(10.dp))
                Txt(metaLine(s, group, rep), Aura.type.callout, c.textSecondary, maxLines = 2)
                val ratings = group.ratings
                if (ratings.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ratings.forEach { RatingBadge(s, it) }
                    }
                }
                val note = when (rep.scrapeStatus) {
                    ScrapeStatus.UNMATCHED -> s["det_unmatched"]
                    ScrapeStatus.ERROR -> s["det_scrape_error"]
                    else -> null
                }
                if (note != null && !group.key.startsWith("jellyfin") && !group.key.startsWith("plex")) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.CircleAlert, c.warning, 15.dp)
                        Spacer(Modifier.width(7.dp))
                        Txt(note, Aura.type.callout, c.warning, maxLines = 3)
                    }
                }
                Spacer(Modifier.height(22.dp))
                Actions(state, group, ordered, numberedRun)
            }
        }
    }
}

@Composable
private fun Actions(state: AppState, group: MediaGroup, ordered: List<MediaItem>, numberedRun: Boolean) {
    val s = LocalStrings.current
    val lib = state.graph.library
    val choice = DetailPlay.choose(ordered, numberedRun)
    val ids = group.items.map { it.id }
    val favorite = group.items.any { it.favorite }
    val allWatched = group.items.all { it.progress.watched }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (choice != null) {
            AuraButton(
                playButtonText(s, choice, numberedRun),
                { state.play(choice.item, ordered, title = group.title) },
                kind = ButtonKind.Primary,
                icon = Lucide.Play,
                height = 38.dp,
                modifier = Modifier.widthIn(max = 360.dp),
            )
        }
        IconButton(
            Lucide.Heart, s[if (favorite) "d_remove_favorite" else "d_add_favorite"],
            { lib.setGroupFavorite(ids, !favorite); state.toast(s[if (favorite) "d_removed_favorite" else "d_added_favorite"]) },
            size = 38.dp, selected = favorite,
        )
        IconButton(
            if (allWatched) Lucide.Undo2 else Lucide.CircleCheck,
            s[if (allWatched) "det_mark_all_unwatched" else "det_mark_all_watched"],
            { lib.setWatched(ids, !allWatched); state.toast(s[if (allWatched) "d_marked_unwatched" else "d_marked_watched"]) },
            size = 38.dp,
        )
        if (!group.key.startsWith("jellyfin") && !group.key.startsWith("plex") && !group.key.startsWith("emby")) {
            IconButton(Lucide.Pencil, s["det_fix_match"], { state.go(Page.FixMatch(group.key)) }, size = 38.dp)
        }
        FolderButton(group)
    }
}

@Composable
private fun FolderButton(group: MediaGroup) {
    val s = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    val folders = remember(group) { group.items.map { folderOf(it.path) }.distinct().sorted() }
    Box {
        IconButton(Lucide.Folder, s["d_file_paths"], { open = true }, size = 38.dp)
        DropdownMenu(
            expanded = open,
            onDismiss = { open = false },
            entries = menu {
                header(s["d_where"])
                folders.forEach { f ->
                    item(f, enabled = Desktop.canReveal(f)) { Desktop.reveal(f) }
                }
            },
        )
    }
}

private fun folderOf(path: String): String {
    val norm = path.trimEnd('/', '\\')
    val cut = maxOf(norm.lastIndexOf('/'), norm.lastIndexOf('\\'))
    return if (cut > 0) norm.substring(0, cut) else norm
}

@Composable
private fun Overview(text: String) {
    val c = Aura.colors
    val s = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 900.dp)) {
        Txt(s["det_overview"], Aura.type.title2, c.text)
        Spacer(Modifier.height(10.dp))
        Txt(text, Aura.type.body.copy(lineHeight = Aura.type.body.lineHeight * 1.12f), c.textSecondary, maxLines = if (expanded) 40 else 4)
        if (!expanded && text.length > 220) {
            Spacer(Modifier.height(6.dp))
            Txt(s["det_more"], Aura.type.callout, c.accent, Modifier.clickable { expanded = true }.clickCursor())
        }
    }
}

@Composable
private fun PersonChip(p: Person) {
    val c = Aura.colors
    Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
            val px = with(LocalDensity.current) { 76.dp.roundToPx() }
            val img by produceState<ImageBitmap?>(null, p) { value = Images.profile(p, px) }
            val i = img
            if (i != null) Image(i, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Txt(p.name.trim().take(1).uppercase(), Aura.type.title2, c.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
        Txt(p.name, Aura.type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), c.text, maxLines = 2, align = androidx.compose.ui.text.style.TextAlign.Center)
        // A crew job in the reader's language (the library stores it as the scraper wrote it); a
        // cast credit's role is the character, shown as it is.
        val role = p.role?.let { r -> CrewRoles.keyOf(r)?.let { LocalStrings.current[it] } ?: r }
        if (!role.isNullOrBlank()) Txt(role, Aura.type.caption, c.textTertiary, maxLines = 2, align = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun Files(
    state: AppState, group: MediaGroup, ordered: List<MediaItem>, numberedRun: Boolean,
    sections: List<DetailSections.Section>, keys: DetailKeys,
) {
    val c = Aura.colors
    val s = LocalStrings.current
    if (sections.isEmpty()) return
    val current = sections.getOrElse(keys.section) { sections.first() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt(if (group.seasonal) s["det_section_episodes"] else s["det_files"], Aura.type.title2, c.text)
        Spacer(Modifier.width(16.dp))
        if (sections.size > 1) {
            Segmented(
                options = sections.mapIndexed { i, sec -> i to sectionTitle(s, sec) },
                selected = keys.section,
                onSelect = { keys.section = it; keys.row = -1 },
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Column(Modifier.widthIn(max = 1100.dp)) {
        current.items.forEachIndexed { i, item ->
            EpisodeRow(state, item, numberedRun, selected = i == keys.row, onPlay = { state.play(item, ordered, title = group.title) })
        }
    }
}

/** The sections a card's files are listed in: seasons, OVA, extras — or all of them as one. */
private fun detailSections(group: MediaGroup, ordered: List<MediaItem>, numberedRun: Boolean): List<DetailSections.Section> = when {
    !group.isCollection && ordered.size <= 1 -> emptyList()
    group.seasonal -> DetailSections.of(group.items, numberedRun)
    else -> listOf(DetailSections.Section(DetailSections.Kind.EPISODES, null, ordered))
}

/** Where the keyboard is on a card's page: the section (season) shown, the row chosen (-1: none). */
private class DetailKeys(section: Int) {
    var section by mutableIntStateOf(section)
    var row by mutableIntStateOf(-1)
}

/**
 * A card's page from the keyboard: ↓ and ↑ through the files (↑ from the first back to the top),
 * ← and → through seasons and extras, Home and End, Return or Space to play the file chosen — or,
 * with none, what the page's Play button plays — and Esc to let a file go, then to go back.
 * Space is the page's only when the page itself has focus: on a focused button it presses it.
 * Keys with ⌘, Ctrl or ⌥ are the menus'.
 */
private fun detailKey(
    e: KeyEvent, keys: DetailKeys, sections: List<DetailSections.Section>, playsOnSpace: Boolean,
    onTop: () -> Unit, play: (MediaItem?) -> Boolean, back: () -> Unit,
): Boolean {
    if (e.type != KeyEventType.KeyDown || e.isMetaPressed || e.isCtrlPressed || e.isAltPressed) return false
    val rows = sections.getOrNull(keys.section)?.items.orEmpty()
    when (e.key) {
        Key.DirectionDown -> { if (rows.isNotEmpty()) keys.row = (keys.row + 1).coerceAtMost(rows.lastIndex) }
        Key.DirectionUp -> { if (keys.row > 0) keys.row-- else if (keys.row == 0) { keys.row = -1; onTop() } else return false }
        Key.DirectionRight, Key.DirectionLeft -> {
            if (sections.size < 2) return false
            val next = (keys.section + if (e.key == Key.DirectionRight) 1 else -1).coerceIn(0, sections.lastIndex)
            if (next != keys.section) { keys.section = next; if (keys.row >= 0) keys.row = 0 }
        }
        Key.MoveHome -> { if (rows.isNotEmpty()) keys.row = 0 }
        Key.MoveEnd -> { if (rows.isNotEmpty()) keys.row = rows.lastIndex }
        Key.Enter, Key.NumPadEnter -> return play(rows.getOrNull(keys.row))
        Key.Spacebar -> return playsOnSpace && play(rows.getOrNull(keys.row))
        Key.Escape -> if (keys.row >= 0) keys.row = -1 else back()
        else -> return false
    }
    return true
}

private fun sectionTitle(s: Strings, sec: DetailSections.Section): String = when (sec.kind) {
    DetailSections.Kind.EPISODES -> s["det_section_episodes"]
    DetailSections.Kind.SEASON -> s["det_section_season", sec.season ?: 1]
    DetailSections.Kind.OVA -> "OVA"
    DetailSections.Kind.EXTRAS -> s["det_section_extras"]
}

@Composable
private fun EpisodeRow(state: AppState, item: MediaItem, numberedRun: Boolean, selected: Boolean, onPlay: () -> Unit) {
    val c = Aura.colors
    val s = LocalStrings.current
    val source = remember { MutableInteractionSource() }
    val wash = interactionWash(source)
    val rowState = DetailPlay.rowState(item)
    // The row the keyboard chose is in sight, with room around it.
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) runCatching { bring.bringIntoView() } }
    WithContextMenu(entries = { fileMenu(state, s, item, onPlay) }) {
        Row(
            Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bring)
                .clip(AuraShapes.card)
                .background(if (selected) c.accentSoft else Color.Transparent)
                .border(if (selected) 1.5.dp else 0.dp, if (selected) c.accent else Color.Transparent, AuraShapes.card)
                .background(wash)
                .clickable(interactionSource = source, indication = null, onClick = onPlay)
                .clickCursor()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(144.dp).aspectRatio(16f / 9f).clip(AuraShapes.small).background(c.surface), contentAlignment = Alignment.Center) {
                val px = with(LocalDensity.current) { 144.dp.roundToPx() }
                val still by produceState<ImageBitmap?>(null, item.id) { value = Images.still(item, px, frames = true) }
                val img = still
                if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Lucide.Play, c.textTertiary, 18.dp)
                if (item.progress.started && !item.progress.watched && item.progress.fraction > 0f) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66000000))) {
                        Box(Modifier.fillMaxWidth(item.progress.fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Txt(episodeLabel(s, item, numberedRun), Aura.type.bodyStrong, if (item.progress.watched) c.textSecondary else c.text)
                Spacer(Modifier.height(2.dp))
                Txt(item.fileName, Aura.type.caption, c.textTertiary)
            }
            if (rowState != null) {
                Spacer(Modifier.width(12.dp))
                Txt(rowStateText(s, rowState), Aura.type.caption, if (rowState is DetailPlay.RowState.Watched) c.textTertiary else c.accent)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                if (item.progress.watched) Lucide.Check else Lucide.CircleCheck,
                s[if (item.progress.watched) "det_mark_unwatched" else "det_mark_watched"],
                { state.graph.library.setWatched(listOf(item.id), !item.progress.watched) },
                tint = if (item.progress.watched) c.accent else c.textTertiary,
            )
        }
    }
}

/** What a right-click on a file offers. */
fun fileMenu(state: AppState, s: Strings, item: MediaItem, onPlay: () -> Unit) = menu {
    item(s["d_play"], Lucide.Play, onClick = onPlay)
    if (item.progress.started) item(s["d_play_from_start"]) { state.play(item, listOf(item), startMs = 0) }
    separator()
    if (item.progress.watched) item(s["det_mark_unwatched"]) { state.graph.library.setWatched(listOf(item.id), false) }
    else item(s["det_mark_watched"]) { state.graph.library.setWatched(listOf(item.id), true) }
    separator()
    if (Desktop.canReveal(item.path)) item(Desktop.revealLabel(s)) { Desktop.reveal(item.path) }
    item(s["d_copy_path"]) { Desktop.copy(item.path); state.toast(s["d_copied"]) }
}

private fun metaLine(s: Strings, group: MediaGroup, rep: MediaItem): String {
    val runtime = group.runtime?.let { formatRuntime(s, it) }
    val count = when {
        !group.isCollection -> null
        group.isSeries -> s.plural("det_n_episodes", group.count)
        else -> s.plural("det_n_files", group.count)
    }
    return buildList {
        group.year?.let { add(it.toString()) }
        runtime?.let { add(if (group.isCollection) s["det_runtime_each", it] else it) }
        group.certification?.let { add(it) }
        count?.let { add(it) }
        addAll(rep.genres.take(5).map { GenreCn.zh(it) })
    }.joinToString("  ·  ")
}

fun formatRuntime(s: Strings, min: Int): String =
    if (min >= 60) {
        val h = min / 60
        val m = min % 60
        if (m == 0) s["det_runtime_h", h] else s["det_runtime_hm", h, m]
    } else {
        s["det_runtime_min", min]
    }

@Composable
private fun RatingBadge(s: Strings, r: RatingScore) {
    val (label, bg, fg) = when (r.source) {
        RatingSource.IMDB -> Triple("IMDb", Color(0xFFF5C518), Color.Black)
        RatingSource.ROTTEN_TOMATOES -> Triple(s["det_brand_rt"], Color(0xFFFA320A), Color.White)
        RatingSource.METACRITIC -> Triple("Metacritic", Color(0xFF54A72A), Color.White)
        RatingSource.TMDB -> Triple("TMDB", Color(0xFF01B4E4), Color.White)
        RatingSource.DOUBAN -> Triple(s["det_brand_douban"], Color(0xFF2E963B), Color.White)
        RatingSource.BANGUMI -> Triple("Bangumi", Color(0xFFF09199), Color.White)
        RatingSource.MAL -> Triple("MAL", Color(0xFF2E51A2), Color.White)
        RatingSource.ANILIST -> Triple("AniList", Color(0xFF02A9FF), Color.White)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(AuraShapes.small).background(bg).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Txt(label, Aura.type.micro, fg)
        }
        Spacer(Modifier.width(6.dp))
        Txt(r.value, Aura.type.bodyStrong, Aura.colors.text)
    }
}
