package com.therealityteam.videoplayer.ui.library

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.ScrollIndicator
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.tooltip
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.ui.common.hoverDebug
import kotlinx.coroutines.launch

/**
 * The spatial poster wall — adaptive grid of grouped title cards, with the system scroll indicator
 * at its edge: a wall of several hundred cards is a long way down, and the indicator is both where
 * you are in it and the handle to get somewhere else. In title order the letter rail ([index])
 * takes its place: it shows where you are as well, and goes straight to a letter.
 *
 * [empty] is drawn, centred, in place of the grid when there are no cards and no [header].
 */
@Composable
fun PosterWall(
    groups: List<MediaGroup>,
    onSelect: (MediaGroup) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    /** Drawn above the grid across its full width — the 继续观看 row. */
    header: (@Composable () -> Unit)? = null,
    /**
     * The same cards under headings — 历史 by day: each title spans the width above its run of cards.
     * When given, it is what the grid shows, in its order, instead of [groups].
     */
    sections: List<Pair<String, List<MediaGroup>>>? = null,
    /** 字母索引: the marks beside a wall in title order and each one's first card in [groups] (see TitleIndex). */
    index: List<Pair<Char, Int>>? = null,
    empty: @Composable () -> Unit,
) {
    if (groups.isEmpty() && header == null) {
        Box(modifier = Modifier.fillMaxSize().then(modifier), contentAlignment = Alignment.Center) { empty() }
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().then(modifier)) {
        // The rail only where its marks fit at a size a pointer can rest on; a short window keeps the indicator.
        val rail = index?.takeIf { it.size > 1 && sections == null && maxHeight >= RAIL_MIN_MARK * it.size + RAIL_MARGIN * 2 }
        val markHeight = rail?.let { ((maxHeight - RAIL_MARGIN * 2) / it.size).coerceAtMost(RAIL_MAX_MARK) }
        val endPadding = if (rail != null) RAIL_GUTTER else 32.dp
        // The width of a column, as the grid will give it ([GridCells.Adaptive]'s sum), for every card
        // at once — so each need not measure itself ([LocalPosterWidthPx]).
        val density = LocalDensity.current
        val cellPx = with(density) {
            val available = constraints.maxWidth - 32.dp.roundToPx() - endPadding.roundToPx()
            val gap = COLUMN_GAP.roundToPx()
            val count = maxOf((available + gap) / (CARD_MIN.roundToPx() + gap), 1)
            (available - gap * (count - 1)) / count
        }
        LazyVerticalGrid(
            // Four across a default window rather than five: a poster read from a metre away has to be
            // big enough to recognise, and a wider window simply holds more.
            columns = GridCells.Adaptive(minSize = CARD_MIN),
            state = state,
            modifier = Modifier.fillMaxSize(),
            // PICO's spacing tokens: the window's Padding Large (32) at the sides and foot, Gap Large
            // (16) between columns, Padding Medium (24) between rows — which is also what puts a
            // section heading in the header closer to the posters it names than to the row above it.
            contentPadding = PaddingValues(start = 32.dp, top = 8.dp, end = endPadding, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (header != null) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
            }
            if (sections == null) {
                items(items = groups, key = { it.key }) { group ->
                    CompositionLocalProvider(LocalPosterWidthPx provides cellPx) {
                        PosterCard(group = group, onClick = { onSelect(group) })
                    }
                }
            } else {
                sections.forEachIndexed { i, (title, cards) ->
                    item(key = "section/$title", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = title,
                            style = PicoTheme.typography.titleLarge,
                            color = PicoTheme.colorScheme.labelPrimary,
                            // A day's heading sits nearer its own cards than the last day's (the grid's
                            // row gap below it, a larger step above it).
                            modifier = Modifier.padding(top = if (i == 0) 8.dp else 20.dp),
                        )
                    }
                    items(items = cards, key = { it.key }) { group ->
                        CompositionLocalProvider(LocalPosterWidthPx provides cellPx) {
                            PosterCard(group = group, onClick = { onSelect(group) })
                        }
                    }
                }
            }
        }
        if (rail != null && markHeight != null) {
            LetterRail(
                rail, state,
                headerOffset = if (header != null) 1 else 0,
                markHeight = markHeight,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            )
        } else {
            ScrollIndicator(state = state)
        }
    }
}

/**
 * 字母索引: the wall's marks in a column at its end edge. A press goes to the mark's first card at
 * once — no animated sweep past the eyes, the thing to spare somebody in a headset — and the mark of
 * the cards in view is lit, as the indicator's thumb showed where you were.
 *
 * The lit mark is that of the last card in the top row in sight: a grid scrolls by rows, so a press
 * on M can only bring the row M begins in to the top — a row that may start with the last L.
 */
@Composable
private fun LetterRail(index: List<Pair<Char, Int>>, state: LazyGridState, headerOffset: Int, markHeight: Dp, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val current by remember(index, headerOffset) {
        derivedStateOf {
            val layout = state.layoutInfo
            val visible = layout.visibleItemsInfo
            // The top row in sight: the grid also lays out the row above the viewport, out of view.
            val first = visible.firstOrNull { it.index >= headerOffset && it.offset.y + it.size.height / 2 > layout.viewportStartOffset }
            val rowLast = first?.let { f -> visible.filter { it.row == f.row }.maxOf { it.index } } ?: headerOffset
            TitleIndex.current(index, rowLast - headerOffset)
        }
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        index.forEach { (mark, position) ->
            RailMark(mark, selected = mark == current, height = markHeight) {
                scope.launch { state.scrollToItem(position + headerOffset) }
            }
        }
    }
}

/** One mark: a letter, or # (digits and signs) and ? (the cards no database named), named in a tooltip. */
@Composable
private fun RailMark(mark: Char, selected: Boolean, height: Dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val name = when (mark) {
        TitleIndex.OTHER -> stringResource(R.string.lib_index_other)
        TitleIndex.UNIDENTIFIED -> stringResource(R.string.lib_index_unidentified)
        else -> mark.toString()
    }
    val colors = PicoTheme.colorScheme
    Box(
        modifier = Modifier
            .then(if (name.length > 1) Modifier.tooltip(text = name) else Modifier)
            .size(width = RAIL_WIDTH, height = height)
            // The whole slot takes the press — no dead line between two letters — but what is drawn
            // and lit sits inside it: stacked edge to edge, a lit letter's box met the chosen one's.
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction)
            .semantics { contentDescription = name }
            .padding(vertical = RAIL_MARK_INSET)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.fillTertiary else Color.Transparent)
            .spatialHoverEffect()
            .hoverDebug()
            .indication(interaction, LocalIndication.current),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            mark.toString(),
            style = PicoTheme.typography.labelMedium,
            color = if (selected) colors.interaction else colors.labelSecondary,
        )
    }
}

/** A mark's size: wide enough to rest a pointer on, tall up to [RAIL_MAX_MARK] and never under [RAIL_MIN_MARK]. */
private val RAIL_WIDTH = 32.dp
private val RAIL_MAX_MARK = 26.dp
private val RAIL_MIN_MARK = 18.dp
/** Above and below a mark's box, inside its slot: two boxes stand 3dp apart. */
private val RAIL_MARK_INSET = 1.5.dp
private val RAIL_MARGIN = 24.dp

/** The grid's end padding beside the rail: the rail and a little air, where the indicator needed 32. */
private val RAIL_GUTTER = 56.dp

/** A column's least width and the gap between columns. */
private val CARD_MIN = 180.dp
private val COLUMN_GAP = 16.dp
