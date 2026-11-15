package com.therealityteam.videoplayer.ui.library

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.menu.Menu
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.design.menu.rememberMenuPositionProvider
import com.pico.spatial.ui.design.windows.popup.HorizontalPlacement
import com.pico.spatial.ui.design.windows.popup.VerticalPlacement
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.SpatialSoundEffect
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.platform.DeepLink
import kotlinx.coroutines.delay

/**
 * What a card's long-press menu does. Provided around the wall (see [LocalCardMenu]); where it is
 * absent — the snapshot wall before the library is live, the detail page's own rows — a card has no
 * menu and a long press is just a press.
 */
class CardMenuActions(
    val onPlay: (MediaGroup, MediaItem) -> Unit,
    val onDetails: (MediaGroup) -> Unit,
    val onToggleFavorite: (MediaGroup) -> Unit,
    val onSetWatched: (List<MediaItem>, Boolean) -> Unit,
    val onHideFromContinue: (ContinueWatching.Entry) -> Unit,
)

val LocalCardMenu = compositionLocalOf<CardMenuActions?> { null }

/** Whether a card's menu is open. */
@Stable
class CardMenuState {
    var open by mutableStateOf(false)
}

@Composable
fun rememberCardMenuState(): CardMenuState = remember { CardMenuState() }

/**
 * The card's long press — pinch and hold, or the trigger held, which PICO's guidelines give to
 * menus — opening its menu with the system's long-press sound; null where no menu is provided.
 */
@Composable
fun cardLongPress(state: CardMenuState): (() -> Unit)? {
    LocalCardMenu.current ?: return null
    val sound = LocalAudioEffectPlayer.current
    return {
        sound.playSystem(SpatialSoundEffect.OpLongPress)
        state.open = true
    }
}

/**
 * The menu, composed inside the card so the card is its anchor — and centred on it, so it reads as
 * this card's and stays inside the window for a card in the last column too. [entry] is the
 * 继续观看 entry a card shows, which adds 从「继续观看」中移除.
 */
@Composable
fun CardMenuHost(state: CardMenuState, group: MediaGroup, entry: ContinueWatching.Entry? = null) {
    val actions = LocalCardMenu.current ?: return
    // Automation (`-e aura.card_menu <text>`, `cw:<text>` for a 继续观看 card): open the menu of the
    // first card whose name has the text, as a long press would — `input tap` reaches no spatial window.
    val request by DeepLink.cardMenuRequest.collectAsState()
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        val forContinue = r.startsWith("cw:")
        if (forContinue == (entry != null) && group.title.contains(r.removePrefix("cw:"), ignoreCase = true)) {
            delay(400)
            state.open = true
            // Consumed only now: clearing the request re-keys this effect and would cancel the wait.
            DeepLink.cardMenuRequest.value = null
        }
    }
    if (state.open) CardMenu(group, entry, actions, onDismiss = { state.open = false })
}

/**
 * 播放 (worded as the detail page's big button words it: 继续播放, 播放第 3 集, 重看…), 详情, 收藏,
 * 标为已看 and — on a 继续观看 card — 从「继续观看」中移除. Marking a whole series, or a file somebody is
 * part-way through, asks first, as on the detail page.
 */
