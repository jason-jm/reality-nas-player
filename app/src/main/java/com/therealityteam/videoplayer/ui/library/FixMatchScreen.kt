package com.therealityteam.videoplayer.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Badge
import com.pico.spatial.ui.design.BadgeDefaults
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.LocalDisableAlpha
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SearchField
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.TitleAlignment
import com.pico.spatial.ui.design.TitleBar
import com.pico.spatial.ui.design.windows.AlertDialog
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.disableSpatialHoverEffect
import com.pico.spatial.ui.foundation.tooltip
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.scrape.AnimeParse
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.MetaResult
import com.therealityteam.videoplayer.di.AURA_SESSION_SCOPE_ID
import com.therealityteam.videoplayer.ui.common.EmptyState
import com.therealityteam.videoplayer.ui.theme.secondaryButtonColors
import com.therealityteam.videoplayer.ui.theme.secondaryIconButtonColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/** Where a fix-match search looks. */
private enum class MatchSource { TMDB, ANILIST }

/** One search's answer, kept with the database it came from. */
private class Found(val from: MatchSource, val results: List<MetaResult>, val failed: Boolean)

/**
 * Setting a title's identity by hand: search TMDB — or AniList, where most anime are — for the right
 * entry, pick it, confirm, and the card is refetched (poster, synopsis, cast, ratings) and pinned so
 * no later scrape undoes it. A series is fixed as a whole: every file of the card moves, each keeping
 * its own episode. A card of unrelated files can be fixed one file at a time.
 *
 * [item] is the file the page was opened for, [group] the card it is on now. The work itself runs in
 * the library's scope ([LibraryViewModel.matchApply]), so leaving the page does not cancel it; the
 * page only says how it is going. [onApplyStarted] lets the caller follow the file to its new card.
 */
