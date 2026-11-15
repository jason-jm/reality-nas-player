package com.therealityteam.videoplayer.ui.library

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.LinearProgressIndicator
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.TitleAlignment
import com.pico.spatial.ui.design.TitleBar
import com.pico.spatial.ui.design.windows.LocalSnackbarHostState
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.tooltip
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.download.Download
import com.therealityteam.videoplayer.data.download.Downloader
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.ui.common.EmptyState
import com.therealityteam.videoplayer.ui.theme.secondaryButtonColors
import com.therealityteam.videoplayer.ui.theme.secondaryIconButtonColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的 › 已下载: the copies on the headset and the ones on their way — what is downloading at the top,
 * with how far and how fast; what is here below, each playing from the headset. A row goes by the
 * work and the episode, with the file's own name beneath, as every list of files in the app does.
 */
@Composable
fun DownloadsPage(
    downloader: Downloader,
    items: List<MediaItem>,
    groups: List<MediaGroup>,
    onPlay: (MediaItem, List<MediaItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    // 删除下载 says so, as it does from a menu.
    val remove: (String) -> Unit = LocalDownloads.current?.onRemove ?: { id -> downloader.remove(id) }
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val all by downloader.store.all.collectAsState()
    val active by downloader.active.collectAsState()
    val speed by downloader.speed.collectAsState()
    val waitingForShare by downloader.waitingForShare.collectAsState()
    val slowed by downloader.slowedForPlayback.collectAsState()
    val byId = remember(items) { items.associateBy { it.id } }
    val cardOf = remember(groups) { HashMap<String, MediaGroup>().also { m -> groups.forEach { g -> g.items.forEach { m[it.id] = g } } } }
    // What is on its way first, in the order it goes; then what is here, the latest first.
    val waiting = remember(all) { all.filterNot { it.isDone }.sortedWith(compareBy({ it.state != Download.State.RUNNING }, { it.addedAt })) }
    val done = remember(all) { all.filter { it.isDone }.sortedByDescending { it.addedAt } }
    // How much the copies take and what is left beside them, read again every few seconds.
    val space by produceState<Pair<Long, Long>?>(null, done.size) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                val dir = downloader.dir
                val used = done.sumOf { java.io.File(it.localPath).length() }
                val free = runCatching { dir.mkdirs(); android.os.StatFs(dir.path).availableBytes }.getOrDefault(0L)
                used to free
            }
            delay(5_000)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TitleBar(
            title = {
                // The whole room between the actions, so the title is centred in it (Center alone set it at its start).
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.nav_downloads), maxLines = 1)
                    space?.takeIf { all.isNotEmpty() }?.let { (used, free) ->
                        Text(
                            // Nothing whole yet: only the room there is — "已下载 0 B" counted nothing.
                            text = if (used > 0) stringResource(R.string.dl_space, Formatter.formatShortFileSize(ctx, used), Formatter.formatShortFileSize(ctx, free))
                            else stringResource(R.string.dl_space_free, Formatter.formatShortFileSize(ctx, free)),
                            style = PicoTheme.typography.bodySmall,
                            color = PicoTheme.colorScheme.labelSecondary,
                        )
                    }
                }
            },
            titleAlignment = TitleAlignment.Center, // beside 清理, never under it
            // 清理已看完: the copies watched to the end, off the headset in one go — what fills it up
            // after a trip is the episodes already seen.
            trailingActions = {
                val watched = done.filter { d -> byId[d.itemId]?.progress?.watched == true }
                if (watched.isNotEmpty()) {
                    Button(
                        onClick = { cleanWatched(ctx, downloader, items, snackbar, scope) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_line_delete), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        colors = secondaryButtonColors(),
                    ) { Text(pluralStringResource(R.plurals.dl_clean_watched, watched.size, watched.size)) }
                }
            },
        )
        if (all.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(title = stringResource(R.string.dl_empty), iconRes = R.drawable.ic_line_download, message = stringResource(R.string.dl_empty_hint))
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (waiting.isNotEmpty()) {
                item(key = "h:active") { SectionLabel(stringResource(R.string.dl_section_active)) }
                items(waiting, key = { "w:" + it.itemId }) { d ->
                    // The running one's bytes come from the downloader as they arrive; the list's own
                    // copy of it is written about once a second too, but the flow is the fresher.
                    val live = active?.takeIf { it.itemId == d.itemId } ?: d
                    val item = byId[d.itemId]
                    val card = cardOf[d.itemId]
                    DownloadRow(
                        d = live,
                        item = item,
                        card = card,
                        // Held back while a film streams: said, so a slower copy is not a mystery.
                        status = statusOf(ctx, live, speed, waitingForShare && live.itemId == active?.itemId) +
                            (if (slowed && live.state == Download.State.RUNNING) " · " + ctx.getString(R.string.dl_slowed) else ""),
                        // Not here yet, but at home it plays from the share as it always did.
                        onClick = item?.let { i -> { onPlay(i, card?.let { PlayQueue.of(it, i) } ?: listOf(i)) } },
                        actions = {
                            when (live.state) {
                                Download.State.RUNNING, Download.State.QUEUED ->
                                    RowAction(R.drawable.ic_pc_pause, stringResource(R.string.dl_pause)) { downloader.pause(d.itemId) }
                                Download.State.PAUSED ->
                                    RowAction(R.drawable.ic_pc_play, stringResource(R.string.dl_resume)) { downloader.resume(d.itemId) }
                                Download.State.FAILED ->
                                    RowAction(R.drawable.ic_refresh, stringResource(R.string.dl_retry)) { downloader.resume(d.itemId) }
                                Download.State.DONE -> Unit
                            }
                            RowAction(R.drawable.ic_line_delete, stringResource(R.string.dl_remove)) { remove(d.itemId) }
                        },
                    )
                }
            }
            if (done.isNotEmpty()) {
                item(key = "h:done") { SectionLabel(stringResource(R.string.dl_section_done)) }
                items(done, key = { "d:" + it.itemId }) { d ->
                    val item = byId[d.itemId]
                    val card = cardOf[d.itemId]
                    DownloadRow(
                        d = d,
                        item = item,
                        card = card,
                        status = listOfNotNull(
                            Formatter.formatShortFileSize(ctx, d.totalBytes),
                            item?.let { watchedText(ctx, it) },
                        ).joinToString(" · "),
                        // A copy whose file has left the library — its source removed, its share renamed —
                        // is still on the headset, and still plays: as itself, by its own name.
                        onClick = {
                            val i = item ?: MediaItem(d.itemId, DOWNLOAD_SOURCE, d.localPath, d.fileName, title = d.fileName)
                            onPlay(i, card?.let { PlayQueue.of(it, i) } ?: listOf(i))
                        },
                        actions = {
                            RowAction(R.drawable.ic_line_delete, stringResource(R.string.dl_remove)) { remove(d.itemId) }
                        },
                    )
                }
            }
        }
    }
}

