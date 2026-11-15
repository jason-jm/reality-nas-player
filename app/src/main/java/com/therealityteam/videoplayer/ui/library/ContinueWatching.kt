package com.therealityteam.videoplayer.ui.library

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.therealityteam.videoplayer.data.playback.ResumeFrame
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.vibrant.Vibrant
import com.pico.spatial.ui.foundation.vibrant.withVibrant
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.scrape.EpisodeInfo
import com.therealityteam.videoplayer.data.scrape.GenreCn
import com.therealityteam.videoplayer.ui.common.cardHoverArea
import com.therealityteam.videoplayer.ui.common.cardHoverPicture
import com.therealityteam.videoplayer.ui.common.hoverDebug
import com.therealityteam.videoplayer.ui.common.rememberCardHover
import com.therealityteam.videoplayer.ui.theme.ThemeBridge
import com.therealityteam.videoplayer.ui.theme.glassSurface
import java.util.Calendar
import androidx.compose.runtime.key

/**
 * "Episode 5 · 42 min left" — what the tap resumes; "Next up · Episode 6" for what it starts — and the
 * episode by its own [name] where the database has one: 第 5 集：孩子 · 还剩 42 分钟.
 */
fun ContinueWatching.subtitle(ctx: Context, entry: ContinueWatching.Entry, name: String? = null): String {
    val sub = sub(entry)
    val where = sub.episode?.let { EpisodeNaming.label(ctx, it, entry.numberedRun) }
        ?.let { label -> if (name.isNullOrBlank()) label else ctx.getString(R.string.ep_with_name, label, name) }
    val tail = when {
        sub.nextUp -> null
        sub.minutesLeft != null -> sub.minutesLeft!!.let { ctx.resources.getQuantityString(R.plurals.cw_left_min, it, it) }
        // A position in a file of unknown length is "0% watched", which says nothing worth reading.
        (sub.percent ?: 0) > 0 -> ctx.getString(R.string.cw_seen_pct, sub.percent)
        else -> null
    }
    val head = if (sub.nextUp) ctx.getString(R.string.cw_next_up) else null
    return listOfNotNull(head, where, tail).joinToString(ctx.getString(R.string.meta_separator))
}

/** A series laid out as a numbered run of files rather than named episodes (see [EpisodeNaming]). */
private val ContinueWatching.Entry.numberedRun: Boolean
    get() = group.numberedRun

/** Whether the card has a resume position worth a bar: begun, with a known length, not the next episode. */
private val ContinueWatching.Entry.showsProgress: Boolean
    get() = !nextUp && item.progress.durationMs > 0L

/**
 * The top of the 全部 wall. When there is something to go back to, under one heading: the latest
 * title as a cinematic card — its backdrop across the width of the window, where you are, and
 * 继续播放 — then the rest of what is part-way through as a lane of wide cards. Then 最近添加, the
 * newest arrivals as a lane of posters: the wall itself is usually in A→Z order, where a title that
 * came in yesterday sits somewhere in the middle. Then 因为你看过 and 系列, and a heading for the
 * whole library below. A wall
 * a metre away has to say "this is where you were" and "this is new" before it says anything else.
 */
