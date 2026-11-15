package com.therealityteam.videoplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ChipsDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleableChip
import com.pico.spatial.ui.design.windows.SpatialPopup
import com.pico.spatial.ui.design.windows.popup.HorizontalPlacement
import com.pico.spatial.ui.design.windows.popup.VerticalPlacement
import com.pico.spatial.ui.design.windows.rememberSpatialPopupPositionProvider
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.ui.library.WallFilter.Watch
import com.therealityteam.videoplayer.ui.theme.secondaryButtonColors
import kotlinx.coroutines.delay

/**
 * The wall's 筛选, beside the order: a chip that is lit and counts the choices while any are made, and
 * opens a panel of them — how far a title has been watched, its genre, its decade. A choice applies at
 * once and the panel stays open, the wall behind it already showing the result; another tap on a lit
 * genre or decade takes it back off.
 *
 * [pool] is what the wall would show without the filter (the shelf, or the search): the panel offers
 * only genres and decades that are in it, each with how many cards it has.
 */
@Composable
fun FilterChip(
    filter: WallFilter,
    pool: List<MediaGroup>,
    onChange: (WallFilter) -> Unit,
    modifier: Modifier = Modifier,
    /** Automation: open the panel as if the chip were tapped (`-e aura.screen filter_menu`). */
    openRequested: Boolean = false,
    onOpened: () -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openRequested) {
        if (!openRequested) return@LaunchedEffect
        delay(400)
        open = true
        onOpened() // consumed only now: clearing first re-keys this effect and cancels the open
    }
    Box(modifier = modifier) {
        ToggleableChip(
            label = { Text(if (filter.isActive) stringResource(R.string.lib_filter_n, filter.activeCount) else stringResource(R.string.lib_filter)) },
            isToggleOn = filter.isActive,
            onClick = { open = true },
            chipSize = ChipsDefaults.Regular,
            leadingIcon = { Icon(painterResource(R.drawable.ic_filter), contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, modifier = Modifier.size(16.dp)) },
        )
        if (open) FilterPanel(filter, pool, onChange, onDismiss = { open = false })
    }
}

/** What a set filter reads as under the title: "科幻 · 未看 · 1990 年代 · 全景". */
@Composable
fun filterSummary(filter: WallFilter): String = listOfNotNull(
    filter.genre,
    watchLabel(filter.watch).takeIf { filter.watch != Watch.ANY },
    filter.decade?.let { stringResource(R.string.lib_filter_decade_n, it) },
    when (filter.picture) {
        WallFilter.Picture.PANORAMA -> stringResource(R.string.lib_filter_panorama)
        WallFilter.Picture.THREE_D -> stringResource(R.string.lib_filter_3d)
        WallFilter.Picture.ANY -> null
    },
).joinToString(stringResource(R.string.meta_separator))

@Composable
private fun watchLabel(watch: Watch): String = stringResource(
    when (watch) {
        Watch.ANY -> R.string.lib_filter_any
        Watch.UNWATCHED -> R.string.lib_filter_unwatched
        Watch.IN_PROGRESS -> R.string.lib_filter_in_progress
        Watch.WATCHED -> R.string.lib_filter_watched
    },
)

@Composable
private fun FilterPanel(filter: WallFilter, pool: List<MediaGroup>, onChange: (WallFilter) -> Unit, onDismiss: () -> Unit) {
    val watchCounts = remember(pool) { WallFilter.watchCounts(pool) }
    val genres = remember(pool) { WallFilter.genreCounts(pool) }
    val decades = remember(pool) { WallFilter.decadeCounts(pool) }
    val pictures = remember(pool) { WallFilter.pictureCounts(pool) }
    SpatialPopup(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(PANEL_WIDTH),
        popupPositionProvider = rememberSpatialPopupPositionProvider(
            horizontalPlacement = HorizontalPlacement.alignEnd(),
            verticalPlacement = VerticalPlacement.below(offset = 8.dp),
        ),
        defaultMinWidth = Dp.Unspecified,
        defaultMinHeight = Dp.Unspecified,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            FilterSection(stringResource(R.string.lib_filter_watch)) {
                Watch.entries.forEach { w ->
                    val n = if (w == Watch.ANY) pool.size else watchCounts[w] ?: 0
                    // A state nothing on the shelf is in is no choice at all — unless it is the one set.
                    if (w == Watch.ANY || n > 0 || filter.watch == w) {
                        Choice(stringResource(R.string.lib_filter_choice, watchLabel(w), n), selected = filter.watch == w) {
                            onChange(filter.copy(watch = w))
                        }
                    }
                }
            }
            if (genres.isNotEmpty() || filter.genre != null) {
                FilterSection(stringResource(R.string.lib_filter_genre)) {
                    Choice(stringResource(R.string.lib_filter_any), selected = filter.genre == null) { onChange(filter.copy(genre = null)) }
                    val chosen = filter.genre // a property of another module's class: no smart cast
                    val shown = if (chosen != null && genres.none { it.first == chosen }) genres + (chosen to 0) else genres
                    shown.forEach { (genre, n) ->
                        Choice(stringResource(R.string.lib_filter_choice, genre, n), selected = filter.genre == genre) {
                            onChange(filter.copy(genre = if (filter.genre == genre) null else genre))
                        }
                    }
                }
            }
            if (decades.isNotEmpty() || filter.decade != null) {
                FilterSection(stringResource(R.string.lib_filter_decade)) {
                    Choice(stringResource(R.string.lib_filter_any), selected = filter.decade == null) { onChange(filter.copy(decade = null)) }
                    val chosen = filter.decade
                    val shown = if (chosen != null && decades.none { it.first == chosen }) decades + (chosen to 0) else decades
                    shown.forEach { (decade, n) ->
                        Choice(
                            stringResource(R.string.lib_filter_choice, stringResource(R.string.lib_filter_decade_n, decade), n),
                            selected = filter.decade == decade,
                        ) { onChange(filter.copy(decade = if (filter.decade == decade) null else decade)) }
                    }
                }
            }
            // 画面: 全景 and 3D, offered only where the shelf has them (or one is set).
            if (pictures.isNotEmpty() || filter.picture != WallFilter.Picture.ANY) {
                FilterSection(stringResource(R.string.lib_filter_picture)) {
                    Choice(stringResource(R.string.lib_filter_any), selected = filter.picture == WallFilter.Picture.ANY) {
                        onChange(filter.copy(picture = WallFilter.Picture.ANY))
                    }
                    listOf(WallFilter.Picture.PANORAMA to R.string.lib_filter_panorama, WallFilter.Picture.THREE_D to R.string.lib_filter_3d).forEach { (p, label) ->
                        val n = pictures[p] ?: 0
                        if (n > 0 || filter.picture == p) {
                            Choice(stringResource(R.string.lib_filter_choice, stringResource(label), n), selected = filter.picture == p) {
                                onChange(filter.copy(picture = if (filter.picture == p) WallFilter.Picture.ANY else p))
                            }
                        }
                    }
                }
            }
            if (filter.isActive) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { onChange(WallFilter()) }, colors = secondaryButtonColors()) {
                        Text(stringResource(R.string.lib_filter_reset))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: String, content: @Composable FlowRowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** One choice: the system's toggleable chip, lit when chosen, at the size a pointer at a metre hits. */
@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    ToggleableChip(
        label = { Text(label, maxLines = 1) },
        isToggleOn = selected,
        onClick = onClick,
        chipSize = ChipsDefaults.Regular,
    )
}

private val PANEL_WIDTH = 560.dp