/** The source id of a copy played with no library item left behind it. */
private const val DOWNLOAD_SOURCE = "download"

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = PicoTheme.typography.titleSmall,
        color = PicoTheme.colorScheme.labelSecondary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * One copy: the work's poster, what it is (the card, the episode) over the file's own name, how it
 * stands — and, while it downloads, a bar of how far.
 */
@Composable
private fun DownloadRow(
    d: Download,
    item: MediaItem?,
    card: MediaGroup?,
    status: String,
    onClick: (() -> Unit)?,
    actions: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val title = card?.title ?: item?.title?.ifBlank { null } ?: d.fileName
    // Which of the show's files: 第 3 集, OVA 2, 预告 20 — a film's one file needs no word.
    val episode = if (item != null && card != null && card.isSeries && EpisodeNaming.parse(item, card.numberedRun).cat != EpisodeNaming.Cat.FEATURE) {
        EpisodeNaming.label(ctx, item, card.numberedRun)
    } else null
    val shape = RoundedCornerShape(16.dp)
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickableRow(onClick) else Modifier),
        shape = shape,
        leadingContent = { Poster(card?.posterPath ?: item?.posterPath) },
        headlineContent = {
            Text(
                listOfNotNull(title, episode).joinToString("  "),
                style = PicoTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The file's own name, always: which copy, which rip.
                Text(d.fileName, style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    status,
                    style = PicoTheme.typography.bodySmall,
                    color = if (d.state == Download.State.FAILED) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary,
                    maxLines = 1,
                )
                if (d.state == Download.State.RUNNING || (d.state == Download.State.PAUSED && d.doneBytes > 0)) {
                    LinearProgressIndicator(progress = { d.fraction.coerceIn(0.01f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        },
    )
}

@Composable
private fun RowAction(icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.tooltip(text = label), colors = secondaryIconButtonColors()) {
        Icon(painterResource(icon), label, Modifier.size(20.dp))
    }
}

/** A 2:3 poster at a list row's height; the line icon's tile while there is none. */
@Composable
private fun Poster(path: String?) {
    val widthPx = with(LocalDensity.current) { 48.dp.roundToPx() }
    val bitmap by produceState(cachedArtwork(path, widthPx), path) { value = decodeArtwork(path, widthPx) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(width = 48.dp, height = 72.dp).clip(shape).background(PicoTheme.colorScheme.fillSecondary),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(painterResource(R.drawable.ic_line_video), null, Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelTertiary)
        }
    }
}

@Composable
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
        .controllerHapticFeedback(interactionSource = interaction)
}

