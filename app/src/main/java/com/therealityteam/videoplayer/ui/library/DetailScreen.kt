package com.therealityteam.videoplayer.ui.library

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Chip
import com.pico.spatial.ui.design.ColorScheme
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.LinearProgressDefaults
import com.pico.spatial.ui.design.LinearProgressIndicator
import com.pico.spatial.ui.design.Link
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.ListItemDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.TitleAlignment
import com.pico.spatial.ui.design.TitleBar
import com.pico.spatial.ui.design.ToggleIconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.ToggleIconButtonDefaults
import com.pico.spatial.ui.design.menu.Menu
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.design.menu.rememberMenuPositionProvider
import com.pico.spatial.ui.design.windows.AlertDialog
import com.pico.spatial.ui.design.windows.Sheet
import com.pico.spatial.ui.design.windows.popup.HorizontalPlacement
import com.pico.spatial.ui.design.windows.popup.VerticalPlacement
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.tooltip
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.foundation.vibrant.Vibrant
import com.pico.spatial.ui.foundation.vibrant.withVibrant
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.playback.QualityTags
import com.therealityteam.videoplayer.data.playback.ResumeFrame
import com.therealityteam.videoplayer.data.scrape.EpisodeInfo
import com.therealityteam.videoplayer.data.scrape.GenreCn
import com.therealityteam.videoplayer.platform.DeepLink
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.therealityteam.videoplayer.ui.common.SideRowGap
import com.therealityteam.videoplayer.ui.common.cardHoverArea
import com.therealityteam.videoplayer.ui.common.cardHoverPicture
import com.therealityteam.videoplayer.ui.common.hoverDebug
import com.therealityteam.videoplayer.ui.common.rememberCardHover
import com.therealityteam.videoplayer.ui.theme.ThemeBridge
import com.therealityteam.videoplayer.ui.theme.dangerButtonColors
import com.therealityteam.videoplayer.ui.theme.glassSurface
import com.therealityteam.videoplayer.ui.theme.secondaryButtonColors
import com.therealityteam.videoplayer.ui.theme.secondaryIconButtonColors
import androidx.compose.foundation.combinedClickable
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.Material
import com.pico.spatial.ui.platform.SpatialSoundEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The title detail page — one layout for a single movie and for a multi-file series or collection.
 * The title's picture behind the top of the page, the poster standing on it with what the title is
 * beside it and the play button under that; then the synopsis, the cast, and the episode or file
 * list, which scrolls up under the bar.
 */
