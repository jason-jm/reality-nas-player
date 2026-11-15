package com.therealityteam.videoplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.TitleAlignment
import com.pico.spatial.ui.design.TitleBar
import com.pico.spatial.ui.foundation.tooltip
import com.pico.spatial.ui.design.PicoTheme
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.common.DetailActionRow
import com.therealityteam.videoplayer.ui.common.EmptyState
import com.therealityteam.videoplayer.ui.theme.secondaryIconButtonColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件夹: the library as the shares hold it ([FolderTree]) — each source, its folders, its files — for
 * whoever knows where a thing is rather than what a database calls it, and for the files no card
 * owns. A folder opens the next level; a file plays, with the folder's other files after it, as a
 * disc's do. Every file goes by its own name (the card it belongs to beside it): this is the page
 * where the name on the share is the point.
 */
@Composable
fun FolderBrowser(
    items: List<MediaItem>,
    sources: List<MediaSourceConfig>,
    groups: List<MediaGroup>,
    showAdult: Boolean,
    path: FolderTree.Path?,
    onPath: (FolderTree.Path?) -> Unit,
    onPlay: (MediaItem, List<MediaItem>) -> Unit,
    modifier: Modifier = Modifier,
    playIndex: Int? = null,
    onPlayIndexHandled: () -> Unit = {},
    /** The sidebar's search, here read as a file's own name — across every folder. */
    query: String = "",
) {
    // Built off the main thread — 9,600 paths — and again only when the library or its sources move.
    val built by produceState<Pair<FolderTree, Map<String, MediaGroup>>?>(null, items, sources, groups, showAdult) {
        value = withContext(Dispatchers.Default) {
            val cardOf = HashMap<String, MediaGroup>()
            groups.forEach { g -> g.items.forEach { cardOf[it.id] = g } }
            val hidden = if (showAdult) emptySet() else groups.filter { it.category == LibCategory.ADULT }
                .flatMapTo(HashSet()) { g -> g.items.map { it.id } }
            val roots = sources.filter { FolderTree.hasFolders(it.type) }.map { FolderTree.Root(it.id, it.name, it.uri) }
            FolderTree(items.filterNot { it.id in hidden }, roots) to cardOf
        }
    }
    val tree = built?.first
    val cardOf = built?.second.orEmpty()
    val listing = remember(tree, path) { tree?.let { t -> path?.let { t.list(it) } } }
    val searching = query.isNotBlank()
    val found by produceState<List<FolderTree.Found>?>(null, tree, query) {
        value = if (tree == null || !searching) null else withContext(Dispatchers.Default) { tree.search(query.trim()) }
    }
    // A folder gone from the library (a rescan moved it) is left for its parent, or the top.
    LaunchedEffect(tree, path) {
        if (tree != null && path != null && tree.list(path) == null) onPath(path.parent)
    }
    // `aura.screen folders:<path>#<n>`: the n-th file of the folder played, as its row's tap would.
    LaunchedEffect(listing, playIndex) {
        val n = playIndex ?: return@LaunchedEffect
        val files = listing?.files ?: return@LaunchedEffect
        files.getOrNull(n - 1)?.let { onPlay(it, files) }
        onPlayIndexHandled()
    }
    val listState = rememberLazyListState()
    LaunchedEffect(path) { listState.scrollToItem(0) }

    Column(modifier = modifier.fillMaxSize()) {
        TitleBar(
            title = {
                // The whole room between the actions, so the title is centred in it (Center alone set it at its start).
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when {
                            searching -> stringResource(R.string.folders_search_title, query.trim())
                            else -> path?.let { tree?.crumbs(it)?.lastOrNull() } ?: stringResource(R.string.nav_folders)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val crumbs = if (searching) emptyList() else path?.let { tree?.crumbs(it) }?.dropLast(1).orEmpty()
                    found?.takeIf { searching && it.isNotEmpty() }?.let { f ->
                        Text(
                            text = pluralStringResource(R.plurals.folders_files, f.size, f.size),
                            style = PicoTheme.typography.bodySmall,
                            color = PicoTheme.colorScheme.labelSecondary,
                        )
                    }
                    if (crumbs.isNotEmpty()) {
                        Text(
                            text = crumbs.joinToString(" › "),
                            style = PicoTheme.typography.bodySmall,
                            color = PicoTheme.colorScheme.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            titleAlignment = TitleAlignment.Center, // beside its play button, never under it
            // A folder of files plays as a disc does: on from where it was left — the file half
            // watched, else the first not yet seen — with the rest after it.
            trailingActions = {
                val files = if (searching) emptyList() else listing?.files.orEmpty()
                FolderDownload(files)
                val next = FolderTree.nextUp(files)
                if (next != null) {
                    val resuming = next.progress.started && !next.progress.watched
                    Button(
                        onClick = { onPlay(next, files) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_pc_play), contentDescription = null, modifier = Modifier.size(18.dp)) },
                    ) { Text(stringResource(if (resuming) R.string.folders_resume else R.string.folders_play)) }
                }
            },
            leadingActions = {
                if (path != null && !searching) {
                    val up = stringResource(R.string.folders_up)
                    IconButton(onClick = { onPath(path.parent) }, modifier = Modifier.tooltip(text = up), colors = secondaryIconButtonColors()) {
                        Icon(painterResource(R.drawable.ic_pc_back), up, Modifier.size(20.dp))
                    }
                }
            },
        )
        when {
            tree != null && searching -> {
                val results = found
                if (results != null && results.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(title = stringResource(R.string.folders_search_none, query.trim()), iconRes = R.drawable.ic_line_folder)
                    }
                } else if (results != null) {
                    FoundList(listState, results, tree, onPlay)
                }
            }
            tree == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(title = stringResource(R.string.library_loading), loading = true)
            }
            path == null -> {
                val top = tree.sources()
                if (top.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(title = stringResource(R.string.folders_empty), iconRes = R.drawable.ic_line_folder, message = stringResource(R.string.folders_empty_hint))
                    }
                } else {
                    FolderList(listState, top, emptyList(), cardOf, folderName = null, onOpen = { onPath(it) }, onPlay = onPlay)
                }
            }
            listing != null -> FolderList(
                listState, listing.folders, listing.files, cardOf,
                folderName = path?.segments?.lastOrNull(),
                onOpen = { onPath(it) }, onPlay = onPlay,
            )
        }
    }
}

