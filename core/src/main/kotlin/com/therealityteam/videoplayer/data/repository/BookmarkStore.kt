package com.therealityteam.videoplayer.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 书签: moments in a file the viewer wants to come back to, by file id.
 *
 * Kept beside the library rather than in it, like [ProgressSidecar] and for the same reason: the
 * library is one large document, and a bookmark is a one-line change that should be on disk at once.
 * Small by construction — a file keeps its [MAX_PER_FILE] latest bookmarks, the store its
 * [MAX_FILES] most recently marked files.
 */
class BookmarkStore(filesDir: File) {

    data class Bookmark(val positionMs: Long, val createdAt: Long)

    private val file = File(filesDir, "aura/bookmarks.json")
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val _all = MutableStateFlow(runCatching { if (file.exists()) decode(file.readText()) else emptyMap() }.getOrDefault(emptyMap()))

    /** Every file's bookmarks, each list in playback order. */
    val all: StateFlow<Map<String, List<Bookmark>>> = _all

    fun of(fileId: String): List<Bookmark> = _all.value[fileId].orEmpty()

    /**
     * Mark [positionMs] in [fileId]. A mark within [NEAR_MS] of one already there is the same moment
     * pressed twice, and is not added again; returns whether a mark was added.
     */
    fun add(fileId: String, positionMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        val at = positionMs.coerceAtLeast(0L)
        val existing = of(fileId)
        if (existing.any { kotlin.math.abs(it.positionMs - at) < NEAR_MS }) return false
        val kept = (existing + Bookmark(at, now)).sortedByDescending { it.createdAt }.take(MAX_PER_FILE).sortedBy { it.positionMs }
        update { it + (fileId to kept) }
        return true
    }

    /** Every mark in the file gone (a test's, undone). */
    fun forget(fileId: String) {
        if (fileId in _all.value) update { it - fileId }
    }

    fun remove(fileId: String, positionMs: Long) {
        val left = of(fileId).filterNot { it.positionMs == positionMs }
        update { if (left.isEmpty()) it - fileId else it + (fileId to left) }
    }

    /** Marks carried to their files' new ids (a share added again); marks already under a new id stay. */
    fun renamed(ids: Map<String, String>) {
        val moved = _all.value.keys.filter { id -> ids[id]?.let { it !in _all.value } == true }
        if (moved.isEmpty()) return
        update { all -> all - moved.toSet() + moved.associate { ids.getValue(it) to all.getValue(it) } }
    }

    private fun update(change: (Map<String, List<Bookmark>>) -> Map<String, List<Bookmark>>) {
        _all.value = change(_all.value)
        writes.launch {
            writeLock.withLock {
                // The state as it is now, not as it was when this write was asked for: the writes of
                // marks pressed in quick succession can reach the lock in any order, and one carrying
                // an older picture used to land last and lose the newest mark.
                val latest = _all.value
                runCatching {
                    file.parentFile?.mkdirs()
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(encode(latest))
                    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                }
            }
        }
    }

    companion object {
        const val MAX_PER_FILE = 50
        const val MAX_FILES = 500
        /** Two presses this close together mark one moment. */
        const val NEAR_MS = 3_000L

        fun encode(all: Map<String, List<Bookmark>>): String {
            val arr = JSONArray()
            all.entries
                .sortedByDescending { (_, marks) -> marks.maxOfOrNull { it.createdAt } ?: 0L }
                .take(MAX_FILES)
                .forEach { (id, marks) ->
                    val list = JSONArray()
                    marks.forEach { list.put(JSONObject().put("pos", it.positionMs).put("at", it.createdAt)) }
                    arr.put(JSONObject().put("id", id).put("marks", list))
                }
            return arr.toString()
        }

        fun decode(json: String): Map<String, List<Bookmark>> = runCatching {
            val arr = JSONArray(json)
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val list = o.optJSONArray("marks") ?: continue
                    val marks = (0 until list.length()).mapNotNull { j ->
                        list.optJSONObject(j)?.let { Bookmark(it.optLong("pos"), it.optLong("at")) }
                    }.sortedBy { it.positionMs }
                    if (marks.isNotEmpty()) put(id, marks)
                }
            }
        }.getOrDefault(emptyMap())
    }
}
