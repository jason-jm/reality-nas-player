package com.therealityteam.videoplayer.data.repository

import com.therealityteam.videoplayer.util.Log
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaJson
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.model.VideoFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import com.therealityteam.videoplayer.util.elapsedMillis

/** Reactive store of the scraped library. JSON-file backed; swappable for Room later. */
interface MediaRepository {
    val items: StateFlow<List<MediaItem>>
    /** False until the persisted library has been read; [items] is empty before that, not "empty". */
    val loaded: StateFlow<Boolean>
    fun all(): List<MediaItem>
    fun get(id: String): MediaItem?
    fun upsert(item: MediaItem)
    fun upsertAll(newItems: List<MediaItem>)

    /**
     * Overwrite many existing items at once.
     *
     * Unlike [upsertAll] this keeps nothing of what was there — the caller has already decided what
     * each item should now be. It exists because doing the same thing with [upsert] in a loop costs
     * a copy of the whole list and a notification to everybody watching, per item: folder
     * reconciliation rewrites about 1,300 items after every scrape, and on a real library that was
     * 22 seconds of a 45-second pass.
     */
    fun replaceAll(items: List<MediaItem>)
    fun updateProgress(id: String, positionMs: Long, durationMs: Long, watched: Boolean = false)

    /**
     * A position from somewhere else — another device, through sync — taken as it came (its own
     * time included) and only if it is newer than what this device has. True if it was taken.
     */
    fun mergeProgress(id: String, progress: PlaybackProgress): Boolean

    /**
     * Mark files watched or not, as a viewer does by hand — episodes seen elsewhere, a film to see
     * again. Watched puts the position at the end (or leaves it at 0 for a file never played here);
     * unwatched resets it, so the row, the wall's bar and 继续观看 all read the same way.
     */
    fun setWatched(ids: Collection<String>, watched: Boolean) {
        ids.forEach { id ->
            val dur = get(id)?.progress?.durationMs ?: 0L
            updateProgress(id, if (watched) dur else 0L, dur, watched)
        }
    }

    /**
     * Put files back as never played here — no position, not watched, no time it was last played (which
     * 历史 and 继续观看 go by): what a test's plays leave behind, taken away again.
     */
    fun forgetProgress(ids: Collection<String>)

    fun setFavorite(id: String, favorite: Boolean)
    /** Set favorite for a whole group of items at once (one emission / persist). */
    fun setFavorite(ids: Collection<String>, favorite: Boolean)
    fun setVideoFlags(id: String, flags: VideoFlags)
    /** Forget the viewer's word on [id]'s 3D / projection: its name (and the file's own word, at play) decide again. */
    fun clearVideoFlags(id: String)

    /**
     * What the file said of itself as it played (VideoFlags.fromFile), kept as it is — unlike the viewer's
     * word ([setVideoFlags]) it never outranks a choice the viewer made since.
     */
    fun learnVideoFlags(id: String, flags: VideoFlags) {}
    /** Remember the audio / subtitle track a file was last watched with ("off" = subtitles off). */
    fun setAudioTrackChoice(id: String, trackId: String?)
    fun setSubtitleTrackChoice(id: String, trackId: String?)
    fun removeBySource(sourceId: String)
    /** Drop items of a source that a fresh scan no longer produced (stale paths / collapsed discs). */
    fun retainForSource(sourceId: String, keepIds: Set<String>)
    fun remove(id: String)
    /** Wipe the whole library (keeps source configs) — for a from-zero rebuild. */
    fun clear()

    /**
     * Write pending changes to disk NOW. Upserts are coalesced (see [JsonMediaRepository.persist]),
     * so a bulk operation that ends and lets its process die can lose its last window of work —
     * a scan that discovered 890 files as its final act lost all of them. Call at the end of scan
     * and scrape.
     */
    fun flush()
}