@Composable
fun FixMatchScreen(
    item: MediaItem,
    group: MediaGroup,
    onApplyStarted: () -> Unit,
    onApplied: () -> Unit,
    onBack: () -> Unit,
) {
    val koinScope = remember { GlobalContext.get().getScope(AURA_SESSION_SCOPE_ID) }
    val vm = remember(koinScope) { koinScope.get<LibraryViewModel>() }
    val scope = rememberCoroutineScope()
    val colors = PicoTheme.colorScheme

    // Keyed by the file, not the card: the card's key is exactly what a running match changes.
    val animeCard = remember(item.id) {
        group.category == LibCategory.ANIME ||
            group.items.any { it.collectionKey?.startsWith("anilist:") == true } ||
            AnimeParse.parse(item.fileName).isAnimeLikely ||
            FilenameParser.isUnderAnimeRoot(item.path)
    }
    var source by rememberSaveable(item.id) { mutableStateOf(if (animeCard) MatchSource.ANILIST else MatchSource.TMDB) }
    // A series is one work, so the whole card moves; a box of unrelated films is fixed file by file.
    var wholeCard by rememberSaveable(item.id) { mutableStateOf(group.isSeries || group.count <= 1) }
    var query by rememberSaveable(item.id) { mutableStateOf(searchTitleFor(item, group)) }
    // Null until a search has answered: the page says it is searching, never "no results" first.
    var found by remember(item.id) { mutableStateOf<Found?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var confirming by remember { mutableStateOf<MetaResult?>(null) }
    val apply by vm.matchApply.collectAsState()
    val running = apply?.state == MatchApplyState.RUNNING
    val mine = apply?.takeIf { item.id in it.ids }

    fun search() {
        val q = query.trim()
        val from = source
        // A newer search replaces an older one outright: the older answer, arriving late, must not
        // overwrite the newer list. A new search also puts away how the last pick went.
        searchJob?.cancel()
        vm.clearMatch()
        if (q.isEmpty()) {
            found = Found(from, emptyList(), failed = false)
            return
        }
        found = null
        searchJob = scope.launch {
            val result = runCatching {
                if (from == MatchSource.ANILIST) vm.searchAnimeMatches(q) else vm.searchMatches(q)
            }
            if (!isActive) return@launch
            found = Found(from, result.getOrDefault(emptyList()), failed = result.isFailure)
        }
    }
    LaunchedEffect(item.id, source) { search() }
    // Done: the page closes, and the caller shows the card the file is on now.
    LaunchedEffect(mine?.state) {
        if (mine?.state == MatchApplyState.DONE) {
            vm.clearMatch()
            onApplied()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TitleBar(
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.det_fix_match), maxLines = 1)
                    Text(
                        group.title,
                        style = PicoTheme.typography.bodyMedium,
                        color = colors.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            titleAlignment = TitleAlignment.CenterInBar,
            leadingActions = {
                val back = stringResource(R.string.det_back)
                IconButton(onClick = onBack, modifier = Modifier.tooltip(text = back), colors = secondaryIconButtonColors()) {
                    Icon(painterResource(R.drawable.ic_pc_back), back, Modifier.size(20.dp))
                }
            },
        )
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 32.dp, end = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    onSearch = { search() },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.fix_query_hint)) },
                    enabled = !running,
                )
                Button(onClick = { search() }, enabled = query.isNotBlank() && !running) {
                    Text(stringResource(R.string.fix_search))
                }
            }

            // Where to look, and — on a card of several files — how much of it to move.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                OptionGroup(stringResource(R.string.fix_source)) {
                    SegmentControl(modifier = Modifier.width(IntrinsicSize.Max)) {
                        SegmentItem(
                            selected = source == MatchSource.TMDB,
                            onClick = { if (!running) source = MatchSource.TMDB },
                            title = { Text(stringResource(R.string.fix_source_tmdb), maxLines = 1, softWrap = false) },
                        )
                        SegmentItem(
                            selected = source == MatchSource.ANILIST,
                            onClick = { if (!running) source = MatchSource.ANILIST },
                            title = { Text(stringResource(R.string.fix_source_anilist), maxLines = 1, softWrap = false) },
                        )
                    }
                }
                if (group.items.size > 1) {
                    OptionGroup(stringResource(R.string.fix_scope)) {
                        SegmentControl(modifier = Modifier.width(IntrinsicSize.Max)) {
                            val n = group.items.size
                            SegmentItem(
                                selected = wholeCard,
                                onClick = { if (!running) wholeCard = true },
                                title = { Text(pluralStringResource(R.plurals.fix_scope_card, n, n), maxLines = 1, softWrap = false) },
                            )
                            SegmentItem(
                                selected = !wholeCard,
                                onClick = { if (!running) wholeCard = false },
                                title = { Text(stringResource(R.string.fix_scope_file), maxLines = 1, softWrap = false) },
                            )
                        }
                    }
                }
            }

            val list = found
            // How the pick is going; before a pick, what to do with the list. (Any 修正匹配 still
            // running holds the page — this one's, or one started on another card a moment ago.)
            when {
                running -> StatusLine(stringResource(R.string.fix_applying), busy = true)
                mine?.state == MatchApplyState.FAILED -> StatusLine(stringResource(R.string.fix_failed), warning = true)
                list != null && list.results.isNotEmpty() -> StatusLine(stringResource(R.string.fix_hint))
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                when {
                    list == null -> EmptyState(title = stringResource(R.string.fix_searching), loading = true)
                    list.failed -> EmptyState(
                        title = stringResource(R.string.fix_error_title),
                        iconRes = R.drawable.ic_det_warning,
                        message = stringResource(R.string.fix_error_body),
                        action = {
                            Button(onClick = { search() }, colors = secondaryButtonColors()) { Text(stringResource(R.string.fix_retry)) }
                        },
                    )
                    list.results.isEmpty() -> EmptyState(
                        title = stringResource(R.string.fix_no_results_title),
                        message = stringResource(if (list.from == MatchSource.ANILIST) R.string.fix_no_results_anilist else R.string.fix_no_results),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 32.dp),
                    ) {
                        // TMDB numbers its films and its series separately, so an id alone is not a key.
                        items(list.results, key = { resultKey(list.from, it) }) { r ->
                            val key = resultKey(list.from, r)
                            CandidateRow(
                                r = r,
                                current = isCurrent(r, list.from, group),
                                applying = mine?.state == MatchApplyState.RUNNING && mine.result == key,
                                enabled = !running,
                            ) { confirming = r }
                        }
                    }
                }
            }
        }
    }

    // A pick is asked about first: it re-identifies files and pins the answer for good.
    val picked = confirming
    if (picked != null) {
        val from = found?.from ?: source
        val targets = if (wholeCard || group.items.size == 1) group.items else listOf(item)
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = {
                val year = picked.year
                Text(
                    if (year != null) stringResource(R.string.fix_confirm_title_year, picked.title, year)
                    else stringResource(R.string.fix_confirm_title, picked.title),
                )
            },
            content = {
                Text(
                    if (targets.size > 1) pluralStringResource(R.plurals.fix_confirm_card, targets.size, targets.size)
                    else stringResource(R.string.fix_confirm_file, targets.first().fileName),
                )
            },
            buttons = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { confirming = null }, colors = secondaryButtonColors()) { Text(stringResource(R.string.cancel)) }
                    Button(
                        onClick = {
                            confirming = null
                            onApplyStarted()
                            vm.startMatch(targets, picked, anime = from == MatchSource.ANILIST, resultKey = resultKey(from, picked))
                        },
                    ) { Text(stringResource(R.string.fix_apply)) }
                }
            },
        )
    }
}

/** How a result is told apart from every other one in a list: its database, its kind and its id. */
private fun resultKey(from: MatchSource, r: MetaResult): String = "${from.name}:${r.kind.name}:${r.providerId}"

/** Whether [r] is what the card is filed under now — the entry a fix would be moving away from. */
private fun isCurrent(r: MetaResult, from: MatchSource, group: MediaGroup): Boolean {
    val rep = group.representative
    if (rep.scrapeStatus != ScrapeStatus.MATCHED) return false
    return when (from) {
        MatchSource.ANILIST -> group.items.any { it.collectionKey == "anilist:${r.providerId}" }
        // An AniList match files its id where TMDB's goes, so an anilist: card is never TMDB's.
        MatchSource.TMDB -> rep.tmdbId == r.providerId &&
            rep.collectionKey?.startsWith("anilist:") != true &&
            (r.kind == MediaKind.MOVIE) == (rep.kind == MediaKind.MOVIE)
    }
}

