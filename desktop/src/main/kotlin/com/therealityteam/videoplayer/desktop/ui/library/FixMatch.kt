package com.therealityteam.videoplayer.desktop.ui.library

import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Star
import com.therealityteam.videoplayer.data.scrape.MetaResult
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.ui.AppState
import com.therealityteam.videoplayer.desktop.ui.components.AuraButton
import com.therealityteam.videoplayer.desktop.ui.components.AuraTextField
import com.therealityteam.videoplayer.desktop.ui.components.ButtonKind
import com.therealityteam.videoplayer.desktop.ui.components.Icon
import com.therealityteam.videoplayer.desktop.ui.components.Spinner
import com.therealityteam.videoplayer.desktop.ui.components.Txt
import com.therealityteam.videoplayer.desktop.ui.components.clickCursor
import com.therealityteam.videoplayer.desktop.ui.components.interactionWash
import com.therealityteam.videoplayer.desktop.ui.images.Images
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import com.therealityteam.videoplayer.desktop.ui.theme.AuraShapes
import com.therealityteam.videoplayer.data.scrape.AnimeParse
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.desktop.ui.components.Segmented
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.therealityteam.videoplayer.desktop.ui.Page
import kotlinx.coroutines.launch

/** Where a fix-match search looks. */
private enum class MatchSource { TMDB, ANILIST }

/**
 * Setting a title's identity by hand: search TMDB — or AniList, where most anime are — for the
 * right work, pick it, and the card is refetched (poster, summary, cast, ratings) and pinned so no
 * later scrape undoes it. A series is fixed as a whole: every file of the card moves, each keeping
 * its own episode; a card of unrelated files can be fixed one file at a time.
 */
@Composable
fun FixMatchPage(state: AppState, key: String) {
    val c = Aura.colors
    val s = LocalStrings.current
    val groups by state.graph.library.groups.collectAsState()
    val group = groups.firstOrNull { it.key == key } ?: return
    val item = group.representative
    val scope = rememberCoroutineScope()
    val animeCard = remember(key) {
        group.items.any { it.collectionKey?.startsWith("anilist:") == true } ||
            AnimeParse.parse(item.fileName).isAnimeLikely || FilenameParser.isUnderAnimeRoot(item.path)
    }
    var source by remember(key) { mutableStateOf(if (animeCard) MatchSource.ANILIST else MatchSource.TMDB) }
    var wholeCard by remember(key) { mutableStateOf(group.isSeries || group.items.size == 1) }
    var query by remember(key) { mutableStateOf(group.title) }
    var results by remember(key) { mutableStateOf<List<MetaResult>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf<String?>(null) }
    fun search() {
        loading = true
        val from = source
        scope.launch {
            results = runCatching {
                if (from == MatchSource.ANILIST) state.graph.library.searchAnimeMatches(query.trim())
                else state.graph.library.searchMatches(query.trim())
            }.getOrDefault(emptyList())
            loading = false
        }
    }
    LaunchedEffect(key, source) { search() }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp)) {
        Txt(s["det_fix_match"], Aura.type.title1, c.text)
        Spacer(Modifier.height(4.dp))
        Txt(item.fileName, Aura.type.caption, c.textTertiary)
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AuraTextField(query, { query = it }, Modifier.widthIn(max = 520.dp).weight(1f, fill = false), leading = Lucide.Search, onSubmit = ::search, height = 36.dp)
            Spacer(Modifier.width(10.dp))
            AuraButton(s["fix_search"], ::search, kind = ButtonKind.Primary, height = 36.dp)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(s["d_fix_source"], Aura.type.callout, c.textSecondary)
                Spacer(Modifier.width(8.dp))
                Segmented(listOf(MatchSource.TMDB to (if (TmdbConfig.effectiveKey.isBlank()) s["nav_movies"] else "TMDB"), MatchSource.ANILIST to "AniList"), source, { source = it })
            }
            if (group.items.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(s["d_fix_scope"], Aura.type.callout, c.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    Segmented(
                        listOf(true to s["d_fix_scope_card", group.items.size], false to s["d_fix_scope_file"]),
                        wholeCard, { wholeCard = it },
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        val list = results
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(18.dp)
                Spacer(Modifier.width(10.dp))
                Txt(s["fix_searching"], Aura.type.body, c.textSecondary)
            }
            list != null && list.isEmpty() -> Txt(s["fix_no_results"], Aura.type.body, c.textSecondary, maxLines = 3)
            list != null -> Box(Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(list, key = { source.name + ":" + it.kind.name + ":" + (it.publicMovieKey ?: it.providerId) }) { r ->
                        Candidate(r, applying == (r.publicMovieKey ?: r.providerId.toString())) {
                            if (applying != null) return@Candidate
                            applying = r.publicMovieKey ?: r.providerId.toString()
                            val targets = if (wholeCard) group.items else listOf(item)
                            val anime = source == MatchSource.ANILIST
                            scope.launch {
                                runCatching { state.graph.library.applyMatch(targets, r, anime) }
                                applying = null
                                state.back()
                                // The card is keyed by its identity, which just changed: once the wall
                                // has regrouped, the title page follows the file to its new card.
                                val id = item.id
                                state.scope.launch {
                                    val moved = withTimeoutOrNull(8_000) {
                                        state.graph.library.groups.first { gs -> gs.any { g -> g.key != key && g.items.any { it.id == id } } }
                                    }?.firstOrNull { g -> g.items.any { it.id == id } }
                                    if (moved != null && state.page == Page.Detail(key)) state.replaceTop(Page.Detail(moved.key))
                                }
                            }
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Candidate(r: MetaResult, busy: Boolean, onPick: () -> Unit) {
    val c = Aura.colors
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 980.dp)
            .clip(AuraShapes.card)
            .background(interactionWash(source))
            .clickable(interactionSource = source, indication = null, onClick = onPick)
            .clickCursor()
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(64.dp).aspectRatio(2f / 3f).clip(AuraShapes.small).background(c.surface)) {
            val px = with(LocalDensity.current) { 64.dp.roundToPx() }
            val img by produceState<ImageBitmap?>(null, r.posterUrl) { value = Images.remote(r.posterUrl, px) }
            img?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(r.title, Aura.type.headline, c.text, Modifier.weight(1f, fill = false))
                r.year?.let { Txt("  $it", Aura.type.body, c.textSecondary) }
                r.rating?.takeIf { it > 0 }?.let {
                    Spacer(Modifier.width(10.dp))
                    Icon(Lucide.Star, c.warning, 13.dp)
                    Txt(" %.1f".format(it), Aura.type.callout, c.textSecondary)
                }
            }
            r.originalTitle?.takeIf { it != r.title }?.let { Txt(it, Aura.type.caption, c.textTertiary) }
            r.overview?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Txt(it, Aura.type.caption, c.textSecondary, maxLines = 2)
            }
        }
        if (busy) {
            Spacer(Modifier.width(12.dp))
            Spinner(18.dp)
        }
    }
}
