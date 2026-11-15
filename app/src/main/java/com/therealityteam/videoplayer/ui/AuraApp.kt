package com.therealityteam.videoplayer.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import com.therealityteam.videoplayer.ui.common.SideRowGap
import com.therealityteam.videoplayer.ui.common.hoverDebug
import com.therealityteam.videoplayer.ui.library.BecauseYouWatched
import com.therealityteam.videoplayer.ui.library.HistoryBuckets
import com.therealityteam.videoplayer.ui.library.TitleIndex
import androidx.compose.runtime.CompositionLocalProvider
import com.therealityteam.videoplayer.ui.library.LocalCardMenu
import com.therealityteam.videoplayer.ui.library.LocalWallSort
import com.therealityteam.videoplayer.ui.library.CardMenuActions
import com.therealityteam.videoplayer.ui.library.WatchStats
import com.therealityteam.videoplayer.ui.library.LibrarySort
import com.therealityteam.videoplayer.ui.library.filterSummary
import com.therealityteam.videoplayer.ui.library.FilterChip
import com.therealityteam.videoplayer.ui.library.WallFilter
import com.therealityteam.videoplayer.ui.library.PeopleIndex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.therealityteam.videoplayer.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.LocalDisableAlpha
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SearchField
import com.pico.spatial.ui.design.SideNavigation
import com.pico.spatial.ui.design.SideNavigationItem
import com.pico.spatial.ui.design.SideNavigationSection
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.TitleAlignment
import com.pico.spatial.ui.design.TitleBar
import com.pico.spatial.ui.design.windows.AlertDialog
import com.pico.spatial.ui.design.windows.LocalSnackbarHostState
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.therealityteam.videoplayer.VIDEO_WC_ID
import com.therealityteam.videoplayer.VIDEO_WC_TAG
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.di.AURA_SESSION_SCOPE_ID
import com.therealityteam.videoplayer.ui.common.EmptyState
import com.therealityteam.videoplayer.ui.library.ContinueHeader
import com.therealityteam.videoplayer.ui.library.ContinueWatching
import com.therealityteam.videoplayer.ui.library.DetailScreen
import com.therealityteam.videoplayer.ui.library.FixMatchScreen
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.LibSection
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.PRIMARY_SECTIONS
import com.therealityteam.videoplayer.ui.library.SECONDARY_SECTIONS
import com.therealityteam.videoplayer.ui.library.LibSort
import com.therealityteam.videoplayer.ui.library.SortChip
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import com.therealityteam.videoplayer.ui.library.LibraryNavState
import com.therealityteam.videoplayer.ui.library.LibraryTaskProgress
import com.therealityteam.videoplayer.ui.library.LibraryViewModel
import com.therealityteam.videoplayer.ui.library.PosterWall
import com.therealityteam.videoplayer.ui.library.TaskProgress
import com.therealityteam.videoplayer.ui.library.icon
import com.therealityteam.videoplayer.ui.library.label
import com.therealityteam.videoplayer.ui.library.SourcesScreen
import com.therealityteam.videoplayer.ui.library.taskProgressText
import com.therealityteam.videoplayer.ui.player.PlaybackViewModel
import com.therealityteam.videoplayer.ui.library.FolderBrowser
import com.therealityteam.videoplayer.ui.settings.SettingsScreen
import com.therealityteam.videoplayer.ui.theme.secondaryButtonColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import com.therealityteam.videoplayer.ui.library.PeopleSearch
import com.therealityteam.videoplayer.ui.library.PersonWorksSheet
import com.therealityteam.videoplayer.ui.library.SeriesSheet
import com.therealityteam.videoplayer.ui.library.SeriesTitles
import com.therealityteam.videoplayer.ui.library.SearchPeopleLane
import androidx.compose.runtime.withFrameNanos
import com.pico.spatial.ui.design.Chip
import com.pico.spatial.ui.design.ChipsDefaults
import com.therealityteam.videoplayer.ui.library.SurprisePick
import com.therealityteam.videoplayer.ui.library.PlayQueue
import com.pico.spatial.ui.design.windows.SnackbarDuration
import com.pico.spatial.ui.design.windows.SnackbarResult

private val CATEGORY_SECTIONS = PRIMARY_SECTIONS
private val LIST_SECTIONS = listOf(LibSection.FAVORITES, LibSection.HISTORY)

/**
 * Aura's Shared-Space root: a left sidebar (the search box; the shelves, with 更多 folding the
 * uncommon ones; 收藏/历史; then 来源 / 刮削 / 设置) and a right-hand spatial poster wall.
 * All PICO Spatial UI; Vibrant on.
 */
@Composable
fun AuraApp() {
    JankWatch("library")
    androidx.compose.runtime.DisposableEffect(Unit) {
        val n = com.therealityteam.videoplayer.libraryWindowsOpen.incrementAndGet()
        android.util.Log.i("AuraWindows", "library window up ($n)")
        onDispose { android.util.Log.i("AuraWindows", "library window gone (${com.therealityteam.videoplayer.libraryWindowsOpen.decrementAndGet()})") }
    }
    // 下载到本机, for the menus anywhere in the window to offer; the queue carries on as the window opens.
    val downloader = remember { GlobalContext.get().get<com.therealityteam.videoplayer.data.download.Downloader>() }
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val downloads = remember(downloader) {
        com.therealityteam.videoplayer.ui.library.DownloadActions(
            downloader,
            onAdd = { files ->
                val n = downloader.add(files)
                val text = if (n > 0) ctx.resources.getQuantityString(R.plurals.dl_added, n, n) else ctx.getString(R.string.dl_added_none)
                scope.launch { snackbar.show(message = text) }
            },
            onRemove = { id ->
                downloader.remove(id)
                scope.launch { snackbar.show(message = ctx.getString(R.string.dl_removed)) }
            },
        )
    }
    LaunchedEffect(downloader) { downloader.kick() }
    // A download that stopped says so, and why, wherever the window is — not only on 已下载.
    LaunchedEffect(downloader) {
        downloader.failures.collect { d ->
            snackbar.show(message = ctx.getString(R.string.dl_failed_snack, d.fileName, com.therealityteam.videoplayer.ui.library.failureText(ctx, d.error)))
        }
    }
    CompositionLocalProvider(com.therealityteam.videoplayer.ui.library.LocalDownloads provides downloads) { AuraAppContent() }
}