@Composable
private fun FolderList(
    state: androidx.compose.foundation.lazy.LazyListState,
    folders: List<FolderTree.Folder>,
    files: List<MediaItem>,
    cardOf: Map<String, MediaGroup>,
    folderName: String?,
    onOpen: (FolderTree.Path) -> Unit,
    onPlay: (MediaItem, List<MediaItem>) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val onHeadset = rememberOnHeadset()
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(folders, key = { "d:" + it.path.segments.joinToString("/") + it.path.sourceId }) { f ->
            DetailActionRow(
                title = f.name,
                onClick = { onOpen(f.path) },
                icon = R.drawable.ic_line_folder,
                value = pluralStringResource(R.plurals.folders_files, f.files, f.files),
            )
        }
        items(files, key = { "f:" + it.id }) { item ->
            DetailActionRow(
                title = item.fileName,
                onClick = { onPlay(item, files) },
                description = fileAbout(ctx, item, cardOf[item.id], folderName),
                icon = R.drawable.ic_line_video,
                value = fileState(ctx, item),
                // A file with its copy on the headset says so at the row's end.
                trailingIcon = R.drawable.ic_line_downloaded.takeIf { item.id in onHeadset },
            )
        }
    }
}

/** A search's files, each with the folder it is in beneath; it plays with that folder's others after it. */
@Composable
private fun FoundList(
    state: androidx.compose.foundation.lazy.LazyListState,
    found: List<FolderTree.Found>,
    tree: FolderTree,
    onPlay: (MediaItem, List<MediaItem>) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val onHeadset = rememberOnHeadset()
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(found, key = { "s:" + it.item.id }) { f ->
            DetailActionRow(
                title = f.item.fileName,
                onClick = { onPlay(f.item, tree.list(f.folder)?.files ?: listOf(f.item)) },
                description = tree.crumbs(f.folder).joinToString(" › "),
                icon = R.drawable.ic_line_video,
                value = fileState(ctx, f.item),
                trailingIcon = R.drawable.ic_line_downloaded.takeIf { f.item.id in onHeadset },
            )
        }
    }
}

