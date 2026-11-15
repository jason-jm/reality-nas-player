package com.therealityteam.videoplayer.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.Sheet
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.ui.common.cardHoverArea
import com.therealityteam.videoplayer.ui.common.cardHoverPicture
import com.therealityteam.videoplayer.ui.common.rememberCardHover
import com.therealityteam.videoplayer.ui.theme.ThemeBridge
import com.therealityteam.videoplayer.ui.theme.glassSurface

/**
 * A series as one tile of the 系列 lane: its first three posters fanned on a glass card tinted by
 * the first, the earliest in front — a stack that says "several of one thing" before its name does —
 * then the name and how many there are. The card lights as one, as a poster does.
 */
@Composable
internal fun SeriesTile(series: SeriesTitles.Series, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val hover = rememberCardHover()
    val colors = PicoTheme.colorScheme
    val first = series.cards.first()
    Column(
        modifier = modifier
            .width(SERIES_TILE_WIDTH)
            .cardHoverArea(hover)
            .clickable(interactionSource = hover.interaction, indication = null, onClick = onClick)
            .controllerHapticFeedback(interactionSource = hover.interaction),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SERIES_TILE_HEIGHT)
                .glassSurface(corner = 16.dp, tint = ThemeBridge.ambient(first.dominantColor, first.title))
                .cardHoverPicture(hover, SeriesShape, LocalIndication.current),
        ) {
            // Back to front, so the earliest is drawn last, on top; each one back a little smaller, so
            // the three read as a stack rather than a row.
            series.cards.take(STACK).withIndex().reversed().forEach { (i, card) ->
                val scale = 1f - STACK_SHRINK * i
                StackedPoster(
                    card = card,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = STACK_INSET + STACK_STEP * i)
                        .size(width = STACK_POSTER_WIDTH * scale, height = STACK_POSTER_HEIGHT * scale),
                )
            }
        }
        Text(
            text = series.name,
            style = PicoTheme.typography.titleMedium,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = pluralStringResource(R.plurals.lib_series_count, series.cards.size, series.cards.size),
            style = PicoTheme.typography.bodySmall,
            color = colors.labelSecondary,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** One poster of a stack: the card's artwork, or its name set as a cover while there is none. */
@Composable
private fun StackedPoster(card: MediaGroup, modifier: Modifier = Modifier) {
    val reqPx = with(LocalDensity.current) { STACK_POSTER_WIDTH.roundToPx() }
    val poster by produceState<ImageBitmap?>(cachedArtwork(card.posterPath, reqPx), card.posterPath, reqPx) {
        value = decodeArtwork(card.posterPath, reqWidthPx = reqPx)
    }
    Box(modifier = modifier.clip(RoundedCornerShape(10.dp))) {
        val bmp = poster
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            TitleTile(title = card.title, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * A series opened from its tile: every card of it in this library, in the order they came out, as
 * the wall shows them; one opens that title's page.
 */
@Composable
internal fun SeriesSheet(series: SeriesTitles.Series, onOpen: (MediaGroup) -> Unit, onDismiss: () -> Unit) {
    Sheet(onDismissRequest = onDismiss, title = { Text(series.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }) {
        Column(modifier = Modifier.width(720.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                text = pluralStringResource(R.plurals.lib_series_in_order, series.cards.size, series.cards.size),
                style = PicoTheme.typography.bodyLarge,
                color = PicoTheme.colorScheme.labelSecondary,
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Each by what it is within the series: the sheet's title already says 机动战士高达.
                items(series.cards, key = { it.key }) { card ->
                    PosterCard(group = card, onClick = { onOpen(card) }, title = series.shortTitle(card))
                }
            }
        }
    }
}

/** How many posters a tile fans, and how: each the next one's step to the right, behind it. */
private const val STACK = 3
private val SERIES_TILE_WIDTH = 264.dp
private val SERIES_TILE_HEIGHT = 180.dp
private val STACK_POSTER_HEIGHT = 152.dp
private val STACK_POSTER_WIDTH = 101.dp
private val STACK_INSET = 14.dp
private val STACK_STEP = 66.dp
private const val STACK_SHRINK = 0.06f
private val SeriesShape = RoundedCornerShape(16.dp)
