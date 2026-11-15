package com.therealityteam.videoplayer.data.sync

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.repository.MediaRepository
import com.therealityteam.videoplayer.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** This device, as the others will see it in the sync folder. */
data class SyncDevice(val id: String, val name: String, val platform: String, val app: String)

/** Another device the sync folder knows, and when it last wrote. */
data class SeenDevice(val id: String, val name: String, val platform: String, val lastSeen: Long)

/** What one sync did. */
data class SyncReport(
    val at: Long,
    val devices: List<SeenDevice>,
    /** Positions and favourites taken from other devices. */
    val applied: Int,
    /**
     * Whether this device could write its own files — false on a read-only share, null when there
     * was nothing new to write and so nothing was tried.
     */
    val writable: Boolean?,
    val bytes: Long,
    val error: String? = null,
    /**
     * Matches another device fixed by hand (修正匹配) that this one does not have yet. Adopting one
     * fetches its entry from the database, which is the platform's to do through the scrape engine;
     * until it is adopted, every sync offers it again.
     */
    val corrections: List<SyncCorrection> = emptyList(),
)

/**
 * A match fixed by hand on another device, for this one to adopt: [itemIds] are this device's
 * files under that key, [source] `tmdb` or `anilist`, [id] the entry there, [kind] MOVIE or SHOW.
 */
data class SyncCorrection(val itemIds: List<String>, val source: String, val id: Int, val kind: MediaKind)

/**
 * How a file is known in the sync folder: the share it lives on and its path inside that share,
 * lower-cased — `smb://192.168.1.10/video/Films/Dune.mkv` on the headset, `smb://nas.local/video/…`
 * on a laptop and `/Volumes/video/Films/Dune.mkv` on a Mac that mounted the share are all
 * `video/films/dune.mkv`. The host is left out on purpose: one device reaches the NAS by address,
 * another by name. Case is folded because SMB, APFS and NTFS all fold it.
 */
object SyncKeys {
    /** `smb://host/share/rest` → `share/rest`; null for anything else. */
    fun ofSmb(path: String): String? {
        if (!path.startsWith("smb://", ignoreCase = true)) return null
        val parts = path.substring(6).replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        return of(parts[1], parts.drop(2).joinToString("/"))
    }