@Composable
fun ContinueHeader(
    entries: List<ContinueWatching.Entry>,
    onPlay: (ContinueWatching.Entry) -> Unit,
    onDetails: (ContinueWatching.Entry) -> Unit,
    modifier: Modifier = Modifier,
    recent: List<MediaGroup> = emptyList(),
    because: BecauseYouWatched.Row? = null,
    series: List<SeriesTitles.Series> = emptyList(),
    onOpen: (MediaGroup) -> Unit = {},
    onOpenSeries: (SeriesTitles.Series) -> Unit = {},
    /** Each episode as the database tells it, by item id (see LibraryViewModel.episodes). */
    loadEpisodes: suspend (MediaGroup) -> Map<String, EpisodeInfo> = { emptyMap() },
) {
    if (entries.isEmpty() && recent.isEmpty() && because == null && series.isEmpty()) return
    val recentPx = with(LocalDensity.current) { RECENT_CARD_WIDTH.roundToPx() }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).then(modifier)) {
        if (entries.isNotEmpty()) {
            SectionHeading(stringResource(R.string.cw_title))
            Spacer(Modifier.height(12.dp))
            // A card of its own for each title: when the first entry changes — taken out of the row,
            // or another watched more lately — the pictures of the one before do not stay on it.
            val first = entries.first()
            key(first.group.key, first.item.id) {
                // The episode it would play, by its own name and what happens in it: the card says
                // where you are, and a number is only half of that.
                val told by produceState(emptyMap<String, EpisodeInfo>(), first.group.key) { value = loadEpisodes(first.group) }
                FeaturedCard(first, info = told[first.item.id], onPlay = { onPlay(first) }, onDetails = { onDetails(first) })
            }
            val rest = entries.drop(1)
            if (rest.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(rest, key = { it.group.key + "/" + it.item.id }) { entry ->
                        ContinueCard(entry, onClick = { onPlay(entry) })
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
        if (recent.isNotEmpty()) {
            // Its cards say when each came, whatever order the wall below is in — unless they all came
            // on one day, as a library brought in at one sitting does: then the day is said once, on
            // the heading, and the cards give their years as the wall's do. Five "9月13日添加" in a
            // row said nothing five times.
            val ctx = LocalContext.current
            val oneDay = remember(recent) { sameDay(recent.map { g -> g.items.maxOf { it.addedAt } }) }
            SectionHeading(
                oneDay?.let { stringResource(R.string.lib_recently_added_on, relativeDay(ctx, it)) }
                    ?: stringResource(R.string.lib_recently_added),
            )
            Spacer(Modifier.height(12.dp))
            CompositionLocalProvider(LocalWallSort provides LibSort.RECENT.takeIf { oneDay == null }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    items(recent, key = { "recent/" + it.key }) { card ->
                        CompositionLocalProvider(LocalPosterWidthPx provides recentPx) {
                            PosterCard(group = card, onClick = { onOpen(card) }, modifier = Modifier.width(RECENT_CARD_WIDTH))
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        if (because != null) {
            SectionHeading(stringResource(R.string.lib_because, because.seed.title))
            Spacer(Modifier.height(12.dp))
            // Its cards give their scores: a suggestion is weighed, and the score is what weighs it.
            CompositionLocalProvider(LocalWallSort provides LibSort.RATING) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    items(because.cards, key = { "because/" + it.key }) { card ->
                        CompositionLocalProvider(LocalPosterWidthPx provides recentPx) {
                            PosterCard(group = card, onClick = { onOpen(card) }, modifier = Modifier.width(RECENT_CARD_WIDTH))
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        if (series.isNotEmpty()) {
            // 系列: the library's series as stacks, the largest first; one opens the whole of it in order.
            SectionHeading(stringResource(R.string.lib_series))
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(series, key = { "series/" + it.name }) { s -> SeriesTile(s, onClick = { onOpenSeries(s) }) }
            }
            Spacer(Modifier.height(28.dp))
        }
        // Further from the lanes above than from the posters below (the grid's row gap, 24): a
        // heading belongs to what it names.
        SectionHeading(stringResource(R.string.lib_all_titles))
    }
}

/** The day all of [times] fall on (one of them), or null when they span more than one or any is unknown. */
private fun sameDay(times: List<Long>): Long? {
    if (times.isEmpty() || times.any { it <= 0L }) return null
    val dayOf = { ms: Long -> Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) } }
    val first = dayOf(times.first())
    return times.first().takeIf { times.all { dayOf(it) == first } }
}

/** A 最近添加 poster: a little narrower than the wall's, so the lane reads as a preview of it. */
private val RECENT_CARD_WIDTH = 168.dp

/** A heading over a run of the wall: Title L, a clear step above the cards' own Title M names. */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = PicoTheme.typography.titleLarge,
        color = PicoTheme.colorScheme.labelPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The latest title, large: a wide picture — its backdrop, or the episode's still — filling a
 * 24dp-cornered card, a dark veil from the start edge so its words read over any frame, and the two
 * things to do with it. A title with no wide picture shows its poster at the poster's own shape
 * beside the words instead of having it cut down to a band; behind either, the poster's colours,
 * blurred, fill the card. Text on a picture is white on that veil, held out of the glass's colour
 * adaptation (Vibrant.None) — the PICO guidance for type over imagery; the buttons are the light
 * ones it gives for a picture.
 */
@Composable
private fun FeaturedCard(entry: ContinueWatching.Entry, info: EpisodeInfo?, onPlay: () -> Unit, onDetails: () -> Unit) {
    val group = entry.group
    val item = entry.item
    val ctx = LocalContext.current
    val wideSource = group.backdropPath ?: item.stillPath ?: rememberResumeFrame(entry)
    val wide by produceState<ImageBitmap?>(
        initialValue = cachedArtwork(wideSource, 1600),
        wideSource, item.stillUrl,
    ) {
        // Not the last picture while this one decodes (a produceState keeps its value across keys).
        value = cachedArtwork(wideSource, 1600)
        value = decodeArtwork(wideSource, reqWidthPx = 1600)
        if (group.backdropPath == null) decodeStill(ctx, item, reqWidthPx = 1600)?.let { value = it }
    }
    val poster by produceState<ImageBitmap?>(initialValue = cachedArtwork(group.posterPath, 480), group.posterPath) {
        value = cachedArtwork(group.posterPath, 480)
        value = decodeArtwork(group.posterPath, reqWidthPx = 480)
    }
    val ambient = ThemeBridge.ambient(group.dominantColor, group.title)
    val white = Color.White.withVibrant(Vibrant.None)
    val interaction = remember { MutableInteractionSource() }
    val menu = rememberCardMenuState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(ambient.withVibrant(Vibrant.Semidark))
            .spatialHoverEffect()
            .hoverDebug()
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onLongClick = cardLongPress(menu),
                onClick = onPlay,
            )
            .controllerHapticFeedback(interactionSource = interaction),
    ) {
        CardMenuHost(menu, group, entry)
        val wideArt = wide
        val posterArt = poster
        // Whether a wide picture is on its way. Decoding one takes a moment on a cold start, and the
        // card used to wear the poster layout meanwhile — then change shape when the picture landed,
        // the poster vanishing and the words jumping left. It takes the wide layout from the start.
        val expectsWide = wideSource != null || item.stillUrl != null
        // Under everything, the poster's own colours blurred far past recognition: what the card
        // shows until, or instead of, a wide picture — not the flat slate that left half of the
        // window's largest card empty.
        if (posterArt != null) {
            Image(
                bitmap = posterArt,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(56.dp, BlurredEdgeTreatment.Rectangle),
            )
        }
        // The wide picture fades in over it once decoded (already there from the cache: no fade).
        val wideAlpha by animateFloatAsState(if (wideArt != null) 1f else 0f, tween(450), label = "heroWide")
        if (wideArt != null) {
            Image(
                bitmap = wideArt,
                contentDescription = null, // the title is written over it
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(wideAlpha),
            )
        }
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.78f).withVibrant(Vibrant.None),
                    0.55f to Color.Black.copy(alpha = 0.38f).withVibrant(Vibrant.None),
                    1f to Color.Transparent,
                ),
            ),
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            if (!expectsWide && posterArt != null) {
                Image(
                    bitmap = posterArt,
                    contentDescription = null, // the title is written beside it
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxHeight().aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)),
                )
            }
            Column(modifier = Modifier.widthIn(max = 560.dp)) {
                Text(
                    text = group.title,
                    style = PicoTheme.typography.displayMedium,
                    color = white,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // …and 已下载到本机 after the year and genres when the file it would play is here: the hero
                // sets words over its picture, so the fact goes in its words, not in a badge on the art.
                val here = isOnHeadset(item.id)
                val onHeadsetWord = stringResource(R.string.dl_on_headset)
                listOfNotNull(heroMeta(group, item), onHeadsetWord.takeIf { here }).joinToString(" · ").ifBlank { null }?.let {
                    Text(
                        text = it,
                        style = PicoTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f).withVibrant(Vibrant.None),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    text = ContinueWatching.subtitle(ctx, entry, info?.name),
                    style = PicoTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.86f).withVibrant(Vibrant.None),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                com.therealityteam.videoplayer.ui.common.readableSynopsis(info?.overview)?.let {
                    Text(
                        text = it,
                        style = PicoTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f).withVibrant(Vibrant.None),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (entry.showsProgress) {
                    CardProgress(
                        fraction = item.progress.fraction.coerceAtLeast(MIN_VISIBLE_PROGRESS),
                        onScrim = true,
                        modifier = Modifier.padding(top = 16.dp).width(280.dp),
                    )
                }
                Row(modifier = Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onPlay,
                        leadingIcon = { Icon(painterResource(R.drawable.ic_pc_play), contentDescription = null, modifier = Modifier.size(20.dp)) },
                        colors = ButtonDefaults.buttonColors(containerColor = white, contentColor = Color.Black.withVibrant(Vibrant.None)),
                    ) {
                        // The next episode of a series has not been started: it plays, it does not resume.
                        Text(
                            text = if (entry.nextUp) {
                                stringResource(R.string.cw_play_next, EpisodeNaming.label(ctx, item, entry.numberedRun))
                            } else {
                                stringResource(R.string.cw_resume)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = onDetails,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.22f).withVibrant(Vibrant.None),
                            contentColor = white,
                        ),
                    ) { Text(stringResource(R.string.det_details)) }
                }
            }
        }
    }
}