@Composable
private fun AuraAppContent() {
    val koinScope = remember { GlobalContext.get().getScope(AURA_SESSION_SCOPE_ID) }
    val downloadActions = com.therealityteam.videoplayer.ui.library.LocalDownloads.current
    val hookContext = androidx.compose.ui.platform.LocalContext.current
    val hookSnackbar = LocalSnackbarHostState.current
    val hookScope = rememberCoroutineScope()
    val vm = remember(koinScope) { koinScope.get<LibraryViewModel>() }
    val playbackVm = remember(koinScope) { koinScope.get<PlaybackViewModel>() }
    val offline by playbackVm.networkDown.collectAsState()
    val onHeadsetCount = downloadActions?.downloader?.store?.all?.collectAsState()?.value?.count { it.isDone } ?: 0
    val nav = remember(koinScope) { koinScope.get<LibraryNavState>() }
    val taskProgress by vm.taskProgress.collectAsState()
    val navigator = LocalSpatialNavigator.current
    val items by vm.items.collectAsState()

    val settingsRepo = remember { GlobalContext.get().get<com.therealityteam.videoplayer.data.repository.SettingsRepository>() }
    var view by remember { mutableStateOf(nav.view) }
    var query by remember { mutableStateOf(nav.query) }
    // The wall's order: remembered across sessions (settings) and across the window closing (nav).
    var sort by remember {
        mutableStateOf(LibSort.entries.firstOrNull { it.name == settingsRepo.get().librarySort } ?: nav.sort)
    }
    // Folded until asked for, every session: the shelves behind 更多 are the uncommon ones. A shelf
    // that is opened while folded away — the one the window was left on, a deep link — unfolds them,
    // so the selected row is always on screen; the user can still fold them again.
    var moreOpen by remember { mutableStateOf(view in SECONDARY_SECTIONS) }
    LaunchedEffect(view) { if (view in SECONDARY_SECTIONS) moreOpen = true }
    LaunchedEffect(sort) {
        nav.sort = sort
        val current = settingsRepo.get()
        if (current.librarySort != sort.name) settingsRepo.update(current.copy(librarySort = sort.name))
    }
    var selectedKey by remember { mutableStateOf(nav.selectedKey) }
    var showSettings by remember { mutableStateOf(false) }
    // Which settings page a `-e aura.screen settings_<page>` asked for; null opens the first.
    var settingsPage by remember { mutableStateOf<String?>(null) }
    var showSources by remember { mutableStateOf(false) }
    // 文件夹 (FolderBrowser): the wall's place taken by the shares' own folders, and where in them.
    var foldersOpen by rememberSaveable { mutableStateOf(false) }
    var folderPath by remember { mutableStateOf<com.therealityteam.videoplayer.ui.library.FolderTree.Path?>(null) }
    var folderPlay by remember { mutableStateOf<Int?>(null) }
    // 已下载 (DownloadsPage): the copies on the headset, in the wall's place as 文件夹 is.
    var downloadsOpen by rememberSaveable { mutableStateOf(false) }
    var fixMatchId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(view) { nav.view = view }
    LaunchedEffect(query) { nav.query = query }
    // 筛选: kept for the session (across the window closing, like the shelf), not across launches.
    var wallFilter by remember { mutableStateOf(nav.filter) }
    LaunchedEffect(wallFilter) { nav.filter = wallFilter }
    var filterMenuRequested by remember { mutableStateOf(false) }
    var titleJump by remember { mutableStateOf<Char?>(null) }
    var personRequest by remember { mutableStateOf<Int?>(null) }
    var seriesRequest by remember { mutableStateOf<Int?>(null) }
    var surpriseRequest by remember { mutableStateOf(false) }
    var cwRemoveRequest by remember { mutableStateOf<String?>(null) }
    var wallScroll by remember { mutableStateOf<Float?>(null) }
    var wallGlide by remember { mutableStateOf<List<Int>?>(null) }
    // The pages a person's other works led through, so 返回 retraces them before it leaves for the wall.
    var detailTrail by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(selectedKey) {
        nav.selectedKey = selectedKey
        if (selectedKey == null) detailTrail = emptyList()
    }

    val gridState = rememberLazyGridState(nav.gridIndex, nav.gridOffset)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (i, o) -> nav.gridIndex = i; nav.gridOffset = o }
    }
    // The shelf, search and order the grid's scroll position belongs to (see the wall below). Held
    // up here so a visit to a detail page — which leaves the wall's composition — does not forget it.
    var wallScrolledFor by remember { mutableStateOf(listOf<Any?>(view, query, sort, wallFilter)) }

    val groups by vm.groups.collectAsState() // grouped off-main; see LibraryViewModel.groups
    // Who is credited on which card, library-wide — the detail page's "另有 N 部". Built off the main
    // thread whenever the grouping changes; hidden adult cards stay hidden here as everywhere else.
    // The cards the library shows, adult ones gone while they are hidden — for the detail page's
    // "另有 N 部" and 相似作品, which reach past the shelf on screen.
    val visibleGroups = remember(groups) {
        if (settingsRepo.get().showAdult) groups else groups.filterNot { it.category == LibCategory.ADULT }
    }
    val peopleIndex by produceState(emptyMap<String, List<MediaGroup>>(), visibleGroups) {
        value = withContext(Dispatchers.Default) { PeopleIndex.of(visibleGroups) }
    }
    // …and the same cards with pinyin / romaji search and sort keys, once those are computed.
    val searchable by vm.searchableGroups.collectAsState()
    // Until the library is read and grouped, the wall paints last session's snapshot: the same
    // cards to look at, not to open (each stands on a stub) — the shell keeps taps off.
    val wallLive by vm.wallLive.collectAsState()
    val snapshot by vm.wallSnapshot.collectAsState()
    LaunchedEffect(wallLive, snapshot.size, groups.size) {
        android.util.Log.i("AuraPerf", "wall state: live=$wallLive snapshot=${snapshot.size} groups=${groups.size}")
    }
    val wallGroups = when {
        !wallLive -> snapshot
        searchable.size == groups.size -> searchable
        else -> groups
    }
    // A card tapped while the wall still stands on last session's snapshot (a cold start after the library
    // changed: 7-9 s on the B3110) was dropped. It is kept now — the last one tapped — and opened the moment
    // the wall is live, if the card is still there.
    var tappedEarly by remember { mutableStateOf<Pair<String, Long>?>(null) }
    fun open(key: String) {
        if (wallLive) selectedKey = key else tappedEarly = key to android.os.SystemClock.elapsedRealtime()
    }
    LaunchedEffect(wallLive, groups) {
        val (key, at) = tappedEarly ?: return@LaunchedEffect
        if (!wallLive) return@LaunchedEffect
        tappedEarly = null
        if (groups.any { it.key == key }) {
            selectedKey = key
            android.util.Log.i("AuraPerf", "a card tapped before the wall was live opens ${android.os.SystemClock.elapsedRealtime() - at} ms later")
        }
    }

    fun play(item: MediaItem, queue: List<MediaItem>, startMs: Long? = null) {
        playbackVm.open(item, queue, startMs = startMs)
        navigator.openWindowContainer(VIDEO_WC_ID, tag = VIDEO_WC_TAG)
        navigator.closeWindowContainer()
    }

    // Launch-intent play hook (see DeepLink). Waits for GROUPS, not items: grouping runs
    // debounced off-main after items load, and playing before it lands would queue a single
    // file instead of the whole card.
    val pendingPlay by com.therealityteam.videoplayer.platform.DeepLink.playRequest.collectAsState()
    LaunchedEffect(pendingPlay, groups) {
        val q = pendingPlay?.lowercase() ?: return@LaunchedEffect
        if (groups.isEmpty()) return@LaunchedEffect
        val match = items.firstOrNull { it.fileName.lowercase().contains(q) || it.title.lowercase().contains(q) }
        if (match != null) {
            com.therealityteam.videoplayer.platform.DeepLink.playRequest.value = null
            val seekMs = com.therealityteam.videoplayer.platform.DeepLink.seekMsRequest.value
            com.therealityteam.videoplayer.platform.DeepLink.seekMsRequest.value = null
            // Queue what the item's card would, exactly like tapping it in the wall (see PlayQueue).
            val queue = groups.firstOrNull { g -> g.items.any { it.id == match.id } }?.let { PlayQueue.of(it, match) }
            playbackVm.open(match, queue ?: listOf(match), startMs = seekMs)
            navigator.openWindowContainer(VIDEO_WC_ID, tag = VIDEO_WC_TAG)
            navigator.closeWindowContainer()
        }
    }

    // Direct-file hook: a path/URL that is not in the library plays as an ad-hoc item.
    // Frame-poster deep link (-e aura.frame_poster /path): draws the stand-in poster for one
    // file and logs where it went, so the composition can be checked without a scrape.
    val frameReq by com.therealityteam.videoplayer.platform.DeepLink.framePosterRequest.collectAsState()
    LaunchedEffect(frameReq) {
        val path = frameReq ?: return@LaunchedEffect
        android.util.Log.i("AuraFrame", "request for $path")
        val fp = koinScope.get<com.therealityteam.videoplayer.data.scrape.FramePoster>()
        val name = path.substringAfterLast('/')
        // One probe per file: a poster made for another (or before a change) is not handed back.
        val probe = MediaItem(id = "frame_probe_${Integer.toHexString(path.hashCode())}_${System.currentTimeMillis()}", sourceId = "", path = path, fileName = name, title = name.substringBeforeLast('.'), year = 2026)
        val out = fp.generate(probe)
        android.util.Log.i("AuraFrame", "poster for $name: ${out ?: "none"}")
        // Consumed only now: clearing the request first re-keys this effect and cancels the work.
        com.therealityteam.videoplayer.platform.DeepLink.framePosterRequest.value = null
    }

    val pendingUri by com.therealityteam.videoplayer.platform.DeepLink.playUriRequest.collectAsState()
    LaunchedEffect(pendingUri, wallLive) {
        val request = pendingUri ?: return@LaunchedEffect
        // `aura.play_as` waits for the live library, whose title the file is to stand in for.
        val playAs = com.therealityteam.videoplayer.platform.DeepLink.playAsRequest.value
        if (playAs != null && !wallLive) return@LaunchedEffect
        com.therealityteam.videoplayer.platform.DeepLink.playAsRequest.value = null
        com.therealityteam.videoplayer.platform.DeepLink.playUriRequest.value = null
        // One path, or several joined by '|': they play as one ad-hoc queue, so prev/next
        // (and a headset's skip keys) have somewhere to go.
        val displayName = com.therealityteam.videoplayer.platform.DeepLink.playUriDisplayName.value
        com.therealityteam.videoplayer.platform.DeepLink.playUriDisplayName.value = null
        val castTitle = com.therealityteam.videoplayer.platform.DeepLink.playUriTitle.value
        com.therealityteam.videoplayer.platform.DeepLink.playUriTitle.value = null
        // A file:// link is a file on the headset: taken as its path, as the library's own are, so what lies
        // beside it — subtitles, 外挂音轨 — is found (only a path is looked beside).
        val parts = request.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (it.startsWith("file://")) android.net.Uri.parse(it).path ?: it else it }
        val queue = parts.map { uri ->
            // A content:// uri's last segment is an id; the file manager's display name is the title.
            val fileName = displayName?.takeIf { parts.size == 1 }
                ?: android.net.Uri.decode(uri.substringAfterLast('/'))
            // A cast is filed apart (no progress is kept for a phone's URL); a link from another app as ad hoc.
            val source = if (castTitle != null || com.therealityteam.videoplayer.platform.DeepLink.playUriCast.value) {
                com.therealityteam.videoplayer.data.cast.CAST_SOURCE
            } else "adhoc"
            MediaItem(
                id = MediaItem.idFor(source, uri),
                sourceId = source,
                path = uri,
                fileName = fileName,
                title = castTitle?.takeIf { parts.size == 1 } ?: fileName.substringBeforeLast('.'),
                video = com.therealityteam.videoplayer.data.playback.ProjectionDetector.detect(fileName),
            )
        }
        // The card of that very name, else the shortest that holds it ("特工" is 特工, not 王牌特工：特工学院).
        val standIn = playAs?.lowercase()?.let { q ->
            groups.firstOrNull { it.title.lowercase() == q } ?: groups.filter { it.title.lowercase().contains(q) }.minByOrNull { it.title.length }
        }
            ?.representative?.takeIf { parts.size == 1 }?.copy(path = parts.first())
        if (standIn != null) android.util.Log.i("AuraPlay", "playing ${parts.first()} as ${standIn.title}")
        com.therealityteam.videoplayer.platform.DeepLink.playUriCast.value = false
        val first = standIn ?: queue.firstOrNull() ?: return@LaunchedEffect
        val seekMs = com.therealityteam.videoplayer.platform.DeepLink.seekMsRequest.value
        com.therealityteam.videoplayer.platform.DeepLink.seekMsRequest.value = null
        playbackVm.open(first, if (standIn != null) listOf(standIn) else queue, startMs = seekMs)
        navigator.openWindowContainer(VIDEO_WC_ID, tag = VIDEO_WC_TAG)
        navigator.closeWindowContainer()
    }

    // Automation hook (`-e aura.screen settings|settings_<page>|sources|library|view:<shelf>|search:<text>`):
    // the pages a sidebar tap opens, and the way back to the wall from any of them — a review walks
    // every page in one sitting instead of a cold start per page. Composed above the early returns
    // so it is heard from wherever the window is.
    val screenRequest by com.therealityteam.videoplayer.platform.DeepLink.screenRequest.collectAsState()
    LaunchedEffect(screenRequest) {
        val raw = screenRequest ?: return@LaunchedEffect
        val r = raw.lowercase()
        when {
            r == "settings" -> showSettings = true
            r.startsWith("settings_") -> { settingsPage = r.removePrefix("settings_"); showSettings = true }
            // "sources_add" / "sources_browse:<host>" open the screen here and are consumed inside
            // it, so the panes behind a tap can be reviewed without a hand in the headset.
            r.startsWith("sources") -> showSources = true
            // `filter:<parts>` sets 筛选 (comma-separated: unwatched|in_progress|watched, a decade like
            // 1990, any other word a genre); `filter_menu` opens its panel — for screenshots.
            r.startsWith("filter:") -> wallFilter = parseFilter(raw.substringAfter(':'))
            r == "filter_menu" -> filterMenuRequested = true
            r.startsWith("jump:") -> titleJump = raw.substringAfter(':').firstOrNull()?.uppercaseChar()
            r == "surprise" -> surpriseRequest = true
            // `folders` opens 文件夹; `folders:<source>/<folder>/…` goes straight to a folder in it, and
            // `…#<n>` plays its n-th file.
            // `foldersearch:<words>`: 文件夹's search, by files' own names.
            // `downloads` opens 已下载; `download:<words>` queues the first file whose name has the words,
            // as its menu's 下载到本机 would.
            r == "downloads" -> { downloadsOpen = true; foldersOpen = false; query = "" }
            // `probe_tracks`: one MKV of every folder on the shares has its track list read, and the
            // sound and subtitles Media3 would drop are logged (AuraProbe) — how many releases play
            // silent or unsubtitled without the hand-over to mpv. Reads a few KB a file; writes nothing.
            // `probe_ls:<words>`: the names beside the first file whose name has the words (AuraProbe). Read-only.
            // `probe_sample`: the library's files by extension, with three picked at random of each — names to play
            // in a sweep of every kind the library holds. Logged only.
            r == "probe_sample" -> {
                val all = androidx.compose.runtime.snapshotFlow { items }.first { it.isNotEmpty() }
                all.groupBy { it.fileName.substringAfterLast('.', "").lowercase() }.entries.sortedByDescending { it.value.size }.forEach { (ext, files) ->
                    android.util.Log.i("AuraProbe", "sample .$ext ×${files.size}: " + files.shuffled().take(3).joinToString(" | ") { it.fileName })
                }
            }
            r.startsWith("probe_ls:") -> {
                val w = raw.substringAfter(':')
                val all = androidx.compose.runtime.snapshotFlow { items }.first { it.isNotEmpty() }
                val item = all.firstOrNull { it.path.startsWith("smb://") && it.fileName.contains(w, ignoreCase = true) }
                if (item != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val server = GlobalContext.get().get<com.therealityteam.videoplayer.data.playback.LocalStreamServer>()
                    val src = GlobalContext.get().get<com.therealityteam.videoplayer.data.repository.SourceRepository>().get(item.sourceId)
                    val key = "probe:" + item.path
                    server.register(item.path, src?.username, src?.password, key)
                    runCatching { server.siblings(key) }.onSuccess { android.util.Log.i("AuraProbe", "beside ${item.fileName}: ${it.joinToString(" | ")}") }
                    runCatching { server.listBeside(key, "Subs") }.onSuccess { subs ->
                        android.util.Log.i("AuraProbe", "Subs/: ${subs.joinToString(" | ")}")
                        subs.filter { it.endsWith("/") }.take(2).forEach { d ->
                            runCatching { server.listBeside(key, "Subs/$d") }.onSuccess { android.util.Log.i("AuraProbe", "Subs/$d: ${it.joinToString(" | ")}") }
                        }
                    }
                    server.unregister(key)
                }
            }
            // `probe_subs`: every folder on the shares with a Subs/ folder beside its videos, and what is in it.
            r == "probe_subs" -> {
                val all = androidx.compose.runtime.snapshotFlow { items }.first { it.isNotEmpty() }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val server = GlobalContext.get().get<com.therealityteam.videoplayer.data.playback.LocalStreamServer>()
                    val sources = GlobalContext.get().get<com.therealityteam.videoplayer.data.repository.SourceRepository>()
                    val picks = all.filter { it.path.startsWith("smb://") }.groupBy { it.path.substringBeforeLast('/') }.values.map { it.first() }
                    var found = 0
                    picks.forEach { item ->
                        val src = sources.get(item.sourceId)
                        val key = "probe:" + item.path
                        server.register(item.path, src?.username, src?.password, key) ?: return@forEach
                        try {
                            val dirs = runCatching { server.siblings(key) }.getOrDefault(emptyList()).filter { it.endsWith("/") }
                            dirs.filter { it.trimEnd('/').lowercase() in setOf("subs", "sub", "subtitles", "字幕") }.forEach { d ->
                                found++
                                val inner = runCatching { server.listBeside(key, d) }.getOrDefault(emptyList())
                                android.util.Log.i("AuraProbe", "SUBS ${item.path.substringAfter("/video/").substringBeforeLast('/')}/$d ${inner.size}: ${inner.take(4).joinToString(" | ")}")
                            }
                        } finally { server.unregister(key) }
                    }
                    android.util.Log.i("AuraProbe", "subs done: $found folders of ${picks.size}")
                }
            }
            r == "probe_tracks" -> {
                val all = androidx.compose.runtime.snapshotFlow { items }.first { it.isNotEmpty() }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val server = GlobalContext.get().get<com.therealityteam.videoplayer.data.playback.LocalStreamServer>()
                    val sources = GlobalContext.get().get<com.therealityteam.videoplayer.data.repository.SourceRepository>()
                    val picks = all.filter { it.path.startsWith("smb://") && it.fileName.endsWith(".mkv", ignoreCase = true) }
                        .groupBy { it.path.substringBeforeLast('/') }.values.map { it.first() }
                    android.util.Log.i("AuraProbe", "probing ${picks.size} folders")
                    var hit = 0
                    picks.forEachIndexed { n, item ->
                        val src = sources.get(item.sourceId)
                        val key = "probe:" + item.path
                        val url = server.register(item.path, src?.username, src?.password, key) ?: return@forEachIndexed
                        try {
                            val dropped = com.therealityteam.videoplayer.data.playback.MatroskaTracks.droppedByMedia3(
                                com.therealityteam.videoplayer.data.playback.HttpByteSource(url),
                            )
                            if (dropped.isNotEmpty()) {
                                hit++
                                android.util.Log.i("AuraProbe", "DROPS ${dropped.joinToString()}: ${item.path.substringAfter("/video/")}")
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("AuraProbe", "unreadable: ${item.fileName}: ${e.message}")
                        } finally {
                            server.unregister(key)
                        }
                        if (n % 50 == 49) android.util.Log.i("AuraProbe", "${n + 1}/${picks.size}, $hit with drops")
                    }
                    android.util.Log.i("AuraProbe", "done: $hit of ${picks.size} folders have tracks Media3 drops")
                }
            }
            // `downloads_clean`: 已下载's 清理已看完, as its button would.
            r == "downloads_clean" -> {
                downloadsOpen = true; foldersOpen = false; query = ""
                val all = androidx.compose.runtime.snapshotFlow { items }.first { it.isNotEmpty() }
                downloadActions?.let { com.therealityteam.videoplayer.ui.library.cleanWatched(hookContext, it.downloader, all, hookSnackbar, hookScope) }
            }
            // `download_remove:<words>`: that file's 删除下载, as its menu would.
            r.startsWith("download_remove:") -> raw.substringAfter(':').takeIf { it.isNotBlank() }?.let { w ->
                downloadActions?.downloader?.store?.all?.value?.firstOrNull { it.fileName.contains(w, ignoreCase = true) }
                    ?.let { downloadActions.onRemove(it.itemId) }
            }
            r.startsWith("download:") -> raw.substringAfter(':').takeIf { it.isNotBlank() }?.let { w ->
                // A cold start's request comes before the library is read: wait for it.
                val all = androidx.compose.runtime.snapshotFlow { items }.first { it.isNotEmpty() }
                val hit = all.firstOrNull { it.fileName.contains(w, ignoreCase = true) }
                android.util.Log.i("AuraDownload", "hook: '$w' -> ${hit?.fileName} (of ${all.size}; actions ${downloadActions != null})")
                hit?.let { downloadActions?.onAdd?.invoke(listOf(it)) }
            }
            r.startsWith("foldersearch:") -> { foldersOpen = true; query = raw.substringAfter(':') }
            r == "folders" || r.startsWith("folders:") -> {
                foldersOpen = true
                downloadsOpen = false
                folderPlay = raw.substringAfter('#', "").toIntOrNull()
                folderPath = raw.substringAfter(':', "").substringBefore('#').takeIf { it.isNotBlank() }?.split('/')?.filter { it.isNotEmpty() }?.let { parts ->
                    vm.sources.value.firstOrNull { it.name.equals(parts.first(), ignoreCase = true) }
                        ?.let { com.therealityteam.videoplayer.ui.library.FolderTree.Path(it.id, parts.drop(1)) }
                }
            }
            // `series:<n>`: the n-th tile of 系列 on 全部 opened, as a tap would.
            r.startsWith("series:") -> seriesRequest = raw.substringAfter(':').toIntOrNull()
            // `fixmatch:<words>`: 修正匹配 for the card of that title, as its page's button opens it — the
            // search runs at once (nothing is applied without a pick).
            r.startsWith("fixmatch:") -> raw.substringAfter(':').lowercase().let { q ->
                groups.firstOrNull { it.title.lowercase().contains(q) }
            }?.let { g ->
                showSettings = false; settingsPage = null; showSources = false; foldersOpen = false; downloadsOpen = false
                selectedKey = g.key
                fixMatchId = g.representative.id
            }
            // `cw_remove:<words>`: a 继续观看 card's 从「继续观看」中移除, as its menu would — for the 撤销 snack.
            r.startsWith("cw_remove:") -> cwRemoveRequest = raw.substringAfter(':')
            // `person:<n>:<text>` searches for the text and opens the n-th face of 人物 over the answer,
            // once the faces are found (one launch carries one screen request).
            r.startsWith("person:") -> {
                val parts = raw.split(':', limit = 3)
                parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { query = it }
                personRequest = parts.getOrNull(1)?.toIntOrNull()
            }
            // `scroll:<px>` moves the wall by that much (negative: up), for screenshots of any part of it.
            r.startsWith("scroll:") -> wallScroll = raw.substringAfter(':').toFloatOrNull()
            // `glide:<px>:<ms>[:<wait ms>[:<passes>]]` scrolls the wall that far at an even pace (each further
            // pass back the other way), timing every frame (AuraJank) — how smooth the wall is to read
            // down, posters arriving as it goes; the wait lets a launch's background work finish first.
            r.startsWith("glide:") -> raw.split(':').drop(1).mapNotNull { it.toIntOrNull() }.let { p ->
                if (p.isNotEmpty()) wallGlide = listOf(p[0], p.getOrElse(1) { 4_000 }, p.getOrElse(2) { 2_500 }, p.getOrElse(3) { 1 })
            }
            r == "library" || r.startsWith("view:") || r.startsWith("search:") -> {
                showSettings = false; settingsPage = null; showSources = false; fixMatchId = null; selectedKey = null; foldersOpen = false; downloadsOpen = false
                if (r.startsWith("view:")) LibSection.entries.firstOrNull { it.name.equals(r.removePrefix("view:"), ignoreCase = true) }?.let { view = it }
                query = if (r.startsWith("search:")) raw.substringAfter(':') else ""
            }
            else -> {}
        }
        if (!r.startsWith("sources") || r == "sources") {
            com.therealityteam.videoplayer.platform.DeepLink.screenRequest.value = null
        }
    }

    // What runs whichever page is up — the wall, a title's page, settings: the pages below return early,
    // and an effect written after them was not there while one was open (the 看完了 card's 查看 did
    // nothing over a title's page, the library's own update waited for the wall).
    // 自动更新媒体库: once the library is read, a source scan to take in files added to the shares since —
    // at most every AUTO_SCAN_EVERY_MS, in the background (the title bar shows it running), and only
    // new files are matched. Off in 设置 › 媒体库.
    LaunchedEffect(wallLive) {
        if (!wallLive) return@LaunchedEffect
        val s = settingsRepo.get()
        val now = System.currentTimeMillis()
        if (!s.autoScan || now - s.lastAutoScanAt < com.therealityteam.videoplayer.data.repository.AUTO_SCAN_EVERY_MS) return@LaunchedEffect
        if (vm.sources.value.isEmpty()) return@LaunchedEffect
        android.util.Log.i("AuraScan", "auto scan: last ${if (s.lastAutoScanAt == 0L) "never" else "${(now - s.lastAutoScanAt) / 60_000} min ago"}")
        settingsRepo.update(s.copy(lastAutoScanAt = now))
        vm.scanForNew()
    }

    // Automation hook (`-e aura.cast on|off`): 接收投屏 switched as its switch in 设置 would.
    val castRequest by com.therealityteam.videoplayer.platform.DeepLink.castRequest.collectAsState()
    LaunchedEffect(castRequest) {
        val on = castRequest ?: return@LaunchedEffect
        com.therealityteam.videoplayer.platform.DeepLink.castRequest.value = null
        settingsRepo.update(settingsRepo.get().copy(castReceiver = on))
        android.util.Log.i("AuraCast", "receiver ${if (on) "on" else "off"} by automation")
    }
    // Automation hook (`-e aura.engine exo|system`): 引擎 switched as its switch in 设置 would; the next launch plays with it.
    val engineRequest by com.therealityteam.videoplayer.platform.DeepLink.engineRequest.collectAsState()
    LaunchedEffect(engineRequest) {
        val exo = engineRequest ?: return@LaunchedEffect
        com.therealityteam.videoplayer.platform.DeepLink.engineRequest.value = null
        settingsRepo.update(settingsRepo.get().copy(useExoEngine = exo))
        android.util.Log.i("AuraPlay", "engine ${if (exo) "Media3 (+mpv)" else "the system's player"} by automation, from the next launch")
    }
    // Automation hook (`-e aura.open …`): a card's detail page, as a tap on it opens it — on the snapshot
    // wall before it is live too (by title: its cards stand on stubs), which opens once the wall is.
    val openReq by com.therealityteam.videoplayer.platform.DeepLink.openRequest.collectAsState()
    LaunchedEffect(openReq, wallLive, snapshot.size) {
        val q = openReq?.lowercase() ?: return@LaunchedEffect
        val hit = if (wallLive) groups.firstOrNull { g -> g.title.lowercase().contains(q) || g.items.any { it.fileName.lowercase().contains(q) } }
        else snapshot.firstOrNull { g -> g.title.lowercase().contains(q) }
        hit ?: return@LaunchedEffect
        open(hit.key)
        com.therealityteam.videoplayer.platform.DeepLink.openRequest.value = null // consumed after the work
    }
    // The player's 看完了 card asked for a title's page: its card, opened once the wall is live.
    val openKey by com.therealityteam.videoplayer.platform.DeepLink.openKeyRequest.collectAsState()
    LaunchedEffect(openKey, wallLive) {
        val key = openKey ?: return@LaunchedEffect
        if (!wallLive) return@LaunchedEffect
        if (groups.any { it.key == key }) {
            // Over a title's page (the film was played from it), that page is where 返回 leads.
            selectedKey?.takeIf { it != key }?.let { detailTrail = detailTrail + it }
            selectedKey = key
        }
        com.therealityteam.videoplayer.platform.DeepLink.openKeyRequest.value = null
    }
    // Automation hook (`-e aura.scrape 1`): the same pass the 刮削 button starts — and, like the
    // button, only once there is something to start it on. Fired on the launch intent it arrives
    // while the library is still loading and a scan is still in flight, and `runTask` drops a
    // request made then on the floor; a finger cannot hit that window because the button stays
    // disabled until the library is live and no task is running.
    val scrapeRequest by com.therealityteam.videoplayer.platform.DeepLink.scrapeRequest.collectAsState()
    LaunchedEffect(scrapeRequest, wallLive, taskProgress == null) {
        if (scrapeRequest == null || !wallLive || taskProgress != null) return@LaunchedEffect
        val asked = scrapeRequest
        android.util.Log.i("AuraScrape", "scrape requested by automation${if (asked != "1") ": $asked" else ""}")
        com.therealityteam.videoplayer.platform.DeepLink.scrapeRequest.value = null
        // `-e aura.scrape 1` is the 刮削 button; `-e aura.scrape '<word|word>'` tries only the files whose path holds one.
        vm.rescrape(only = asked?.takeIf { it != "1" })
    }
    // Automation hook (`-e aura.add_local <folder>`): a folder on the headset as a 本地 source, scanned — what
    // 来源 › 添加 › 本地 does, for a device whose spatial panels cannot be tapped from a computer. An
    // smb://, ftp://, sftp:// or nfs:// address adds that share or file server, an http(s):// one a WebDAV server (a login in
    // the address);
    // `jellyfin:` or `emby:` before an http(s):// address that media server, signed in with the address's login;
    // `-e aura.remove_source <folder or address>` takes one added so away again.
    val addLocalRequest by com.therealityteam.videoplayer.platform.DeepLink.addLocalRequest.collectAsState()
    LaunchedEffect(addLocalRequest, wallLive, taskProgress == null) {
        val folder = addLocalRequest ?: return@LaunchedEffect
        if (!wallLive || taskProgress != null) return@LaunchedEffect
        com.therealityteam.videoplayer.platform.DeepLink.addLocalRequest.value = null
        val (type, path) = automationSource(folder)
        // A file server's or media server's address may carry its login (`sftp://user:pass@host/…`, a test
        // server's): kept apart, as 添加 would.
        val login = path.substringAfter("://", "").substringBefore('/').substringBefore('@', "").takeIf {
            it.isNotEmpty() && type in setOf(
                com.therealityteam.videoplayer.data.model.SourceType.SMB,
                com.therealityteam.videoplayer.data.model.SourceType.FTP,
                com.therealityteam.videoplayer.data.model.SourceType.SFTP,
                com.therealityteam.videoplayer.data.model.SourceType.WEBDAV,
                com.therealityteam.videoplayer.data.model.SourceType.JELLYFIN,
                com.therealityteam.videoplayer.data.model.SourceType.EMBY,
            )
        }
        // `dlna` is the search for DLNA servers on the network, as 添加 › DLNA makes it (no address); `dlna:<description url>` one server.
        val address = if (login != null) path.replaceFirst("$login@", "") else if (type == com.therealityteam.videoplayer.data.model.SourceType.DLNA) path.removePrefix("dlna").removePrefix(":") else path
        android.util.Log.i("AuraScan", "$type source requested by automation: $address${if (login != null) " (with a login)" else ""}")
        vm.addSource(
            com.therealityteam.videoplayer.data.model.MediaSourceConfig(
                // As remove_source names it: the address as given, a login left out of it.
                id = automationSourceId(type, if (login != null) address else path),
                type = type,
                name = if (type == com.therealityteam.videoplayer.data.model.SourceType.DLNA && address.isBlank()) "DLNA"
                else address.substringAfterLast('/').ifBlank { address.substringAfter("://").substringBefore('/') },
                uri = address,
                username = login?.substringBefore(':'),
                password = login?.substringAfter(':', ""),
            ),
        )
    }
    val forgetProgressRequest by com.therealityteam.videoplayer.platform.DeepLink.forgetProgressRequest.collectAsState()
    LaunchedEffect(forgetProgressRequest, wallLive) {
        val asked = forgetProgressRequest ?: return@LaunchedEffect
        if (!wallLive) return@LaunchedEffect
        com.therealityteam.videoplayer.platform.DeepLink.forgetProgressRequest.value = null
        // Everything a test's play leaves on a file: its position, its track choices, its show's track picks
        // (a track chosen by hand is the show's too), its bookmarks.
        val repo = koinScope.get<com.therealityteam.videoplayer.data.repository.MediaRepository>()
        // `since:<epoch ms>`: every file played since the test began — for a build whose files run-as
        // cannot read (-Paura.optimized), where the ids could not be looked up first.
        val ids = asked.removePrefix("since:").takeIf { it != asked }?.toLongOrNull()
            ?.let { from -> repo.all().filter { it.progress.updatedAt >= from }.map { it.id } }
            ?: asked.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (asked.startsWith("since:")) {
            android.util.Log.i("AuraScan", "played since then: ${ids.mapNotNull { repo.get(it)?.fileName }.joinToString()}")
        }
        val picks = koinScope.get<com.therealityteam.videoplayer.data.repository.TrackPickStore>()
        val marks = koinScope.get<com.therealityteam.videoplayer.data.repository.BookmarkStore>()
        repo.forgetProgress(ids)
        ids.forEach { id ->
            val item = repo.get(id) ?: return@forEach
            if (item.audioTrackId != null) repo.setAudioTrackChoice(id, null)
            if (item.subtitleTrackId != null) repo.setSubtitleTrackChoice(id, null)
            picks.forget(com.therealityteam.videoplayer.data.repository.SkipStore.keyOf(item))
            marks.forget(id)
        }
        android.util.Log.i("AuraScan", "progress, track picks and bookmarks forgotten by automation: ${ids.size} files")
    }
    val removeSourceRequest by com.therealityteam.videoplayer.platform.DeepLink.removeSourceRequest.collectAsState()
    LaunchedEffect(removeSourceRequest, wallLive) {
        val asked = removeSourceRequest ?: return@LaunchedEffect
        if (!wallLive) return@LaunchedEffect
        com.therealityteam.videoplayer.platform.DeepLink.removeSourceRequest.value = null
        val (type, path) = automationSource(asked)
        android.util.Log.i("AuraScan", "$type source removed by automation, and its files: $path")
        vm.removeSource(automationSourceId(type, path))
        // A test's source, taken away whole: 移除来源 keeps a source's entries (to be taken over when it is
        // added again), which would leave a test's files on the wall for good.
        koinScope.get<com.therealityteam.videoplayer.data.repository.MediaRepository>().removeBySource(automationSourceId(type, path))
    }
    val showAdult = settingsRepo.get().showAdult
    // A search looks through the whole library, whichever shelf is open: the field sits above all of
    // them. The title bar says what was searched for and how much came back, no shelf is marked
    // while it is on, and picking a shelf ends it.
    val searching = query.isNotBlank()
    // Filtered and ordered once per change, not on every recomposition — AuraApp recomposes with each
    // progress tick of a scrape and each keystroke in the search box, and this sorts the library.
    // 历史 is a timeline: it is always in the order things were watched, whatever order the other
    // shelves are in (A→Z over a history answers nothing) — so it has no order chip either.
    val historyTimeline = view == LibSection.HISTORY && !searching
    val wallFiltered = remember(wallGroups, view, sort, showAdult) {
        LibraryGrouping.filter(wallGroups, view, "", if (view == LibSection.HISTORY) LibSort.WATCHED else sort, showAdult)
    }
    // Remembered here, above the pages that return early (设置, 来源, a title's page): below them, each of those
    // pages dropped what the wall had worked out, and the way back sorted, filtered and counted the whole library
    // again on the main thread — part of the 33-78 ms frame back to the wall (simpleperf: filter, sort, counts ~9%).
    val recent = remember(wallGroups, wallLive, view, searching, showAdult, wallFilter.isActive, sort) {
        if (!wallLive || view != LibSection.ALL || searching || wallFilter.isActive || sort == LibSort.RECENT) emptyList()
        else LibrarySort.apply(if (showAdult) wallGroups else wallGroups.filterNot { it.category == LibCategory.ADULT }, LibSort.RECENT).take(RECENT_COUNT)
    }
    val counts = remember(wallGroups, showAdult) { shelfCounts(wallGroups, showAdult) }
    if (showSettings) {
        SystemBack(what = "设置 closes") { showSettings = false; settingsPage = null }
        SettingsScreen(
            onBack = { showSettings = false; settingsPage = null },
            initialPage = settingsPage,
            onOpenSources = { showSettings = false; settingsPage = null; showSources = true },
            onOpenDownloads = { showSettings = false; settingsPage = null; downloadsOpen = true; foldersOpen = false; query = "" },
        )
        return
    }
    if (showSources) {
        SystemBack(what = "来源 closes") { showSources = false }
        SourcesScreen(onBack = { showSources = false })
        return
    }
    // 修正匹配 re-files a card's files under their new identity — under a different card key, which
    // for a one-file card means the old key stops existing. The file being fixed, and the card it was
    // fixed from: while that card is still the one on screen, the title page follows the file.
    var follow by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val fixing: MediaItem? = fixMatchId?.let { id -> items.firstOrNull { it.id == id } }
    val fixingCard = fixing?.let { f -> groups.firstOrNull { g -> g.items.any { it.id == f.id } } }
    if (fixing != null && fixingCard != null) {
        SystemBack(what = "修正匹配 closes") { fixMatchId = null }
        FixMatchScreen(
            item = fixing,
            group = fixingCard,
            onApplyStarted = { follow = fixing.id to selectedKey },
            onApplied = { fixMatchId = null },
            onBack = { fixMatchId = null },
        )
        return
    }
    val followed = follow?.takeIf { it.second == selectedKey }
        ?.let { (id, _) -> groups.firstOrNull { g -> g.items.any { it.id == id } } }
    LaunchedEffect(follow, selectedKey, followed?.key) {
        val f = follow ?: return@LaunchedEffect
        when {
            f.second != selectedKey -> follow = null // went somewhere else meanwhile
            followed != null && followed.key != selectedKey -> {
                selectedKey = followed.key
                follow = null
            }
        }
    }
    val selected = followed ?: selectedKey?.let { key -> groups.firstOrNull { it.key == key } }
    if (selected != null) key(selected.key) {
        val restore = nav.detailKey == selected.key
        // Read as state: a source renamed or added while the page is up relabels its files' folders.
        val sourcesNow by vm.sources.collectAsState()
        SystemBack(what = "the title's page closes") {
            val previous = detailTrail.lastOrNull()
            detailTrail = detailTrail.dropLast(1)
            selectedKey = previous
        }
        DetailScreen(
            group = selected,
            onPlay = { item -> play(item, PlayQueue.of(selected, item)) },
            onPlayFromStart = { item -> play(item, PlayQueue.of(selected, item), startMs = 0L) },
            onToggleFavorite = {
                val fav = !selected.items.any { it.favorite }
                vm.setGroupFavorite(selected.items.map { it.id }, fav)
            },
            onFixMatch = { fixMatchId = selected.representative.id },
            onBack = {
                val previous = detailTrail.lastOrNull()
                detailTrail = detailTrail.dropLast(1)
                selectedKey = previous
            },
            onSetWatched = { files, watched -> vm.setWatched(files.map { it.id }, watched) },
            initialScrollIndex = if (restore) nav.detailIndex else 0,
            initialScrollOffset = if (restore) nav.detailOffset else 0,
            onScroll = { i, o -> nav.detailKey = selected.key; nav.detailIndex = i; nav.detailOffset = o },
            peopleIndex = peopleIndex,
            library = visibleGroups,
            onOpenTitle = { card ->
                detailTrail = detailTrail + selected.key
                selectedKey = card.key
            },
            loadEpisodes = { vm.episodes(it) },
            folderLabel = sourcesNow.let { srcs ->
                { item: MediaItem ->
                    srcs.firstOrNull { it.id == item.sourceId && com.therealityteam.videoplayer.ui.library.FolderTree.hasFolders(it.type) }?.let { src ->
                        (listOf(src.name) + com.therealityteam.videoplayer.ui.library.FolderTree.relative(item.path, src.uri).dropLast(1)).joinToString(" › ")
                    }
                }
            },
            // 文件路径's folders lead into 文件夹, at that folder — on a share; a media server's files have none.
            onOpenFolder = sourcesNow.let { srcs ->
                { item: MediaItem ->
                    srcs.firstOrNull { it.id == item.sourceId && com.therealityteam.videoplayer.ui.library.FolderTree.hasFolders(it.type) }?.let { src ->
                        folderPath = com.therealityteam.videoplayer.ui.library.FolderTree.Path(
                            src.id, com.therealityteam.videoplayer.ui.library.FolderTree.relative(item.path, src.uri).dropLast(1),
                        )
                        foldersOpen = true
                        downloadsOpen = false
                        query = ""
                        detailTrail = emptyList()
                        selectedKey = null
                    }
                    Unit
                }
            },
        )
        return
    }

    // Automation hook (`-e aura.sort …`): an order name is applied here; "open" is handed to the chip.
    val sortRequest by com.therealityteam.videoplayer.platform.DeepLink.sortRequest.collectAsState()
    LaunchedEffect(sortRequest) {
        val r = sortRequest ?: return@LaunchedEffect
        val wanted = LibSort.entries.firstOrNull { it.name.equals(r, ignoreCase = true) } ?: return@LaunchedEffect
        android.util.Log.i("AuraSort", "sort request $r -> ${wanted.name}")
        sort = wanted
        com.therealityteam.videoplayer.platform.DeepLink.sortRequest.value = null
    }
    // A search is worked out off the main thread: each keystroke looks through every card's keys and
    // every file's name, and which cards it found only through their people — 15-50 ms on the headset,
    // where the frame is 11, so the keyboard's echo stuttered at every letter. The last answer stays
    // up until the next is ready.
    val searchAnswer by produceState<SearchAnswer?>(null, wallGroups, query, sort, showAdult, wallLive) {
        val asked = query
        if (asked.isBlank()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            val started = System.nanoTime()
            val found = LibraryGrouping.filter(wallGroups, LibSection.ALL, asked, sort, showAdult)
            val q = com.therealityteam.videoplayer.ui.library.SearchKeys.Query(asked)
            val byPeople = if (!wallLive) emptySet()
            else found.filter { LibraryGrouping.foundOnlyByPeople(it, q) }.mapTo(HashSet()) { it.key }
            android.util.Log.i(
                "AuraPerf",
                "search \"$asked\": ${found.size} of ${wallGroups.size} cards (${byPeople.size} by their people) in ${(System.nanoTime() - started) / 1_000_000} ms",
            )
            SearchAnswer(found, byPeople)
        }
    }
    val filtered = if (searching) searchAnswer?.found ?: wallFiltered else wallFiltered
    // …and narrowed by 筛选, whose panel counts against [filtered]: what the shelf or search holds.
    val shown = remember(filtered, wallFilter) { wallFilter.apply(filtered) }
    // 继续观看 sits above the 全部 wall when nothing is being searched for — live cards only, since a
    // tap plays a file and the snapshot's cards have none — and is drawn from the same pool as the
    // wall: a title hidden with the adult shelf stays hidden here, the largest card on the page too.
    // …less the cards taken out of it from their long-press menus, until each is watched again.
    val continueHidden = remember { GlobalContext.get().get<com.therealityteam.videoplayer.data.repository.ContinueHiddenStore>() }
    val hiddenFromContinue by continueHidden.all.collectAsState()
    val resume = remember(wallGroups, wallLive, view, searching, showAdult, wallFilter.isActive, hiddenFromContinue) {
        if (!wallLive || view != LibSection.ALL || searching || wallFilter.isActive) emptyList()
        else ContinueWatching.of(
            if (showAdult) wallGroups else wallGroups.filterNot { it.category == LibCategory.ADULT },
            hidden = hiddenFromContinue,
        )
    }
    val snackbar = LocalSnackbarHostState.current
    // 自动更新媒体库's catch said once it is matched, so new posters on the wall are not a mystery.
    val newFiles by vm.newFiles.collectAsState()
    val newFilesText = newFiles?.let { pluralStringResource(R.plurals.lib_new_files, it, it) }
    LaunchedEffect(newFilesText) {
        val text = newFilesText ?: return@LaunchedEffect
        vm.newFilesShown()
        android.util.Log.i("AuraScan", "said: $text")
        snackbar.show(message = text)
    }

    val scope = rememberCoroutineScope()
    // A card's long-press menu: the detail page's actions, one press away from the wall.
    val cardMenu = remember(vm) {
        CardMenuActions(
            onPlay = { g, item -> play(item, PlayQueue.of(g, item)) },
            onDetails = { g -> selectedKey = g.key },
            onToggleFavorite = { g -> vm.setGroupFavorite(g.items.map { it.id }, !g.items.any { it.favorite }) },
            onSetWatched = { files, watched -> vm.setWatched(files.map { it.id }, watched) },
            // Taken out of 继续观看 with nothing to show for it — and no way back short of watching the
            // film again — a slip of the trigger lost the card. It says so now, with 撤销.
            onHideFromContinue = { entry ->
                val key = entry.group.key
                val before = continueHidden.all.value[key]
                continueHidden.hide(key, entry.at)
                scope.launch {
                    val result = snackbar.show(
                        message = { Text(stringResource(R.string.cw_removed, entry.group.title)) },
                        trailingActions = {
                            Button(onClick = { performAction() }) { Text(stringResource(R.string.cw_undo)) }
                        },
                        duration = SnackbarDuration.custom(UNDO_MS),
                    )
                    android.util.Log.i("AuraUndo", "removed ${entry.group.title} from 继续观看: $result")
                    if (result == SnackbarResult.ActionPerformed) continueHidden.restore(key, before)
                }
            },
        )
    }
    val ctxForCopies = androidx.compose.ui.platform.LocalContext.current
    // 看完后删除下载: a copy whose file was watched to the end goes — not while it is the one playing
    // (its end credits still rolling), and said, so a copy does not vanish without a word.
    val deleteWatched = settingsRepo.settings.collectAsState().value.deleteWatchedCopies
    val playingNow by playbackVm.current.collectAsState()
    LaunchedEffect(deleteWatched, items, playingNow?.id) {
        val dl = downloadActions?.downloader ?: return@LaunchedEffect
        if (!deleteWatched) return@LaunchedEffect
        val watched = items.filter { it.progress.watched }.mapTo(HashSet()) { it.id }
        val gone = dl.store.all.value.filter { it.isDone && it.itemId in watched && it.itemId != playingNow?.id }
        if (gone.isEmpty()) return@LaunchedEffect
        gone.forEach { dl.remove(it.itemId) }
        android.util.Log.i("AuraDownload", "removed ${gone.size} watched copies")
        snackbar.show(message = ctxForCopies.resources.getQuantityString(R.plurals.dl_auto_removed, gone.size, gone.size))
    }
    // 已下载到本机, said once a copy is whole — at once while the window is open, else the next time it
    // opens (the headset taken off, the download finishing in the dark) — with 查看 for the page.
    val finishedCopies = downloadActions?.downloader?.finished?.collectAsState()?.value.orEmpty()
    LaunchedEffect(finishedCopies) {
        val done = finishedCopies.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        // A moment for a queue's next one to finish too: three episodes are one message.
        kotlinx.coroutines.delay(1_500)
        downloadActions?.downloader?.finishedSaid(done)
        val text = if (done.size == 1) ctxForCopies.getString(R.string.dl_finished_one, done.first().fileName)
        else ctxForCopies.resources.getQuantityString(R.plurals.dl_finished_n, done.size, done.size)
        android.util.Log.i("AuraDownload", "said: $text")
        val result = snackbar.show(
            message = { Text(text, maxLines = 2) },
            trailingActions = {
                Button(onClick = { performAction() }) { Text(stringResource(R.string.set_downloads_open)) }
            },
        )
        if (result == SnackbarResult.ActionPerformed) {
            downloadsOpen = true; foldersOpen = false; query = ""; selectedKey = null
        }
    }
    LaunchedEffect(cwRemoveRequest, resume) {
        val words = cwRemoveRequest ?: return@LaunchedEffect
        val entry = resume.firstOrNull { it.group.title.contains(words, ignoreCase = true) } ?: return@LaunchedEffect
        cwRemoveRequest = null
        cardMenu.onHideFromContinue(entry)
    }
    // 最近添加 on 全部, under 继续观看 — not while the wall itself is in that order, nor while it is
    // searched or filtered (then the wall is the answer, and a lane above it only pushes it down).
    // 因为你看过《…》 on 全部, under 最近添加: titles like one watched lately, not begun — worked out off
    // the main thread (a similarity pass over the library), and only where the lanes above show.
    val because by produceState<BecauseYouWatched.Row?>(null, wallGroups, wallLive, view, searching, showAdult, wallFilter.isActive) {
        value = if (!wallLive || view != LibSection.ALL || searching || wallFilter.isActive) null
        else withContext(Dispatchers.Default) {
            BecauseYouWatched.of(if (showAdult) wallGroups else wallGroups.filterNot { it.category == LibCategory.ADULT })
        }
    }
    // 系列 on 全部, under 因为你看过: the library's series as stacks — worked out off the main thread,
    // and only where the lanes above show.
    val seriesLane by produceState(emptyList<SeriesTitles.Series>(), wallGroups, wallLive, view, searching, showAdult, wallFilter.isActive) {
        value = if (!wallLive || view != LibSection.ALL || searching || wallFilter.isActive) emptyList()
        else withContext(Dispatchers.Default) {
            SeriesTitles.all(if (showAdult) wallGroups else wallGroups.filterNot { it.category == LibCategory.ADULT })
        }
    }
    var seriesShown by remember { mutableStateOf<SeriesTitles.Series?>(null) }
    LaunchedEffect(seriesRequest, seriesLane) {
        val n = seriesRequest ?: return@LaunchedEffect
        seriesLane.getOrNull(n - 1)?.let { seriesShown = it; seriesRequest = null }
    }
    // 历史 by day — today, yesterday, this week… — while it is in the order it was watched (its own
    // 最近添加 reads as 最近观看) and nothing narrows it; another order or a filter shows the flat wall.
    val historyByDay = historyTimeline && !wallFilter.isActive
    val bucketLabels = HistoryBuckets.Bucket.entries.associateWith { stringResource(it.label) }
    val historySections = remember(shown, historyByDay, bucketLabels) {
        if (!historyByDay) null else HistoryBuckets.of(shown).map { (bucket, cards) -> bucketLabels.getValue(bucket) to cards }
    }
    // A search's 作品 are the cards whose names hold the query; a card it found only through somebody in
    // its cast or crew — 火星救援 for 「高达」, for its writer 德鲁·高达 — follows them, under a heading of
    // its own, rather than sorting in among them.
    val titlesLabel = stringResource(R.string.lib_search_titles)
    val byPeopleLabel = stringResource(R.string.lib_search_by_people)
    val searchSections = remember(shown, searchAnswer, searching, wallLive, titlesLabel, byPeopleLabel) {
        val answer = searchAnswer
        if (!searching || !wallLive || answer == null) return@remember null
        val (byPeople, own) = shown.partition { it.key in answer.byPeople }
        if (own.isEmpty() || byPeople.isEmpty()) null else listOf(titlesLabel to own, byPeopleLabel to byPeople)
    }
    // The day headings arrive after the first paint (the library is grouped, then bucketed), and the
    // grid anchors on the card it was showing: the first heading landed above the viewport, out of
    // sight. When they appear while the wall is at its top, go to the top with them.
    // 观影统计, under 历史's title: this year's films and episodes seen through, and about how long.
    val historyStats = remember(shown, historyByDay) { if (historyByDay) WatchStats.ofYear(shown) else null }
    LaunchedEffect(historySections != null) {
        if (historySections != null && gridState.firstVisibleItemIndex <= 1 && gridState.firstVisibleItemScrollOffset < 400) {
            gridState.scrollToItem(0)
        }
    }
    // The grid anchors on the card it was showing first, so a row prepended to it after the wall
    // is up (the snapshot has none, the live library does) would sit above the viewport: when it
    // appears while the wall is at the top, go to the top with it. A wall scrolled away stays put.
    // 人物 over a search: the people whose names hold it, from the same credits the detail page's
    // faces come from — matched off the main thread against names normalised once per index.
    val peopleKeys by produceState(emptyList<Pair<String, String>>(), peopleIndex) {
        value = withContext(Dispatchers.Default) { PeopleSearch.keys(peopleIndex) }
    }
    val peopleHits by produceState(emptyList<PeopleSearch.Hit>(), query, peopleKeys, wallLive) {
        value = if (!wallLive || query.isBlank()) emptyList()
        else withContext(Dispatchers.Default) { PeopleSearch.match(peopleIndex, peopleKeys, query) }
    }
    val searchPeople = if (searching && wallLive) peopleHits else emptyList()
    var personShown by remember { mutableStateOf<PeopleSearch.Hit?>(null) }
    // Automation (`aura.screen person:<n>`): the n-th face of 人物 opened, as a tap would.
    LaunchedEffect(personRequest, searchPeople) {
        val n = personRequest ?: return@LaunchedEffect
        searchPeople.getOrNull(n - 1)?.let { personShown = it; personRequest = null }
    }
    // 随便看看 (see the title bar): the last titles it offered wait while there are others.
    var surpriseRecent by remember { mutableStateOf(listOf<String>()) }
    val canSurprise = remember(shown, wallLive, view) { wallLive && view != LibSection.HISTORY && shown.any(SurprisePick::fresh) }
    fun surprise() {
        val pick = SurprisePick.of(shown, surpriseRecent) ?: return
        android.util.Log.i("AuraSurprise", "offered ${pick.title}")
        surpriseRecent = (surpriseRecent + pick.key).takeLast(SURPRISE_MEMORY)
        selectedKey = pick.key
    }
    // Automation (`aura.screen surprise`): the chip's press, once the wall is live.
    LaunchedEffect(surpriseRequest, canSurprise) {
        if (surpriseRequest && canSurprise) { surpriseRequest = false; surprise() }
    }
    val hasHeader = searchPeople.isNotEmpty() || resume.isNotEmpty() || recent.isNotEmpty() || because != null
    // 字母索引 beside a long wall in title order — not a search's answer, nor 历史's own order.
    val titleIndex = remember(shown, sort, searching, view) {
        if (sort != LibSort.TITLE || searching || view == LibSection.HISTORY || shown.size < TITLE_INDEX_MIN) null
        else TitleIndex.of(shown)
    }
    // Automation (`aura.screen jump:<letter>`): the rail's press, once the wall and its index have
    // settled — a cold start's live wall re-sorts a second later, when its CJK titles' pinyin keys
    // arrive, and a jump made before that lands wherever the re-sort carries the cards in view.
    LaunchedEffect(titleJump, titleIndex, wallLive) {
        val mark = titleJump ?: return@LaunchedEffect
        val index = titleIndex?.takeIf { wallLive } ?: return@LaunchedEffect
        delay(2_500) // restarted by every new index, so this waits for one that holds
        index.firstOrNull { it.first == mark }?.let { gridState.scrollToItem(it.second + if (hasHeader) 1 else 0) }
        titleJump = null
    }
    LaunchedEffect(wallScroll, shown, wallLive) {
        val by = wallScroll ?: return@LaunchedEffect
        if (!wallLive) return@LaunchedEffect
        delay(2_500) // as for jump: the order settles first
        gridState.scrollBy(by)
        wallScroll = null
    }
    LaunchedEffect(wallGlide, shown, wallLive) {
        val (by, ms, wait, passes) = wallGlide ?: return@LaunchedEffect
        if (!wallLive) return@LaunchedEffect
        delay(wait.toLong())
        repeat(passes) { pass ->
            val gaps = ArrayList<Long>()
            val timing = launch {
                var last = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    gaps += now - last
                    last = now
                }
            }
            val from = gridState.firstVisibleItemIndex
            val px = if (pass % 2 == 0) by.toFloat() else -by.toFloat()
            gridState.animateScrollBy(px, androidx.compose.animation.core.tween(ms, easing = androidx.compose.animation.core.LinearEasing))
            timing.cancel()
            JankReport.log("wall glide ${px.toInt()} px in $ms ms (pass ${pass + 1}), cards $from→${gridState.firstVisibleItemIndex}", gaps)
            delay(1_000)
        }
        wallGlide = null // last: clearing it ends this effect
    }
    LaunchedEffect(hasHeader) {
        if (hasHeader && gridState.firstVisibleItemIndex <= 1 && gridState.firstVisibleItemScrollOffset < 400) {
            gridState.scrollToItem(0)
        }
    }
    // 人物 is worked out after the cards it sits over, and the grid, anchored on the card it was
    // showing, kept the faces above the viewport (only the foot of the row, 作品, peeking in at the
    // top). When they arrive, go to the top with them — once the grid has laid them out, or the
    // scroll is undone by that same anchoring. A search has only just been typed: nobody has
    // scrolled it yet.
    LaunchedEffect(searchPeople.isNotEmpty()) {
        if (searchPeople.isEmpty()) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        if (gridState.firstVisibleItemIndex <= 1) gridState.scrollToItem(0)
    }
    // …and so do a search's 作品 / 相关人物的作品 headings, which arrive with the live library: the grid
    // stayed on the first card and the faces and the 作品 heading above it were out of sight.
    LaunchedEffect(searchSections != null) {
        if (searchSections == null) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        if (gridState.firstVisibleItemIndex <= 2) gridState.scrollToItem(0)
    }
    // Another shelf, search or order opens at its top. One grid state serves them all, and it used to
    // keep the last one's place — anchored on whichever card it had been showing — so a shelf opened
    // halfway down and a new order started somewhere in the middle. Coming back from a detail page
    // changes none of the three, and the wall is where it was left.
    LaunchedEffect(view, query, sort, wallFilter) {
        val shown = listOf<Any?>(view, query, sort, wallFilter)
        if (shown != wallScrolledFor) {
            wallScrolledFor = shown
            gridState.scrollToItem(0)
        }
    }

    val nothingToMatch = stringResource(R.string.lib_scrape_nothing)
    // One library task at a time; nothing starts before the library is read.
    val canStartTask = wallLive && taskProgress == null

    // On the wall the shelves are the window's tabs: 返回 leaves a search, climbs out of a folder, and at the top
    // goes on to the system (the library closes, as any app's first page does).
    SystemBack(enabled = query.isNotEmpty() || (foldersOpen && folderPath != null), what = "the wall steps back") {
        if (query.isNotEmpty()) query = "" else folderPath = folderPath?.parent
    }
    Row(modifier = Modifier.fillMaxSize()) {
        Sidebar(
            current = if (searching || foldersOpen || downloadsOpen) null else view,
            query = query,
            // A search is the wall's (or 文件夹's), so typing leaves 已下载 for it.
            onQuery = { query = it; if (it.isNotBlank()) downloadsOpen = false },
            onSelect = { view = it; query = ""; foldersOpen = false; downloadsOpen = false },
            foldersOpen = foldersOpen,
            onFolders = { foldersOpen = true; downloadsOpen = false; query = "" },
            downloadsOpen = downloadsOpen,
            onDownloads = { downloadsOpen = true; foldersOpen = false; query = "" },
            counts = counts,
            showAdult = showAdult,
            moreOpen = moreOpen,
            onMoreOpen = { moreOpen = it },
            taskProgress = taskProgress,
            canStartTask = canStartTask,
            onSources = { showSources = true },
            onScrape = {
                vm.rescrape { looked ->
                    if (looked == 0) scope.launch { snackbar.show(message = nothingToMatch) }
                }
            },
            onSettings = { showSettings = true },
        )
        if (downloadsOpen) {
            com.therealityteam.videoplayer.ui.library.DownloadsPage(
                downloader = downloadActions!!.downloader,
                items = items,
                groups = groups,
                onPlay = { item, queue -> play(item, queue) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        } else if (foldersOpen) {
            val sourceList by vm.sources.collectAsState()
            FolderBrowser(
                items = items,
                sources = sourceList,
                groups = groups,
                showAdult = showAdult,
                path = folderPath,
                onPath = { folderPath = it },
                onPlay = { item, queue -> play(item, queue) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                playIndex = folderPlay,
                onPlayIndexHandled = { folderPlay = null },
                query = query,
            )
        } else
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // The page's title bar, as PICO's template sets one beside a side navigation: the
            // shelf's name (or the search) in the middle, the running task at its start, the page's
            // own controls — 随便看看, the filter, the order — at its end.
            TitleBar(
                title = {
                    // The whole room between the actions, so the title is centred in it (Center alone set it at its start).
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (searching) stringResource(R.string.lib_search_title, query.trim()) else stringResource(view.label),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        when {
                            !wallLive && wallGroups.isNotEmpty() -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(progressSize = CircularProgressDefaults.Small)
                                TitleBarNote(stringResource(R.string.library_loading))
                            }
                            // Away from the network: said where the eye goes first, with what still plays —
                            // the copies on the headset — so a card's 读取不到 is not the first news of it.
                            offline && wallLive && !searching -> TitleBarNote(
                                if (onHeadsetCount > 0) pluralStringResource(R.plurals.lib_offline_copies, onHeadsetCount, onHeadsetCount)
                                else stringResource(R.string.lib_offline),
                            )
                            wallFilter.isActive && wallLive -> TitleBarNote(
                                filterSummary(wallFilter) + stringResource(R.string.meta_separator) +
                                    pluralStringResource(R.plurals.lib_filter_count, shown.size, shown.size),
                            )
                            // Nothing found says so once, below — not "0 个结果" up here as well. Not before the
                            // first answer is in either: until then the wall is still the whole library's.
                            searching && wallLive && searchAnswer != null && (filtered.isNotEmpty() || searchPeople.isNotEmpty()) -> TitleBarNote(
                                listOfNotNull(
                                    pluralStringResource(R.plurals.lib_search_results, filtered.size, filtered.size)
                                        .takeIf { filtered.isNotEmpty() },
                                    pluralStringResource(R.plurals.lib_search_people, searchPeople.size, searchPeople.size)
                                        .takeIf { searchPeople.isNotEmpty() },
                                ).joinToString(stringResource(R.string.meta_separator)),
                            )
                            searching -> Unit
                            wallLive && historyStats != null && !historyStats.isEmpty -> TitleBarNote(historyStatsText(historyStats))
                        }
                    }
                },
                // Centred in the room the actions leave, not in the bar: in Russian and Polish 随便看看 · 筛选 · 排序
                // reached past the middle and the shelf's name was drawn under them ("Всеи меня").
                titleAlignment = TitleAlignment.Center,
                // The running task at the bar's start, where nothing else sits. At its end, before
                // 随便看看 · 筛选 · 标题, the four reached past the middle and the shelf's name was
                // drawn over its count: "刮削 166/774全部".
                leadingActions = {
                    taskProgress?.let { LibraryTaskProgress(it, Modifier.width(200.dp)) }
                },
                trailingActions = {
                    // 随便看看: a title from this wall nobody has begun, its page opened (SurprisePick) —
                    // only where there is one to offer, so never on 历史.
                    if (canSurprise) {
                        Chip(
                            label = { Text(stringResource(R.string.lib_surprise)) },
                            onClick = { surprise() },
                            chipSize = ChipsDefaults.Regular,
                            leadingIcon = { Icon(painterResource(R.drawable.ic_surprise), contentDescription = null, modifier = Modifier.size(20.dp)) },
                        )
                    }
                    FilterChip(
                        filter = wallFilter,
                        pool = filtered,
                        onChange = { wallFilter = it },
                        openRequested = filterMenuRequested,
                        onOpened = { filterMenuRequested = false },
                    )
                    if (!historyTimeline) {
                        SortChip(
                            sort = sort,
                            onSelect = { sort = it },
                            openRequested = sortRequest.equals("open", ignoreCase = true),
                            onOpened = { com.therealityteam.videoplayer.platform.DeepLink.sortRequest.value = null },
                        )
                    }
                },
            )
            // Menus only on live cards: the snapshot's stand-ins have no files to act on.
            // …and the order, for the cards' second line — not 历史's, whose day headings say it already.
            CompositionLocalProvider(
                LocalCardMenu provides cardMenu.takeIf { wallLive },
                LocalWallSort provides sort.takeIf { historySections == null && view != LibSection.HISTORY },
            ) {
                PosterWall(
                    groups = shown,
                    sections = historySections ?: searchSections,
                    index = titleIndex,
                    onSelect = { open(it.key) },
                    modifier = Modifier.weight(1f),
                    state = gridState,
                    header = if (!hasHeader) null else if (searchPeople.isNotEmpty()) {
                        { SearchPeopleLane(searchPeople, onOpen = { personShown = it }, titlesBelow = shown.isNotEmpty() && searchSections == null) }
                    } else {
                        {
                            ContinueHeader(
                                entries = resume,
                                onPlay = { entry -> play(entry.item, PlayQueue.of(entry.group, entry.item)) },
                                onDetails = { entry -> open(entry.group.key) },
                                recent = recent,
                                because = because,
                                series = seriesLane,
                                onOpen = { card -> open(card.key) },
                                onOpenSeries = { seriesShown = it },
                                loadEpisodes = { vm.episodes(it) },
                            )
                        }
                    },
                    empty = {
                        WallEmptyState(
                            loading = !wallLive,
                            filtered = wallFilter.isActive && filtered.isNotEmpty(),
                            onClearFilter = { wallFilter = WallFilter() },
                            search = query.trim().takeIf { searching },
                            view = view,
                            libraryEmpty = items.isEmpty(),
                            onClearSearch = { query = "" },
                            onShowAll = { view = LibSection.ALL },
                            onAddSource = { showSources = true },
                        )
                    },
                )
            }
        }
    }

    seriesShown?.let { series ->
        SeriesSheet(series, onOpen = { seriesShown = null; selectedKey = it.key }, onDismiss = { seriesShown = null })
    }

    personShown?.let { hit ->
        PersonWorksSheet(
            person = hit.person,
            works = hit.works,
            onOpen = { personShown = null; selectedKey = it.key },
            onDismiss = { personShown = null },
            allOfThem = true,
        )
    }

}

