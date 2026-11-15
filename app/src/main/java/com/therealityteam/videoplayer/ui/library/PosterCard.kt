package com.therealityteam.videoplayer.ui.library

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import com.pico.spatial.ui.design.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Badge
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.tooltip
import com.pico.spatial.ui.foundation.vibrant.Vibrant
import com.pico.spatial.ui.foundation.vibrant.withVibrant
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.common.cardHoverArea
import com.therealityteam.videoplayer.ui.common.cardHoverPicture
import com.therealityteam.videoplayer.ui.common.rememberCardHover
import com.therealityteam.videoplayer.ui.theme.ThemeBridge
import com.therealityteam.videoplayer.ui.theme.glassSurface
import java.util.Calendar

/** The poster card's corner, and the shape its hover highlight and press take. */
private val CardShape = RoundedCornerShape(16.dp)

/**
 * One poster on the spatial wall — a whole title ([MediaGroup]), not a single file. Shows the
 * cached poster when scraped, otherwise a dominant-color vibrant-glass placeholder. A series /
 * multi-file group gets a count badge ("N 集" / "N 个文件").
 *
 * It clicks the way SpatialUI's own controls do: the system hover highlight (the default one — no
 * zoom, no lift), the press indication and the controller's haptics, all on one interaction source.
 * The whole card answers the gaze, its name too, but only the poster lights up and shows the press
 * ([CardHover]): lit as one tile with its words, the card's box came right up against the name, the
 * year and the next card. A name the card has to cut short is given in full on hover.
 */