/**
 * What the file is, beside its name: the card it belongs to — unless the folder is already called that,
 * as a show's folder of twenty-five episodes is — and which episode it is.
 */
private fun fileAbout(ctx: android.content.Context, item: MediaItem, card: MediaGroup?, folderName: String?): String? {
    if (card == null || item.scrapeStatus != ScrapeStatus.MATCHED) return null
    val norm = { s: String -> s.lowercase().filter { it.isLetterOrDigit() } }
    val title = card.title.takeUnless { folderName != null && norm(folderName).let { f -> f.isNotEmpty() && (f.contains(norm(it)) || norm(it).contains(f)) } }
    val episode = if (card.isSeries && EpisodeNaming.parse(item).cat == EpisodeNaming.Cat.EPISODE) EpisodeNaming.label(ctx, item) else null
    return listOfNotNull(title, episode).joinToString(" · ").ifBlank { null }
}

/** How far the file was watched: 已看完, or 看到 12:30 — nothing for one never started. */
private fun fileState(ctx: android.content.Context, item: MediaItem): String? {
    val p = item.progress
    return when {
        p.watched -> ctx.getString(R.string.folders_watched)
        p.started && p.positionMs >= 1_000 -> ctx.getString(R.string.folders_at, formatClock(p.positionMs))
        else -> null
    }
}

private fun formatClock(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (t % 3600) / 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/**
 * 下载这个文件夹: a season is a folder more often than it is a card — its files, the ones not on the
 * headset yet, downloaded one after another. Asked first, with how many and the room left, since a
 * folder can hold a hundred files.
 */
@Composable
private fun FolderDownload(files: List<MediaItem>) {
    val dl = LocalDownloads.current ?: return
    val list by dl.downloader.store.all.collectAsState()
    val todo = remember(files, list) {
        val have = list.mapTo(HashSet()) { it.itemId }
        files.filter { it.id !in have && dl.downloader.canDownload(it) }
    }
    if (todo.isEmpty()) return
    var ask by remember { mutableStateOf(false) }
    // `-e aura.detail_sheet folder_download`: the question, as the button's press would — for screenshots.
    com.therealityteam.videoplayer.ui.common.SheetHook("folder_download") { ask = true }
    val label = stringResource(R.string.dl_folder)
    IconButton(onClick = { ask = true }, modifier = Modifier.tooltip(text = label), colors = secondaryIconButtonColors()) {
        Icon(painterResource(R.drawable.ic_line_download), label, Modifier.size(20.dp))
    }
    if (ask) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val free = remember {
            runCatching { dl.downloader.dir.mkdirs(); android.os.StatFs(dl.downloader.dir.path).availableBytes }.getOrDefault(0L)
        }
        com.pico.spatial.ui.design.windows.AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text(pluralStringResource(R.plurals.dl_folder_q, todo.size, todo.size)) },
            content = { Text(stringResource(R.string.dl_folder_body, android.text.format.Formatter.formatShortFileSize(ctx, free))) },
            buttons = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { ask = false }, colors = com.therealityteam.videoplayer.ui.theme.secondaryButtonColors()) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(onClick = { ask = false; dl.onAdd(todo) }) { Text(stringResource(R.string.dl_folder_go)) }
                }
            },
        )
    }
}

/** The ids of the files with a whole copy on the headset. */
@Composable
private fun rememberOnHeadset(): Set<String> {
    val dl = LocalDownloads.current ?: return emptySet()
    val list by dl.downloader.store.all.collectAsState()
    return remember(list) { list.filter { it.isDone }.mapTo(HashSet()) { it.itemId } }
}
