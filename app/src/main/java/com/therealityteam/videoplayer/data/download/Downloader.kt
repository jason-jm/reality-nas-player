package com.therealityteam.videoplayer.data.download

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.playback.LocalStreamServer
import com.therealityteam.videoplayer.data.repository.SourceRepository
import com.therealityteam.videoplayer.data.subtitle.SubtitleEngine
import com.therealityteam.videoplayer.platform.LibraryTaskService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * 下载到本机: moves the bytes the [DownloadStore] asks for, one file at a time, from the share (read
 * through [LocalStreamServer], as the player reads it) or a media server into the headset's own
 * storage. A copy is written to `<name>.part` and continued from where it stopped — a Wi-Fi drop, a
 * closed app — then renamed whole; the subtitles beside it on the share come along, so the copy
 * plays as the original does.
 */
class Downloader(
    private val context: Context,
    val store: DownloadStore,
    private val sources: SourceRepository,
    private val streamServer: LocalStreamServer,
    private val media: com.therealityteam.videoplayer.data.repository.MediaRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var transfer: Job? = null
    @Volatile private var transferring: String? = null

    private val _active = MutableStateFlow<Download?>(null)
    /** The download under way, its bytes as they arrive (about once a second); null when none is. */
    val active: StateFlow<Download?> = _active.asStateFlow()

    private val _finished = MutableStateFlow<List<Download>>(emptyList())
    /**
     * Copies finished and not yet said — kept until the library window says them, so a download that
     * finished with the headset off is told the next time the window opens. See [finishedSaid].
     */
    val finished: StateFlow<List<Download>> = _finished.asStateFlow()

    /**
     * The copy is here, its header a read away: what it says of its picture — a 360°/180° sphere, a 3D
     * packing — the library learns now (SphericalInfo.learnedFlags), so the card is tagged and the film
     * opens as one before it is ever played, the headset off the network.
     */
    private fun learnPicture(itemId: String, file: File) {
        runCatching {
            val item = media.get(itemId) ?: return
            val info = com.therealityteam.videoplayer.data.playback.SphericalMetadata.read(
                com.therealityteam.videoplayer.data.playback.FileByteSource(file),
            )
            info.learnedFlags(item.video)?.takeIf { it != item.video }?.let { learned ->
                media.learnVideoFlags(itemId, learned)
                Log.i(TAG, "${item.fileName}: the library learns $learned")
            }
        }.onFailure { Log.w(TAG, "could not read the picture of ${file.name}: ${it.message}") }
    }

    /** The window told of [said]: they leave [finished]. */
    fun finishedSaid(said: List<Download>) {
        val ids = said.mapTo(HashSet()) { it.itemId }
        _finished.value = _finished.value.filterNot { it.itemId in ids }
    }

    private val _failures = kotlinx.coroutines.flow.MutableSharedFlow<Download>(extraBufferCapacity = 8)
    /** Each download as it stops for good — said where the user is, not only on 已下载. */
    val failures: kotlinx.coroutines.flow.SharedFlow<Download> = _failures

    private val _speed = MutableStateFlow(0L)
    /** Bytes a second of the download under way. */
    val speed: StateFlow<Long> = _speed.asStateFlow()

    private val _waitingForShare = MutableStateFlow(false)
    /** The download under way has lost its share and waits to try again — said so, not its last speed. */
    val waitingForShare: StateFlow<Boolean> = _waitingForShare.asStateFlow()

    /**
     * Set while a film plays over the network: the download is held to [STREAMING_PACE] bytes a
     * second meanwhile — a trickle beside a 4K remux's 10 MB/s, so the film never buffers for it.
     */
    var streaming: Boolean
        get() = _streaming.value
        set(value) {
            if (_streaming.value != value) Log.i(TAG, if (value) "a film streams: downloads slowed" else "downloads at full speed")
            _streaming.value = value
        }
    private val _streaming = MutableStateFlow(false)
    /** Whether downloads are held back for a film streaming beside them — said on 已下载. */
    val slowedForPlayback: StateFlow<Boolean> = _streaming.asStateFlow()

    /** Where the copies live: the app's own movies folder, gone with the app. */
    val dir: File get() = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "downloads")

    /** Whether [item] is somewhere a copy can be made from (a file on the headset already is one). */
    fun canDownload(item: MediaItem): Boolean = com.therealityteam.videoplayer.data.playback.LocalStreamServer.serves(item.path) || item.path.startsWith("http")

    /** Queues [items] in their order; returns how many were new to the list. */
    fun add(items: List<MediaItem>): Int {
        val now = System.currentTimeMillis()
        val added = store.enqueue(
            items.filter(::canDownload).mapIndexed { i, item ->
                Download(
                    itemId = item.id,
                    fileName = item.fileName,
                    remotePath = item.path,
                    localPath = DownloadPlan.localFile(dir, item.id, item.fileName).path,
                    addedAt = now + i,
                )
            },
        )
        Log.i(TAG, "queued $added of ${items.size}")
        kick()
        return added
    }

    fun pause(itemId: String) {
        store.update(itemId) { if (it.isDone) it else it.copy(state = Download.State.PAUSED) }
        if (transferring == itemId) transfer?.cancel()
    }

    /** Back in the queue — a paused one, or a failed one tried again. */
    fun resume(itemId: String) {
        store.update(itemId) { if (it.isDone) it else it.copy(state = Download.State.QUEUED, error = null) }
        kick()
    }

    /** Off the list, and its copy (or the part of one) off the headset. */
    fun remove(itemId: String) {
        val gone = store.remove(itemId) ?: return
        if (transferring == itemId) transfer?.cancel()
        scope.launch {
            transfer?.takeIf { transferring == itemId }?.join()
            // The item's own folder, only ever one inside [dir] — never anything above it.
            val folder = File(gone.localPath).parentFile
            if (folder != null && folder.parentFile?.canonicalPath == dir.canonicalPath) folder.deleteRecursively()
        }
    }

    /** Starts working through the queue, if it is not already; called at launch to carry on. */
    fun kick() {
        if (store.next() == null) return
        synchronized(this) {
            if (loop?.isActive == true) return
            LibraryTaskService.start(context)
            loop = scope.launch {
                while (true) {
                    val next = store.next() ?: break
                    val job = launch { run(next) }
                    transferring = next.itemId
                    transfer = job
                    job.join()
                    transferring = null
                }
                _active.value = null
                _speed.value = 0
            }
        }
    }

    private suspend fun run(d: Download) {
        store.update(d.itemId) { it.copy(state = Download.State.RUNNING, error = null) }
        _active.value = store.get(d.itemId)
        val started = android.os.SystemClock.elapsedRealtime()
        val from = File(d.partPath).length()
        val key = "download:" + d.remotePath
        try {
            var attempt = 0
            var movedAt = android.os.SystemClock.elapsedRealtime()
            while (true) {
                val had = File(d.partPath).length()
                try {
                    val url = urlOf(d.remotePath, key) ?: throw Failure(ERROR_UNREACHABLE)
                    copy(d, url)
                    break
                } catch (e: IOException) {
                    // A dropped connection, a NAS waking up, the Wi-Fi gone a while: tried again from where it
                    // stopped, and patiently — given up only after [PATIENCE_MS] without a byte more. It used to be
                    // five tries in all, across the whole copy: a 700 MB download through two short blips was given
                    // up 15 s into the second, and stayed so when the NAS came back.
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (File(d.partPath).length() > had) { attempt = 0; movedAt = now }
                    if (now - movedAt > PATIENCE_MS) throw Failure(ERROR_UNREACHABLE, e)
                    val wait = minOf(++attempt * 3_000L, MAX_WAIT_MS)
                    Log.w(TAG, "${d.fileName}: ${e.message}; again in ${wait / 1000}s")
                    _waitingForShare.value = true
                    _speed.value = 0
                    delay(wait)
                }
            }
            val part = File(d.partPath)
            val whole = File(d.localPath)
            whole.delete()
            if (!part.renameTo(whole)) throw Failure(ERROR_DISK)
            runCatching { copySidecars(d) }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "subtitles of ${d.fileName}: ${it.message}") }
            store.update(d.itemId) { it.copy(state = Download.State.DONE, doneBytes = whole.length(), totalBytes = whole.length()) }
            learnPicture(d.itemId, whole)
            store.get(d.itemId)?.let { done -> _finished.value = _finished.value + done }
            val secs = (android.os.SystemClock.elapsedRealtime() - started) / 1000.0
            Log.i(TAG, "done: ${d.fileName} (${whole.length()} bytes, %.1f MB/s over %.0f s)".format((whole.length() - from) / 1e6 / secs.coerceAtLeast(0.001), secs))
        } catch (e: CancellationException) {
            // Paused or removed: the state was set by whoever stopped it.
            throw e
        } catch (e: Failure) {
            Log.w(TAG, "failed: ${d.fileName}: ${e.code} ${e.cause?.message.orEmpty()}")
            store.update(d.itemId) { it.copy(state = Download.State.FAILED, error = e.code) }
            store.get(d.itemId)?.let { _failures.tryEmit(it) }
        } catch (e: Exception) {
            Log.w(TAG, "failed: ${d.fileName}", e)
            store.update(d.itemId) { it.copy(state = Download.State.FAILED, error = ERROR_UNREACHABLE) }
            store.get(d.itemId)?.let { _failures.tryEmit(it) }
        } finally {
            _waitingForShare.value = false
            if (com.therealityteam.videoplayer.data.playback.LocalStreamServer.serves(d.remotePath)) streamServer.unregister(key)
        }
    }

    /** Appends to the `.part` from where it ends, until the file is whole. */
    private suspend fun copy(d: Download, url: String) {
        val part = File(d.partPath)
        part.parentFile?.mkdirs()
        val have = part.length()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            val append: Boolean
            val total: Long
            when (code) {
                206 -> {
                    append = true
                    total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                }
                200 -> {
                    // No ranges (or the first request): from the start.
                    append = false
                    total = conn.contentLengthLong
                }
                416 -> return // the part is already whole
                // Not there any more: no wait would bring it back.
                404, 410 -> throw Failure(ERROR_UNREACHABLE, IOException("HTTP $code"))
                else -> throw IOException("HTTP $code")
            }
            // The file on the share is not the one the part began from — replaced by another release
            // while the copy waited: its tail would be grafted onto the other's head. Begin again.
            val known = store.get(d.itemId)?.totalBytes ?: 0L
            if (append && known > 0 && total > 0 && total != known) {
                Log.w(TAG, "${d.fileName} changed on the share ($known → $total bytes): starting over")
                part.delete()
                store.update(d.itemId) { it.copy(totalBytes = 0, doneBytes = 0) }
                throw IOException("source changed")
            }
            val from = if (append) have else 0L
            if (total > 0 && !DownloadPlan.fits(total - from, freeBytes())) {
                Log.w(TAG, "${d.fileName}: ${(total - from) shr 20} MB to come, ${freeBytes() shr 20} MB free (${DownloadPlan.RESERVE_BYTES shr 20} MB kept)")
                throw Failure(ERROR_NO_ROOM)
            }
            store.update(d.itemId) { it.copy(totalBytes = total.coerceAtLeast(0), doneBytes = from) }
            _waitingForShare.value = false
            var done = from
            var lastReport = System.currentTimeMillis()
            var lastDone = done
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        // The headset's side failing is not the share's: no retry would help, and
                        // 连不上共享 would send somebody to look at the NAS for a full disk here.
                        try {
                            out.write(buf, 0, n)
                        } catch (e: IOException) {
                            throw Failure(if (DownloadPlan.isDiskFull(e.message)) ERROR_NO_ROOM else ERROR_DISK, e)
                        }
                        done += n
                        if (streaming) pace(n)
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 1_000) {
                            _speed.value = (done - lastDone) * 1000 / (now - lastReport)
                            lastReport = now
                            lastDone = done
                            store.update(d.itemId) { it.copy(doneBytes = done) }
                            _active.value = store.get(d.itemId)
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("short: $done of $total")
        } finally {
            conn.disconnect()
        }
    }

    /** Holds the copy to [STREAMING_PACE] while a film streams: [n] bytes take their share of a second. */
    private suspend fun pace(n: Int) = delay(n * 1000L / STREAMING_PACE)

    /** The subtitles beside the file on its share, kept beside the copy under the copy's own rule for names. */
    private fun copySidecars(d: Download) {
        if (!com.therealityteam.videoplayer.data.playback.LocalStreamServer.serves(d.remotePath)) return
        val src = sourceOf(d.remotePath)
        val key = "download:" + d.remotePath
        streamServer.register(d.remotePath, src?.username, src?.password, key) ?: return
        try {
            val names = streamServer.siblings(key)
            val folder = d.remotePath.substringBeforeLast('/')
            // Beside the file, and in its Subs/ (a fansub's sub/): the same choice the player makes.
            // Picture ones too: a VobSub's .idx comes with its .sub, for mpv to draw on the copy.
            val beside = SubtitleEngine.matchSidecars(d.fileName, names, imageFormats = true).map { it.fileName to it.fileName }
            val subFolder = com.therealityteam.videoplayer.data.subtitle.SubtitleFolders.folderIn(names)
            val inFolder = subFolder?.let { f -> runCatching { streamServer.listBeside(key, f) }.getOrDefault(emptyList()) }.orEmpty()
            val isFeature = com.therealityteam.videoplayer.ui.library.EpisodeNaming.parse(
                com.therealityteam.videoplayer.data.model.MediaItem(d.itemId, "", d.remotePath, d.fileName, title = d.fileName),
            ).cat == com.therealityteam.videoplayer.ui.library.EpisodeNaming.Cat.FEATURE
            val fromFolder = subFolder?.let { f ->
                com.therealityteam.videoplayer.data.subtitle.SubtitleFolders.match(d.fileName, inFolder, isFeature, imageFormats = true)
                    .map { "$f/${it.fileName}" to it.fileName }
            }.orEmpty()
            val subs = (beside + fromFolder).distinctBy { it.second }
            if (subs.isNotEmpty()) Log.i(TAG, "${subs.size} subtitle file(s) for ${d.fileName}")
            val stem = d.fileName.substringBeforeLast('.')
            // Each to a file by its own name, streamed: a VobSub's pictures run to tens of MB.
            fun copy(remote: String, to: java.io.File) {
                val subKey = "download:$remote"
                val url = streamServer.register(remote, src?.username, src?.password, subKey) ?: return
                try {
                    val part = java.io.File(to.path + ".part")
                    try {
                        URL(url).openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                    } catch (e: IOException) {
                        part.delete() // cut off: no half a subtitle beside the copy
                        throw e
                    }
                    if (!part.renameTo(to)) part.delete()
                } finally {
                    streamServer.unregister(subKey)
                }
            }
            subs.forEach { (relative, name) ->
                // One from Subs/ ("English.srt") takes the video's name before its own, so the copy's
                // lookup — names that start with the video's — finds it beside the copy.
                val local = if (name.startsWith(stem)) name else "$stem.$name"
                val file = DownloadPlan.localFile(dir, d.itemId, local, videoName = d.fileName)
                copy("$folder/$relative", file)
                // An .idx is the index of the pictures in the .sub beside it: that comes too, by the
                // copy's own name with .sub for .idx — the name its reader asks for.
                if (name.endsWith(".idx", ignoreCase = true) && file.isFile) {
                    val wanted = name.dropLast(4) + ".sub"
                    val pictures = if ('/' in relative) inFolder.firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { relative.substringBeforeLast('/') + "/" + it }
                    else names.firstOrNull { it.equals(wanted, ignoreCase = true) }
                    pictures?.let { copy("$folder/$it", java.io.File(file.path.dropLast(4) + ".sub")) }
                }
            }
            // …and its 外挂音轨 (a dub's .mka beside it), so the copy plays in the language it was watched in.
            val dubs = com.therealityteam.videoplayer.data.playback.ExternalAudio.match(d.fileName, names)
            if (dubs.isNotEmpty()) Log.i(TAG, "${dubs.size} sound file(s) for ${d.fileName}")
            dubs.forEach { a -> copy("$folder/${a.fileName}", DownloadPlan.localFile(dir, d.itemId, a.fileName, videoName = d.fileName)) }
        } finally {
            streamServer.unregister(key)
        }
    }

    /** An http URL the file can be read from: the share's through the loopback proxy, a server's signed. */
    private fun urlOf(path: String, key: String): String? {
        val src = sourceOf(path)
        if (com.therealityteam.videoplayer.data.playback.LocalStreamServer.serves(path)) return streamServer.register(path, src?.username, src?.password, key)
        val token = src?.token
        if (token.isNullOrBlank()) return path
        return when (src.type) {
            SourceType.JELLYFIN, SourceType.EMBY -> path + "&api_key=" + java.net.URLEncoder.encode(token, "UTF-8")
            SourceType.PLEX -> com.therealityteam.videoplayer.data.source.PlexClient.signed(path, token)
            else -> path
        }
    }

    private fun sourceOf(path: String) =
        sources.all().filter { path.startsWith(it.uri.trimEnd('/') + "/") || path == it.uri }.maxByOrNull { it.uri.length }

    private fun freeBytes(): Long = runCatching { dir.mkdirs(); StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    private class Failure(val code: String, cause: Throwable? = null) : Exception(code, cause)

    companion object {
        private const val TAG = "AuraDownload"
        /** How long a download waits for its share with nothing coming before it is given up: 10 minutes. */
        private const val PATIENCE_MS = 10 * 60_000L
        /** The longest wait between tries (3, 6, 9… s up to this). */
        private const val MAX_WAIT_MS = 30_000L
        /** How fast a download goes while a film streams beside it: 2 MB/s. */
        private const val STREAMING_PACE = 2L * 1024 * 1024
        const val ERROR_NO_ROOM = "no_room"
        const val ERROR_UNREACHABLE = "unreachable"
        const val ERROR_DISK = "disk"
    }
}