@Composable
fun PosterCard(
    group: MediaGroup,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // The name under the poster, where the context already says part of it (a series' own sheet).
    title: String = group.title,
) {
    val hover = rememberCardHover()
    var clipped by remember(group.title) { mutableStateOf(false) }
    val menu = rememberCardMenuState()
    val sub = cardSubtitle(group)
    val count = if (!group.isCollection) null
    else if (group.countsEpisodes) pluralStringResource(R.plurals.card_n_episodes, group.shownCount, group.shownCount)
    else pluralStringResource(R.plurals.card_n_files, group.shownCount, group.shownCount)
    CardBox(
        modifier = modifier
            .then(if (clipped) Modifier.tooltip(text = group.title) else Modifier)
            .cardHoverArea(hover)
            .combinedClickable(
                interactionSource = hover.interaction,
                indication = null,
                onLongClick = cardLongPress(menu),
                onClick = onClick,
            )
            // One node for the whole card — its name, its line and its count read as one, its tap and
            // long press kept. The headset reads every node on screen for its hover, every frame the
            // wall moves: with each card's picture and words as nodes of their own, that took nearly
            // as long as drawing the wall.
            .clearAndSetSemantics { contentDescription = listOfNotNull(title, sub, count).joinToString(", ") }
            .controllerHapticFeedback(interactionSource = hover.interaction),
    ) { reqPx ->
        CardMenuHost(menu, group)
        // Null while the decode is still in flight; a [Decoded] once it has finished, carrying null when
        // there was nothing to decode — no artwork was ever scraped, or the file the library points at
        // is no longer on disk. Keeping those two apart is what lets the tile wait quietly for a poster
        // that is coming without waiting forever for one that is not.
        val poster by produceState<Decoded?>(
            initialValue = cachedArtwork(group.posterPath, reqPx)?.let(::Decoded)
                ?: Decoded(null).takeIf { group.posterPath == null },
            group.posterPath,
            reqPx,
        ) {
            value = Decoded(decodeArtwork(group.posterPath, reqWidthPx = reqPx))
        }
        val ambient = ThemeBridge.ambient(group.dominantColor, group.title)
        val colors = PicoTheme.colorScheme
        val rep = group.representative

        // The words' own foot of air, so the next row of posters keeps its distance from this year.
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .glassSurface(corner = 16.dp, tint = ambient)
                    .cardHoverPicture(hover, CardShape, LocalIndication.current),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = poster?.bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = group.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CardShape),
                    )
                } else if (poster != null) {
                    // Nothing to draw and nothing still coming: the name, set as a cover (see
                    // [TitleTile]). Files no database could name are a real part of a real library, and
                    // a tile that only says `S` tells you something is there without telling you what.
                    //
                    // Only once the decode has settled. A card whose poster is still being read keeps
                    // its tinted tile: on a cold start the whole wall would otherwise spell out titles
                    // for the better part of a minute and then swap them for pictures, which reads as a
                    // library that had lost its artwork. But a card whose artwork really is gone —
                    // cleared storage, a library restored onto a device without its cache — settles on
                    // the name instead of an empty tile that never resolves.
                    TitleTile(title = group.title, modifier = Modifier.fillMaxSize())
                }
                // Count badge for a series / multi-file group.
                if (count != null) {
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                        Badge { Text(count) }
                    }
                }
                // How far through the card you are: episodes finished for a series, position for a
                // single film. Two shows of 26 episodes, one of them half seen, used to look identical.
                val seen = group.watchedFraction
                val filmLeft = if (!group.isCollection && rep.progress.started && !rep.progress.watched) rep.progress.fraction else 0f
                val bar = maxOf(seen, filmLeft)
                if (bar > 0f) {
                    CardProgress(
                        fraction = bar.coerceAtLeast(MIN_VISIBLE_PROGRESS),
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                    )
                }
                // Everything here has been watched; and what of it is on the headset, for the trip.
                val onHeadset = downloadedCount(group)
                val coming = downloadingPercent(group)
                if (seen >= 1f || onHeadset > 0 || coming != null) {
                    Row(
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (seen >= 1f) Badge { Text(stringResource(R.string.card_watched)) }
                        // On its way: how far the file now downloading has come (the glyph alone while
                        // the card's files wait their turn); once none is left to come, how many are here.
                        when {
                            coming != null -> DownloadingBadge(coming.takeIf { it >= 0 })
                            onHeadset > 0 -> DownloadedBadge(if (group.isCollection) onHeadset else null)
                        }
                    }
                }
            }
            // The year sits right under the name, however many lines the name took. The name used to
            // be held at two lines so the years of a row lined up — which left "1917" with its year
            // floating a whole empty line below it.
            Text(
                text = title,
                style = PicoTheme.typography.titleMedium,
                color = colors.labelPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (clipped != it.hasVisualOverflow) clipped = it.hasVisualOverflow },
                modifier = Modifier.padding(top = 12.dp),
            )
            sub?.let { line ->
                Text(
                    text = line,
                    style = PicoTheme.typography.bodyMedium,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * The line under a card's name, or null for none. A series says when it aired as a film does: two
 * cards called 攻壳机动队 S.A.C. 2nd GIG (a split the databases have not healed yet) or Ghost in the
 * Shell 1995 / 2017 tell apart. The episode count is the badge's to give, not this line's again.
 * A file no database could name says so, rather than showing its extension.
 */
private val NON_DIGITS = Regex("[^0-9]+")

@Composable
private fun cardSubtitle(group: MediaGroup): String? {
    // A name that already says its year — 简爱 (1996) beside 简爱 (2011), "2002 Sound Horizon
    // Thanatos", "Animelo Summer Live 2007" — is not followed by the same year again. Only the year
    // standing as a number of its own: "2012" (2009) and "1917" (2019) keep theirs.
    val year = group.year?.takeUnless { y -> group.title.split(NON_DIGITS).any { it == y.toString() } }
    // In an order of its own the line says what the card is placed by — the score it ranks on, when
    // it came, when it was last watched — rather than a year that explains nothing about where it is.
    when (LocalWallSort.current) {
        LibSort.RATING -> LibrarySort.ratingFor(group.ratings)?.let { r ->
            val score = "${ratingBrand(r.source).label} ${scoreText(r.value)}"
            return listOfNotNull(score, if (group.isSeries) stringResource(R.string.card_series) else year?.toString()).joinToString(" · ")
        }
        LibSort.RECENT -> group.items.maxOf { it.addedAt }.takeIf { it > 0L }?.let {
            return stringResource(R.string.card_added, relativeDay(LocalContext.current, it))
        }
        LibSort.WATCHED -> group.items.maxOf { it.progress.updatedAt }.takeIf { it > 0L }?.let {
            return stringResource(R.string.card_watched_at, relativeDay(LocalContext.current, it))
        }
        else -> Unit
    }
    return when {
        group.isSeries && year != null -> stringResource(R.string.card_sub_year_kind, year, stringResource(R.string.card_series))
        group.isSeries -> stringResource(R.string.card_series)
        year != null -> year.toString()
        group.representative.scrapeStatus == ScrapeStatus.UNMATCHED -> stringResource(R.string.card_unmatched)
        else -> null
    }
}

/**
 * How far into a card's film or series you are, drawn the same way on every card of the library
 * window: a 4dp bar with round ends, inset from the artwork's edges so the card's rounded corner
 * cannot eat the start of it. It sits on a picture, not on the glass, so its colours are fixed ones
 * held out of the Vibrant adaptation: a white fill on a dark track over a poster or a still, on a
 * light track where it lies on the hero's dark veil ([onScrim]).
 */
@Composable
internal fun CardProgress(fraction: Float, modifier: Modifier = Modifier, onScrim: Boolean = false) {
    val shape = RoundedCornerShape(2.dp)
    val track = if (onScrim) Color.White.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.45f)
    Box(
        modifier = modifier
            .height(4.dp)
            .clip(shape)
            .background(track.withVibrant(Vibrant.None)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(shape)
                .background(Color.White.withVibrant(Vibrant.None)),
        )
    }
}

/** The least of a bar that is drawn for something begun, so a few minutes in still shows. */
internal const val MIN_VISIBLE_PROGRESS = 0.03f

/** The card's poster once the decode has settled; [bitmap] is null when there is nothing to show. */
private class Decoded(val bitmap: ImageBitmap?)

/**
 * What a card asks the decoder for, from the width it is drawn at.
 *
 * Rounded up to a step so neighbouring columns — which differ by a pixel or two once the grid has
 * shared out the remainder — ask the same question and share one decode and one cache entry.
 * Clamped so an unmeasured card (a constraint of zero or infinity during layout) still gets
 * something sensible rather than nothing or a full-size decode.
 */
/**
 * The width a card on the wall is drawn at, in pixels, when the grid it is in has worked it out for
 * all its cards at once ([PosterWall], the lanes over it) — so a card need not measure itself first.
 *
 * A card that measured itself (a BoxWithConstraints) composed its contents a second time, inside its
 * own measuring: on the headset a row of new cards coming into a scrolled wall cost ~30 ms, three
 * frames at 90 Hz. Absent, the card still measures itself, as anywhere else it is placed.
 */
val LocalPosterWidthPx = compositionLocalOf<Int?> { null }

/** The card's box: told its width ([LocalPosterWidthPx]) or else measuring it; [content] gets the poster size to ask for. */
@Composable
private fun CardBox(modifier: Modifier, content: @Composable (reqPx: Int) -> Unit) {
    val known = LocalPosterWidthPx.current
    if (known != null) {
        Box(modifier = modifier) { content(posterRequestPx(known)) }
    } else {
        // The width this card is actually drawn at, not a number measured once on one headset. The grid
        // sizes its cells to the window, so the same 160.dp minimum is ~320px at 2x and ~480px at 3x;
        // a fixed request left every poster upscaled on the denser screen and oversized on the coarser.
        BoxWithConstraints(modifier = modifier) { content(posterRequestPx(constraints.maxWidth)) }
    }
}

internal fun posterRequestPx(cardWidthPx: Int): Int {
    if (cardWidthPx <= 0 || cardWidthPx == Int.MAX_VALUE) return DEFAULT_POSTER_PX
    val step = 64
    return ((cardWidthPx + step - 1) / step * step).coerceIn(192, 1024)
}

/** When the card has not been measured yet — a wall card is ~160-200dp, which is this at 2x. */
private const val DEFAULT_POSTER_PX = 400


/**
 * The order the wall is in, for the cards' second line (see cardSubtitle): provided around the wall
 * and the 最近添加 lane; absent elsewhere, where a card just gives its year.
 */
val LocalWallSort = compositionLocalOf<LibSort?> { null }

/**
 * "今天" / "昨天" / "3 天前" within the week, then the date — "9月13日", with the year only when it is
 * not this one — in the reader's language.
 */
internal fun relativeDay(context: Context, atMs: Long): String {
    val now = System.currentTimeMillis()
    if (now - atMs < DateUtils.WEEK_IN_MILLIS) {
        return DateUtils.getRelativeTimeSpanString(atMs, now, DateUtils.DAY_IN_MILLIS).toString()
    }
    val yearOf = { ms: Long -> Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR) }
    val year = if (yearOf(atMs) == yearOf(now)) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
    return DateUtils.formatDateTime(context, atMs, DateUtils.FORMAT_SHOW_DATE or year)
}