/** How a download not yet whole stands: how far and how fast, waiting, paused, or why it stopped. */
private fun statusOf(ctx: Context, d: Download, speed: Long, waitingForShare: Boolean = false): String = when (d.state) {
    Download.State.RUNNING ->
        if (waitingForShare && d.totalBytes > 0) ctx.getString(
            R.string.dl_waiting_share,
            Formatter.formatShortFileSize(ctx, d.doneBytes),
            Formatter.formatShortFileSize(ctx, d.totalBytes),
        )
        else if (d.totalBytes <= 0) ctx.getString(R.string.dl_state_starting)
        else ctx.getString(
            R.string.dl_running,
            Formatter.formatShortFileSize(ctx, d.doneBytes),
            Formatter.formatShortFileSize(ctx, d.totalBytes),
            Formatter.formatShortFileSize(ctx, speed),
        )
    Download.State.QUEUED -> ctx.getString(R.string.dl_state_queued)
    Download.State.PAUSED -> ctx.getString(R.string.dl_state_paused) +
        (if (d.totalBytes > 0) ctx.getString(R.string.meta_separator) + com.therealityteam.videoplayer.ui.common.percentText((d.fraction * 100).toInt()) else "")
    Download.State.FAILED -> failureText(ctx, d.error)
    Download.State.DONE -> ctx.getString(R.string.dl_on_headset)
}

/** Why a download stopped, in words: no room, the share out of reach, the file not saved. */
fun failureText(ctx: Context, error: String?): String = ctx.getString(
    when (error) {
        Downloader.ERROR_NO_ROOM -> R.string.dl_failed_no_room
        Downloader.ERROR_DISK -> R.string.dl_failed_disk
        else -> R.string.dl_failed_unreachable
    },
)

private fun watchedText(ctx: Context, item: MediaItem): String? = when {
    item.progress.watched -> ctx.getString(R.string.folders_watched)
    item.progress.started -> DetailPlay.rowState(item)?.let { DetailPlay.rowStateText(ctx, it) }
    else -> null
}

/** 清理已看完: every whole copy of a file watched to the end, removed, and the space it gave back said. */
fun cleanWatched(
    ctx: Context,
    downloader: Downloader,
    items: List<MediaItem>,
    snackbar: com.pico.spatial.ui.design.windows.SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val watchedIds = items.filter { it.progress.watched }.mapTo(HashSet()) { it.id }
    val watched = downloader.store.all.value.filter { it.isDone && it.itemId in watchedIds }
    if (watched.isEmpty()) return
    val bytes = watched.sumOf { it.totalBytes }
    watched.forEach { downloader.remove(it.itemId) }
    android.util.Log.i("AuraDownload", "cleaned ${watched.size} watched copies, $bytes bytes")
    val text = ctx.resources.getQuantityString(R.plurals.dl_cleaned, watched.size, watched.size, Formatter.formatShortFileSize(ctx, bytes))
    scope.launch { snackbar.show(message = text) }
}

/**
 * 下载到本机 as the menus offer it — a card's, an episode row's — provided around the library; where
 * it is absent the menus simply have no such row.
 */
class DownloadActions(
    val downloader: Downloader,
    /** Queues the files and says so (how many, or that they already were). */
    val onAdd: (List<MediaItem>) -> Unit,
    val onRemove: (String) -> Unit,
)

val LocalDownloads = androidx.compose.runtime.compositionLocalOf<DownloadActions?> { null }