class JsonMediaRepository(
    /** The app's private data directory; the library is `aura/library.json` under it. */
    filesDir: File,
    /** Test seam: how long the loader waits before it starts reading, to put a write ahead of it. */
    private val loadDelayMsForTest: Long = 0L,
) : MediaRepository {
    private val file = File(filesDir, "aura/library.json").apply { parentFile?.mkdirs() }
    /**
     * The library as it stood at the last launch that read it whole — what a damaged library.json is
     * rebuilt from, rather than from nothing. A truncated file is refused (see [readLibrary]), and the
     * first write after that used to put what little the app then held over it: everything in it, gone.
     */
    // Declared before the loader thread starts (init, below): it reads this.
    private val backupFile = File(file.parentFile, "library.json.bak")

    /** Playback positions, written on their own (see [ProgressSidecar]) so a save is cheap and frequent. */
    private val progressFile = File(filesDir, "aura/progress.json")
    private val recentProgress = LinkedHashMap<String, ProgressSidecar.Entry>()
    @Volatile private var progressSavePending = false
    private val lock = Any()
    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    override val items: StateFlow<List<MediaItem>> = _items.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    override val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val loadThread = Thread({
        if (loadDelayMsForTest > 0) Thread.sleep(loadDelayMsForTest)
        load()
        _loaded.value = true
    }, "aura-library-load")

    init {
        // Off the constructing thread: Koin builds this on the main thread inside the library's
        // first composition, and a 9.6k-file library is 32 MB of JSON — eight seconds on the
        // emulator during which nothing drew. The lock keeps an early upsert behind the load.
        loadThread.start()
    }

    /**
     * Every write waits for the read. The lock alone did not: a write that took it BEFORE the load
     * thread did ran on an empty map and persisted that — a targeted rescrape started right after
     * launch wrote `[]` over a 9.6k-file library. Called outside the lock (the loader needs it).
     */
    private fun ensureLoaded() {
        if (!_loaded.value && Thread.currentThread() !== loadThread) loadThread.join()
    }

    private fun load() = synchronized(lock) {
        if (!file.exists()) return
        val t0 = elapsedMillis()
        val read = readLibrary(file)
        val items = when {
            read != null -> {
                // Kept for the next launch, off the reading thread: the library is whole now. A thread
                // of its own — this runs from the constructor, before the save scope below exists.
                Thread({ keepBackup() }, "aura-library-backup").start()
                read
            }
            else -> {
                // Damaged: set aside, never written over, and the last whole copy read instead.
                val damaged = File(file.parentFile, "library.json.damaged-" + System.currentTimeMillis())
                val kept = file.renameTo(damaged)
                val restored = if (backupFile.exists()) readLibrary(backupFile) else null
                Log.e(
                    "AuraPerf",
                    "library.json unreadable (kept as ${if (kept) damaged.name else "—"}); " +
                        (restored?.let { "restored ${it.size} items from ${backupFile.name}" } ?: "no backup to restore from"),
                )
                // …and put back where it belongs, as the copy it is: no write can come before this
                // read finishes, so nothing else touches the file meanwhile.
                if (restored != null) runCatching {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    backupFile.copyTo(tmp, overwrite = true)
                    tmp.renameTo(file)
                }
                restored
            }
        }
        if (items != null) {
            // In memory now, on disk with the next write — the repair is cheap enough to repeat until then.
            val (repaired, named) = LibraryRepair.seriesTitles(items)
            if (named > 0) Log.i("AuraPerf", "library: $named episodes' series now named as the database names it")
            publish(repaired)
        }
        // Positions saved since the library was last written win over what it carries.
        runCatching {
            if (progressFile.exists()) {
                val entries = ProgressSidecar.decode(progressFile.readText())
                entries.forEach { recentProgress[it.id] = it }
                publish(ProgressSidecar.merge(_items.value, entries))
            }
        }
        Log.i(
            "AuraPerf",
            "library loaded: ${_items.value.size} items in ${elapsedMillis() - t0}ms",
        )
        Unit
    }

    /**
     * The items in [f], or null when it cannot be read whole. One object at a time: the whole file as
     * a string plus the whole object tree was hundreds of megabytes of transient heap before the first
     * item existed. A truncated file is refused outright — a half-read library that then gets persisted
     * would throw the rest away.
     */
    private fun readLibrary(f: File): List<MediaItem>? = runCatching {
        val out = ArrayList<MediaItem>(4096)
        var skipped = 0
        // The array may open with a credits header (see CreditsTable): the cast and crew the files
        // share, written once rather than once each. Nothing else may precede the items, so one look
        // at the first object settles it.
        var credits: com.therealityteam.videoplayer.data.model.CreditsTable? = null
        var first = true
        val shared = Shared()
        val whole = f.bufferedReader().use { r ->
            com.therealityteam.videoplayer.data.model.JsonArrayStream.forEachObject(r) { text ->
                runCatching {
                    val o = JSONObject(text)
                    if (first && com.therealityteam.videoplayer.data.model.CreditsTable.isHeader(o)) {
                        credits = com.therealityteam.videoplayer.data.model.CreditsTable.read(o)
                    } else {
                        out.add(shared.of(MediaJson.item(o, credits)))
                    }
                    first = false
                }.onFailure { skipped++; first = false }
            }
        }
        if (!whole) {
            Log.e("AuraPerf", "${f.name} ends mid-array — refusing a partial read")
            return null
        }
        if (skipped > 0) Log.w("AuraPerf", "${f.name}: skipped $skipped unreadable items")
        out
    }.getOrNull()

    /**
     * One instance of each text and list the library repeats: a show's every episode carries the show's
     * overview, poster, backdrop, genres and ratings. On a real NAS library 6,930 overviews were 601
     * texts — 4.7 MB of file, twice that in memory as each item's own copy; read through this, the
     * items share them.
     */
    private class Shared {
        private val strings = HashMap<String, String>()
        private val lists = HashMap<List<Any>, List<Any>>()

        private fun s(v: String?): String? = v?.let { strings.getOrPut(it) { it } }

        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> l(v: List<T>): List<T> = if (v.isEmpty()) v else lists.getOrPut(v) { v } as List<T>

        fun of(item: MediaItem): MediaItem = item.copy(
            sourceId = s(item.sourceId)!!,
            title = s(item.title)!!,
            originalTitle = s(item.originalTitle),
            aliases = l(item.aliases),
            overview = s(item.overview),
            ratings = l(item.ratings),
            certification = s(item.certification),
            genres = l(item.genres),
            posterPath = s(item.posterPath),
            backdropPath = s(item.backdropPath),
            showTitle = s(item.showTitle),
            collectionKey = s(item.collectionKey),
        )
    }

    /** library.json, as just read whole, copied aside (through a temp file, so a kill leaves the old copy). */
    @Synchronized private fun keepBackup() {
        runCatching {
            val tmp = File(file.parentFile, backupFile.name + ".tmp")
            file.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(backupFile)) tmp.delete()
        }.onFailure { Log.w(TAG, "library backup failed: ${it.message}") }
    }

    // Persisting the whole library is O(items): serializing ~3k items is ~4MB. A scrape upserts
    // thousands of times in a burst, so writing on every upsert is O(n²) — it crawls and thrashes GC.
    // Debounce instead: the in-memory StateFlow updates immediately (UI is live); disk is flushed once
    // the burst settles. Worst case on a hard kill is a few un-persisted matches that re-scrape next run.
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveMutex = Mutex()
    @Volatile private var savePending = false
    /**
     * Whether something asked to be written has not been yet. A flush with nothing to write writes
     * nothing — each source's scan ends with one, and on the B3110 an auto scan that found nothing new
     * rewrote 12.8 MB per source (1.5 s each); and a write a flush already made is not made again
     * when the window it was waiting in closes.
     */
    @Volatile private var dirty = false
    @Volatile private var lastRequest = 0L
    /** How long the last write took, and when it ended — the budget below is paced off these. */
    @Volatile private var lastWriteMs = 0L
    @Volatile private var lastWriteEndedAt = 0L

    /**
     * Coalesce with a SLIDING window: wait until upserts go quiet for [QUIET_MS], but never hold a
     * write longer than [MAX_COALESCE_MS]. A fixed 1.5s delay still wrote continuously through a
     * scrape — upserts never stop for 1.5s, so every window elapsed mid-burst and the whole library
     * (~20MB of JSON for 6k items) was re-serialized ~40x a minute, burning a core the scrape needed.
     * Interactive edits (favorite, progress) are unaffected: they go quiet immediately and still land
     * after 1.5s.
     *
     * The quiet window alone stopped being enough once the scrape learned to wait: rate limiting
     * leaves gaps longer than 1.5s all through a pass, so a full re-scrape wrote the library 102
     * times, 5-17s each at 16.7MB. So a write also has to earn its place — it waits until at least
     * [WRITE_BUDGET] times its own last duration has passed since the last one finished, which caps
     * the share of a bulk operation spent writing at roughly 1/[WRITE_BUDGET] whatever the library's
     * size or the device's speed. A small library writes as often as it ever did; a large one stops
     * spending its scrape on itself.
     */
    private fun persist() {
        dirty = true
        lastRequest = System.currentTimeMillis()
        if (savePending) return
        savePending = true
        saveScope.launch {
            val deadline = System.currentTimeMillis() + MAX_COALESCE_MS
            while (System.currentTimeMillis() < deadline &&
                (
                    System.currentTimeMillis() - lastRequest < QUIET_MS ||
                        System.currentTimeMillis() - lastWriteEndedAt < lastWriteMs * WRITE_BUDGET
                    )
            ) {
                delay(250)
            }
            savePending = false
            saveMutex.withLock { if (dirty) runCatching { persistNow() } }
        }
    }

    /**
     * Stream the library out item by item, into a temp file that replaces the real one only once
     * the write is COMPLETE.
     *
     * Both halves of that are load-bearing. Building the whole document as one String needs a
     * contiguous char array twice its size — at 7007 items that is a 72MB allocation, which the
     * app's 192MB growth limit could not serve while a scrape held the heap. Every write threw
     * OutOfMemoryError, the surrounding runCatching swallowed it, and four hours of scraping never
     * reached disk while the log filled with "Failed to allocate a 75497480 byte allocation".
     * Writing in place, meanwhile, leaves a truncated library behind if the process dies mid-write.
     */
    private fun persistNow() {
        if (!_loaded.value) return // never write over a library that has not been read yet
        // (Cleared before the snapshot: a change made while this writes asks for the next write.)
        dirty = false
        val snapshot = _items.value
        val startedAt = elapsedMillis()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val written = runCatching {
            // Pool the credits first: on a real library they were 58% of the file and eleven
            // copies of everything, because a cast belongs to a work and this stores files.
            val (credits, keyOf) =
                com.therealityteam.videoplayer.data.model.CreditsTable.of(snapshot)
            tmp.bufferedWriter().use { w ->
                w.write("[")
                if (!credits.isEmpty) w.write(credits.header().toString())
                snapshot.forEachIndexed { i, item ->
                    if (i > 0 || !credits.isEmpty) w.write(",")
                    w.write(MediaJson.item(item, keyOf[item.id], credits.textRef(item.overview)).toString())
                }
                w.write("]")
            }
        }.onFailure { Log.e(TAG, "library persist failed (${snapshot.size} items)", it) }.isSuccess
        if (written && !tmp.renameTo(file)) Log.e(TAG, "library rename failed: ${tmp.absolutePath}")
        if (!written) dirty = true // still to be written: the next flush or window tries again
        // A scrape writes the whole library every time its upserts go quiet, so what one write costs
        // is worth knowing rather than guessing at.
        if (written) {
            Log.i(
                "AuraPerf",
                "library written: ${snapshot.size} items, ${file.length() / 1024}KB, " +
                    "${elapsedMillis() - startedAt}ms",
            )
        }
        lastWriteMs = elapsedMillis() - startedAt
        lastWriteEndedAt = System.currentTimeMillis()
    }

    private companion object {
        const val TAG = "AuraLibrary"
        /** How long upserts must go quiet before a write. */
        const val QUIET_MS = 1_500L
        /**
         * …and the longest a write may be deferred while they keep coming. A bulk scrape upserts
         * continuously for hours, so this is what actually paces its writes: at 7007 items each one
         * is ~25MB of JSON, and a 20s cap meant re-writing the whole library three times a minute.
         * A minute bounds what a hard kill can lose, and scrapeAll/scanAll flush when they finish.
         */
        const val MAX_COALESCE_MS = 60_000L

        /**
         * How many times its own cost a write must wait before the next one — so writing can never
         * take more than about a [WRITE_BUDGET]th of a long pass. [MAX_COALESCE_MS] still caps the
         * wait, so a slow write cannot defer the next one indefinitely.
         */
        const val WRITE_BUDGET = 6
    }

    override fun flush() {
        runBlocking { saveMutex.withLock { if (dirty) runCatching { persistNow() } } }
    }

    /**
     * Where each id sits in [_items], so finding one is a lookup rather than a walk.
     *
     * A scrape reads an item back and writes it again for every file it finishes, and both did a
     * linear scan by id — at a real shelf's size that is 9,597 walks of a 9,597-element list.
     * Measured on a laptop JVM, the same pass costs 26ms at a thousand items, 77ms at three
     * thousand and 686ms at 9,597: linear until it isn't. ART is several times slower again.
     *
     * Null means "not built yet"; every bulk change drops it and the next lookup rebuilds it once.
     * Nothing outside [publish] and [publishKeepingOrder] may assign [_items], which is what keeps
     * the two from drifting apart.
     */
    /** An id lookup, and the exact list instance it describes. */
    private class Index(val list: List<MediaItem>, val byId: HashMap<String, Int>)

    @Volatile
    private var idIndex: Index? = null

    /**
     * The list changed shape — positions are no longer trustworthy, so the index goes.
     *
     * Nothing outside this and [publishKeepingOrder] may assign [_items], which is what keeps the
     * two from drifting apart; and an index carries the list it was built from, so a reader that
     * raced a writer rebuilds rather than answering from the wrong one.
     */
    private fun publish(list: List<MediaItem>) {
        _items.value = list
        idIndex = null
    }

    /** The list changed in place: same ids, same positions, so the index carries over. */
    private fun publishKeepingOrder(list: List<MediaItem>) {
        val carried = idIndex?.byId
        _items.value = list
        idIndex = carried?.let { Index(list, it) }
    }

    /**
     * Where [id] sits in [list], building the lookup the first time it is asked for.
     *
     * A scrape reads an item back and writes it again for every file it finishes, and both used to
     * walk the list by id — 9,597 walks of a 9,597-element list on a real shelf. Measured on a
     * laptop JVM, that pass costs 26ms at a thousand items, 77ms at three thousand and 686ms at
     * 9,597: linear until it isn't. ART is several times slower again.
     *
     * Lock-free on purpose: the UI reads items by id, and it must not queue behind a bulk write.
     */
    private fun indexOf(id: String, list: List<MediaItem>): Int {
        val cur = idIndex
        val byId = if (cur != null && cur.list === list) {
            cur.byId
        } else {
            HashMap<String, Int>(list.size * 2)
                .also { m -> list.forEachIndexed { i, item -> m[item.id] = i } }
                .also { idIndex = Index(list, it) }
        }
        return byId[id] ?: -1
    }

    override fun all(): List<MediaItem> = _items.value

    override fun get(id: String): MediaItem? {
        val list = _items.value
        return indexOf(id, list).takeIf { it >= 0 }?.let { list[it] }
    }

    override fun upsert(item: MediaItem) { ensureLoaded(); synchronized(lock) {
        val idx = indexOf(item.id, _items.value)
        // The same file as it already stands is no change: a pass that re-saves what it read wrote the
        // whole library (17 MB, 1.7 s on the B3110) every 15 s for minutes with nothing in it different.
        if (idx >= 0 && _items.value[idx] == item) return
        val list = _items.value.toMutableList()
        if (idx >= 0) {
            list[idx] = item
            publishKeepingOrder(list)
        } else {
            // A brand-new id at the head shifts everything below it; discovery adds in bulk.
            list.add(0, item)
            publish(list)
        }
        persist()
    } }

    override fun upsertAll(newItems: List<MediaItem>) { ensureLoaded(); synchronized(lock) {
        if (newItems.isEmpty()) return
        val map = LinkedHashMap<String, MediaItem>()
        _items.value.forEach { map[it.id] = it }
        newItems.forEach { incoming ->
            val existing = map[incoming.id]
            map[incoming.id] = when {
                existing == null -> incoming
                // A user-pinned match (修正匹配) survives re-scan untouched.
                existing.userMatched -> existing
                // Same id ⇒ same file at the same path: its scraped identity (poster, ratings,
                // collectionKey, MATCHED status…) is still valid, so KEEP it. Re-discovery must never
                // reset a matched file back to PENDING — that wiped the whole library on every scan and
                // forced a full multi-hour re-scrape. Only refresh the live-detected projection flags,
                // and what lies beside the file: an nfo written since is what the next pass reads.
                else -> existing.copy(
                    video = if (existing.video.userOverridden || existing.video.fromFile) existing.video else incoming.video,
                    local = incoming.local,
                )
            }
        }
        val merged = map.values.sortedByDescending { it.addedAt }
        // A re-scan of a source nothing has happened to produces exactly what is already there, and
        // writing that out costs 16.9MB and three seconds for no change at all. Comparing 9,597
        // items is element-wise on data classes and takes a few milliseconds.
        if (merged == _items.value) return
        publish(merged)
        persist()
    } }

    override fun replaceAll(items: List<MediaItem>) { ensureLoaded(); synchronized(lock) {
        if (items.isEmpty()) return
        val list = _items.value.toMutableList()
        var added = false
        items.forEach { item ->
            val idx = indexOf(item.id, _items.value)
            if (idx >= 0) list[idx] = item else { list.add(0, item); added = true }
        }
        if (list == _items.value) return
        // Positions only move if something was added; otherwise the index still describes the list.
        if (added) publish(list) else publishKeepingOrder(list)
        persist()
    } }

    override fun updateProgress(id: String, positionMs: Long, durationMs: Long, watched: Boolean) {
        val progress = PlaybackProgress(
            positionMs = positionMs,
            durationMs = durationMs,
            watched = watched || (durationMs > 0 && positionMs >= durationMs * 0.92),
            updatedAt = System.currentTimeMillis(),
        )
        // The library file is not rewritten for a position: it lands in the sidecar within a
        // second, and in the library whenever that is next written for another reason.
        mutate(id, persistLibrary = false) { it.copy(progress = progress) }
        synchronized(lock) {
            recentProgress[id] = ProgressSidecar.Entry(id, progress.positionMs, progress.durationMs, progress.watched, progress.updatedAt)
            while (recentProgress.size > ProgressSidecar.MAX_ENTRIES) recentProgress.remove(recentProgress.keys.first())
        }
        persistProgress()
    }

    override fun mergeProgress(id: String, progress: PlaybackProgress): Boolean {
        val current = get(id) ?: return false
        if (progress.updatedAt <= current.progress.updatedAt) return false
        mutate(id, persistLibrary = false) { it.copy(progress = progress) }
        synchronized(lock) {
            recentProgress[id] = ProgressSidecar.Entry(id, progress.positionMs, progress.durationMs, progress.watched, progress.updatedAt)
            while (recentProgress.size > ProgressSidecar.MAX_ENTRIES) recentProgress.remove(recentProgress.keys.first())
        }
        persistProgress()
        return true
    }

    private fun persistProgress() {
        if (progressSavePending) return
        progressSavePending = true
        saveScope.launch {
            delay(1_000)
            progressSavePending = false
            val json = synchronized(lock) { ProgressSidecar.encode(recentProgress.values) }
            runCatching {
                val tmp = File(progressFile.parentFile, progressFile.name + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(progressFile)) progressFile.writeText(json)
            }.onFailure { Log.e(TAG, "progress persist failed", it) }
        }
    }

    override fun forgetProgress(ids: Collection<String>) { ensureLoaded(); synchronized(lock) {
        val gone = ids.toHashSet()
        val none = PlaybackProgress()
        var changed = false
        val list = _items.value.map { if (it.id in gone && it.progress != none) { changed = true; it.copy(progress = none) } else it }
        val sidecar = gone.count { recentProgress.remove(it) != null }
        if (changed) {
            publishKeepingOrder(list)
            persist()
        }
        if (sidecar > 0) persistProgress()
    } }

    override fun setFavorite(id: String, favorite: Boolean) =
        mutate(id) { it.copy(favorite = favorite) }

    override fun setFavorite(ids: Collection<String>, favorite: Boolean) { ensureLoaded(); synchronized(lock) {
        val idSet = ids.toHashSet()
        publishKeepingOrder(_items.value.map { if (it.id in idSet) it.copy(favorite = favorite) else it })
        persist()
    } }

    override fun setVideoFlags(id: String, flags: VideoFlags) =
        mutate(id) { it.copy(video = flags.copy(userOverridden = true)) }

    override fun learnVideoFlags(id: String, flags: VideoFlags) =
        mutate(id) { if (it.video.userOverridden) it else it.copy(video = flags.copy(userOverridden = false, fromFile = true)) }

    override fun clearVideoFlags(id: String) =
        mutate(id) { it.copy(video = com.therealityteam.videoplayer.data.playback.ProjectionDetector.detect(it.fileName)) }

    override fun setAudioTrackChoice(id: String, trackId: String?) =
        mutate(id) { it.copy(audioTrackId = trackId) }

    override fun setSubtitleTrackChoice(id: String, trackId: String?) =
        mutate(id) { it.copy(subtitleTrackId = trackId) }

    override fun removeBySource(sourceId: String) { ensureLoaded(); synchronized(lock) {
        removeWhere { it.sourceId == sourceId }
    } }

    override fun retainForSource(sourceId: String, keepIds: Set<String>) { ensureLoaded(); synchronized(lock) {
        removeWhere { it.sourceId == sourceId && it.id !in keepIds }
    } }

    override fun remove(id: String) { ensureLoaded(); synchronized(lock) {
        removeWhere { it.id == id }
    } }

    /**
     * Drops what [gone] picks, and writes the library only when that was something: every scan ends by
     * keeping its source's files that are still there — on the B3110 an auto scan that found nothing new
     * rewrote 12.8 MB once per source (1.5 s each) for no change.
     */
    private fun removeWhere(gone: (MediaItem) -> Boolean) {
        val kept = _items.value.filterNot(gone)
        if (kept.size == _items.value.size) return
        publish(kept)
        persist()
    }

    override fun clear() { ensureLoaded(); synchronized(lock) {
        publish(emptyList())
        persist()
    } }

    private fun mutate(id: String, persistLibrary: Boolean = true, transform: (MediaItem) -> MediaItem) { ensureLoaded(); synchronized(lock) {
        val idx = indexOf(id, _items.value)
        if (idx >= 0) {
            val list = _items.value.toMutableList()
            list[idx] = transform(list[idx])
            publishKeepingOrder(list)
            if (persistLibrary) persist()
        }
    } }
}