/** `-e aura.screen filter:unwatched,1990,科幻,panorama` → the 筛选 those words name (see the screen hook). */
private fun parseFilter(spec: String): WallFilter = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    .fold(WallFilter()) { f, part ->
        val watch = WallFilter.Watch.entries.firstOrNull { it.name.equals(part, ignoreCase = true) }
        val picture = WallFilter.Picture.entries.firstOrNull { it != WallFilter.Picture.ANY && it.name.equals(part, ignoreCase = true) }
        when {
            watch != null -> f.copy(watch = watch)
            picture != null -> f.copy(picture = picture)
            part.length == 4 && part.all(Char::isDigit) -> f.copy(decade = WallFilter.decadeOf(part.toInt()))
            else -> f.copy(genre = part)
        }
    }

/** 历史's day headings. */
private val HistoryBuckets.Bucket.label: Int
    get() = when (this) {
        HistoryBuckets.Bucket.TODAY -> R.string.lib_history_today
        HistoryBuckets.Bucket.YESTERDAY -> R.string.lib_history_yesterday
        HistoryBuckets.Bucket.THIS_WEEK -> R.string.lib_history_this_week
        HistoryBuckets.Bucket.THIS_MONTH -> R.string.lib_history_this_month
        HistoryBuckets.Bucket.EARLIER -> R.string.lib_history_earlier
    }

