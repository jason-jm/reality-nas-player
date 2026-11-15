package com.therealityteam.videoplayer.desktop.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import kotlinx.coroutines.launch

/**
 * A row of cards a mouse can page through as well as a trackpad. It runs out to the page's edges
 * through the [gutter] the page keeps, so cards slide under the edge rather than being cut at the
 * margin; and while the pointer is over it, round arrows at either end turn a page — the card cut
 * at the edge becomes the first. A wheel only scrolls a page up and down, and a row whose end is
 * out of sight otherwise gives no sign that it goes on.
 *
 * [arrowCenter] is the arrows' height from the row's top: the middle of the pictures, not of the
 * words under them.
 */
@Composable
fun <T> PagedRow(
    items: List<T>,
    key: (T) -> Any,
    gutter: Dp,
    arrowCenter: Dp,
    modifier: Modifier = Modifier,
    spacing: Dp = 18.dp,
    state: LazyListState = rememberLazyListState(),
    content: @Composable (T) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val scope = rememberCoroutineScope()
    Box(modifier.bleed(gutter).hoverable(source)) {
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items(items, key = key) { content(it) }
        }
        PageArrow(Lucide.ChevronLeft, visible = hovered && state.canScrollBackward, Modifier.align(Alignment.TopStart), arrowCenter) {
            scope.launch { state.page(forward = false) }
        }
        PageArrow(Lucide.ChevronRight, visible = hovered && state.canScrollForward, Modifier.align(Alignment.TopEnd), arrowCenter) {
            scope.launch { state.page(forward = true) }
        }
    }
}

/** Wider than the parent by [gutter] on each side, reaching over its padding. */
private fun Modifier.bleed(gutter: Dp): Modifier = layout { measurable, constraints ->
    if (constraints.maxWidth == Constraints.Infinity) {
        val p = measurable.measure(constraints)
        return@layout layout(p.width, p.height) { p.place(0, 0) }
    }
    val extra = (gutter * 2).roundToPx()
    val p = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(p.width - extra, p.height) { p.place(-gutter.roundToPx(), 0) }
}

@Composable
private fun PageArrow(icon: ImageVector, visible: Boolean, modifier: Modifier, center: Dp, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(140))
    if (alpha == 0f) return
    val c = Aura.colors
    val size = 36.dp
    Box(
        modifier
            .offset(y = center - size / 2)
            .padding(horizontal = 10.dp)
            .alpha(alpha)
            .size(size)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(c.raised)
            .border(1.dp, c.strokeStrong, CircleShape)
            .clickable(enabled = visible, role = Role.Button, onClick = onClick)
            .clickCursor(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, c.text, 20.dp)
    }
}

/**
 * Turn a page: as many cards as fit wholly, forward or back, so a page starts with a whole card
 * at the margin — forward, the one that was cut at the far edge.
 */
internal suspend fun LazyListState.page(forward: Boolean) {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return
    val areaEnd = info.viewportEndOffset - info.afterContentPadding
    val whole = info.visibleItemsInfo.filter { it.offset >= 0 && it.offset + it.size <= areaEnd }
    val first = whole.firstOrNull()?.index ?: firstVisibleItemIndex
    val n = whole.size.coerceAtLeast(1)
    animateScrollToItem((if (forward) first + n else first - n).coerceIn(0, info.totalItemsCount - 1))
}
