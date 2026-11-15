package com.therealityteam.videoplayer.desktop.sync

import com.therealityteam.videoplayer.util.PRODUCT_NAME
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.sync.LibrarySync
import com.therealityteam.videoplayer.data.sync.LocalSyncStore
import com.therealityteam.videoplayer.data.sync.SmbSyncStore
import com.therealityteam.videoplayer.data.sync.SyncDevice
import com.therealityteam.videoplayer.data.sync.SyncKeys
import com.therealityteam.videoplayer.data.sync.SyncCorrection
import com.therealityteam.videoplayer.data.sync.SyncReport
import com.therealityteam.videoplayer.data.sync.SyncStore
import com.therealityteam.videoplayer.desktop.AppGraph
import com.therealityteam.videoplayer.desktop.platform.Host
import com.therealityteam.videoplayer.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Somewhere the `.aura` folder can live. */
sealed interface SyncLocation {
    /** What the settings remember. */
    val id: String
    /** The share's name, or the folder's. */
    val label: String
    /** Where that is: the NAS, the mount point, the folder's path. */
    val detail: String

    fun store(): SyncStore

    /** A share this app reaches itself, with the source's own account. */
    data class Smb(val host: String, val share: String, val username: String?, val password: String?) : SyncLocation {
        override val id = "smb://${host.lowercase()}/$share"
        override val label = share
        override val detail = host
        override fun store() = SmbSyncStore(host, share, LibrarySync.FOLDER, username, password)
        override fun toString() = "Smb($host/$share)"
    }

    /** A share the computer has mounted, holding one of the library's folders. */
    data class Mounted(val mount: NetworkMount) : SyncLocation {
        override val id = "mount:${mount.point}"
        override val label = mount.share
        override val detail = mount.point
        override fun store() = LocalSyncStore(File(mount.point, LibrarySync.FOLDER))
    }

    /** Any folder — one another sync program already carries between computers, say. */
    data class Folder(val path: String) : SyncLocation {
        override val id = "folder:$path"
        override val label = File(path).name.ifEmpty { path }
        override val detail = path
        override fun store() = LocalSyncStore(File(path, LibrarySync.FOLDER))
    }
}

/**
 * Keeps this computer's progress and favourites in step with the user's other devices, through
 * [LibrarySync] — when the user asked for that, and never behind their back otherwise.
 *
 * It does not poll: a NAS whose disks sleep when idle should stay asleep while nobody is watching.
 * It syncs when the app starts, when its window comes back to the front, just before a video
 * opens (so it resumes where another device left it), after one stops, and on quitting.
 */
class SyncService(private val graph: AppGraph) {

    data class Status(
        val syncing: Boolean = false,
        val last: SyncReport? = null,
        /** The last answer to "could this device write", kept across syncs that did not try. */
        val writable: Boolean? = null,
        val location: SyncLocation? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var lastAt = 0L

    private val enabled get() = graph.settings.value.syncEnabled

    /** Sync once the library is read, and again whenever the user turns it on or moves it. */
    fun start() {
        scope.launch {
            graph.media.loaded.first { it }
            graph.settings.settings.map { it.syncEnabled to it.syncLocation }.distinctUntilChanged().collect { (on, _) ->
                if (on) syncNow() else _status.value = Status()
            }
        }
    }

    /** Where the folder could go: the library's shares first, the one holding most of it on top. */
    fun candidates(): List<SyncLocation> {
        val items = graph.media.all()
        val smb = graph.sources.all().filter { it.type == SourceType.SMB }.mapNotNull { src ->
            val parts = src.uri.removePrefix("smb://").removePrefix("//").trim('/').split('/')
            if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) null
            else SyncLocation.Smb(parts[0], parts[1], src.username, src.password)
        }.distinctBy { it.id }
        val mounts = NetworkMounts.list()
        val mounted = mounts.filter { m -> graph.sources.all().any { it.type == SourceType.LOCAL && m.contains(it.uri) } }
            .map { SyncLocation.Mounted(it) }
        fun weight(loc: SyncLocation): Int = when (loc) {
            is SyncLocation.Smb -> items.count { SyncKeys.ofSmb(it.path)?.startsWith(loc.share.lowercase() + "/") == true }
            is SyncLocation.Mounted -> items.count { loc.mount.keyOf(it.path) != null }
            is SyncLocation.Folder -> 0
        }
        return (smb + mounted).sortedByDescending(::weight)
    }