/** How long 撤销 stays on offer after a card leaves 继续观看. */
private const val UNDO_MS = 6_000L

/** How many of its last offers 随便看看 keeps from offering again. */
private const val SURPRISE_MEMORY = 20

/** How many of the newest arrivals 最近添加 shows. */
private const val RECENT_COUNT = 12

/** A wall this long gets the letter rail; a shorter one is a short scroll anyway. */
private const val TITLE_INDEX_MIN = 60

/** The small line under the title bar's title: what the wall is doing, or how much a search found. */
@Composable
private fun TitleBarNote(text: String) {
    Text(
        text = text,
        style = PicoTheme.typography.bodySmall,
        color = PicoTheme.colorScheme.labelSecondary,
        maxLines = 1,
    )
}

/** How many cards each shelf holds, by the same rule the wall filters with. */
private fun shelfCounts(groups: List<MediaGroup>, showAdult: Boolean): Map<LibSection, Int> =
    LibSection.entries.associateWith { LibraryGrouping.filter(groups, it, "", LibSort.RECENT, showAdult).size }

/**
 * What the wall says when it has no cards to show — and, where there is one, the way out of it:
 * a library still loading, a search that found nothing, a library with no source yet, an empty
 * shelf. Adding a source is the one primary action; the others are light.
 */