    /** The key of [rest] (a `/` or `\\` path) inside [share]. */
    fun of(share: String, rest: String): String? {
        val tail = rest.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (share.isBlank() || tail.isEmpty()) return null
        return Normalizer.normalize((listOf(share) + tail).joinToString("/"), Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }
}

/**
 * "The NAS is the server": progress and favourites shared between every Aura that reads the same
 * library, through a hidden `.aura` folder on the NAS — no account, no server, nothing leaves the
 * user's network. Off unless the user turns it on.
 *
 * Files are matched by [SyncKeys]. Each device writes only its own files (`devices/<id>/…`), whole,
 * through a rename, so no two writers ever touch one file and no reader sees half a one; readers
 * merge by time — the newest position of a file, the newest favourite mark, wins.
 *
 * Only files every device could reach are written out: those on a share. Nothing played from this
 * computer's own disk is listed on the NAS for the rest of the household to read.
 *
 * @param store the `.aura` folder.
 * @param keyOf a file's key, or null to keep it out of sync.
 * @param stateFile where this device remembers when it last changed each favourite (the model
 *   keeps no time for them, and "newest wins" needs one).
 */
class LibrarySync(
    private val repo: MediaRepository,
    private val store: SyncStore,
    private val device: SyncDevice,
    private val stateFile: File,
    private val keyOf: (MediaItem) -> String? = { SyncKeys.ofSmb(it.path) },
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Read every device's files, take what is newer, then write this device's own. */
    fun sync(): SyncReport {
        val at = now()
        return runCatching {
            store.batch {
                val local = repo.all().mapNotNull { item -> keyOf(item)?.let { it to item } }
                    .groupBy({ it.first }, { it.second })
                val state = loadState()
                stampLocalChanges(local, state, at)
                val (seen, applied) = pull(local, state)
                val corrections = offeredFixes(local, state)
                val writable = runCatching { push(local, state, at) }
                    .onFailure { Log.w(TAG, "sync folder is not writable: ${it.message}") }
                    .let { r -> if (r.isFailure) false else r.getOrNull() }
                saveState(state)
                SyncReport(at, seen, applied, writable, runCatching { store.size("") }.getOrDefault(0L), corrections = corrections)
            }
        }.getOrElse { e ->
            Log.w(TAG, "sync failed", e)
            SyncReport(at, emptyList(), 0, writable = false, bytes = 0L, error = e.message ?: e.toString())
        }
    }

    /** Take the newest position and favourite mark of every file from the other devices. */
    private fun pull(local: Map<String, List<MediaItem>>, state: State): Pair<List<SeenDevice>, Int> {
        val seen = mutableListOf<SeenDevice>()
        val bestProgress = HashMap<String, PlaybackProgress>()
        val bestFavorite = HashMap<String, Mark>()
        bestFix.clear()
        val dirs = store.list(DEVICES).filter { it.endsWith("/") }.map { it.trimEnd('/') }
        // Our own files gone (the folder was removed from another device): write them all again.
        if (device.id !in dirs) state.written.clear()
        for (dir in dirs) {
            if (dir == device.id) continue
            store.read("$DEVICES/$dir/device.json")?.let { bytes ->
                runCatching {
                    val o = JSONObject(String(bytes, Charsets.UTF_8))
                    seen += SeenDevice(dir, o.optString("name", dir), o.optString("platform"), o.optLong("at"))
                }
            }
            store.read("$DEVICES/$dir/progress.json")?.let { bytes ->
                runCatching {
                    val items = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("items") ?: JSONArray()
                    for (i in 0 until items.length()) {
                        val e = items.optJSONObject(i) ?: continue
                        val key = e.optString("k").ifEmpty { continue }
                        val p = PlaybackProgress(e.optLong("p"), e.optLong("d"), e.optBoolean("w"), e.optLong("t"))
                        if (p.updatedAt > (bestProgress[key]?.updatedAt ?: 0L)) bestProgress[key] = p
                    }
                }
            }
            store.read("$DEVICES/$dir/favorites.json")?.let { bytes ->
                runCatching {
                    val items = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("items") ?: JSONArray()
                    for (i in 0 until items.length()) {
                        val e = items.optJSONObject(i) ?: continue
                        val key = e.optString("k").ifEmpty { continue }
                        val m = Mark(e.optBoolean("f"), e.optLong("t"))
                        if (m.at > (bestFavorite[key]?.at ?: 0L)) bestFavorite[key] = m
                    }
                }
            }
            store.read("$DEVICES/$dir/corrections.json")?.let { bytes ->
                runCatching {
                    val items = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("items") ?: JSONArray()
                    for (i in 0 until items.length()) {
                        val e = items.optJSONObject(i) ?: continue
                        val key = e.optString("k").ifEmpty { continue }
                        val source = e.optString("s").takeIf { it == "tmdb" || it == "anilist" } ?: continue
                        val kind = if (e.optString("kind") == "MOVIE") MediaKind.MOVIE else MediaKind.SHOW
                        val f = Fix(source, e.optInt("id").takeIf { it > 0 } ?: continue, kind, e.optLong("t"))
                        if (f.at > (bestFix[key]?.at ?: 0L)) bestFix[key] = f
                    }
                }
            }
        }
        var applied = 0
        bestProgress.forEach { (key, p) ->
            local[key]?.forEach { item -> if (repo.mergeProgress(item.id, p)) applied++ }
        }
        bestFavorite.forEach { (key, m) ->
            val mine = state.favorites[key]
            if (m.at <= (mine?.at ?: 0L)) return@forEach
            local[key]?.forEach { item ->
                if (item.favorite != m.favorite) {
                    repo.setFavorite(item.id, m.favorite)
                    applied++
                }
            }
            // Adopted: this device now holds that mark, as of when it was made.
            state.favorites[key] = m
        }
        return seen.sortedByDescending { it.lastSeen } to applied
    }

    /** The newest fix of each file the other devices hold, as the last [pull] read them. */
    private val bestFix = HashMap<String, Fix>()

    /**
     * The fixes another device made later than this one's own, where this device's files are not
     * already filed that way. Not adopted here: the files change only once the platform has fetched
     * the entry, and the next sync then holds the fix as this device's own.
     */
    private fun offeredFixes(local: Map<String, List<MediaItem>>, state: State): List<SyncCorrection> =
        bestFix.mapNotNull { (key, f) ->
            if (f.at <= (state.corrections[key]?.at ?: 0L)) return@mapNotNull null
            val items = local[key] ?: return@mapNotNull null
            if (fixOf(items)?.sameAs(f) == true) {
                state.corrections[key] = f // filed that way already, by hand: hold it as of when it was made
                return@mapNotNull null
            }
            SyncCorrection(items.map { it.id }, f.source, f.id, f.kind)
        }.sortedBy { it.itemIds.first() }

    /**
     * A favourite marked or unmarked here since the last sync is stamped with this sync's time —
     * before anything is read, so a change made here is not undone by an older one from elsewhere.
     * A match fixed by hand here is stamped the same way.
     */
    private fun stampLocalChanges(local: Map<String, List<MediaItem>>, state: State, at: Long) {
        local.forEach { (key, items) ->
            val fav = items.any { it.favorite }
            val mine = state.favorites[key]
            if (mine == null && fav || mine != null && mine.favorite != fav) state.favorites[key] = Mark(fav, at)
            val fix = fixOf(items)
            val held = state.corrections[key]
            if (fix != null && (held == null || !held.sameAs(fix))) state.corrections[key] = fix.copy(at = at)
        }
    }

    /** What a file was filed as by hand, if it was: its AniList entry, or its TMDB one. */
    private fun fixOf(items: List<MediaItem>): Fix? {
        val item = items.firstOrNull { it.userMatched } ?: return null
        val kind = if (item.kind == MediaKind.MOVIE) MediaKind.MOVIE else MediaKind.SHOW
        item.collectionKey?.takeIf { it.startsWith("anilist:") }?.removePrefix("anilist:")?.toIntOrNull()
            ?.let { return Fix("anilist", it, kind, 0L) }
        return item.tmdbId?.let { Fix("tmdb", it, kind, 0L) }
    }

    /**
     * Write this device's files — only those whose content changed, so a sync with nothing new
     * leaves the NAS alone (its disks may be asleep). Who this device is goes along with any change,
     * or once a day, so the others can tell when it was last about. True if anything was written.
     */
    private fun push(local: Map<String, List<MediaItem>>, state: State, at: Long): Boolean? {
        // Every position this device has a time for — a reset to "not watched" included, or
        // the other devices would never hear of it.
        val progress = local.mapNotNull { (key, items) ->
            val p = items.map { it.progress }.maxByOrNull { it.updatedAt } ?: return@mapNotNull null
            if (p.updatedAt <= 0L) return@mapNotNull null
            key to p
        }.sortedWith(compareByDescending<Pair<String, PlaybackProgress>> { it.second.updatedAt }.thenBy { it.first })
            .take(MAX_PROGRESS)
        val files = linkedMapOf(
            "progress.json" to JSONObject().put("v", 1).put("items", JSONArray().apply {
                progress.forEach { (k, p) ->
                    put(JSONObject().put("k", k).put("p", p.positionMs).put("d", p.durationMs).put("w", p.watched).put("t", p.updatedAt))
                }
            }).toString().toByteArray(),
            "favorites.json" to JSONObject().put("v", 1).put("items", JSONArray().apply {
                state.favorites.entries.sortedBy { it.key }.forEach { (k, m) ->
                    put(JSONObject().put("k", k).put("f", m.favorite).put("t", m.at))
                }
            }).toString().toByteArray(),
        )
        // Hand-made fixes only from a device that has made or taken one: most never do, and their
        // folder stays the three files it always was.
        if (state.corrections.isNotEmpty() || "corrections.json" in state.written) {
            files["corrections.json"] = JSONObject().put("v", 1).put("items", JSONArray().apply {
                state.corrections.entries.sortedBy { it.key }.forEach { (k, f) ->
                    put(JSONObject().put("k", k).put("s", f.source).put("id", f.id).put("kind", f.kind.name).put("t", f.at))
                }
            }).toString().toByteArray()
        }
        val identity = listOf(device.name, device.platform, device.app).joinToString("\u0000")
        val changed = files.filter { (name, bytes) -> state.written[name] != digest(bytes) }
        val identityDue = state.written["device.json"] != digest(identity.toByteArray()) || at - state.deviceAt > DAY_MS
        if (changed.isEmpty() && !identityDue) return null
        ensureFolder()
        val dir = "$DEVICES/${device.id}"
        changed.forEach { (name, bytes) ->
            store.writeAtomic("$dir/$name", bytes)
            state.written[name] = digest(bytes)
        }
        store.writeAtomic(
            "$dir/device.json",
            JSONObject().put("v", 1).put("id", device.id).put("name", device.name)
                .put("platform", device.platform).put("app", device.app).put("at", at).toString().toByteArray(),
        )
        state.written["device.json"] = digest(identity.toByteArray())
        state.deviceAt = at
        return true
    }

    /** The folder, its read-me, hidden where the share can hide it. */
    private fun ensureFolder() {
        if (store.read(README) != null) return
        store.mkdirs("")
        store.writeAtomic(README, README_TEXT.toByteArray())
        store.hide("")
    }

    /** Delete the whole sync folder from the NAS, and what this device remembered of it. */
    fun remove() {
        store.deleteTree("")
        stateFile.delete()
    }

    // ---- What this device remembers between syncs ---------------------------------------------

    private data class Mark(val favorite: Boolean, val at: Long)

    /** A match fixed by hand: the entry, and when this device made it or took it in. */
    private data class Fix(val source: String, val id: Int, val kind: MediaKind, val at: Long) {
        fun sameAs(other: Fix) = source == other.source && id == other.id
    }

    /**
     * When each favourite was last changed (the model keeps no time for them, and "newest wins"
     * needs one), and what was last written, so an unchanged file is not written again.
     */
    private class State(
        val favorites: MutableMap<String, Mark> = HashMap(),
        val written: MutableMap<String, String> = HashMap(),
        var deviceAt: Long = 0L,
        val corrections: MutableMap<String, Fix> = HashMap(),
    )

    private fun loadState(): State = runCatching {
        val o = JSONObject(stateFile.readText())
        val fav = o.optJSONObject("favorites") ?: JSONObject()
        val written = o.optJSONObject("written") ?: JSONObject()
        val fixes = o.optJSONObject("corrections") ?: JSONObject()
        State(
            fav.keySet().associateWithTo(HashMap()) { k ->
                val m = fav.getJSONObject(k)
                Mark(m.optBoolean("f"), m.optLong("t"))
            },
            written.keySet().associateWithTo(HashMap()) { written.getString(it) },
            o.optLong("deviceAt"),
            fixes.keySet().associateWithTo(HashMap()) { k ->
                val f = fixes.getJSONObject(k)
                Fix(f.optString("s"), f.optInt("id"), if (f.optString("kind") == "MOVIE") MediaKind.MOVIE else MediaKind.SHOW, f.optLong("t"))
            },
        )
    }.getOrDefault(State())

    private fun saveState(state: State) {
        runCatching {
            val fav = JSONObject()
            state.favorites.forEach { (k, m) -> fav.put(k, JSONObject().put("f", m.favorite).put("t", m.at)) }
            val written = JSONObject()
            state.written.forEach { (k, v) -> written.put(k, v) }
            val fixes = JSONObject()
            state.corrections.forEach { (k, f) ->
                fixes.put(k, JSONObject().put("s", f.source).put("id", f.id).put("kind", f.kind.name).put("t", f.at))
            }
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(
                JSONObject().put("favorites", fav).put("written", written).put("deviceAt", state.deviceAt)
                    .put("corrections", fixes).toString(),
            )
            if (!tmp.renameTo(stateFile)) { stateFile.writeText(tmp.readText()); tmp.delete() }
        }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "AuraSync"
        const val FOLDER = ".aura"
        private const val DEVICES = "devices"
        private const val README = "README.txt"
        private const val MAX_PROGRESS = 5_000
        private const val DAY_MS = 24L * 60 * 60 * 1000

        val README_TEXT = """
            This folder belongs to Aura, the video player. It lets the Aura apps on your devices share
            where you are in each video, what you have marked as a favourite and which titles you
            corrected by hand — nothing else.
            It holds a few small files per device, never any video, and is safe to delete; Aura's
            settings have a button that removes it.

            这个文件夹属于视频播放器 Aura，用来在你的各台设备之间同步每个视频的播放进度、收藏和手动修正的匹配，
            不存放任何视频。它只有几个很小的文件，可以放心删除；Aura 的设置里也有删除它的按钮。
        """.trimIndent() + "\n"
    }
}
