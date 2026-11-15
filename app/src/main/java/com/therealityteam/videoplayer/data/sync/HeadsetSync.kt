package com.therealityteam.videoplayer.data.sync

import com.therealityteam.videoplayer.util.PRODUCT_NAME
import android.content.Context
import android.os.Build
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.repository.MediaRepository
import com.therealityteam.videoplayer.data.repository.SettingsRepository
import com.therealityteam.videoplayer.data.repository.SourceRepository
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

/**
 * The headset's half of 同步: this headset's progress, watched state and favourites kept in step
 * with the user's other devices through [LibrarySync]'s `.aura` folder on the NAS — when the user
 * asked for it, and never behind their back otherwise. The desktop's SyncService does the same with
 * the same folder, so a film paused on the sofa resumes at the desk.
 *
 * It does not poll: a NAS whose disks sleep when idle should stay asleep while nobody is watching.
 * It syncs when the app comes to the front, just before a video opens (so it resumes where another
 * device left it), on a pause, every ten minutes of watching, and when the app goes to the back.
 */
class HeadsetSync(
    private val context: Context,
    private val media: MediaRepository,
    private val sources: SourceRepository,
    private val settings: SettingsRepository,
    /** Files another device's hand-made fixes onto this headset's copies; null takes none in. */
    private val engine: com.therealityteam.videoplayer.data.scrape.ScrapeEngine? = null,
) {

    /** A share the headset reaches itself, with its source's own account. */
    data class Location(val host: String, val share: String, val username: String?, val password: String?) {
        val id: String = "smb://${host.lowercase()}/$share"
        override fun toString() = "Location($host/$share)"
    }

    data class Status(
        val syncing: Boolean = false,
        val last: SyncReport? = null,
        /** The last answer to "could this headset write", kept across syncs that did not try. */
        val writable: Boolean? = null,
        val location: Location? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var lastAt = 0L

    private val enabled get() = settings.get().syncEnabled

    /** Sync once the library is read, and again whenever the user turns it on or moves it. */
    fun start() {
        scope.launch {
            media.loaded.first { it }
            settings.settings.map { it.syncEnabled to it.syncLocation }.distinctUntilChanged().collect { (on, _) ->
                if (on) syncNow() else _status.value = Status()
            }
        }
    }

    /** Where the folder could go: the library's shares, the one holding most of it first. */
    fun candidates(): List<Location> {
        val items = media.all()
        return sources.all().filter { it.type == SourceType.SMB }.mapNotNull { src ->
            val parts = src.uri.removePrefix("smb://").removePrefix("//").trim('/').split('/')
            if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) null
            else Location(parts[0], parts[1], src.username, src.password)
        }.distinctBy { it.id }.sortedByDescending { loc ->
            items.count { SyncKeys.ofSmb(it.path)?.startsWith(loc.share.lowercase() + "/") == true }
        }
    }

    /** The location the settings name, or the best one when they name none. */
    fun resolve(id: String = settings.get().syncLocation): Location? {
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
        if (report.error == null) {
            Log.i(TAG, "synced with ${location.id}: ${report.applied} taken, ${report.devices.size} other devices, ${report.bytes} bytes")
        } else {
            Log.w(TAG, "sync with ${location.id} failed: ${report.error}")
        }
        _status.update { it.copy(syncing = false, last = report, writable = report.writable ?: it.writable, location = location) }
        report
    }

    /** Files another device's fixes moved; a fix whose entry cannot be fetched now is offered again next time. */
    private suspend fun adopt(corrections: List<SyncCorrection>): Int {
        val engine = engine ?: return 0
        return corrections.groupBy { Triple(it.source, it.id, it.kind) }.entries.sumOf { (fix, list) ->
            val items = list.flatMap { it.itemIds }.mapNotNull { media.get(it) }
            if (runCatching { engine.adoptMatch(items, fix.first, fix.second, fix.third) }.getOrDefault(false)) items.size else 0
        }
    }

    /** A sync in the background unless one ran within [minGapMs]. */
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

    /** Delete the folder from where it is, every device's files with it. */
    suspend fun remove(): Result<Unit> = mutex.withLock {
        val location = resolve() ?: return Result.failure(IllegalStateException("no location"))
        runCatching { build(location).remove() }.also { r ->
            r.onSuccess { _status.update { it.copy(last = null, writable = null) } }
        }
    }

    /** The name the other devices see when the user has not given one. */
    val defaultDeviceName: String by lazy {
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL.takeIf { it.isNotBlank() }?.let { if (it.contains("pico", ignoreCase = true)) it else "PICO $it" }
            ?: "PICO"
    }

    private fun build(location: Location): LibrarySync {
        val s = settings.get()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return LibrarySync(
            repo = media,
            store = SmbSyncStore(location.host, location.share, LibrarySync.FOLDER, location.username, location.password),
            device = SyncDevice(s.deviceId, s.deviceName.ifBlank { defaultDeviceName }, PLATFORM, "$PRODUCT_NAME ${version ?: ""}".trim()),
            stateFile = File(context.filesDir, "aura/sync/${digest(location.id)}.json"),
        )
    }

    companion object {
        private const val TAG = "AuraSync"

        /** What the desktop shows beside this headset's name (and picks its icon by). */
        const val PLATFORM = "PICO OS"

        private fun digest(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}