@Composable
private fun WallEmptyState(
    loading: Boolean,
    /** The shelf has cards and 筛选 took them all away. */
    filtered: Boolean,
    onClearFilter: () -> Unit,
    search: String?,
    view: LibSection,
    libraryEmpty: Boolean,
    onClearSearch: () -> Unit,
    onShowAll: () -> Unit,
    onAddSource: () -> Unit,
) {
    val showAll: @Composable () -> Unit = {
        Button(onClick = onShowAll, colors = secondaryButtonColors()) { Text(stringResource(R.string.lib_show_all)) }
    }
    when {
        loading -> EmptyState(title = stringResource(R.string.library_loading), loading = true)
        filtered -> EmptyState(
            title = stringResource(R.string.lib_empty_filter),
            iconRes = R.drawable.ic_filter,
            message = stringResource(R.string.lib_empty_filter_hint),
            action = { Button(onClick = onClearFilter, colors = secondaryButtonColors()) { Text(stringResource(R.string.lib_filter_clear)) } },
        )
        search != null -> EmptyState(
            title = stringResource(R.string.lib_empty_search, search),
            iconRes = R.drawable.ic_search,
            message = stringResource(R.string.lib_empty_search_hint),
            action = {
                Button(onClick = onClearSearch, colors = secondaryButtonColors()) { Text(stringResource(R.string.lib_search_clear)) }
            },
        )
        libraryEmpty -> EmptyState(
            title = stringResource(R.string.lib_empty_library_title),
            iconRes = R.drawable.ic_source,
            message = stringResource(R.string.lib_empty_library),
            action = {
                Button(
                    onClick = onAddSource,
                    leadingIcon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(20.dp)) },
                ) { Text(stringResource(R.string.lib_add_source)) }
            },
        )
        view == LibSection.FAVORITES -> EmptyState(
            title = stringResource(R.string.lib_empty_favorites_title),
            iconRes = R.drawable.ic_fav,
            message = stringResource(R.string.lib_empty_favorites),
            action = showAll,
        )
        view == LibSection.HISTORY -> EmptyState(
            title = stringResource(R.string.lib_empty_history_title),
            iconRes = R.drawable.ic_history,
            message = stringResource(R.string.lib_empty_history),
            action = showAll,
        )
        else -> EmptyState(
            title = stringResource(R.string.library_empty_in, stringResource(view.label)),
            iconRes = view.icon,
            message = stringResource(R.string.lib_empty_shelf_hint),
            action = if (view == LibSection.ALL) null else showAll,
        )
    }
}