@Composable
fun DetailScreen(
    group: MediaGroup,
    onPlay: (MediaItem) -> Unit,
    onToggleFavorite: () -> Unit,
    onFixMatch: () -> Unit,
    /** 从头播放 from a row's long-press menu: the file from its first frame, whatever it was left at. */
    onPlayFromStart: (MediaItem) -> Unit = onPlay,
    onBack: () -> Unit,
    onSetWatched: (List<MediaItem>, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    initialScrollIndex: Int = 0,
    initialScrollOffset: Int = 0,
    onScroll: (Int, Int) -> Unit = { _, _ -> },
    /** Who is credited on which cards, library-wide (see [PeopleIndex]) — for "另有 N 部". */
    peopleIndex: Map<String, List<MediaGroup>> = emptyMap(),
    /** Open another card's page, from a person's other works or 相似作品. */
    onOpenTitle: (MediaGroup) -> Unit = {},
    /** The cards the library shows (adult ones gone when hidden) — what 相似作品 is chosen from. */
    library: List<MediaGroup> = emptyList(),
    /** Each episode as the database tells it, by item id (see LibraryViewModel.episodes). */
    loadEpisodes: suspend (MediaGroup) -> Map<String, EpisodeInfo> = { emptyMap() },
    /** 文件路径's folder tapped: that folder, in 文件夹 — given one of its files. */
    onOpenFolder: ((MediaItem) -> Unit)? = null,
    /** A file's folder as 文件夹 names it — `video › Movie › 1917` — where it is on a known source. */
    folderLabel: (MediaItem) -> String? = { null },
) {
    val colors = PicoTheme.colorScheme
    val rep = group.representative
    // AuraPerf: how long a page takes to be drawn once it is asked for — a show of hundreds of files too.
    val openedAt = remember(group.key) { android.os.SystemClock.elapsedRealtime() }
    LaunchedEffect(group.key) {
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        android.util.Log.i("AuraPerf", "detail shown: ${group.title}, ${group.items.size} files, in ${android.os.SystemClock.elapsedRealtime() - openedAt} ms")
    }
    val ambient = ThemeBridge.ambient(group.dominantColor, group.title)
    // A numbered run no database named: its files' bare numbers are the episode numbers.
    val numberedRun = group.numberedRun
    val items = remember(group) {
        group.items.sortedWith(compareBy({ EpisodeNaming.sortKey(it, numberedRun) }, { it.fileName.lowercase() }))
    }
    val ctx = LocalContext.current
    val autoChoice = remember(items) { DetailPlay.choose(items, numberedRun, group.seasonal) ?: DetailPlay.Choice(items.first(), DetailPlay.Kind.START) }
    // 版本: which of a film's versions the big button plays — the one it would pick, until another is
    // chosen beside it. Chosen for this visit: once played, a version stands for the film by its progress.
    val versions = remember(items, autoChoice) {
        if (group.seasonal) listOf(autoChoice.item) else DetailPlay.versionsOf(items, autoChoice.item, numberedRun)
    }
    var chosenVersion by remember(group.key) { mutableStateOf<String?>(null) }
    val playChoice = remember(autoChoice, versions, chosenVersion) {
        versions.firstOrNull { it.id == chosenVersion }?.let(DetailPlay::choiceFor) ?: autoChoice
    }
    val playTarget = playChoice.item
    // What the file the big button plays is, by its name: 4K, Dolby Vision, Atmos (1080p, the common
    // case, says nothing worth a badge).
    // A film kept in several versions says it on its 版本 button instead, once.
    val quality = remember(playTarget, versions, ctx) {
        if (versions.size > 1) return@remember emptyList()
        QualityTags.of(playTarget.fileName, playTarget.video)
            .filter { it.kind != QualityTags.Kind.RESOLUTION || it == QualityTags.Tag.UHD_4K || it == QualityTags.Tag.UHD_8K }
            .map { qualityText(ctx, it) }
    }
    // The row whose long-press menu automation has asked for (see the `row_menu:` hook below).
    var rowMenuFor by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState(initialScrollIndex, initialScrollOffset)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (i, o) -> onScroll(i, o) }
    }

    // Seasons + 特典 sections for a show card (season-first navigation in the detail).
    val sections = remember(group, ctx) { if (group.seasonal) sectionsOf(ctx, group.items, numberedRun) else emptyList() }
    // Numbered within the list each row is shown in; a second copy of an episode, filed under 特典,
    // says it is one rather than reading as the episode itself.
    // What each instalment of a run numbered by its names calls itself — `.04.民告官.土地攻防战` —
    // for a show no database told episode by episode.
    val instalments = remember(group) { if (numberedRun) EpisodeNaming.instalmentNames(group.items) else emptyMap() }
    val labels = remember(items, sections, ctx) {
        val copies = if (group.seasonal) DetailSections.copies(group.items, numberedRun) else emptySet()
        val asExtras = sections.filter { it.kind == DetailSections.Kind.EXTRAS }.flatMapTo(HashSet()) { sec -> sec.items.map { it.id } }
        rowLabels(ctx, items, numberedRun, lists = sections.map { it.items }.ifEmpty { listOf(items) }, copies = copies, instalments = instalments, asExtras = asExtras)
    }
    // The page opens on the section holding what the big button plays: after a finished first
    // season that is season 2, not the season that is all ticks.
    var sectionIdx by remember(group.key) {
        mutableStateOf(
            sections.indexOfFirst { sec -> sec.items.any { it.id == playTarget.id } }.takeIf { it >= 0 }
                ?: sections.indexOfFirst { sec -> sec.items.any { it.progress.started && !it.progress.watched } }.coerceAtLeast(0),
        )
    }
    // "第 1 集" is every season's first: with several seasons, the button names the season too.
    val playText = remember(playChoice, labels, sections, ctx) {
        // A single film has nothing to name: "继续 · 正片" says less than 继续播放.
        if (!group.isCollection) {
            return@remember when (playChoice.kind) {
                DetailPlay.Kind.RESUME -> ctx.getString(R.string.cw_resume)
                DetailPlay.Kind.RESTART -> ctx.getString(R.string.det_watch_again)
                else -> ctx.getString(R.string.det_play)
            }
        }
        val what = labels[playTarget.id] ?: EpisodeNaming.label(ctx, playTarget, numberedRun)
        val info = EpisodeNaming.parse(playTarget, numberedRun)
        val multiSeason = sections.count { it.season != null } > 1
        val named = if (multiSeason && info.cat == EpisodeNaming.Cat.EPISODE) {
            ctx.getString(R.string.det_episode_in_season, info.season ?: 1, what)
        } else what
        DetailPlay.buttonText(ctx, playChoice, named)
    }
    val mainCount = remember(items) { DetailPlay.mains(items, numberedRun, group.seasonal).size }
    // A flat list opens on what the card is about, the rest after it: a concert whose five parts each
    // carry a name of their own listed them, as named leftovers, behind its two LIVE DIGESTs.
    val flatFiles = remember(items) {
        val mains = DetailPlay.mains(items, numberedRun).mapTo(HashSet()) { it.id }
        items.sortedBy { it.id !in mains }
    }
    val versionsOnly = remember(items) { !group.seasonal && items.size > 1 && DetailPlay.alternates(items, numberedRun).size == items.size - 1 }
    val markAll = remember(items) { DetailPlay.markAll(items, numberedRun, group.seasonal) }
    var confirmMarkAll by rememberSaveable { mutableStateOf(false) }

    // Once the hero has scrolled away the bar carries the title, so the episode list keeps its context.
    val heroGone by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    // The page's picture fades as the page scrolls: whole behind the header, gone once it has passed.
    val density = LocalDensity.current
    val backdropAlpha by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 0f
            else 1f - (listState.firstVisibleItemScrollOffset / with(density) { BACKDROP_HEIGHT.toPx() * 0.8f }).coerceIn(0f, 1f)
        }
    }
    val hasBackdrop = (group.backdropPath ?: rep.backdropPath) != null
    // Each episode's own name and what happens in it, read as the page opens; the list draws without
    // them meanwhile, and keeps to its file names where the database has none.
    val told by produceState(emptyMap<String, EpisodeInfo>(), group.key) { value = loadEpisodes(group) }
    // 修正匹配 sets what a database calls a title. A media server's cards are named by the server, and
    // a TMDB entry pinned onto one would pull its files away from the server's own card.
    val fixable = remember(group) { !group.items.all { isServerItem(it) } }
    var showMore by rememberSaveable { mutableStateOf(false) }
    SheetHook("more") { showMore = true }
    SheetHook("fix") { if (fixable) onFixMatch() }
    // The director, the leads, the writers, the rest — each person once, with all their parts.
    val credits = remember(rep) { CreditRow.of(rep.crew, rep.cast) }
    // A face leads to the person's other cards in this library, when there are any.
    val othersOf: (Person) -> List<MediaGroup> = { p -> PeopleIndex.othersOf(peopleIndex, p.name, except = group.key) }
    var personShown by remember(group.key) { mutableStateOf<Person?>(null) }
    // 系列作品 and 相似作品, worked out off the main thread from what the library knows (see
    // SeriesTitles, SimilarTitles). A film of the series is offered once, in its series.
    val seriesInfo by produceState<SeriesTitles.Series?>(initialValue = null, group.key, library) {
        value = withContext(Dispatchers.Default) { SeriesTitles.seriesOf(group, library) }
    }
    val series = seriesInfo?.cards.orEmpty()
    val similarAll by produceState(initialValue = emptyList<MediaGroup>(), group.key, library) {
        value = withContext(Dispatchers.Default) { SimilarTitles.of(group, library) }
    }
    val similar = remember(similarAll, series) {
        val inSeries = series.mapTo(HashSet()) { it.key }
        similarAll.filterNot { it.key in inSeries }
    }
    // `-e aura.detail_sheet person`: the first credited person with other cards, for a screenshot —
    // once the library-wide index has been built, which can be after the page opens.
    val sheetRequest by DeepLink.detailSheetRequest.collectAsState()
    LaunchedEffect(sheetRequest, peopleIndex) {
        if (!sheetRequest.equals("person", ignoreCase = true)) return@LaunchedEffect
        val person = credits.firstOrNull { othersOf(it.person).isNotEmpty() }?.person ?: return@LaunchedEffect
        personShown = person
        DeepLink.detailSheetRequest.value = null
    }
    val overview = com.therealityteam.videoplayer.ui.common.readableSynopsis(rep.overview)
    // `-e aura.detail_sheet episodes`: scroll to the episode / file list, which sits below the first
    // screen and so could not be reviewed by screenshot.
    val hookScope = rememberCoroutineScope()
    // `-e aura.detail_sheet similar`: scroll to 相似作品 once it has been worked out.
    SheetHook("similar") {
        hookScope.launch {
            snapshotFlow { similar.isNotEmpty() }.first { it }
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }
    // `-e aura.detail_sheet series`: scroll to 系列作品 once it has been worked out.
    SheetHook("series") {
        hookScope.launch {
            // The states themselves, not this composition's copies: those stay empty while it waits.
            snapshotFlow { seriesInfo?.cards?.isNotEmpty() == true }.first { it }
            // …and the row laid out: the list counts it only once it has composed it.
            val before = listState.layoutInfo.totalItemsCount
            withTimeoutOrNull(SERIES_HOOK_WAIT_MS) { snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > before } }
            delay(SERIES_HOOK_SETTLE_MS)
            val inSeries = seriesInfo?.cards.orEmpty().mapTo(HashSet()) { it.key }
            val similarShown = similarAll.any { it.key !in inSeries }
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - if (similarShown) 2 else 1)
        }
    }
    SheetHook("episodes") {
        val listAt = 1 + (if (overview != null) 1 else 0) + (if (credits.isNotEmpty()) 1 else 0)
        hookScope.launch { listState.scrollToItem(listAt) }
    }
    // `-e aura.detail_sheet version_menu`: the version button's tap; `version:<n>`: the n-th version's
    // pick in its menu.
    var versionMenuRequested by remember { mutableStateOf(false) }
    SheetHook("version_menu") { versionMenuRequested = true }
    LaunchedEffect(sheetRequest, versions) {
        val n = sheetRequest?.takeIf { it.startsWith("version:") }?.substringAfter(':')?.toIntOrNull() ?: return@LaunchedEffect
        versions.getOrNull(n - 1)?.let { chosenVersion = it.id }
        DeepLink.detailSheetRequest.value = null
    }
    // `-e aura.detail_sheet season_menu`: the season chip's tap — the list scrolled to it first, as the
    // chip sits below the first screen and is not composed until then.
    var seasonMenuRequested by remember { mutableStateOf(false) }
    SheetHook("season_menu") {
        val listAt = 1 + (if (overview != null) 1 else 0) + (if (credits.isNotEmpty()) 1 else 0)
        hookScope.launch { listState.scrollToItem(listAt) }
        seasonMenuRequested = true
    }
    // `-e aura.detail_sheet section:<n>`: the n-th of 正片 / 第 2 季 / 特典…, as its segment's tap, with
    // the list scrolled to it.
    LaunchedEffect(sheetRequest) {
        val n = sheetRequest?.takeIf { it.startsWith("section:") }?.substringAfter(':')?.toIntOrNull() ?: return@LaunchedEffect
        if (n in 1..sections.size) sectionIdx = n - 1
        listState.scrollToItem(1 + (if (overview != null) 1 else 0) + (if (credits.isNotEmpty()) 1 else 0))
        DeepLink.detailSheetRequest.value = null
    }
    // `-e aura.detail_sheet glide:<px>:<ms>[:<wait ms>[:<passes>]]`: the page scrolled that far at an even
    // pace (each further pass back up), every frame timed (AuraJank) — as the wall's `aura.screen glide`.
    LaunchedEffect(sheetRequest) {
        val p = sheetRequest?.takeIf { it.startsWith("glide:") }?.split(':')?.drop(1)?.mapNotNull { it.toIntOrNull() } ?: return@LaunchedEffect
        if (p.isEmpty()) return@LaunchedEffect
        delay(p.getOrElse(2) { 2_500 }.toLong())
        repeat(p.getOrElse(3) { 1 }) { pass ->
            val gaps = ArrayList<Long>()
            val timing = launch {
                var last = androidx.compose.runtime.withFrameNanos { it }
                while (true) {
                    val now = androidx.compose.runtime.withFrameNanos { it }
                    gaps += now - last
                    last = now
                }
            }
            val px = (if (pass % 2 == 0) p[0] else -p[0]).toFloat()
            val ms = p.getOrElse(1) { 4_000 }
            listState.animateScrollBy(px, androidx.compose.animation.core.tween(ms, easing = androidx.compose.animation.core.LinearEasing))
            timing.cancel()
            com.therealityteam.videoplayer.ui.JankReport.log("detail glide ${px.toInt()} px in $ms ms (pass ${pass + 1}), ${group.title}, ${group.items.size} files", gaps)
            delay(1_000)
        }
        DeepLink.detailSheetRequest.value = null // last: clearing it ends this effect
    }
    // `-e aura.detail_sheet row_menu:<n>`: the n-th row of the list on show, scrolled into sight with
    // its long-press menu open, as a long press would — `input tap` reaches no spatial window.
    LaunchedEffect(sheetRequest) {
        val n = sheetRequest?.takeIf { it.startsWith("row_menu:") }?.substringAfter(':')?.toIntOrNull() ?: return@LaunchedEffect
        val shown = if (group.seasonal && sections.isNotEmpty()) sections[sectionIdx.coerceIn(0, sections.lastIndex)].items else items
        val k = (n - 1).coerceIn(0, shown.lastIndex)
        val listAt = 1 + (if (overview != null) 1 else 0) + (if (credits.isNotEmpty()) 1 else 0)
        android.util.Log.i("AuraRowMenu", "row_menu:$n -> ${shown[k].fileName}")
        listState.scrollToItem(listAt + k) // the row before it at the top, so it reads in its place
        rowMenuFor = shown[k].id
        DeepLink.detailSheetRequest.value = null
    }

    Box(modifier = modifier.fillMaxSize()) {
        // PICO's content-detail template puts the title's picture behind the page: its backdrop across
        // the top of the window, under the title bar, fading into the glass where the header begins.
        Backdrop(
            group, rep,
            Modifier.fillMaxWidth().height(BACKDROP_HEIGHT).graphicsLayer { alpha = backdropAlpha },
        )
        Column(modifier = Modifier.fillMaxSize()) {
            // The template's TitleBar: 返回 leads; the title joins it once the header has scrolled away.
            TitleBar(
                title = {
                    if (heroGone) Text(group.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                titleAlignment = TitleAlignment.CenterInBar,
                leadingActions = {
                    val back = stringResource(R.string.det_back)
                    // Over the page's picture the button stands on glass of its own: its usual fill is a
                    // tint of whatever lies behind it, and on The Mandalorian's night sky the arrow all
                    // but vanished. Back on the plain glass, once the picture has scrolled away, it is
                    // the bar's ordinary button again.
                    val onPicture = hasBackdrop && backdropAlpha > 0.3f
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.tooltip(text = back)
                            .then(if (onPicture) Modifier.clip(CircleShape).backgroundMaterial(style = Material.Thick) else Modifier),
                        colors = if (onPicture) {
                            IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = PicoTheme.colorScheme.labelPrimary)
                        } else {
                            secondaryIconButtonColors()
                        },
                    ) {
                        Icon(painterResource(R.drawable.ic_pc_back), back, Modifier.size(20.dp))
                    }
                },
            )

            // Everything below scrolls as one page, so the big poster header scrolls away and gives the
            // episode list the full height — instead of a tiny inner scroll region under a fixed header.
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ── Header: the poster standing on the picture's lower edge; what the title is beside it. ──
                item(key = "__header") {
                    // With a picture behind it the poster stands on its lower edge; without one there
                    // is nothing to stand on, and the header starts under the bar.
                    val onPicture = (group.backdropPath ?: rep.backdropPath) != null
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = if (onPicture) 112.dp else 16.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        Poster(group, ambient, Modifier.width(220.dp).aspectRatio(2f / 3f))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = group.title,
                                style = PicoTheme.typography.displayMedium,
                                color = colors.labelPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            rep.originalTitle?.takeIf { it.isNotBlank() && it != group.title }?.let {
                                Text(
                                    text = it,
                                    style = PicoTheme.typography.bodyLarge,
                                    color = colors.labelSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            MetaRow(group, rep, colors, modifier = Modifier.fillMaxWidth())
                            if (group.ratings.isNotEmpty() || quality.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                RatingsRow(group.ratings, quality, modifier = Modifier.fillMaxWidth())
                            }
                            // Why there is no metadata, when there is none — and the two reasons look
                            // nothing alike from inside. UNMATCHED is the databases' final answer, so the
                            // fix is the user's, offered right there. ERROR is the provider refusing to
                            // answer just now (rate limit / outage): whatever identity the item had was
                            // kept, and the next 刮削 retries by itself — so it offers nothing to do.
                            // …and none where no answer was looked for: a clip off a camera is never
                            // looked up, and a music shelf's lessons and a game's recordings are no
                            // database's — a warning on every one of them read as the app failing. Their
                            // 修正匹配 stays on the pencil.
                            val answerExpected = group.category != LibCategory.PERSONAL &&
                                group.category != LibCategory.MUSIC && group.category != LibCategory.GAME
                            when (rep.scrapeStatus) {
                                ScrapeStatus.UNMATCHED -> if (answerExpected) NoticeLine(
                                    iconRes = R.drawable.ic_det_warning,
                                    iconTint = colors.alert,
                                    text = stringResource(R.string.det_unmatched),
                                    link = if (fixable) stringResource(R.string.det_fix_match) else null,
                                    onLink = onFixMatch,
                                    modifier = Modifier.padding(top = 16.dp),
                                )
                                ScrapeStatus.ERROR -> NoticeLine(
                                    iconRes = R.drawable.ic_det_info,
                                    iconTint = colors.labelTertiary,
                                    text = stringResource(R.string.det_scrape_error),
                                    modifier = Modifier.padding(top = 16.dp),
                                )
                                else -> {}
                            }
                            Spacer(Modifier.height(24.dp))
                            // The one primary, then the round secondary actions (收藏 / 修正匹配 /
                            // 全部已看 / 文件路径), each named on hover. All five stand 48dp tall.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Button(
                                    onClick = { onPlay(playTarget) },
                                    // Capped, so a long file name ellipsizes inside the button instead
                                    // of pushing the round actions out of the row.
                                    modifier = Modifier.widthIn(min = 160.dp, max = 360.dp),
                                    leadingIcon = {
                                        Icon(painterResource(R.drawable.ic_pc_play), contentDescription = null, modifier = Modifier.size(20.dp))
                                    },
                                ) {
                                    Text(playText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (versions.size > 1) {
                                    VersionMenu(
                                        versions = versions,
                                        labels = labels,
                                        selected = playTarget.id,
                                        menuRequested = versionMenuRequested,
                                        onMenuRequested = { versionMenuRequested = false },
                                        onSelect = { chosenVersion = it.id },
                                    )
                                }
                                val favorited = group.items.any { it.favorite }
                                ActionToggle(
                                    offIcon = R.drawable.ic_det_fav,
                                    onIcon = R.drawable.ic_det_fav_fill,
                                    desc = stringResource(if (favorited) R.string.det_unfavorite else R.string.det_favorite),
                                    checked = favorited,
                                    onToggle = onToggleFavorite,
                                )
                                if (fixable) ActionIcon(R.drawable.ic_edit, stringResource(R.string.det_fix_match), onClick = onFixMatch)
                                // Seen elsewhere, or to be seen again: the card's episodes (or its feature)
                                // at once. Asks first when that would wipe more than one file's progress.
                                ActionToggle(
                                    offIcon = R.drawable.ic_det_watched,
                                    onIcon = R.drawable.ic_det_watched_fill,
                                    desc = stringResource(
                                        when {
                                            group.isCollection && markAll.allWatched -> R.string.det_mark_all_unwatched
                                            group.isCollection -> R.string.det_mark_all_watched
                                            markAll.allWatched -> R.string.det_mark_unwatched
                                            else -> R.string.det_mark_watched
                                        },
                                    ),
                                    checked = markAll.allWatched,
                                    onToggle = {
                                        when {
                                            markAll.needsConfirm -> confirmMarkAll = true
                                            markAll.targets.isNotEmpty() -> onSetWatched(markAll.targets, markAll.watched)
                                        }
                                    },
                                )
                                DownloadAction(group, items, playTarget)
                                PathButton(
                                    folders = remember(items) {
                                        items.groupBy { locationOf(it) }.map { (folder, files) -> (folderLabel(files.first()) ?: folder) to files.first() }
                                    },
                                    colors = colors,
                                    onOpen = onOpenFolder,
                                )
                            }
                            // Part-way through: how far, under the button that goes back to it — the
                            // home page's hero says so, and the page that opens from it should too.
                            if (playChoice.kind == DetailPlay.Kind.RESUME) ResumeLine(playTarget)
                        }
                    }
                }

                // ── 简介: three lines at reading size; 更多 opens the whole of it with the credits. ──
                if (overview != null) {
                    item(key = "__overview") {
                        Column(modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 32.dp)) {
                            SectionTitle(stringResource(R.string.det_overview), onMore = { showMore = true })
                            Text(
                                text = overview,
                                style = PicoTheme.typography.bodyLargeMultiline,
                                color = colors.labelSecondary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp).widthIn(max = 880.dp),
                            )
                        }
                    }
                }

                // ── 演职人员: the faces, a row of them, each with what they did; 更多 lists them all. ──
                if (credits.isNotEmpty()) {
                    item(key = "__cast") {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                            SectionTitle(
                                stringResource(R.string.det_credits),
                                onMore = { showMore = true },
                                modifier = Modifier.padding(start = 32.dp, end = 32.dp),
                            )
                            LazyRow(
                                contentPadding = PaddingValues(start = 32.dp, end = 32.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                items(credits, key = { it.person.name }) { credit ->
                                    val person = credit.person
                                    val others = othersOf(person).size
                                    PersonTile(person, credit.roles, others = others, onClick = if (others > 0) ({ personShown = person }) else null)
                                }
                            }
                        }
                    }
                }

                // ── Season / 特典 selector + episode list (shows), or a flat file list. ──
                when {
                    group.seasonal && sections.isNotEmpty() -> {
                        val shown = sectionIdx.coerceIn(0, sections.lastIndex)
                        val current = sections[shown]
                        item(key = "__episodes") {
                            Column(modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 24.dp)) {
                                // How far into the list below: beside the season it describes, or —
                                // with no seasons to choose — beside the heading.
                                SectionTitle(
                                    stringResource(R.string.det_section_episodes),
                                    onMore = null,
                                    trailing = if (sections.size > 1) null else ({ SectionProgress(current) }),
                                )
                                if (sections.size > 1) {
                                    Spacer(Modifier.height(12.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SeasonSelector(
                                            sections, shown,
                                            menuRequested = seasonMenuRequested,
                                            onMenuRequested = { seasonMenuRequested = false },
                                        ) { sectionIdx = it }
                                        Spacer(Modifier.weight(1f))
                                        SectionProgress(current)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        val inRun = current.kind == DetailSections.Kind.SEASON || current.kind == DetailSections.Kind.EPISODES
                        items(current.items, key = { it.id }) { item ->
                            Box(Modifier.padding(start = 32.dp, end = 32.dp)) {
                                EpisodeRow(
                                    item = item,
                                    label = labels[item.id] ?: item.fileName,
                                    info = if (inRun) told[item.id] ?: instalments[item.id]?.let { EpisodeInfo(it, null, null) } else null,
                                    // A show's episodes all lead with a picture: their own still, or a
                                    // cover with their number on it until one arrives.
                                    still = true,
                                    tileTitle = group.title,
                                    tileText = episodeNumberText(item, numberedRun),
                                    upNext = playChoice.kind.takeIf { group.isSeries && mainCount > 1 && item.id == playTarget.id },
                                    onClick = { onPlay(item) },
                                    onSetWatched = { onSetWatched(listOf(item), it) },
                                    earlier = { if (group.isSeries) DetailPlay.earlierUnwatched(items, item, numberedRun, group.seasonal) else emptyList() },
                                    onPlayFromStart = { onPlayFromStart(item) },
                                    onSetWatchedFiles = onSetWatched,
                                    menuRequested = rowMenuFor == item.id,
                                    onMenuRequested = { rowMenuFor = null },
                                )
                            }
                        }
                    }

                    // A flat file list — used for collections AND a single movie, so even a one-file
                    // title still shows its file entry (filename) the same way a multi-file title does.
                    // A film kept in several versions and nothing else lists its 版本.
                    else -> {
                        item(key = "__label") {
                            Column(modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 8.dp)) {
                                SectionTitle(stringResource(if (versionsOnly) R.string.det_versions else R.string.det_files), onMore = null)
                            }
                        }
                        items(flatFiles, key = { it.id }) { item ->
                            Box(Modifier.padding(start = 32.dp, end = 32.dp)) {
                                EpisodeRow(
                                    item = item,
                                    label = labels[item.id] ?: item.fileName,
                                    // Only a file with a picture of its own: the title's backdrop repeated
                                    // down a list of films or tracks would say nothing about any of them.
                                    still = item.stillPath != null || item.stillUrl != null,
                                    tileTitle = group.title,
                                    tileText = null,
                                    upNext = playChoice.kind.takeIf { group.isSeries && mainCount > 1 && item.id == playTarget.id },
                                    onClick = { onPlay(item) },
                                    onSetWatched = { onSetWatched(listOf(item), it) },
                                    earlier = { if (group.isSeries) DetailPlay.earlierUnwatched(items, item, numberedRun, group.seasonal) else emptyList() },
                                    onPlayFromStart = { onPlayFromStart(item) },
                                    onSetWatchedFiles = onSetWatched,
                                    menuRequested = rowMenuFor == item.id,
                                    onMenuRequested = { rowMenuFor = null },
                                )
                            }
                        }
                    }
                }

                // ── 系列作品: the rest of this card's series, in the order it came out. ──
                if (series.isNotEmpty()) {
                    item(key = "__series") {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                            SectionTitle(
                                stringResource(R.string.det_series),
                                onMore = null,
                                modifier = Modifier.padding(start = 32.dp, end = 32.dp),
                            )
                            // The row opens where this card stands: the one before it, then what follows.
                            val seriesRow = rememberLazyListState(
                                initialFirstVisibleItemIndex = (SeriesTitles.indexAfter(group, series) - 1).coerceAtLeast(0),
                            )
                            LazyRow(
                                state = seriesRow,
                                contentPadding = PaddingValues(start = 32.dp, end = 32.dp),
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                // Each by what is its own: 1：幽灵的威胁 beside 星球大战7's page, not the series' name again.
                                items(series, key = { it.key }) { card ->
                                    PosterCard(
                                        group = card,
                                        onClick = { onOpenTitle(card) },
                                        modifier = Modifier.width(168.dp),
                                        title = seriesInfo?.shortTitle(card) ?: card.title,
                                    )
                                }
                            }
                        }
                    }
                }

                // ── 相似作品: what to watch after this, from this library — the wall's own cards. ──
                if (similar.isNotEmpty()) {
                    item(key = "__similar") {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                            SectionTitle(
                                stringResource(R.string.det_similar),
                                onMore = null,
                                modifier = Modifier.padding(start = 32.dp, end = 32.dp),
                            )
                            LazyRow(
                                contentPadding = PaddingValues(start = 32.dp, end = 32.dp),
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                items(similar, key = { it.key }) { card ->
                                    PosterCard(group = card, onClick = { onOpenTitle(card) }, modifier = Modifier.width(168.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showMore) {
        MoreDialog(
            overview, rep.crew, rep.cast, colors,
            othersCount = { othersOf(it).size },
            onPerson = { showMore = false; personShown = it },
        ) { showMore = false }
    }
    personShown?.let { person ->
        PersonWorksSheet(
            person = person,
            works = othersOf(person),
            onOpen = { personShown = null; onOpenTitle(it) },
            onDismiss = { personShown = null },
        )
    }
    if (confirmMarkAll) MarkAllDialog(markAll, onDismiss = { confirmMarkAll = false }) {
        confirmMarkAll = false
        onSetWatched(markAll.targets, markAll.watched)
    }
}

/** How tall the picture behind the top of the page is; it fades out over its lower half. */
private val BACKDROP_HEIGHT = 440.dp

/** Wider than this many times its height, a backdrop is a banner strip (AniList's), shown whole. */
private const val STRIP_ASPECT = 3

/** The `series` hook waits this long after the row's cards arrive, for the list to lay the row out. */
private const val SERIES_HOOK_SETTLE_MS = 400L
private const val SERIES_HOOK_WAIT_MS = 3_000L

/** An episode still in the list: 16:9, about the size of a thumbnail in a player's episode picker. */
private val STILL_WIDTH = 160.dp
private val STILL_HEIGHT = 90.dp

/** The media servers whose cards name themselves (their files' collection keys begin with these). */
private val SERVER_KEYS = setOf("jellyfin", "emby", "plex")

private fun isServerItem(item: MediaItem): Boolean =
    item.collectionKey?.substringBefore(':')?.let { it in SERVER_KEYS } == true

/**
 * The title's backdrop, faded into the glass from its middle down — an alpha mask over the picture,
 * not a colour painted over the window's material. No backdrop, no band: the glass stays the glass.
 */
@Composable
private fun Backdrop(group: MediaGroup, rep: MediaItem, modifier: Modifier) {
    val path = group.backdropPath ?: rep.backdropPath ?: return
    val image by produceState<ImageBitmap?>(initialValue = cachedArtwork(path, 1600), path) {
        value = decodeArtwork(path, reqWidthPx = 1600)
    }
    val bmp = image ?: return
    // An anime's AniList banner is a strip (1900x400): cropped to the band it lost two thirds of its
    // width — the face at one end of it — and was blown up twice over. A strip keeps its whole width
    // at its own height and fades within that.
    val strip = bmp.width >= bmp.height * STRIP_ASPECT
    Image(
        bitmap = bmp,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = modifier
            .then(if (strip) Modifier.wrapContentHeight(Alignment.Top).aspectRatio(bmp.width.toFloat() / bmp.height) else Modifier)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, (if (strip) 0.55f else 0.42f) to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

/** A section's name, with 更多 at its end when there is more of it to read. */
@Composable
private fun SectionTitle(
    title: String,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
        trailing?.invoke()
        if (onMore != null) {
            Link(
                onClick = onMore,
                trailingIcon = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(16.dp)) },
                // Named for its section: the page's 简介 and 演职人员 each had a 「更多」, the same word twice.
                semanticsContent = stringResource(R.string.det_more_of, title),
            ) { Text(stringResource(R.string.det_more)) }
        }
    }
}

/**
 * Why a title has no metadata, as a line of its own: a glyph that says what kind of news it is, the
 * reason at reading size, and — when there is one — the way out, right there.
 */
@Composable
private fun NoticeLine(
    iconRes: Int,
    iconTint: Color,
    text: String,
    modifier: Modifier = Modifier,
    link: String? = null,
    onLink: () -> Unit = {},
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Text(
            text = text,
            style = PicoTheme.typography.bodyMedium,
            color = PicoTheme.colorScheme.labelSecondary,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (link != null) Link(onClick = onLink) { Text(link) }
    }
}

/**
 * One face in the 演职人员 row: the headshot, the name, and the role or the character. A person with
 * other cards in this library says how many under their role, and the tile opens them; one without
 * is not pressable — a face that opens a sheet saying "nothing else" would only waste the reach.
 */
@Composable
private fun PersonTile(person: Person, roles: List<String>, others: Int, onClick: (() -> Unit)?) {
    val colors = PicoTheme.colorScheme
    // The whole tile answers the gaze, the face lights up ([CardHover]): a box lit round the name and
    // the roles as well put its edge hard against the words.
    val hover = rememberCardHover()
    val roleText = roles.mapNotNull { crewRoleLabel(it)?.takeIf(String::isNotBlank) }.joinToString(" · ").takeIf { it.isNotEmpty() }
    val more = if (others > 0) pluralStringResource(R.plurals.det_person_more_works, others, others) else null
    Column(
        modifier = Modifier
            .width(112.dp)
            .then(
                if (onClick == null) Modifier
                else Modifier
                    .cardHoverArea(hover)
                    .clickable(interactionSource = hover.interaction, indication = null, onClick = onClick)
                    .controllerHapticFeedback(interactionSource = hover.interaction),
            )
            // One node for the face, the name, the parts and 另有 N 部, as a poster card is one.
            .clearAndSetSemantics { contentDescription = listOfNotNull(person.name, roleText, more).joinToString(", ") }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(if (onClick == null) Modifier else Modifier.cardHoverPicture(hover, CircleShape, LocalIndication.current)) {
            Avatar(person, size = 80.dp)
        }
        Text(
            text = person.name,
            // Balanced: a name that wraps splits evenly ("迪恩-查尔斯·" / "查普曼"), never a lone 曼 under it.
            style = PicoTheme.typography.bodyMedium.copy(
                lineBreak = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Default),
            ),
            color = colors.labelPrimary,
            // Two lines: most Western names do not fit one line of a face's width.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        // Every part they had, each in the reader's words: "Keith · 监制".
        roleText?.let {
            Text(
                text = it,
                style = PicoTheme.typography.bodySmall,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        more?.let {
            Text(
                text = it,
                style = PicoTheme.typography.labelMedium,
                color = colors.interaction,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * A person's other cards in this library, as the wall shows them: their posters, newest first; one
 * opens that title's page (and 返回 there comes back here). Opened from a face in 演职人员, a row of
 * the full credits, or 人物 over a search.
 */
@Composable
internal fun PersonWorksSheet(
    person: Person,
    works: List<MediaGroup>,
    onOpen: (MediaGroup) -> Unit,
    onDismiss: () -> Unit,
    // From a title's page these are the person's *other* cards (片库里还有 N 部); from a search, all of them.
    allOfThem: Boolean = false,
) {
    val colors = PicoTheme.colorScheme
    Sheet(onDismissRequest = onDismiss, title = { Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }) {
        Column(modifier = Modifier.width(720.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Avatar(person, size = 56.dp)
                Text(
                    text = pluralStringResource(
                        if (allOfThem) R.plurals.det_person_all_works_in_library else R.plurals.det_person_works_in_library,
                        works.size, works.size,
                    ),
                    style = PicoTheme.typography.bodyLarge,
                    color = colors.labelSecondary,
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(works, key = { it.key }) { card -> PosterCard(group = card, onClick = { onOpen(card) }) }
            }
        }
    }
}

/** "还剩 67 分钟" over a thin bar of how much of [item] has been watched, under the play button. */
@Composable
private fun ResumeLine(item: MediaItem) {
    val p = item.progress
    val left = DetailPlay.rowState(item)?.let { DetailPlay.rowStateText(LocalContext.current, it) } ?: return
    Row(
        modifier = Modifier.padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (p.durationMs > 0L) {
            // On the glass, not on a picture: the dark of the text for what was watched, so the bar
            // reads at a metre as the line under it does (white on light glass all but vanished).
            LinearProgressIndicator(
                progress = { p.fraction.coerceIn(0.02f, 1f) },
                modifier = Modifier.width(200.dp),
                height = LinearProgressDefaults.Small,
                colors = LinearProgressDefaults.linearProgressColors(
                    indicatorColor = PicoTheme.colorScheme.labelPrimary,
                    backgroundColor = PicoTheme.colorScheme.fillSecondary,
                ),
            )
        }
        Text(left, style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
    }
}

/** A secondary icon action beside the play button, its name shown on hover. */
@Composable
private fun ActionIcon(resId: Int, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.tooltip(text = desc), colors = secondaryIconButtonColors()) {
        Icon(painterResource(resId), contentDescription = desc, modifier = Modifier.size(20.dp))
    }
}

/**
 * 下载到本机 among the page's round actions. A film: the file the big button plays — lit once it is on
 * the headset (a press removes it), showing how far while it comes. A series: the next few episodes
 * from where it would play on, as the card's menu offers; gone once they are all here.
 */
@Composable
private fun DownloadAction(group: MediaGroup, ordered: List<MediaItem>, from: MediaItem) {
    val dl = LocalDownloads.current ?: return
    val list by dl.downloader.store.all.collectAsState()
    val active by dl.downloader.active.collectAsState()
    if (group.isCollection) {
        val have = remember(list) { list.mapTo(HashSet()) { it.itemId } }
        val next = remember(ordered, from, have) {
            ordered.dropWhile { it.id != from.id }.filter { it.id !in have && dl.downloader.canDownload(it) }.take(DETAIL_NEXT_EPISODES)
        }
        if (next.isEmpty()) return
        ActionIcon(R.drawable.ic_line_download, pluralStringResource(R.plurals.dl_add_next, next.size, next.size)) { dl.onAdd(next) }
        return
    }
    if (!dl.downloader.canDownload(from)) return
    val d = active?.takeIf { it.itemId == from.id } ?: list.firstOrNull { it.itemId == from.id }
    when {
        d == null -> ActionIcon(R.drawable.ic_line_download, stringResource(R.string.dl_add)) { dl.onAdd(listOf(from)) }
        // Stopped: why, and a press tries again.
        d.state == com.therealityteam.videoplayer.data.download.Download.State.FAILED -> ActionIcon(
            R.drawable.ic_line_download,
            failureText(LocalContext.current, d.error) + " · " + stringResource(R.string.dl_retry),
        ) { dl.downloader.resume(from.id) }
        d.isDone -> ActionToggle(
            offIcon = R.drawable.ic_line_download,
            onIcon = R.drawable.ic_line_downloaded,
            desc = stringResource(R.string.dl_on_headset) + " · " + stringResource(R.string.dl_remove),
            checked = true,
            onToggle = { dl.onRemove(from.id) },
        )
        else -> ActionToggle(
            offIcon = R.drawable.ic_line_download,
            onIcon = R.drawable.ic_line_download,
            desc = (if (d.totalBytes > 0) com.therealityteam.videoplayer.ui.common.percentText((d.fraction * 100).toInt()) + stringResource(R.string.meta_separator) else "") + stringResource(R.string.dl_cancel),
            checked = true,
            onToggle = { dl.onRemove(from.id) },
        )
    }
}

/** How many episodes the page's 下载 takes at once, as the card's menu does. */
private const val DETAIL_NEXT_EPISODES = 5

/**
 * An on/off action (收藏, 全部已看). Off, it is the same light round button as [ActionIcon] with an
 * outline glyph; on, the glyph fills and lights in the accent on the strong fill — the player's
 * chosen-tile idiom — so the state reads without hovering. The system's own checked fill is the
 * light fill the off state already uses, which is why both states used to look identical.
 */
@Composable
private fun ActionToggle(offIcon: Int, onIcon: Int, desc: String, checked: Boolean, onToggle: () -> Unit) {
    val colors = PicoTheme.colorScheme
    ToggleIconButton(
        checked = checked,
        onCheckedChange = { onToggle() },
        modifier = Modifier.tooltip(text = desc),
        colors = ToggleIconButtonDefaults.toggleIconButtonColors(
            checkedContainerColor = colors.fillPrimary,
            checkedContentColor = colors.interaction,
            // The roles of secondaryIconButtonColors(), the round actions beside it.
            uncheckedContainerColor = colors.fillSecondary,
            uncheckedContentColor = colors.labelPrimary,
        ),
        size = ToggleIconButtonDefaults.Regular,
    ) {
        Icon(painterResource(if (checked) onIcon else offIcon), contentDescription = desc, modifier = Modifier.size(20.dp))
    }
}

/**
 * One line of metadata under the title: 年份 · 时长 · 分级 · 集数/文件数 · 类型. Plain text (not
 * chips/buttons) — this information isn't tappable, so it shouldn't look clickable. It wraps to a
 * second line rather than running off the edge.
 */
@Composable
private fun MetaRow(
    group: MediaGroup,
    rep: MediaItem,
    colors: ColorScheme,
    modifier: Modifier = Modifier,
) {
    val runtime = group.runtime?.let { formatRuntime(it) }
    val eachOf = stringResource(R.string.det_runtime_each, runtime ?: "")
    val countLabel = when {
        !group.isCollection -> null
        group.isSeries -> pluralStringResource(R.plurals.det_n_episodes, group.shownCount, group.shownCount)
        else -> pluralStringResource(R.plurals.det_n_files, group.shownCount, group.shownCount)
    }
    val parts = buildList {
        group.year?.let { add(it.toString()) }
        runtime?.let { add(if (group.isSeries) eachOf else it) } // "/集" is an episode's length, not a version's
        group.certification?.let { add(it) }
        countLabel?.let { add(it) }
        addAll(rep.genres.flatMap(GenreCn::zhAll).distinct().take(5))
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString("   ·   "),
        style = PicoTheme.typography.bodyMedium,
        color = colors.labelSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Per-site ratings: a colored brand badge + the score, one per source. Wraps to a 2nd line when the
 *  sources don't all fit on one — so no score is hidden when a title is on many sites. After them, what
 *  the file is ([quality]): grey tags, apart from the scores' coloured brand marks — and after, so the
 *  scores stand where they stand on every other page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatingsRow(ratings: List<RatingScore>, quality: List<String>, modifier: Modifier = Modifier) {
    val colors = PicoTheme.colorScheme
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ratings.forEach { r ->
            val brand = ratingBrand(r.source)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(brand.bg.withVibrant(Vibrant.None))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(brand.label, style = PicoTheme.typography.labelMedium, color = brand.fg.withVibrant(Vibrant.None), maxLines = 1, softWrap = false)
                }
                Text(scoreText(r.value), style = PicoTheme.typography.titleSmall, color = colors.labelPrimary, maxLines = 1, softWrap = false)
            }
        }
        if (quality.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                quality.forEach { tag ->
                    // The brand badges' shape and height, in the glass's own quiet fill.
                    Text(
                        text = tag,
                        style = PicoTheme.typography.labelMedium,
                        color = colors.labelSecondary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.fillTertiary)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** A quality tag as the reader calls it: the formats' own names, the Dolby marks in the reader's words. */
private fun qualityText(ctx: Context, tag: QualityTags.Tag): String = when (tag) {
    QualityTags.Tag.UHD_8K -> "8K"
    QualityTags.Tag.UHD_4K -> "4K"
    QualityTags.Tag.FHD -> "1080P"
    QualityTags.Tag.HD -> "720P"
    QualityTags.Tag.DOLBY_VISION -> ctx.getString(R.string.q_dolby_vision)
    QualityTags.Tag.HDR10_PLUS -> "HDR10+"
    QualityTags.Tag.HDR10 -> "HDR"
    QualityTags.Tag.HLG -> "HLG"
    QualityTags.Tag.ATMOS -> ctx.getString(R.string.q_dolby_atmos)
    QualityTags.Tag.DTS_X -> "DTS:X"
    QualityTags.Tag.TRUEHD -> "TrueHD"
    QualityTags.Tag.DTS_HD_MA -> "DTS-HD MA"
    QualityTags.Tag.REMUX -> "Remux"
    QualityTags.Tag.THREE_D -> "3D"
    QualityTags.Tag.VR360 -> "360°"
    QualityTags.Tag.VR180 -> "180°"
}

/** A site's own badge colours — brand marks keep their colour on any glass (Vibrant.None). */
internal data class RatingBrand(val label: String, val bg: Color, val fg: Color)

/** Brand label + colors per rating site (Western + Eastern). */
@Composable
internal fun ratingBrand(source: RatingSource): RatingBrand = when (source) {
    RatingSource.IMDB -> RatingBrand("IMDb", Color(0xFFF5C518), Color.Black) // design-style: fixed-figma-color IMDb brand
    RatingSource.ROTTEN_TOMATOES -> RatingBrand(stringResource(R.string.det_brand_rt), Color(0xFFFA320A), Color.White) // design-style: fixed-figma-color Rotten Tomatoes brand
    RatingSource.METACRITIC -> RatingBrand("Metacritic", Color(0xFF54A72A), Color.White) // design-style: fixed-figma-color Metacritic brand
    RatingSource.TMDB -> RatingBrand("TMDB", Color(0xFF01B4E4), Color.White) // design-style: fixed-figma-color TMDB brand
    RatingSource.DOUBAN -> RatingBrand(stringResource(R.string.det_brand_douban), Color(0xFF2E963B), Color.White) // design-style: fixed-figma-color Douban brand
    RatingSource.BANGUMI -> RatingBrand("Bangumi", Color(0xFFF09199), Color.White) // design-style: fixed-figma-color Bangumi brand
    RatingSource.MAL -> RatingBrand("MAL", Color(0xFF2E51A2), Color.White) // design-style: fixed-figma-color MyAnimeList brand
    RatingSource.ANILIST -> RatingBrand("AniList", Color(0xFF02A9FF), Color.White) // design-style: fixed-figma-color AniList brand
}

/** Minutes → "2 h 16 min" / "45 min", in the reader's language. */
@Composable
internal fun formatRuntime(min: Int): String =
    if (min >= 60) {
        val h = min / 60
        val m = min % 60
        if (m == 0) stringResource(R.string.det_runtime_h, h) else stringResource(R.string.det_runtime_hm, h, m)
    } else {
        stringResource(R.string.det_runtime_min, min)
    }

/** The full 简介 + 演职人员 in PICO's Sheet — synopsis, then staff, then cast; its own close button. */
@Composable
private fun MoreDialog(
    overview: String?,
    crew: List<Person>,
    cast: List<Person>,
    colors: ColorScheme,
    othersCount: (Person) -> Int = { 0 },
    onPerson: (Person) -> Unit = {},
    onDismiss: () -> Unit,
) {
    Sheet(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.det_details)) }) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            overview?.takeIf { it.isNotBlank() }?.let {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.det_overview), style = PicoTheme.typography.titleSmall, color = colors.labelPrimary)
                    Text(it, style = PicoTheme.typography.bodyMediumMultiline, color = colors.labelSecondary)
                }
            }
            if (crew.isNotEmpty()) CreditGroup(stringResource(R.string.det_crew), crew, colors, othersCount, onPerson)
            if (cast.isNotEmpty()) CreditGroup(stringResource(R.string.det_cast), cast, colors, othersCount, onPerson)
        }
    }
}

/** A list of credits; a person with other cards in the library is a row that opens them. */
@Composable
private fun CreditGroup(
    title: String,
    people: List<Person>,
    colors: ColorScheme,
    othersCount: (Person) -> Int,
    onPerson: (Person) -> Unit,
) {
    // A lit row keeps clear of the rows round it, and its face and words of its edge: rows 4dp apart
    // with 6dp inside put the highlight hard against both.
    Column(verticalArrangement = Arrangement.spacedBy(SideRowGap)) {
        Text(title, style = PicoTheme.typography.titleSmall, color = colors.labelPrimary, modifier = Modifier.padding(bottom = 6.dp))
        people.forEach { person -> key(person.name, person.role) {
            val others = othersCount(person)
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (others == 0) Modifier
                        else Modifier
                            .spatialHoverEffect()
                            .hoverDebug()
                            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onPerson(person) }
                            .controllerHapticFeedback(interactionSource = interaction),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(person)
                Column(modifier = Modifier.weight(1f)) {
                    Text(person.name, style = PicoTheme.typography.bodyMedium, color = colors.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    crewRoleLabel(person.role)?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = PicoTheme.typography.bodySmall, color = colors.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (others > 0) {
                    Text(
                        pluralStringResource(R.plurals.det_person_more_works, others, others),
                        style = PicoTheme.typography.labelMedium,
                        color = colors.interaction,
                        maxLines = 1,
                    )
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.labelTertiary, modifier = Modifier.size(16.dp))
                }
            }
        } }
    }
}

/**
 * A round headshot (cached profile photo), or the initials on a colour of the name's own. Both colours
 * are fixed (Vibrant.None): left to the glass, the hue washed out and the white initial with it.
 */
@Composable
internal fun Avatar(person: Person, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(initialValue = null, person.profilePath, person.profileUrl) {
        value = decodeProfile(ctx, person, reqWidthPx = if (size > 60.dp) 240 else 160)
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(ThemeBridge.hueFromString(person.name).withVibrant(Vibrant.None))
            // Always beside the name written out: the face (or its initial — "渡 渡边谦") said it over again.
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = img
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Sized with the circle: a 16sp letter was a speck in the cast row's 80dp ones.
            Text(
                Initials.of(person.name),
                style = PicoTheme.typography.titleMedium.copy(fontSize = (size.value * 0.36f).sp),
                color = Color.White.withVibrant(Vibrant.None),
                maxLines = 1,
            )
        }
    }
}

/** One season, the single-season episode list, the OVA list, or the extras bucket. */
private data class Section(
    val title: String,
    val items: List<MediaItem>,
    val kind: DetailSections.Kind,
    val season: Int?,
)

/**
 * Split a show's files into season episode lists + OVA + 特典, all re-derived from the filename via
 * [EpisodeNaming] (the persisted season/episode/tag are unreliable). Within a season each episode is
 * shown once: duplicate files for the same (season, episode) — e.g. a creditless "(NC Ver.)" — keep
 * one primary in the list and move the rest to 特典 so the episode list never shows phantom dupes.
 */
private fun sectionsOf(ctx: Context, all: List<MediaItem>, numberedRun: Boolean = false): List<Section> =
    DetailSections.of(all, numberedRun).map { sec ->
        val title = when (sec.kind) {
            // Under the 剧集 heading the main run is 正片, not 剧集 a second time.
            DetailSections.Kind.EPISODES -> ctx.getString(R.string.det_section_main)
            DetailSections.Kind.SEASON -> ctx.getString(R.string.det_section_season, sec.season ?: 1)
            DetailSections.Kind.OVA -> "OVA"
            DetailSections.Kind.EXTRAS -> ctx.getString(R.string.det_section_extras)
        }
        Section(title, sec.items, sec.kind, sec.season)
    }

/** More sections than this and a segmented control turns into a strip of slivers: a menu instead. */
private const val SEGMENT_LIMIT = 5

@Composable
private fun SeasonSelector(
    sections: List<Section>,
    selected: Int,
    menuRequested: Boolean = false,
    onMenuRequested: () -> Unit = {},
    onSelect: (Int) -> Unit,
) {
    if (sections.size > SEGMENT_LIMIT) {
        SeasonMenu(sections, selected, menuRequested, onMenuRequested, onSelect)
        return
    }
    // The PICO segmented control, sized to its longest name: stretched across the page, two seasons
    // were two half-page bars, and ten were slivers with their names cut off.
    SegmentControl(modifier = Modifier.width(IntrinsicSize.Max)) {
        sections.forEachIndexed { i, sec ->
            SegmentItem(
                selected = i == selected,
                onClick = { onSelect(i) },
                title = { Text(sec.title, maxLines = 1, softWrap = false) },
            )
        }
    }
}

/** How far into [sec]: "已看 5 / 24 集", "已看完 · 24 集", or how many there are when none is seen. */
@Composable
private fun sectionProgressText(sec: Section): String {
    val count = sec.items.size
    val episodes = sec.kind == DetailSections.Kind.SEASON || sec.kind == DetailSections.Kind.EPISODES
    if (!episodes) return pluralStringResource(R.plurals.det_n_files, count, count)
    val seen = sec.items.count { it.progress.watched }
    return when (seen) {
        0 -> pluralStringResource(R.plurals.det_n_episodes, count, count)
        count -> pluralStringResource(R.plurals.det_season_done, count, count)
        else -> stringResource(R.string.det_season_progress, seen, count)
    }
}

/** [sectionProgressText] beside the list it describes. */
@Composable
private fun SectionProgress(sec: Section) {
    Text(
        sectionProgressText(sec),
        style = PicoTheme.typography.bodyMedium,
        color = PicoTheme.colorScheme.labelSecondary,
        maxLines = 1,
        modifier = Modifier.padding(start = 16.dp),
    )
}

/** Many seasons: a chip that names the one shown and opens the whole list, the current one ticked. */
@Composable
private fun SeasonMenu(
    sections: List<Section>,
    selected: Int,
    menuRequested: Boolean,
    onMenuRequested: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    // Automation (see the `season_menu` hook): opened once the chip is laid out; consumed only then.
    LaunchedEffect(menuRequested) {
        if (menuRequested) { delay(400); open = true; onMenuRequested() }
    }
    Box {
        Chip(
            label = { Text(sections[selected].title) },
            onClick = { open = true },
            trailingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        if (open) {
            Menu(
                onDismissRequest = { open = false },
                positionProvider = rememberMenuPositionProvider(
                    horizontalPlacement = HorizontalPlacement.alignStart(),
                    verticalPlacement = VerticalPlacement.below(offset = 8.dp),
                ),
                modifier = Modifier.width(280.dp),
            ) {
                sections.forEachIndexed { i, sec ->
                    MenuItem(
                        title = { Text(sec.title) },
                        // How far each season got, so a long show's menu says where you are in it.
                        subtitle = { Text(sectionProgressText(sec)) },
                        trailingIcon = if (i == selected) {
                            {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = PicoTheme.colorScheme.interaction,
                                )
                            }
                        } else null,
                        onClick = {
                            onSelect(i)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * 版本: a film's versions — the one 播放 plays on the button beside it ("4K · 杜比视界 · Remux ⌄"),
 * the others a tap away, each with how far it was watched.
 */
@Composable
private fun VersionMenu(
    versions: List<MediaItem>,
    labels: Map<String, String>,
    selected: String,
    menuRequested: Boolean,
    onMenuRequested: () -> Unit,
    onSelect: (MediaItem) -> Unit,
) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    // Automation (see the `version_menu` hook): opened once the button is laid out; consumed only then.
    LaunchedEffect(menuRequested) {
        if (menuRequested) { delay(400); open = true; onMenuRequested() }
    }
    val nameOf = { item: MediaItem -> labels[item.id] ?: item.fileName }
    Box {
        Button(
            onClick = { open = true },
            colors = secondaryButtonColors(),
            modifier = Modifier.widthIn(max = 280.dp).tooltip(text = stringResource(R.string.det_versions)),
            trailingIcon = { Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, modifier = Modifier.size(16.dp)) },
        ) {
            // Shrinks before the chevron does: a long name ellipsizes, the ⌄ stays.
            Text(
                nameOf(versions.firstOrNull { it.id == selected } ?: versions.first()),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (open) {
            Menu(
                onDismissRequest = { open = false },
                positionProvider = rememberMenuPositionProvider(
                    horizontalPlacement = HorizontalPlacement.alignStart(),
                    verticalPlacement = VerticalPlacement.below(offset = 8.dp),
                ),
                modifier = Modifier.width(360.dp),
            ) {
                versions.forEach { v ->
                    val state = DetailPlay.rowState(v)
                    MenuItem(
                        title = { Text(nameOf(v), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        subtitle = state?.let { st -> { Text(DetailPlay.rowStateText(ctx, st)) } },
                        trailingIcon = if (v.id == selected) {
                            {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = PicoTheme.colorScheme.interaction,
                                )
                            }
                        } else null,
                        onClick = {
                            onSelect(v)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * Each file's name in the list. The parser's words — 第 3 集, OVA 02, 正片 — except where it has one
 * word for several files: versions of one film say what each one is, "720P" and "4K · 杜比视界 ·
 * Remux" (see [DetailPlay.versionLabels]); an album's six tracks all read 正片, so each gets its own
 * name back (see [DetailPlay.distinctNames]); two 预告 become 预告 1 and 预告 2 (see
 * [DetailPlay.numberRepeats]).
 */
private fun rowLabels(
    ctx: Context,
    items: List<MediaItem>,
    numberedRun: Boolean,
    lists: List<List<MediaItem>> = listOf(items),
    copies: Set<String> = emptySet(),
    /** A numbered run's instalments by their own names (EpisodeNaming.instalmentNames). */
    instalments: Map<String, String> = emptyMap(),
    /** Files a show files under 特典: one that is no episode and names nothing is no 正片 there either. */
    asExtras: Set<String> = emptySet(),
): Map<String, String> {
    val base = items.associate { item ->
        val feature = EpisodeNaming.parse(item, numberedRun).cat == EpisodeNaming.Cat.FEATURE
        // An instalment with no number — a run's prologue, `[激流中国].(引子).富人与穷人` — is called
        // by its own name, not 正片.
        val label = instalments[item.id]?.takeIf { feature }
            // A show's leftover, `Mobile Suit Information.mkv` among Reconguista's specials, had read
            // 正片 there: it goes by what its file calls it.
            ?: EpisodeNaming.ownName(item)?.takeIf { feature && item.id in asExtras && EpisodeNaming.parse(item, numberedRun).name.isNullOrBlank() }
            ?: EpisodeNaming.label(ctx, item, numberedRun)
        item.id to if (item.id in copies) ctx.getString(R.string.det_episode_copy, label) else label
    }
    val isFeature = items.associate { it.id to (EpisodeNaming.parse(it, numberedRun).cat == EpisodeNaming.Cat.FEATURE) }
    // Everything else that reads alike — two 预告, two 先导预告 — is numbered in the order of the list
    // it is shown in. Across the whole show, a 正片 episode and its copy under 特典 had come out
    // 第 1 集 1 and 第 1 集 2, in lists that each hold one 第 1 集.
    val numbered = lists.flatMap { list ->
        val others = list.filterNot { isFeature.getValue(it.id) }
        others.zip(DetailPlay.numberRepeats(others.map { base.getValue(it.id) }))
    }.associate { (f, l) -> f.id to l }
    val labels = base + numbered
    val features = items.filter { isFeature.getValue(it.id) }
    if (features.size < 2) return labels
    // Copies of one film that the two databases each identified are its versions whatever their
    // names say: 借物少女艾莉缇's DBD-Raws copy reads 「借物少女艾莉媞」 off its bracket, the THORA one
    // 正片, and the pair had kept those names instead of saying which is which.
    val joined = DetailPlay.versionSetsOf(features).map { idx -> idx.map { features[it] } }
        .filter { set -> set.map { base.getValue(it.id) }.distinct().size > 1 }
    val repeated = (features.groupBy { base.getValue(it.id) }.values.filter { it.size > 1 } + joined)
        .fold(emptyList<List<MediaItem>>()) { groups, next ->
            val ids = next.mapTo(HashSet()) { it.id }
            val (touching, apart) = groups.partition { g -> g.any { it.id in ids } }
            apart + listOf((touching.flatten() + next).distinctBy { it.id })
        }
    if (repeated.isEmpty()) return labels
    val versions = repeated.flatMap { group ->
        // A cut's name is what the versions share; where the names differ (a copy named off its own
        // bracket, 「借物少女艾莉媞」) it is no cut, and each version is told by what it is alone.
        val shared = group.map { base.getValue(it.id) }.distinct().size == 1
        DetailPlay.versionLabels(group).map { (i, label) ->
            val file = group[i]
            val what = when (label) {
                is DetailPlay.VersionLabel.Tags -> label.tags.joinToString(" · ") { qualityText(ctx, it) }
                is DetailPlay.VersionLabel.Name -> label.name
            }
            // A version of a named cut keeps the cut's name: "Director's Cut · 4K".
            val cut = EpisodeNaming.parse(file, numberedRun).name?.takeIf { shared }
            file.id to if (cut.isNullOrBlank()) what else "$cut · $what"
        }
    }.toMap()
    val rest = repeated.flatten().filterNot { it.id in versions }
    val names = DetailPlay.distinctNames(rest.map { it.fileName })
    return labels + versions + rest.zip(names).associate { (file, name) -> file.id to name }
}

/**
 * One file of the list; the whole row plays it. A show's episode leads with its still (the show's
 * picture until the episode's own arrives) with how far it was watched across the bottom; a plain
 * file leads with a glyph. The row the big button would play is lit, and says so.
 */
@Composable
private fun EpisodeRow(
    item: MediaItem,
    label: String,
    /** What the database tells of the episode: its name joins the label, what happens in it stands over the file name. */
    info: EpisodeInfo? = null,
    still: Boolean,
    tileTitle: String,
    tileText: String?,
    upNext: DetailPlay.Kind?,
    onClick: () -> Unit,
    onSetWatched: (Boolean) -> Unit,
    earlier: () -> List<MediaItem> = { emptyList() },
    onPlayFromStart: () -> Unit = onClick,
    onSetWatchedFiles: (List<MediaItem>, Boolean) -> Unit = { _, _ -> },
    menuRequested: Boolean = false,
    onMenuRequested: () -> Unit = {},
) {
    val colors = PicoTheme.colorScheme
    val watched = item.progress.watched
    // A long press (pinch and hold, the trigger held) opens the row's menu, with the system's sound.
    var menuOpen by remember { mutableStateOf(false) }
    val sound = LocalAudioEffectPlayer.current
    LaunchedEffect(menuRequested) {
        if (menuRequested) { delay(300); menuOpen = true; onMenuRequested() }
    }
    // Only progress is worth words here: "watched" is what the filled tick at the end says.
    val stateText = DetailPlay.rowState(item)
        ?.takeIf { it !is DetailPlay.RowState.Watched }
        ?.let { DetailPlay.rowStateText(LocalContext.current, it) }
    // Indication and haptics on one interactionSource. The hover is ListItem's own (it clips to
    // [shape] and lights itself); a second one laid over it here lit the row twice as bright.
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .hoverDebug()
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onLongClick = { sound.playSystem(SpatialSoundEffect.OpLongPress); menuOpen = true },
                onClick = onClick,
            )
            .controllerHapticFeedback(interactionSource = interaction),
        shape = shape,
        // The next-up row stands out the way a chosen thing does on PICO: the stronger light fill.
        colors = if (upNext != null) ListItemDefaults.listItemColors(backgroundColor = colors.fillSecondary) else ListItemDefaults.listItemColors(),
        leadingContent = {
            if (still) {
                EpisodeStill(item, tileTitle, tileText)
            } else {
                // A watched file leads with a tick, a part-watched one with the play glyph in the accent.
                Icon(
                    painter = painterResource(if (watched) R.drawable.ic_check else R.drawable.ic_pc_play),
                    contentDescription = if (watched) stringResource(R.string.det_watched) else null,
                    modifier = Modifier.size(20.dp),
                    tint = when {
                        watched -> colors.labelTertiary
                        item.progress.started -> colors.interaction
                        else -> colors.labelSecondary
                    },
                )
            }
        },
        headlineContent = {
            val primary = if (watched) colors.labelSecondary else colors.labelPrimary
            val name = info?.name
            Text(
                // 第 3 集, quieter, then what the episode is called: 「第 3 集  罪孽」.
                if (name == null) AnnotatedString(label) else buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.labelSecondary)) { append(label) }
                    append("  ")
                    withStyle(SpanStyle(color = primary)) { append(name) }
                },
                style = PicoTheme.typography.bodyLarge,
                color = primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val overview = com.therealityteam.videoplayer.ui.common.readableSynopsis(info?.overview)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (overview != null) {
                    Text(overview, style = PicoTheme.typography.bodySmall, color = colors.labelSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // The file always shows, under whatever the database tells: which copy, which rip, is
                // what tells one file from another, and no name a database gives replaces it.
                Text(item.fileName, style = PicoTheme.typography.bodySmall, color = colors.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        // What comes next and how far it got, then the tick that flips it: watched ↔ not.
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (upNext != null && upNext != DetailPlay.Kind.RESUME) {
                    Text(stringResource(R.string.det_up_next), style = PicoTheme.typography.labelLarge, color = colors.interaction, maxLines = 1)
                }
                if (stateText != null) {
                    Text(stateText, style = PicoTheme.typography.bodyMedium, color = colors.interaction, maxLines = 1)
                }
                DownloadMark(item)
                WatchedToggle(watched = watched, what = label, onSetWatched = onSetWatched)
            }
            if (menuOpen) {
                EpisodeMenu(
                    item = item,
                    earlier = remember(item) { earlier() },
                    onDismiss = { menuOpen = false },
                    onPlay = onClick,
                    onPlayFromStart = onPlayFromStart,
                    onSetWatched = onSetWatchedFiles,
                )
            }
        },
    )
}

/**
 * A row's long-press menu, anchored at the row's end where the long press usually lands: play it (on
 * where it was left), 从头播放 when it was begun, mark it — and 标为已看（含之前各集）, for somebody
 * who has watched up to here somewhere else and would otherwise tick the episodes one by one.
 */
@Composable
private fun EpisodeMenu(
    item: MediaItem,
    earlier: List<MediaItem>,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onSetWatched: (List<MediaItem>, Boolean) -> Unit,
) {
    val p = item.progress
    val resumable = p.started && !p.watched
    Menu(
        onDismissRequest = onDismiss,
        positionProvider = rememberMenuPositionProvider(
            horizontalPlacement = HorizontalPlacement.alignEnd(),
            verticalPlacement = VerticalPlacement.center(),
        ),
    ) {
        MenuItem(
            title = {
                Text(stringResource(when { resumable -> R.string.cw_resume; p.watched -> R.string.det_watch_again; else -> R.string.det_play }))
            },
            leadingIcon = { RowMenuIcon(R.drawable.ic_pc_play) },
            onClick = { onDismiss(); onPlay() },
        )
        if (resumable) {
            MenuItem(
                title = { Text(stringResource(R.string.det_play_from_start)) },
                leadingIcon = { RowMenuIcon(R.drawable.ic_opt_reset) },
                onClick = { onDismiss(); onPlayFromStart() },
            )
        }
        MenuItem(
            title = { Text(stringResource(if (p.watched) R.string.det_mark_unwatched else R.string.det_mark_watched)) },
            leadingIcon = { RowMenuIcon(if (p.watched) R.drawable.ic_det_watched_fill else R.drawable.ic_det_watched) },
            onClick = { onDismiss(); onSetWatched(listOf(item), !p.watched) },
        )
        if (earlier.isNotEmpty()) {
            MenuItem(
                title = { Text(stringResource(R.string.det_mark_through)) },
                leadingIcon = { RowMenuIcon(R.drawable.ic_det_watched_through) },
                onClick = { onDismiss(); onSetWatched(earlier + listOfNotNull(item.takeUnless { p.watched }), true) },
            )
        }
        // 下载到本机 — or 删除下载, once it is on the list.
        LocalDownloads.current?.takeIf { it.downloader.canDownload(item) }?.let { dl ->
            val list by dl.downloader.store.all.collectAsState()
            if (list.any { it.itemId == item.id }) {
                MenuItem(
                    title = { Text(stringResource(R.string.dl_remove)) },
                    leadingIcon = { RowMenuIcon(R.drawable.ic_line_delete) },
                    onClick = { onDismiss(); dl.onRemove(item.id) },
                )
            } else {
                MenuItem(
                    title = { Text(stringResource(R.string.dl_add)) },
                    leadingIcon = { RowMenuIcon(R.drawable.ic_line_download) },
                    onClick = { onDismiss(); dl.onAdd(listOf(item)) },
                )
            }
        }
    }
}

/**
 * Whether the episode is on the headset: the 已下载 glyph once its copy is whole, how far while it
 * downloads; nothing for one not on the list.
 */
@Composable
private fun DownloadMark(item: MediaItem) {
    val dl = LocalDownloads.current ?: return
    val list by dl.downloader.store.all.collectAsState()
    val active by dl.downloader.active.collectAsState()
    val d = active?.takeIf { it.itemId == item.id } ?: list.firstOrNull { it.itemId == item.id } ?: return
    val colors = PicoTheme.colorScheme
    if (d.isDone) {
        val label = stringResource(R.string.dl_on_headset)
        Icon(painterResource(R.drawable.ic_line_downloaded), label, Modifier.size(20.dp).tooltip(text = label), tint = colors.labelTertiary)
    } else if (d.state == com.therealityteam.videoplayer.data.download.Download.State.RUNNING && d.totalBytes > 0) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(R.drawable.ic_line_download), null, Modifier.size(18.dp), tint = colors.labelSecondary)
            Text(com.therealityteam.videoplayer.ui.common.percentText((d.fraction * 100).toInt()), style = PicoTheme.typography.bodyMedium, color = colors.labelSecondary, maxLines = 1)
        }
    } else {
        val label = stringResource(R.string.dl_state_queued)
        Icon(painterResource(R.drawable.ic_line_download), label, Modifier.size(20.dp).tooltip(text = label), tint = colors.labelTertiary)
    }
}

/** A row menu's glyph, tinted outright to the label colour (the menu tints nothing itself). */
@Composable
private fun RowMenuIcon(res: Int) {
    Icon(painterResource(res), contentDescription = null, tint = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.size(20.dp))
}

/**
 * An episode's still at a thumbnail's size, fetched the first time it is shown. Until it arrives, and
 * for an episode that has none, a cover in the show's own colours (the wall's [TitleTile] gradient)
 * with the episode's number on it: the show's backdrop repeated down twenty rows said nothing about
 * any one of them, and read as if every episode had that picture. How far the episode was watched
 * runs across its foot — white on a dark fade, the way anything is set over a picture.
 */
@Composable
private fun EpisodeStill(item: MediaItem, tileTitle: String, tileText: String?) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { STILL_WIDTH.roundToPx() }
    // No still on record: the picture of the episode where it was last watched (ResumeFrame, taken off
    // the screen as it played — no file is opened for it), once it has been.
    val ownStill = item.stillPath != null || item.stillUrl != null
    val watchedFrame = remember(item.id, item.progress.updatedAt) {
        if (ownStill || !item.progress.started) null
        else ResumeFrame.file(ctx.filesDir, item.id).takeIf { it.isFile }?.absolutePath
    }
    val image by produceState(initialValue = cachedArtwork(item.stillPath ?: watchedFrame, px), item.id, item.stillPath, item.stillUrl, watchedFrame) {
        if (ownStill) decodeStill(ctx, item, px)?.let { value = it } else decodeArtwork(watchedFrame, px)?.let { value = it }
    }
    val stops = remember(tileTitle) { titleGradient(tileTitle) }
    Box(
        modifier = Modifier
            .size(STILL_WIDTH, STILL_HEIGHT)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(stops)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = image
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (tileText != null) {
            Text(
                text = tileText,
                style = PicoTheme.typography.displaySmall,
                color = Color.White.copy(alpha = 0.94f).withVibrant(Vibrant.None),
                maxLines = 1,
            )
        } else {
            Icon(
                painterResource(R.drawable.ic_pc_play),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f).withVibrant(Vibrant.None),
                modifier = Modifier.size(22.dp),
            )
        }
        val p = item.progress
        if (p.started && !p.watched && p.fraction > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f).withVibrant(Vibrant.None)))),
            )
            LinearProgressIndicator(
                progress = { p.fraction },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                colors = LinearProgressDefaults.linearProgressColors(
                    indicatorColor = Color.White.withVibrant(Vibrant.None),
                    backgroundColor = Color.White.copy(alpha = 0.35f).withVibrant(Vibrant.None),
                ),
                height = LinearProgressDefaults.Small,
            )
        }
    }
}

/** The number an episode's cover carries: "07" for an episode, "12.5" for a recap; null for anything else. */
private fun episodeNumberText(item: MediaItem, numberedRun: Boolean): String? {
    val info = EpisodeNaming.parse(item, numberedRun)
    if (info.cat != EpisodeNaming.Cat.EPISODE) return null
    val n = info.episode ?: return null
    return if (n == Math.floor(n)) "%02d".format(n.toInt()) else n.toString()
}

/**
 * A row's watched tick: an outline to mark it seen, filled in the accent once it is — named, to whatever reads
 * the page, for the row it marks ([what], 「第 3 集」): a list of them was 「标为已看」 a hundred and fifty times.
 */
@Composable
private fun WatchedToggle(watched: Boolean, what: String?, onSetWatched: (Boolean) -> Unit) {
    val colors = PicoTheme.colorScheme
    val desc = stringResource(if (watched) R.string.det_mark_unwatched else R.string.det_mark_watched)
    val named = what?.let { stringResource(if (watched) R.string.det_mark_unwatched_named else R.string.det_mark_watched_named, it) } ?: desc
    ToggleIconButton(
        checked = watched,
        onCheckedChange = { onSetWatched(!watched) },
        modifier = Modifier.tooltip(text = desc),
        colors = ToggleIconButtonDefaults.toggleIconButtonColors(
            checkedContainerColor = Color.Transparent,
            checkedContentColor = colors.interaction,
            uncheckedContainerColor = Color.Transparent,
            uncheckedContentColor = colors.labelTertiary,
        ),
        // Small (40dp), not Min: at arm's length 32dp is a hard target to hit with a ray.
        size = ToggleIconButtonDefaults.Small,
    ) {
        Icon(
            painter = painterResource(if (watched) R.drawable.ic_det_watched_fill else R.drawable.ic_det_watched),
            contentDescription = named,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 全部标为已看 / 全部标为未看, asked first: how many files it changes, and how many lose their place.
 * Clearing progress is the destructive direction, so its button carries the danger colour.
 */
@Composable
internal fun MarkAllDialog(markAll: DetailPlay.MarkAll, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val n = markAll.targets.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (markAll.watched) R.string.det_mark_all_watched_q else R.string.det_mark_all_unwatched_q))
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (markAll.watched) {
                    Text(pluralStringResource(R.plurals.det_confirm_watched_n, n, n))
                    if (markAll.partWatched > 0) {
                        Text(pluralStringResource(R.plurals.det_confirm_resume_lost_n, markAll.partWatched, markAll.partWatched))
                    }
                } else {
                    Text(pluralStringResource(R.plurals.det_confirm_unwatched_n, n, n))
                }
            }
        },
        buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDismiss, colors = secondaryButtonColors()) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = onConfirm,
                    colors = if (markAll.watched) ButtonDefaults.buttonColors() else dangerButtonColors(),
                ) {
                    Text(stringResource(if (markAll.watched) R.string.det_mark_watched else R.string.det_mark_unwatched))
                }
            }
        },
    )
}

/**
 * The file-location button — a secondary action that opens PICO's Sheet listing every distinct
 * folder the files live in, each leading into 文件夹 at that folder ([onOpen], with one of its files)
 * where it is on a share. The sheet's own close button (and tap-outside) closes it.
 */
@Composable
private fun PathButton(folders: List<Pair<String, MediaItem>>, colors: ColorScheme, onOpen: ((MediaItem) -> Unit)?) {
    var show by rememberSaveable { mutableStateOf(false) }
    SheetHook("paths") { show = true }
    // `-e aura.detail_sheet paths_open`: the first folder's row tapped, into 文件夹.
    SheetHook("paths_open") { folders.firstOrNull { !isServerItem(it.second) }?.let { onOpen?.invoke(it.second) } }
    ActionIcon(R.drawable.ic_folder, stringResource(R.string.det_paths), onClick = { show = true })

    if (show) {
        Sheet(onDismissRequest = { show = false }, title = { Text(stringResource(R.string.det_paths_n, folders.size)) }) {
            Column(
                modifier = Modifier.widthIn(max = 860.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                folders.forEach { (folder, file) ->
                    if (onOpen != null && !isServerItem(file)) {
                        // The folder's own name to read; the way to it beneath, smaller — the sheet is narrow,
                        // and a release folder's name alone fills two lines of it.
                        com.therealityteam.videoplayer.ui.common.DetailActionRow(
                            title = folder.substringAfterLast(" › "),
                            description = folder.substringBeforeLast(" › ", "").ifEmpty { null },
                            onClick = { show = false; onOpen(file) },
                            icon = R.drawable.ic_line_folder,
                        )
                    } else {
                        Text(folder, style = PicoTheme.typography.bodyMedium, color = colors.labelSecondary)
                    }
                }
            }
        }
    }
}

/** Automation hook (`-e aura.detail_sheet <name>`): opens the named sheet once, as its tap would. */
@Composable
private fun SheetHook(name: String, open: () -> Unit) {
    val request by DeepLink.detailSheetRequest.collectAsState()
    LaunchedEffect(request) {
        if (request.equals(name, ignoreCase = true)) {
            open()
            DeepLink.detailSheetRequest.value = null
        }
    }
}

/** What a poster load has come to: the picture, or — once settled without one — that there is none. */
private class PosterLoad(val bitmap: ImageBitmap?, val settled: Boolean)

/**
 * The title's poster; without one, the same name-coloured cover the wall draws for it ([TitleTile]),
 * so a card and its page look alike. While the file is still decoding the tinted glass holds its place.
 */
@Composable
private fun Poster(group: MediaGroup, ambient: Color, modifier: Modifier) {
    val path = group.posterPath
    val poster by produceState(
        initialValue = cachedArtwork(path, 700).let { PosterLoad(it, settled = path == null || it != null) },
        path,
    ) {
        value = PosterLoad(decodeArtwork(path, reqWidthPx = 700), settled = true)
    }
    Box(modifier = modifier.clip(RoundedCornerShape(16.dp))) {
        val bmp = poster.bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp,
                contentDescription = group.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            poster.settled -> TitleTile(title = group.title, modifier = Modifier.fillMaxSize())
            else -> Box(Modifier.fillMaxSize().glassSurface(corner = 16.dp, tint = ambient))
        }
    }
}

/**
 * Where a file lives, as somebody would go looking for it: its folder — or, for a media server's
 * stream, the server, since its "folder" is an opaque item id per episode.
 */
private fun locationOf(item: MediaItem): String {
    if (isServerItem(item)) {
        val scheme = item.path.substringBefore("://", "")
        if (scheme.isNotEmpty()) return scheme + "://" + item.path.substringAfter("://").substringBefore('/')
    }
    return folderOf(item.path)
}

private fun folderOf(path: String): String {
    val norm = path.replace('\\', '/').trimEnd('/')
    return norm.substringBeforeLast('/', norm)
}