/**
 * What to search for first: the name the file itself carries, cleaned of release noise. The card's
 * title is the wrong thing to start from — when the match is wrong, it is the wrong title, and the
 * first search would find the wrong entry again. A file with no usable name falls back to the card's.
 */
private fun searchTitleFor(item: MediaItem, group: MediaGroup): String {
    // A show is fixed as a whole: by the name most of its files carry, not that of the one file the card is
    // shown by — 进击的巨人's 158 files were searched as 「Shingeki no Kyojin - Guren no Yumiya」, a film among them.
    val files = if (group.isSeries && group.items.size > 2) group.items else listOf(item)
    val names = files.mapNotNull(::titleFromFile)
    val usual = names.groupingBy { it.lowercase() }.eachCount().maxByOrNull { it.value }?.key
    return names.firstOrNull { it.lowercase() == usual } ?: group.title
}

/** The title a file's own name gives, as the scan reads it; null for none worth searching. */
private fun titleFromFile(item: MediaItem): String? {
    val anime = AnimeParse.parse(item.fileName)
    val fromFile = anime.title?.takeIf { anime.isAnimeLikely } ?: FilenameParser.parseFromPath(item.path).title
    return fromFile.trim().takeUnless { it.isBlank() || FilenameParser.isJunkTitle(it) }
}

/** A labelled control in the options row: its name, then the control. */
@Composable
private fun OptionGroup(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
        content()
    }
}

/** One line of how things stand: working on it (spinner), something went wrong (warning), or a hint. */
@Composable
private fun StatusLine(text: String, busy: Boolean = false, warning: Boolean = false) {
    val colors = PicoTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            busy -> CircularProgressIndicator()
            warning -> Icon(
                painterResource(R.drawable.ic_det_warning),
                contentDescription = null,
                tint = colors.alert,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(text, style = PicoTheme.typography.bodyMedium, color = if (warning || busy) colors.labelSecondary else colors.labelTertiary)
    }
}

/**
 * One search result as a list row — the whole row picks it, with the system hover and haptics. Its
 * poster, its name with what kind of thing it is, then year · original title · rating and the start
 * of its synopsis: what tells two entries of one name apart.
 */
@Composable
private fun CandidateRow(r: MetaResult, current: Boolean, applying: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = PicoTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            // The row being applied stays at full strength; the others dim and stop answering.
            .alpha(if (enabled || applying) 1f else LocalDisableAlpha.current)
            .clip(shape)
            // ListItem lights itself (one more hover here lit it twice over); a dimmed row does not.
            .disableSpatialHoverEffect(!enabled)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction),
        shape = shape,
        leadingContent = { MatchPoster(r.posterUrl) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Weighted, so a long title ellipsizes instead of pushing the badges out.
                Text(
                    r.title,
                    style = PicoTheme.typography.bodyLarge,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                kindLabel(r.kind)?.let { kind ->
                    Badge(badgeColor = BadgeDefaults.badgeColors(backgroundColor = colors.fillSecondary, contentColor = colors.labelSecondary)) {
                        Text(kind)
                    }
                }
                if (current) {
                    Badge(badgeColor = BadgeDefaults.badgeColors(backgroundColor = colors.interaction, contentColor = colors.labelPrimaryLight)) {
                        Text(stringResource(R.string.fix_current))
                    }
                }
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val facts = listOfNotNull(
                    r.year?.toString(),
                    r.originalTitle?.takeIf { it.isNotBlank() && it != r.title },
                    r.rating?.takeIf { it > 0 }?.let { stringResource(R.string.fix_rating, it) },
                )
                if (facts.isNotEmpty()) {
                    Text(
                        facts.joinToString("  ·  "),
                        style = PicoTheme.typography.bodyMedium,
                        color = colors.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                r.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = PicoTheme.typography.bodySmall, color = colors.labelTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        trailingContent = if (applying) {
            { CircularProgressIndicator() }
        } else null,
    )
}

/** What kind of entry a result is, in the reader's words; nothing when the database does not say. */
@Composable
private fun kindLabel(kind: MediaKind): String? = when (kind) {
    MediaKind.MOVIE -> stringResource(R.string.fix_kind_movie)
    MediaKind.SHOW, MediaKind.EPISODE -> stringResource(R.string.fix_kind_show)
    MediaKind.UNKNOWN -> null
}

/** A result's poster at thumbnail size, fetched through the artwork cache the first time it is shown. */
@Composable
private fun MatchPoster(url: String?) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { POSTER_WIDTH.roundToPx() }
    val poster by produceState<ImageBitmap?>(initialValue = null, url) {
        value = decodeMatchPoster(ctx, url, reqWidthPx = px)
    }
    Box(
        modifier = Modifier
            .size(POSTER_WIDTH, POSTER_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(PicoTheme.colorScheme.fillTertiary),
    ) {
        poster?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

private val POSTER_WIDTH = 48.dp
private val POSTER_HEIGHT = 72.dp