@Composable
private fun Sidebar(
    current: LibSection?,
    query: String,
    onQuery: (String) -> Unit,
    onSelect: (LibSection) -> Unit,
    counts: Map<LibSection, Int>,
    showAdult: Boolean,
    moreOpen: Boolean,
    onMoreOpen: (Boolean) -> Unit,
    taskProgress: TaskProgress?,
    canStartTask: Boolean,
    foldersOpen: Boolean,
    onFolders: () -> Unit,
    downloadsOpen: Boolean,
    onDownloads: () -> Unit,
    onSources: () -> Unit,
    onScrape: () -> Unit,
    onSettings: () -> Unit,
) {
    // PICO's SideNavigation, at the width its spec gives a 1280dp window, with the search box in
    // its header as the spec's anatomy has it; it scrolls its own sections.
    SideNavigation(
        modifier = Modifier.fillMaxHeight().width(316.dp),
        // The header sits at the window's top edge; the search box keeps the window's inset from it.
        header = {
            SearchField(
                value = query,
                onValueChange = onQuery,
                onSearch = {},
                placeholder = { Text(stringResource(R.string.search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
        },
    ) {
        Column {
            SideNavigationSection(title = { Text(stringResource(R.string.nav_group_library)) }) {
                CATEGORY_SECTIONS.forEach { s -> ShelfRow(s, current, counts) { onSelect(s) } }
                // 更多 is a door, not a shelf: one row that stays where it is, its chevron saying
                // whether the uncommon shelves are showing below it. Folded, the six or seven shelves a
                // given library may well have nothing on cost one row; opened, they are all there, in
                // the same order for everybody. The list does not follow the content on purpose — a
                // sidebar that rearranges itself between two people's libraries is one neither can be
                // told how to use.
                val secondary = SECONDARY_SECTIONS.filter { it != LibSection.ADULT || showAdult }
                NavRow(
                    label = stringResource(if (moreOpen) R.string.nav_more_collapse else R.string.nav_more),
                    iconRes = R.drawable.ic_cat_more,
                    // Folded away with one of its shelves open, the door stands in for that shelf.
                    selected = !moreOpen && current in secondary,
                    trailing = {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_down),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp).rotate(if (moreOpen) 180f else 0f),
                        )
                    },
                    onClick = { onMoreOpen(!moreOpen) },
                )
                if (moreOpen) {
                    secondary.forEach { s -> ShelfRow(s, current, counts) { onSelect(s) } }
                }
            }
            SideNavigationSection(title = { Text(stringResource(R.string.nav_group_mine)) }) {
                LIST_SECTIONS.forEach { s -> ShelfRow(s, current, counts) { onSelect(s) } }
                NavRow(stringResource(R.string.nav_folders), R.drawable.ic_folder, selected = foldersOpen, onClick = onFolders)
                DownloadsNavRow(selected = downloadsOpen, onClick = onDownloads)
            }
            SideNavigationSection(title = { Text(stringResource(R.string.nav_group_manage)) }) {
                NavRow(stringResource(R.string.nav_sources), R.drawable.ic_source, selected = false, onClick = onSources)
                // The two library passes say when they are running, and wait their turn: one task
                // runs at a time, and a tap while another ran used to be dropped without a word.
                NavRow(
                    label = stringResource(R.string.nav_scrape),
                    iconRes = R.drawable.ic_scrape,
                    selected = false,
                    enabled = canStartTask,
                    supporting = taskProgress?.let { taskProgressText(it) },
                    onClick = onScrape,
                )
                NavRow(stringResource(R.string.nav_settings), R.drawable.ic_settings, selected = false, onClick = onSettings)
            }
        }
    }
}

/** A shelf of the wall, with how many cards are on it. */
@Composable
private fun ShelfRow(section: LibSection, current: LibSection?, counts: Map<LibSection, Int>, onClick: () -> Unit) {
    NavRow(
        label = stringResource(section.label),
        iconRes = section.icon,
        selected = section == current,
        trailing = counts[section]?.let { n ->
            {
                Text(
                    text = n.toString(),
                    style = PicoTheme.typography.bodySmall,
                    color = PicoTheme.colorScheme.labelTertiary,
                )
            }
        },
        onClick = onClick,
    )
}

/**
 * 我的 › 已下载, with how many copies are here at its end as the shelves carry their counts — and, while
 * a copy is on its way, how far it got and how many wait behind it beneath, so a download going on in
 * the background is never out of sight.
 */
@Composable
private fun DownloadsNavRow(selected: Boolean, onClick: () -> Unit) {
    val dl = com.therealityteam.videoplayer.ui.library.LocalDownloads.current
    val list = dl?.downloader?.store?.all?.collectAsState()?.value.orEmpty()
    val active = dl?.downloader?.active?.collectAsState()?.value
    val waiting = list.count { it.state == com.therealityteam.videoplayer.data.download.Download.State.QUEUED }
    val supporting = active?.takeIf { it.totalBytes > 0 }?.let { a ->
        val pct = (a.fraction * 100).toInt()
        if (waiting > 0) pluralStringResource(R.plurals.dl_nav_more, waiting, pct, waiting) else stringResource(R.string.dl_nav_one, pct)
    }
    val here = list.count { it.isDone }
    NavRow(
        stringResource(R.string.nav_downloads),
        R.drawable.ic_line_download,
        selected = selected,
        supporting = supporting,
        trailing = {
            Text(text = here.toString(), style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelTertiary)
        },
        onClick = onClick,
    )
}

/**
 * One row of the sidebar. [SideNavigationItem] draws the row and its selected state and brings its
 * own controller haptics (it applies controllerHapticFeedback itself), but takes its click from the
 * caller — so the row adds the rest of SpatialUI's contract here, as the settings and sources lists
 * do: clipped to the item's own pill (24dp, its RadiusHuge), the system hover highlight, the press
 * indication, and the disabled alpha when it cannot be used right now.
 */
@Composable
private fun NavRow(
    label: String,
    @DrawableRes iconRes: Int,
    selected: Boolean,
    enabled: Boolean = true,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    SideNavigationItem(
        selected = selected,
        modifier = Modifier
            // Air round the pill ([SideRowGap]): the item draws its chosen fill inside this modifier,
            // so the padding parts the pills — a lit row no longer runs into the chosen one.
            .padding(vertical = SideRowGap / 2)
            .fillMaxWidth()
            .alpha(if (enabled) 1f else LocalDisableAlpha.current)
            .clip(NavRowShape)
            .spatialHoverEffect(enabled = enabled)
            .hoverDebug()
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            ),
        leading = { Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailing = trailing?.let { t -> { t() } },
        content = {
            Column {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = PicoTheme.typography.bodySmall,
                        color = PicoTheme.colorScheme.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
}

/** SideNavigationItem's own shape, so the hover highlight and the press follow the pill. */
private val NavRowShape = RoundedCornerShape(24.dp)

/** "今年看完 3 部电影、12 集 · 约 18 小时" — or only the time, before anything has been seen through. */
@Composable
private fun historyStatsText(s: WatchStats.Summary): String {
    // "3 films and 12 episodes", "3 Filme und 12 Folgen", "3 部电影和 12 集": the language's own list words (ICU).
    val listed = listOfNotNull(
        s.films.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.hist_stats_films, it, it) },
        s.episodes.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.hist_stats_episodes, it, it) },
    )
    val done = if (listed.isEmpty()) "" else
        android.icu.text.ListFormatter.getInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(listed)
    val hours = ((s.minutes + 30) / 60).toInt()
    val time = if (s.minutes >= 60) {
        pluralStringResource(R.plurals.hist_stats_hours, hours, hours)
    } else {
        stringResource(R.string.hist_stats_minutes, s.minutes.toInt())
    }
    return if (done.isNotEmpty()) stringResource(R.string.hist_stats_done, done, time) else stringResource(R.string.hist_stats_time_only, time)
}

