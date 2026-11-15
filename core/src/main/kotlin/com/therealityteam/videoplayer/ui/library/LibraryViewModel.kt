package com.therealityteam.videoplayer.ui.library

import com.therealityteam.videoplayer.data.source.faultOf
import com.therealityteam.videoplayer.data.source.SourceFault
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.repository.MediaRepository
import com.therealityteam.videoplayer.data.repository.SourceRepository
import com.therealityteam.videoplayer.data.source.SourceScanner
import com.therealityteam.videoplayer.data.scrape.EpisodeInfo
import com.therealityteam.videoplayer.data.scrape.MetaResult
import com.therealityteam.videoplayer.data.scrape.ScrapeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.therealityteam.videoplayer.util.Log
import com.therealityteam.videoplayer.util.elapsedMillis

/** Per-source scan feedback shown in the sources screen. */
sealed interface ScanState {
    data object Scanning : ScanState
    /** [skipped] is how many titles a data-source outage left for the next pass, when there was one. */
    data class Done(val count: Int, val skipped: Int? = null) : ScanState
    /** The cause, and whatever raw detail goes with it — the sentence is the UI's to write. */
    data class Failed(val fault: SourceFault, val detail: String? = null) : ScanState
}

/**
 * A running library task (scanning / matching) for the global progress UI — shown both in the sidebar
 * (home) and the sources screen. [total] == 0 means the indeterminate discovery phase.
 */
enum class TaskPhase { SCANNING, SCRAPING }