    /** The location the settings name, or the best one when they name none. */
    fun resolve(id: String = graph.settings.value.syncLocation): SyncLocation? {
        if (id.startsWith("folder:")) return SyncLocation.Folder(id.removePrefix("folder:"))
        val all = candidates()
        return if (id.isBlank()) all.firstOrNull() else all.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    /** Sync now, if sync is on. The report, or null when it is off or there is nowhere to sync. */
    suspend fun syncNow(): SyncReport? = mutex.withLock {
        if (!enabled) return null
        val location = resolve() ?: run {
            _status.update { it.copy(location = null, last = null) }
            return null
        }
        _status.update { it.copy(syncing = true, location = location) }
        val synced = runCatching { build(location).sync() }.getOrElse { e ->
            SyncReport(System.currentTimeMillis(), emptyList(), 0, false, 0, e.message ?: e.toString())
        }
        // Matches fixed by hand on another device, filed here too (each needs its entry fetched).
        val adopted = if (synced.error == null) adopt(synced.corrections) else 0
        val report = if (adopted > 0) synced.copy(applied = synced.applied + adopted) else synced
        lastAt = System.currentTimeMillis()
        if (report.error == null) Log.i(TAG, "synced with ${location.id}: ${report.applied} taken, ${report.devices.size} other devices, ${report.bytes} bytes")
        _status.update { it.copy(syncing = false, last = report, writable = report.writable ?: it.writable, location = location) }
        report
    }

    /** Files another device's fixes moved; a fix whose entry cannot be fetched now is offered again next time. */
    private suspend fun adopt(corrections: List<SyncCorrection>): Int =
        corrections.groupBy { Triple(it.source, it.id, it.kind) }.entries.sumOf { (fix, list) ->
            val items = list.flatMap { it.itemIds }.mapNotNull { graph.media.get(it) }
            if (runCatching { graph.scrape.adoptMatch(items, fix.first, fix.second, fix.third) }.getOrDefault(false)) items.size else 0
        }

    /** A sync in the background unless one ran within [minGapMs] — for the window coming to the front. */
    fun nudge(minGapMs: Long = 60_000) {
        if (!enabled || System.currentTimeMillis() - lastAt < minGapMs) return
        scope.launch { syncNow() }
    }

    /**
     * Just before a video opens: take the other devices' latest positions, but never hold the
     * video back for more than [maxWaitMs] — a slow NAS costs the resume point, not the playback.
     */
    suspend fun freshen(maxWaitMs: Long = 1_500) {
        if (!enabled || System.currentTimeMillis() - lastAt < 20_000) return
        val job = scope.async { syncNow() }
        withTimeoutOrNull(maxWaitMs) { job.await() }
    }

    /** The last sync, on the way out: whatever was watched here reaches the others. */
    fun flush(timeoutMs: Long = 4_000) {
        if (!enabled) return
        runBlocking { withTimeoutOrNull(timeoutMs) { syncNow() } }
    }

    /** Delete the folder from where it is, every device's files with it. */
    suspend fun remove(): Result<Unit> = mutex.withLock {
        val location = resolve() ?: return Result.failure(IllegalStateException("no location"))
        runCatching { build(location).remove() }.also { r ->
            r.onSuccess { _status.update { it.copy(last = null, writable = null) } }
        }
    }

    /** What the folder holds now, for the settings page. */
    suspend fun size(): Long? = runCatching { resolve()?.store()?.size("") }.getOrNull()

    private fun build(location: SyncLocation): LibrarySync {
        val s = graph.settings.value
        val mounts = NetworkMounts.list()
        return LibrarySync(
            repo = graph.media,
            store = location.store(),
            device = SyncDevice(s.deviceId, s.deviceName.ifBlank { defaultDeviceName }, platformName, "$PRODUCT_NAME ${Host.appVersion}"),
            stateFile = File(graph.filesDir, "aura/sync/${digest(location.id)}.json"),
            keyOf = { item -> SyncKeys.ofSmb(item.path) ?: NetworkMounts.keyOf(item.path, mounts) },
        )
    }

    companion object {
        private const val TAG = "AuraSync"

        val platformName: String = when {
            Host.isMac -> "macOS"
            Host.isWindows -> "Windows"
            else -> "Linux"
        }

        /** The computer's own name, as the system shows it ("Anna's MacBook Pro"). */
        val defaultDeviceName: String by lazy {
            runCatching {
                if (Host.isMac) {
                    val p = ProcessBuilder("scutil", "--get", "ComputerName").redirectErrorStream(true).start()
                    val name = p.inputStream.bufferedReader().readText().trim()
                    p.waitFor(3, TimeUnit.SECONDS)
                    name.takeIf { p.exitValue() == 0 && it.isNotBlank() }
                } else System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
            }.getOrNull()
                ?: runCatching { java.net.InetAddress.getLocalHost().hostName.substringBefore('.') }.getOrNull()
                ?: platformName
        }

        private fun digest(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}