/** How many of [group]'s files have a whole copy on the headset (下载到本机). */
@Composable
private fun downloadedCount(group: MediaGroup): Int {
    val dl = LocalDownloads.current ?: return 0
    val all by dl.downloader.store.all.collectAsState()
    return remember(all, group) {
        if (all.isEmpty()) 0 else {
            val done = all.filter { it.isDone }.mapTo(HashSet()) { it.itemId }
            group.items.count { it.id in done }
        }
    }
}

/**
 * Whether any of [group]'s files is on its way to the headset: the percent of the one downloading
 * now, -1 for files only waiting their turn, null when none is coming (or every one stopped).
 */
@Composable
private fun downloadingPercent(group: MediaGroup): Int? {
    val dl = LocalDownloads.current ?: return null
    val all by dl.downloader.store.all.collectAsState()
    val active by dl.downloader.active.collectAsState()
    val ids = remember(group) { group.items.mapTo(HashSet()) { it.id } }
    active?.takeIf { it.itemId in ids && it.totalBytes > 0 }?.let { return (it.fraction * 100).toInt() }
    val waiting = remember(all, ids) {
        all.any {
            it.itemId in ids && (it.state == com.therealityteam.videoplayer.data.download.Download.State.QUEUED ||
                it.state == com.therealityteam.videoplayer.data.download.Download.State.RUNNING)
        }
    }
    return if (waiting) -1 else null
}

/** The 下载中 glyph on a card's corner, with the percent of the file coming now. */
@Composable
private fun DownloadingBadge(percent: Int?) {
    val label = stringResource(R.string.dl_section_active)
    Badge(modifier = Modifier.tooltip(text = label)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(painterResource(R.drawable.ic_line_download), contentDescription = label, modifier = Modifier.size(14.dp))
            if (percent != null) Text(com.therealityteam.videoplayer.ui.common.percentText(percent))
        }
    }
}

/** Whether [itemId] has a whole copy on the headset. */
@Composable
internal fun isOnHeadset(itemId: String): Boolean {
    val dl = LocalDownloads.current ?: return false
    val all by dl.downloader.store.all.collectAsState()
    return remember(all, itemId) { all.any { it.itemId == itemId && it.isDone } }
}

/** The 已下载 glyph on a card's corner — with how many, for a series. */
@Composable
internal fun DownloadedBadge(count: Int?) {
    val label = stringResource(R.string.dl_on_headset)
    Badge(modifier = Modifier.tooltip(text = label)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(painterResource(R.drawable.ic_line_downloaded), contentDescription = label, modifier = Modifier.size(14.dp))
            if (count != null) Text(count.toString())
        }
    }
}