/**
 * 年份 · 类型 · 时长 under the hero's title: what the title is, before what is left of it. The
 * length is the file's own once it has been played — the one the "还剩" under it counts from; the
 * database's runtime said 20 分钟 above 还剩 84 分钟 for a release longer than the work it was matched to.
 */
@Composable
private fun heroMeta(group: MediaGroup, item: MediaItem): String? {
    val parts = buildList {
        group.year?.let { add(it.toString()) }
        addAll(group.representative.genres.flatMap(GenreCn::zhAll).distinct().take(2))
        // A film's length; a series' runtime is an episode's, and its subtitle already says which.
        if (!group.isSeries) {
            val minutes = item.progress.durationMs.takeIf { it > 0L }?.let { (it / 60_000L).toInt() } ?: group.runtime
            minutes?.takeIf { it > 0 }?.let { add(formatRuntime(it)) }
        }
    }
    return parts.joinToString(" · ").ifBlank { null }
}

/**
 * The picture of where [entry] was left, when one was taken (ResumeFrame) — looked for again
 * whenever its position moves, since the file is rewritten as the film plays on.
 */
@Composable
private fun rememberResumeFrame(entry: ContinueWatching.Entry): String? {
    val ctx = LocalContext.current
    val item = entry.item
    if (!ResumeFrame.wanted(item)) return null
    // One stat, where the card is drawn: the large card picks its layout by whether a wide picture is
    // coming, and finding out a frame later moved its words once it had been seen.
    return remember(item.id, item.progress.updatedAt) {
        ResumeFrame.file(ctx.filesDir, item.id).takeIf { it.isFile }?.absolutePath
    }
}

