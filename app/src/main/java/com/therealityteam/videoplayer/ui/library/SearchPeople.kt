package com.therealityteam.videoplayer.ui.library

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.ui.common.cardHoverArea
import com.therealityteam.videoplayer.ui.common.cardHoverPicture
import com.therealityteam.videoplayer.ui.common.rememberCardHover

/**
 * 人物, over a search's answer: the faces whose names hold the query, each with how many cards in
 * this library credit them — one opens them all. Then 作品, the cards the query found, when there
 * are any: a heading belongs to what it names, so it sits nearer the posters than the faces.
 */
@Composable
fun SearchPeopleLane(hits: List<PeopleSearch.Hit>, onOpen: (PeopleSearch.Hit) -> Unit, titlesBelow: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        LaneHeading(stringResource(R.string.lib_search_people_title))
        Spacer(Modifier.height(8.dp))
        // As many lines for every name as the longest takes, so the counts under them stand in one
        // line across the row — and a row of short names keeps no empty line under each.
        var nameLines by remember(hits) { mutableIntStateOf(1) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(hits, key = { "person/" + it.person.name }) { hit ->
                PersonHitTile(hit, nameLines, onNameLines = { if (it > nameLines) nameLines = it }, onClick = { onOpen(hit) })
            }
        }
        if (titlesBelow) {
            Spacer(Modifier.height(28.dp))
            LaneHeading(stringResource(R.string.lib_search_titles))
        }
    }
}

/** A face, the name under it, and how many of their cards the library holds — the whole tile opens them. */
@Composable
private fun PersonHitTile(hit: PeopleSearch.Hit, nameLines: Int, onNameLines: (Int) -> Unit, onClick: () -> Unit) {
    val colors = PicoTheme.colorScheme
    // As the cast row's faces: the whole tile answers the gaze, the face lights up ([CardHover]).
    val hover = rememberCardHover()
    Column(
        modifier = Modifier
            .width(120.dp)
            .cardHoverArea(hover)
            .clickable(interactionSource = hover.interaction, indication = null, onClick = onClick)
            .controllerHapticFeedback(interactionSource = hover.interaction)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.cardHoverPicture(hover, CircleShape, LocalIndication.current)) {
            Avatar(hit.person, size = 80.dp)
        }
        Text(
            text = hit.person.name,
            style = PicoTheme.typography.bodyMedium,
            color = colors.labelPrimary,
            maxLines = 2,
            minLines = nameLines.coerceIn(1, 2),
            onTextLayout = { onNameLines(it.lineCount) },
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = pluralStringResource(R.plurals.lib_search_person_works, hit.works.size, hit.works.size),
            style = PicoTheme.typography.labelMedium,
            color = colors.interaction,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The wall's lane heading (as over 最近添加): Title L, a clear step above the cards' names. */
@Composable
private fun LaneHeading(text: String) {
    Text(text, style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