/** The id an automation-added source gets: `local_…` as it always was, `ftp_…`, `nfs_…` for a file server's. */
/** What a search found ([LibraryGrouping.filter]), and the keys of the cards among them it found only through their people. */
private class SearchAnswer(val found: List<MediaGroup>, val byPeople: Set<String>)

/** What an automation source address names: its type, and the address with a `jellyfin:` / `emby:` mark taken off. */
private fun automationSource(asked: String): Pair<com.therealityteam.videoplayer.data.model.SourceType, String> {
    val server = listOf(
        com.therealityteam.videoplayer.data.model.SourceType.JELLYFIN,
        com.therealityteam.videoplayer.data.model.SourceType.EMBY,
    ).firstOrNull { asked.startsWith(it.name.lowercase() + ":http") }
    if (server != null) return server to asked.substringAfter(':').trimEnd('/')
    val path = asked.trimEnd('/')
    val type = com.therealityteam.videoplayer.data.source.RemoteAddress.parse(path)
        ?.let { com.therealityteam.videoplayer.data.model.SourceType.valueOf(it.scheme.uppercase()) }
        ?: com.therealityteam.videoplayer.data.model.SourceType.SMB.takeIf { path.startsWith("smb://") }
        ?: com.therealityteam.videoplayer.data.model.SourceType.DLNA.takeIf { path == "dlna" || path.startsWith("dlna:") }
        ?: com.therealityteam.videoplayer.data.model.SourceType.WEBDAV.takeIf { path.startsWith("http://") || path.startsWith("https://") }
        ?: com.therealityteam.videoplayer.data.model.SourceType.LOCAL
    return type to path
}

private fun automationSourceId(type: com.therealityteam.videoplayer.data.model.SourceType, path: String): String =
    type.name.lowercase() + "_" + Integer.toHexString(path.hashCode())