/** A 16:9 card: the episode still or the title's backdrop, the progress on it, title and what's left. */
@Composable
private fun ContinueCard(entry: ContinueWatching.Entry, onClick: () -> Unit) {
    val group = entry.group
    val item = entry.item
    // The title's own artwork is already on disk, so draw that at once; the episode's still is
    // fetched on demand (see MediaItem.stillUrl) and replaces it when it arrives. A card that has
    // no still on record, or no network to get one with, simply keeps the backdrop.
    // …and one with neither has the picture where it was left (ResumeFrame) before its poster, whose
    // middle cropped to 16:9 was a strip of somebody's coat.
    val ctx = LocalContext.current
    val resume = rememberResumeFrame(entry)
    val fallback = group.backdropPath ?: resume ?: group.posterPath
    val art by produceState<ImageBitmap?>(
        initialValue = cachedArtwork(item.stillPath ?: fallback, 640),
        item.stillPath, item.stillUrl, fallback,
    ) {
        value = decodeArtwork(item.stillPath ?: fallback, reqWidthPx = 640)
        decodeStill(ctx, item, reqWidthPx = 640)?.let { value = it }
    }
    val ambient = ThemeBridge.ambient(group.dominantColor, group.title)
    val colors = PicoTheme.colorScheme
    // The custom-component contract — hover, press, haptics on one interaction source — the way the
    // wall's posters take it ([CardHover]): the whole card answers the gaze, the picture lights up.
    val hover = rememberCardHover()
    val menu = rememberCardMenuState()
    val subtitle = ContinueWatching.subtitle(ctx, entry)
    Column(
        modifier = Modifier
            .width(272.dp)
            .cardHoverArea(hover)
            .combinedClickable(
                interactionSource = hover.interaction,
                indication = null,
                onLongClick = cardLongPress(menu),
                onClick = onClick,
            )
            // One node, as a poster on the wall is (PosterCard): its picture said the title over again.
            .clearAndSetSemantics { contentDescription = "${group.title}, $subtitle" }
            .controllerHapticFeedback(interactionSource = hover.interaction),
    ) {
        CardMenuHost(menu, group, entry)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .glassSurface(corner = 12.dp, tint = ambient)
                .cardHoverPicture(hover, RoundedCornerShape(12.dp), LocalIndication.current),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = art
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                )
            } else {
                // The same cover the wall gives a title nothing could name.
                TitleTile(title = group.title, modifier = Modifier.fillMaxSize())
            }
            if (entry.showsProgress) {
                CardProgress(
                    fraction = item.progress.fraction.coerceAtLeast(MIN_VISIBLE_PROGRESS),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                )
            }
            // The episode it would go on with is on the headset: it plays away from the NAS too.
            if (isOnHeadset(item.id)) {
                Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) { DownloadedBadge(null) }
            }
        }
        Text(
            text = group.title,
            style = PicoTheme.typography.titleMedium,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = subtitle,
            style = PicoTheme.typography.bodyMedium,
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // A foot of air under the words, as under a poster's year.
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
    }
}