data class TaskProgress(val phase: TaskPhase, val done: Int, val total: Int) {
    val indeterminate: Boolean get() = total <= 0
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** Where a 修正匹配 stands: the files being re-identified, which entry ([result]) and how it went. */
data class MatchApply(val ids: List<String>, val result: String, val state: MatchApplyState)

enum class MatchApplyState { RUNNING, DONE, FAILED }

/**
 * Shared library state holder (lives in the Koin session scope so Shared-Space and
 * Full-Space trees see the same data). Exposes the reactive library and library actions.
 */
class LibraryViewModel(
    private val repo: MediaRepository,
    private val scrape: ScrapeEngine,
    private val scanner: SourceScanner,
    private val sourceRepo: SourceRepository,
    private val platform: LibraryPlatform,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Configured network/local sources (SMB/WebDAV/DLNA/…) for the sources screen. */
    val sources: StateFlow<List<MediaSourceConfig>> = sourceRepo.sources

    private val _scanStatus = MutableStateFlow<Map<String, ScanState>>(emptyMap())
    /** Per-source scan progress/result, keyed by source id. */
    val scanStatus: StateFlow<Map<String, ScanState>> = _scanStatus

    /** Scan targets: the device's own media folders, as the platform knows them. */
    private fun scanDirs(): List<String> = platform.localScanDirs()

    val items: StateFlow<List<MediaItem>> = repo.items

    /**
     * The poster wall's cards, computed OFF the main thread. Grouping a big library takes real
     * time, and during a scrape every finished item re-emits [items] — recomputed in a
     * `remember(items)` on the main thread, that saturated it for the whole pass: input
     * dispatching ANRed, and the foreground service's own startForeground() couldn't get a
     * main-thread slot inside its deadline, which killed the process mid-scrape. The debounce
     * collapses the scrape's burst of emissions into one regroup every quarter second; the first
     * value is an empty wall for a few hundred ms on a cold start, which is the price of never
     * blocking the main thread with it.
     */
    // Declared ABOVE [groups], which reads it from inside its debounce: a StateFlow started eagerly
    // can run that lambda while this class is still being constructed, and a property declared
    // below the flow is still null then. Launched straight into a film, the library's first regroup
    // did exactly that and took the whole process down with a NullPointerException.
    /** Current 扫描 / 刮削 task for the global progress bar (sidebar + sources screen), or null. */
    private val _taskProgress = MutableStateFlow<TaskProgress?>(null)
    val taskProgress: StateFlow<TaskProgress?> = _taskProgress

    /** The files last grouped and the cards made of them — what [GroupRefresh] refreshes. */
    @Volatile private var groupedItems: List<MediaItem>? = null
    @Volatile private var groupedCards: List<MediaGroup>? = null

    /** The cards last romanised, and what came of them — likewise refreshed rather than romanised again. */
    @Volatile private var romanisedIn: List<MediaGroup>? = null
    @Volatile private var romanisedOut: List<MediaGroup>? = null

    /** Only the FIRST regroup of a session may come from the cache; a scrape's do not. */
    private var cacheTried = false
    /** How many answers the name memo on disk holds as last written or read; written again when there are more. */
    @Volatile private var namesKept = -1

    /** The cards shown from the cache, kept until the real grouping can be compared against them. */
    private var revived: List<MediaGroup>? = null

    /**
     * Each name's A→Z key as romanised so far — from the snapshot, then every romanising pass — for
     * the plain cards a regroup makes (see LibraryGrouping.withKnownTitleKeys). Declared above the
     * flows that read and fill it.
     */
    private val knownTitleKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun namesFile() = java.io.File(platform.filesDir, "aura/names.tsv")
    private fun textsFile() = java.io.File(platform.filesDir, "aura/texts.tsv")

    /** The titles as romanised and folded in an earlier launch on this system (TextMemo), read beside the library. */
    private val textsRead = scope.async(Dispatchers.IO) {
        val t = elapsedMillis()
        TextMemo.load(textsFile(), TextMemo.stamp(platform.installedAt))
            .also { Log.i("AuraPerf", "titles as romanised before: $it answers in ${elapsedMillis() - t}ms") }
    }
    /** How many answers the text memo on disk holds as last written or read; written again when there are more. */
    @Volatile private var textsKept = -1

    /**
     * What every name was read as in an earlier launch of this build (NameMemo), read off the disk while the
     * library itself is — ready before the first grouping wants it, and holding up nothing before that.
     */
    private val namesRead = scope.async(Dispatchers.IO) {
        val t = elapsedMillis()
        com.therealityteam.videoplayer.data.scrape.NameMemo.load(namesFile(), platform.installedAt)
            .also { Log.i("AuraPerf", "names as read before: $it answers in ${elapsedMillis() - t}ms") }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val groups: StateFlow<List<MediaGroup>> = repo.items
        // A scrape upserts continuously, so a quarter-second of quiet arrives the moment one regroup
        // finishes and the next begins immediately. Watched during a real NAS scrape: 61 regroups in
        // 105 seconds, about 1.7s each — a core spent redrawing the wall, taken from the scrape that
        // is filling it. While a pass is running the wall updates every few seconds instead, which
        // is as often as anybody can read it; the moment the pass ends it goes back to feeling
        // immediate.
        .debounce { if (_taskProgress.value != null) BUSY_REGROUP_MS else IDLE_REGROUP_MS }
        .transform { items ->
            // The cards this library made last time, if nothing they depend on has moved. It is the
            // same answer, so it goes out at once and the wall is usable while the real grouping is
            // still running — see GroupCache. The real one is computed anyway and replaces it: if
            // the two agree, which is what is expected, nobody sees anything happen.
            if (!cacheTried && repo.loaded.value && items.isNotEmpty()) {
                cacheTried = true
                val t = elapsedMillis()
                // The cards to draw with come from the snapshot, which is read on its own and is
                // normally there well before the library is. Waiting a moment rather than racing it
                // costs nothing when it is, and gives up rather than hanging when it never comes.
                // With no snapshot file at all — a first run, or a library just emptied — there is
                // nothing to wait for: waiting anyway held a first wall back by the full 3 s
                // (measured on the desktop over 9,597 files: live at 4.3 s, grouping 0.74 s of it).
                val painted = if (!WallSnapshot.file(platform.filesDir).exists()) emptyList() else
                    kotlinx.coroutines.withTimeoutOrNull(SNAPSHOT_WAIT_MS) {
                        wallSnapshot.first { it.isNotEmpty() }
                    }.orEmpty()
                GroupCache.revive(platform.filesDir, platform.installedAt, painted, items)?.let { cached ->
                    Log.i(
                        "AuraPerf",
                        "wall revived from cache: ${cached.size} cards in ${elapsedMillis() - t}ms",
                    )
                    revived = cached
                    _wallLive.value = true
                    emit(cached)
                }
            }
            // A position saved as a film plays (every ten seconds), a favourite, a picked track: the
            // cards are the same, only their files' state moved — no grouping (GroupRefresh).
            val prevItems = groupedItems
            val prevGroups = groupedCards
            if (prevItems != null && prevGroups != null && GroupRefresh.groupsAlike(prevItems, items)) {
                val refreshed = GroupRefresh.refreshed(prevGroups, items)
                groupedItems = items
                groupedCards = refreshed
                emit(refreshed)
                return@transform
            }
            val t0 = elapsedMillis()
            // The names as read in an earlier launch, taken up beside the library's own reading (namesRead).
            if (items.isNotEmpty() && namesKept < 0) namesKept = namesRead.await()
            // Every name is read once before grouping starts, on all the cores (see NameWarmup).
            com.therealityteam.videoplayer.data.scrape.NameWarmup.warm(items)
            val warmMs = elapsedMillis() - t0
            emit(
                LibraryGrouping.withKnownTitleKeys(LibraryGrouping.group(items), knownTitleKeys).also { g ->
                    groupedItems = items
                    groupedCards = g
                    if (items.isNotEmpty()) Log.i("AuraPerf", "grouped ${items.size} items into ${g.size} groups in ${elapsedMillis() - t0}ms (warm ${warmMs}ms); in progress: ${items.count { it.progress.started }}")
                    // Did the cards we showed while this was running turn out to be the right ones?
                    // They should always be — the stamp covers everything grouping reads — and if
                    // this ever says otherwise, the stamp is too loose and the wall changed under
                    // somebody. Worth hearing about rather than silently swapping.
                    revived?.let { before ->
                        val same = before.map { c -> c.key to c.items.map { it.id } } ==
                            g.map { c -> c.key to c.items.map { it.id } }
                        Log.i(
                            "AuraPerf",
                            if (same) "wall cache agreed with the real grouping"
                            else "wall cache DISAGREED: ${before.size} cards cached, ${g.size} computed",
                        )
                        revived = null
                    }
                    // Grouped from the library as READ from disk (not the empty list before the read):
                    // from here the wall shows live cards and takes taps.
                    if (repo.loaded.value) _wallLive.value = true
                    // What the names were read as, kept for the next launch — when there is more of it.
                    if (repo.loaded.value) scope.launch(Dispatchers.IO) {
                        val now = com.therealityteam.videoplayer.data.scrape.FilenameParser.parsesSoFar().size +
                            com.therealityteam.videoplayer.data.scrape.ContentClassifier.classificationsSoFar().size
                        if (now > namesKept) namesKept = com.therealityteam.videoplayer.data.scrape.NameMemo.save(namesFile(), platform.installedAt)
                    }
                },
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _wallLive = MutableStateFlow(false)

    private companion object {
        /** How long the first regroup waits for the painted snapshot before grouping from scratch. */
        const val SNAPSHOT_WAIT_MS = 3_000L

        /** How long the library must go quiet before the wall is redrawn — idle, and mid-pass. */
        const val IDLE_REGROUP_MS = 250L
        /** How often the wall's snapshot is written while only its cards' files move (a film playing). */
        const val SAME_CARDS_SAVE_MS = 120_000L
        const val BUSY_REGROUP_MS = 4_000L
    }

    /** True once [groups] reflects the library on disk; until then the wall paints [wallSnapshot]. */
    val wallLive: StateFlow<Boolean> = _wallLive

    /**
     * The wall as it stood at the end of the last session (see [WallSnapshot]) — on disk in a
     * form the cards can be drawn from directly, read in well under a second, so a cold start
     * shows the wall while the library is still being read and grouped.
     */
    val wallSnapshot: StateFlow<List<MediaGroup>> = kotlinx.coroutines.flow.flow {
        emit(WallSnapshot.load(platform.filesDir).also { s -> s.forEach { g -> if (g.titleKey.isNotEmpty()) knownTitleKeys.putIfAbsent(g.title, g.titleKey) } })
    }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * [groups] with pinyin / romaji search keys and A→Z keys — what the wall searches and sorts.
     * Romanising a library's titles takes seconds on a headset, so it runs AFTER the wall is up,
     * off-main, and the wall uses the plain cards until it lands.
     */
    val searchableGroups: StateFlow<List<MediaGroup>> = groups
        .map { g ->
            val lastIn = romanisedIn
            val lastOut = romanisedOut
            if (lastIn != null && lastOut != null && lastOut.size == g.size && GroupRefresh.sameCards(lastIn, g)) {
                val out = GroupRefresh.carryKeys(lastOut, g)
                romanisedIn = g
                romanisedOut = out
                return@map out
            }
            if (g.isNotEmpty() && textsKept < 0) textsKept = textsRead.await()
            val t0 = elapsedMillis()
            val out = LibraryGrouping.romanized(g, Romanizer::romanize)
            romanisedIn = g
            romanisedOut = out
            out.forEach { c -> knownTitleKeys[c.title] = c.titleKey }
            // Each card's shown count read here, off-main, rather than by the first frame that draws it.
            out.forEach { c -> c.shownCount }
            if (g.isNotEmpty()) {
                // Did romanising move anything in title order? With the known keys carried, only a
                // name romanised for the first time should — anything more would be a reshuffle.
                val moved = LibrarySort.apply(g, LibSort.TITLE).map { it.key } != LibrarySort.apply(out, LibSort.TITLE).map { it.key }
                Log.i(
                    "AuraPerf",
                    "search keys romanised: ${g.size} groups in ${elapsedMillis() - t0}ms " +
                        "(icu ${Romanizer.calls.get()} calls, ${Romanizer.spentNanos.get() / 1_000_000}ms total; " +
                        "han fold ${HanFold.spentNanos.get() / 1_000_000}ms); title order ${if (moved) "MOVED" else "held"}",
                )
                scope.launch(Dispatchers.IO) {
                    if (Romanizer.soFar().size + HanFold.soFar().size > textsKept) {
                        textsKept = TextMemo.save(textsFile(), TextMemo.stamp(platform.installedAt))
                    }
                }
            }
            out
        }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())


    private val vmBorn = elapsedMillis()


    /**
     * Carry on where an interrupted pass stopped. PENDING items exist only when discovery ran and
     * scraping did not finish — a closed window, a died process, a headset on the shelf overnight.
     * The foreground service shortens the list of ways a pass dies; this picks up the survivors:
     * scraping is idempotent and rate-polite, so quietly resuming costs nothing when there is
     * nothing to do, and saves the user from a wall that stays half-grey until they guess that
     * 刮削 needs pressing.
     */
    /** The language the library's words were last fetched in, kept beside it. */
    private fun languageStamp() = java.io.File(platform.filesDir, "aura/metadata-language.txt")

    /**
     * Rewrite the library in the metadata language when it was fetched in another ([ScrapeEngine.relocalize]).
     * A library with no stamp yet was fetched in this one (it was scraped in the language the app had), and is
     * stamped. Interrupted, the stamp is not moved: the next launch carries on. Dropped while another task
     * runs, it is asked again when that one ends.
     */
    private fun relocalizeIfStale() {
        val now = com.therealityteam.videoplayer.data.scrape.TmdbConfig.language
        val stamp = languageStamp()
        val was = runCatching { stamp.readText().trim() }.getOrNull()
        if (was == null || repo.all().none { it.scrapeStatus == ScrapeStatus.MATCHED }) {
            runCatching { stamp.parentFile?.mkdirs(); stamp.writeText(now) }
            return
        }
        if (was == now || com.therealityteam.videoplayer.data.scrape.TmdbConfig.offline || relocalizeTried == now) return
        Log.i("AuraScan", "metadata language $was → $now: rewriting the library's words")
        runTask {
            relocalizeTried = now // once a launch: with the network down every work fails, and this would go round
            _taskProgress.value = TaskProgress(TaskPhase.SCRAPING, 0, 0)
            val rewritten = scrape.relocalize(repo.all()) { done, total -> _taskProgress.value = TaskProgress(TaskPhase.SCRAPING, done, total) }
            // Stamped with what was asked for (a change meanwhile is the next pass's) — once something answered.
            if (rewritten > 0) runCatching { stamp.writeText(now) }
        }
    }

    /** The language a rewrite was last started for in this launch. */
    @Volatile private var relocalizeTried: String? = null

    private fun resumeInterruptedScrape() {
        val interrupted = repo.all().count {
            it.scrapeStatus == ScrapeStatus.PENDING || it.scrapeStatus == ScrapeStatus.ERROR
        }
        if (interrupted == 0) return
        rescrape()
    }

    /** Whether a library task runs (or is about to): one at a time. Guarded by [taskLock]. */
    private var busy = false
    private val taskLock = Any()

    /**
     * Sources asked to be scanned while another task ran, scanned in turn as soon as it ends.
     * Such a request used to be dropped: a share added during a long first match was saved and
     * never read, until someone thought to scan everything again. Guarded by [taskLock].
     */
    private val waiting = LinkedHashMap<String, MediaSourceConfig>()

    /** Run a single library task at a time, clearing the progress when it finishes. */
    private fun runTask(block: suspend () -> Unit) {
        synchronized(taskLock) {
            if (busy) return // a scan/scrape is already in flight
            busy = true
        }
        launchTask(block)
    }

    /** Run [block] as the library task; [busy] is already set, and stays set for what waits. */
    private fun launchTask(block: suspend () -> Unit) {
        // Hours of scraping must not share the window's lifetime: the foreground service keeps
        // the process alive when the pane closes, and stops itself when the progress flows drain.
        platform.keepWorking()
        scope.launch {
            // Not silently: the progress bar clears either way, so a scan or scrape that DIED and
            // one that finished were the same event from outside. A whole pass was once read as
            // "did nothing" when it had in fact never started.
            runCatching { block() }
                .onFailure { Log.w("AuraTask", "task failed and was dropped", it) }
            _taskProgress.value = null
            val next = synchronized(taskLock) {
                waiting.values.toList().also { waiting.clear(); if (it.isEmpty()) busy = false }
            }
            if (next.isNotEmpty()) launchTask { next.forEach { scanOne(it) } } else relocalizeIfStale()
        }
    }

    /**
     * Re-run scraping for pending/errored items (fills posters once a TMDB key is set).
     *
     * [onDone] hears how many files the pass looked up once it has finished — 0 when every file was
     * already matched, which the library window says out loud rather than flashing its progress bar
     * on and off. Not called when the request was dropped because another task was running.
     */
    /** [only]: the files whose path holds one of these words (`|` between them) — a corner tried again by automation. */
    fun rescrape(only: String? = null, onDone: (looked: Int) -> Unit = {}) = runTask {
        // Indeterminate until the pass knows its size: a placeholder total of 1 read "刮削 0/1".
        _taskProgress.value = TaskProgress(TaskPhase.SCRAPING, 0, 0)
        var looked = 0
        val words = only?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val library = repo.all().let { all -> if (words.isEmpty()) all else all.filter { item -> words.any { item.path.contains(it) } } }
        scrape.scrapeAll(library) { done, total ->
            looked = total
            _taskProgress.value = TaskProgress(TaskPhase.SCRAPING, done, total)
        }
        annotateStatuses()
        onDone(looked)
    }

    // ---- Network/local sources --------------------------------------------------------------

    /** Add a source, persist it, and scan it into the library immediately. */
    fun addSource(config: MediaSourceConfig) {
        sourceRepo.upsert(config)
        rescanSource(config)
    }

    fun removeSource(id: String) {
        sourceRepo.remove(id)
        _scanStatus.value = _scanStatus.value - id
    }

    /**
     * Re-scan one source, surfacing progress/result (count or error) for the UI — now, or after
     * the task that is running (shown as scanning meanwhile).
     */
    fun rescanSource(config: MediaSourceConfig) {
        val now = synchronized(taskLock) {
            if (busy) { waiting[config.id] = config; false } else { busy = true; true }
        }
        if (now) launchTask { scanOne(config) } else setStatus(config.id, ScanState.Scanning)
    }

    /**
     * Scan every configured source. Discover ALL of them first (fast — listing is cheap metadata, so
     * every file appears in the library quickly), THEN run one scrape pass over the whole library —
     * instead of blocking each share's discovery behind the previous share's rate-limited scrape.
     */
    fun scanAllSources() = runTask {
        for (config in sourceRepo.all()) {
            setStatus(config.id, ScanState.Scanning)
            _taskProgress.value = TaskProgress(TaskPhase.SCANNING, 0, 0) // discovery: total unknown
            runCatching { scanner.discover(config) }
                .onSuccess { n -> setStatus(config.id, ScanState.Done(n)) }
                .onFailure { e -> setStatus(config.id, ScanState.Failed(faultOf(e), e.message)) }
        }
        scrape.scrapeAll(repo.all()) { done, total -> _taskProgress.value = TaskProgress(TaskPhase.SCRAPING, done, total) }
        annotateStatuses()
    }

    /**
     * What an opening of the app asks for (自动更新媒体库): every source listed again for files added
     * since, and only THOSE matched. [scanAllSources] then re-asks every file nothing could name — 1,658
     * on a real library, four minutes of rate-limited lookups that gave the same answers — which is what
     * a 刮削 the user asks for is for, not what a quiet look for new files should cost.
     */
    /** How many files the last [scanForNew] took in — for the window to say so; null once it has. */
    private val _newFiles = MutableStateFlow<Int?>(null)
    val newFiles: StateFlow<Int?> = _newFiles
    fun newFilesShown() { _newFiles.value = null }

    fun scanForNew() = runTask {
        val before = repo.all().mapTo(HashSet()) { it.id }
        for (config in sourceRepo.all()) {
            setStatus(config.id, ScanState.Scanning)
            _taskProgress.value = TaskProgress(TaskPhase.SCANNING, 0, 0)
            runCatching { scanner.discover(config) }
                .onSuccess { n -> setStatus(config.id, ScanState.Done(n)) }
                .onFailure { e -> setStatus(config.id, ScanState.Failed(faultOf(e), e.message)) }
        }
        val fresh = repo.all().filter { it.id !in before || it.scrapeStatus == ScrapeStatus.PENDING }
        Log.i("AuraScan", "scan for new: ${fresh.size} new files")
        if (fresh.isNotEmpty()) {
            scrape.scrapeAll(fresh) { done, total -> _taskProgress.value = TaskProgress(TaskPhase.SCRAPING, done, total) }
            _newFiles.value = fresh.size
        }
        annotateStatuses()
    }

    /** Scan one source: per-source status + the global 扫描→刮削 progress bar. No coroutine of its own. */
    private suspend fun scanOne(config: MediaSourceConfig) {
        setStatus(config.id, ScanState.Scanning)
        _taskProgress.value = TaskProgress(TaskPhase.SCANNING, 0, 0) // discovery: total unknown
        runCatching {
            scanner.scan(config) { done, total -> _taskProgress.value = TaskProgress(TaskPhase.SCRAPING, done, total) }
        }
            .onSuccess { n -> setStatus(config.id, ScanState.Done(n, scrape.lastReport?.leftOver)) }
            .onFailure { e -> setStatus(config.id, ScanState.Failed(faultOf(e), e.message)) }
    }

    // When each source's scan began: a failed one is logged with how long it took to say so — a NAS that is
    // off answers nothing, and how long 扫描中 then stood before 连接失败 was nowhere to be read.
    private val scanStartedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun setStatus(id: String, state: ScanState) {
        when (state) {
            ScanState.Scanning -> scanStartedAt[id] = elapsedMillis()
            is ScanState.Failed -> {
                val took = scanStartedAt.remove(id)?.let { elapsedMillis() - it }
                Log.w("AuraScan", "${sourceRepo.get(id)?.uri ?: id}: not scanned, ${state.fault} after ${took ?: "?"} ms (${state.detail})")
            }
            else -> scanStartedAt.remove(id)
        }
        _scanStatus.value = _scanStatus.value + (id to state)
    }

    /** After a scrape pass, every finished source carries the pass's caveat (or none). */
    private fun annotateStatuses() {
        val left = scrape.lastReport?.leftOver
        _scanStatus.value = _scanStatus.value.mapValues { (_, st) -> if (st is ScanState.Done) st.copy(skipped = left) else st }
    }

    // ---- Manual "fix match" -----------------------------------------------------------------

    /** Free-text TMDB search for the manual fix-match UI. */
    suspend fun searchMatches(query: String): List<MetaResult> = scrape.searchManual(query)

    /**
     * Each episode of [group] as the database tells it, by item id (see ScrapeEngine.episodeGuide) —
     * and, for a run numbered by its names, as its file calls it where no database told it
     * (EpisodeNaming.instalmentNames): 继续观看 reads 「第 2 集：青岛养老院的故事」.
     */
    suspend fun episodes(group: MediaGroup): Map<String, EpisodeInfo> =
        withContext(Dispatchers.IO) {
            val own = if (group.numberedRun) EpisodeNaming.instalmentNames(group.items).mapValues { EpisodeInfo(it.value, null, null) } else emptyMap()
            own + scrape.episodeGuide(group.items)
        }

    /** Apply a user-chosen match to an item (re-fetches details + artwork, upserts MATCHED). */
    suspend fun applyMatch(item: MediaItem, result: MetaResult): MediaItem = scrape.applyMatch(item, result)

    /** Free-text AniList search for fixing an anime card. */
    suspend fun searchAnimeMatches(query: String): List<MetaResult> = scrape.searchManualAnime(query)

    /** Pin every file of [items] (a card, usually) to [result]; [anime] when it is an AniList entry. */
    suspend fun applyMatch(items: List<MediaItem>, result: MetaResult, anime: Boolean): List<MediaItem> =
        scrape.applyMatch(items, result, anime)

    private val _matchApply = MutableStateFlow<MatchApply?>(null)

    /**
     * The 修正匹配 running now, or how the last one ended — for the page that asked for it.
     *
     * The work runs here, in the library's scope, not in the page's: fetching a series' details and
     * artwork takes a while, and leaving the page half-way used to cancel it without a word.
     */
    val matchApply: StateFlow<MatchApply?> = _matchApply

    // After every property: the coroutines it starts can run at once (an empty library loads at once —
    // a new install), and a task taken before [taskLock] and the flows below were set threw (null lock).
    init {
        // The library is read off-thread now; the resume check has to wait for it.
        scope.launch {
            repo.loaded.first { it }
            resumeInterruptedScrape()
        }
        // 元数据语言 changed (or the app's language it follows): the library's words follow, once it is loaded.
        scope.launch {
            repo.loaded.first { it }
            com.therealityteam.videoplayer.data.scrape.TmdbConfig.languageFlow.collect { relocalizeIfStale() }
        }
        // Keep the snapshot current: after every settled regroup of the LIVE library (an empty
        // one included, which is how 清空媒体库 clears it), write the cards the next launch opens on.
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            var saved: List<MediaGroup>? = null
            var savedAt = 0L
            searchableGroups.debounce(5_000).collect { g ->
                if (!_wallLive.value) return@collect
                // No cards from a library that has files is no wall at all: it is the search keys' first
                // value, still empty while the live cards are being romanised (2.5 s past going live,
                // more than the debounce). It was written, and a process ended in those seconds — a
                // film opened straight away, then closed — left the next launch an empty wall for 6 s.
                if (g.isEmpty() && repo.items.value.isNotEmpty()) return@collect
                // The same cards with their files further along (a film playing): the bars on them can
                // wait a couple of minutes — the progress sidecar has the positions meanwhile.
                val last = saved
                if (last != null && GroupRefresh.sameCards(last, g) && elapsedMillis() - savedAt < SAME_CARDS_SAVE_MS) return@collect
                saved = g
                savedAt = elapsedMillis()
                WallSnapshot.save(platform.filesDir, g)
                // Written after the snapshot, and stamped with the library file as it stands now —
                // so if anything has written the library since, the stamp simply will not match and
                // the next launch groups from scratch.
                GroupCache.save(platform.filesDir, platform.installedAt, g)
            }
        }
        // Startup beacons: when does the wall have content to show — the snapshot, then the library?
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            val n = wallSnapshot.first { it.isNotEmpty() }.size
            Log.i("AuraPerf", "wall snapshot shown: $n cards ${elapsedMillis() - vmBorn}ms after VM init")
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            val n = groups.first { it.isNotEmpty() }.size
            Log.i(
                "AuraPerf",
                "poster wall ready: $n groups ${elapsedMillis() - vmBorn}ms after VM init",
            )
        }
    }

    /**
     * Pin [items] to [result] in the background, one 修正匹配 at a time; [resultKey] is how the page
     * names the entry it was given, so it can show which one is being applied.
     */
    fun startMatch(items: List<MediaItem>, result: MetaResult, anime: Boolean, resultKey: String) {
        if (items.isEmpty() || _matchApply.value?.state == MatchApplyState.RUNNING) return
        val ids = items.map { it.id }
        _matchApply.value = MatchApply(ids, resultKey, MatchApplyState.RUNNING)
        scope.launch {
            val ok = runCatching { scrape.applyMatch(items, result, anime) }
                .onFailure { Log.w("AuraFixMatch", "applying ${result.providerId} to ${ids.size} file(s) failed", it) }
                .isSuccess
            _matchApply.value = MatchApply(ids, resultKey, if (ok) MatchApplyState.DONE else MatchApplyState.FAILED)
        }
    }

    /** The page has shown how the last 修正匹配 ended; one still running is left alone. */
    fun clearMatch() {
        if (_matchApply.value?.state != MatchApplyState.RUNNING) _matchApply.value = null
    }

    /** Scan common on-device media folders into the library (real files → real playback). */
    fun scanLocal() = runTask {
        scanDirs().forEach { dir ->
            scanOne(
                MediaSourceConfig(
                    id = "local:$dir",
                    type = SourceType.LOCAL,
                    name = dir,
                    uri = dir,
                    addedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun toggleFavorite(id: String) {
        repo.get(id)?.let { repo.setFavorite(id, !it.favorite) }
    }

    /** Favorite/unfavorite a whole title (all of a collection's files) as one unit. */
    fun setGroupFavorite(ids: List<String>, favorite: Boolean) = repo.setFavorite(ids, favorite)

    /** 标为已看 / 标为未看 — one row, or every file of a card. */
    fun setWatched(ids: List<String>, watched: Boolean) = repo.setWatched(ids, watched)

}