@Composable
private fun CardMenu(group: MediaGroup, entry: ContinueWatching.Entry?, actions: CardMenuActions, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val numberedRun = group.numberedRun
    val ordered = remember(group) {
        group.items.sortedWith(compareBy({ EpisodeNaming.sortKey(it, numberedRun) }, { it.fileName.lowercase() }))
    }
    val choice = remember(ordered, entry) {
        entry?.let { DetailPlay.Choice(it.item, if (it.nextUp) DetailPlay.Kind.NEXT else DetailPlay.Kind.RESUME) }
            ?: DetailPlay.choose(ordered, numberedRun, group.seasonal)
            ?: DetailPlay.Choice(ordered.first(), DetailPlay.Kind.START)
    }
    val playText = remember(choice, ctx) {
        if (!group.isCollection) {
            when (choice.kind) {
                DetailPlay.Kind.RESUME -> ctx.getString(R.string.cw_resume)
                DetailPlay.Kind.RESTART -> ctx.getString(R.string.det_watch_again)
                else -> ctx.getString(R.string.det_play)
            }
        } else {
            DetailPlay.buttonText(ctx, choice, EpisodeNaming.label(ctx, choice.item, numberedRun))
        }
    }
    val markAll = remember(ordered) { DetailPlay.markAll(ordered, numberedRun, group.seasonal) }
    val favorite = group.items.any { it.favorite }
    var confirm by remember { mutableStateOf(false) }

    if (confirm) {
        MarkAllDialog(markAll, onDismiss = { confirm = false; onDismiss() }) {
            confirm = false
            onDismiss()
            actions.onSetWatched(markAll.targets, markAll.watched)
        }
        return
    }
    Menu(
        onDismissRequest = onDismiss,
        positionProvider = rememberMenuPositionProvider(
            horizontalPlacement = HorizontalPlacement.center(),
            verticalPlacement = VerticalPlacement.center(),
        ),
    ) {
        MenuItem(
            title = { Text(playText) },
            leadingIcon = { MenuIcon(R.drawable.ic_pc_play) },
            onClick = { onDismiss(); actions.onPlay(group, choice.item) },
        )
        MenuItem(
            title = { Text(stringResource(R.string.det_details)) },
            leadingIcon = { MenuIcon(R.drawable.ic_line_info) },
            onClick = { onDismiss(); actions.onDetails(group) },
        )
        MenuItem(
            title = { Text(stringResource(if (favorite) R.string.det_unfavorite else R.string.det_favorite)) },
            leadingIcon = { MenuIcon(if (favorite) R.drawable.ic_det_fav_fill else R.drawable.ic_det_fav) },
            onClick = { onDismiss(); actions.onToggleFavorite(group) },
        )
        if (markAll.targets.isNotEmpty()) {
            val text = when {
                group.isCollection && markAll.watched -> R.string.det_mark_all_watched
                group.isCollection -> R.string.det_mark_all_unwatched
                markAll.watched -> R.string.det_mark_watched
                else -> R.string.det_mark_unwatched
            }
            MenuItem(
                title = { Text(stringResource(text)) },
                leadingIcon = { MenuIcon(if (markAll.watched) R.drawable.ic_det_watched else R.drawable.ic_det_watched_fill) },
                onClick = {
                    if (markAll.needsConfirm) {
                        confirm = true
                    } else {
                        onDismiss()
                        actions.onSetWatched(markAll.targets, markAll.watched)
                    }
                },
            )
        }
        DownloadMenuItems(group, ordered, choice.item, onDismiss)
        if (entry != null) {
            MenuItem(
                title = { Text(stringResource(R.string.cw_remove)) },
                leadingIcon = { MenuIcon(R.drawable.ic_close) },
                onClick = { onDismiss(); actions.onHideFromContinue(entry) },
            )
        }
    }
}

/**
 * 下载到本机 on a card: a film's one file (删除下载 once it is here); a series' next few episodes from
 * where it would play on — the ones not on the headset yet.
 */
@Composable
private fun DownloadMenuItems(group: MediaGroup, ordered: List<MediaItem>, from: MediaItem, onDismiss: () -> Unit) {
    val dl = LocalDownloads.current ?: return
    val list by dl.downloader.store.all.collectAsState()
    val have = remember(list) { list.mapTo(HashSet()) { it.itemId } }
    if (!group.isCollection) {
        if (!dl.downloader.canDownload(from)) return
        if (from.id in have) {
            MenuItem(
                title = { Text(stringResource(R.string.dl_remove)) },
                leadingIcon = { MenuIcon(R.drawable.ic_line_delete) },
                onClick = { onDismiss(); dl.onRemove(from.id) },
            )
        } else {
            MenuItem(
                title = { Text(stringResource(R.string.dl_add)) },
                leadingIcon = { MenuIcon(R.drawable.ic_line_download) },
                onClick = { onDismiss(); dl.onAdd(listOf(from)) },
            )
        }
        return
    }
    val next = remember(ordered, from, have) {
        ordered.dropWhile { it.id != from.id }.filter { it.id !in have && dl.downloader.canDownload(it) }.take(NEXT_EPISODES)
    }
    if (next.isEmpty()) return
    MenuItem(
        title = { Text(pluralStringResource(R.plurals.dl_add_next, next.size, next.size)) },
        leadingIcon = { MenuIcon(R.drawable.ic_line_download) },
        onClick = { onDismiss(); dl.onAdd(next) },
    )
}

/** How many episodes a card's 下载 takes at once: an evening's, not a season's worth of storage. */
private const val NEXT_EPISODES = 5

/**
 * A menu row's glyph, in the row's label colour. Tinted outright: the menu tints nothing itself, so a
 * glyph drawn white for a picture (the play mark, ✕) came out white on the light glass.
 */
@Composable
private fun MenuIcon(res: Int) {
    Icon(painterResource(res), contentDescription = null, tint = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.size(20.dp))
}
