package com.therealityteam.videoplayer.data.download

import com.therealityteam.videoplayer.util.DurableFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 下载到本机: a file copied from its share onto the headset, for when the headset leaves the house and
 * the NAS stays behind. One entry per library item; [localPath] is where the copy lives once whole.
 */
data class Download(
    val itemId: String,
    val fileName: String,
    /** The library item's own path (`smb://…`), what the copy is read from. */
    val remotePath: String,
    val localPath: String,
    val totalBytes: Long = 0L,
    val doneBytes: Long = 0L,
    val state: State = State.QUEUED,
    val addedAt: Long = 0L,
    /** Why it stopped, when it [State.FAILED]. */
    val error: String? = null,
) {
    enum class State { QUEUED, RUNNING, PAUSED, DONE, FAILED }

    val fraction: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val isDone: Boolean get() = state == State.DONE
    /** The copy as it is being written; renamed to [localPath] once whole. */
    val partPath: String get() = "$localPath.part"
}

/**
 * The downloads, kept in `aura/downloads.json` (written as [DurableFile] writes). Pure state and its
 * rules — what downloads next, what a copy is called — so the downloader on the app side only moves
 * bytes and reports them.
 */
class DownloadStore(filesDir: File) {

    private val file = File(filesDir, "aura/downloads.json")
    private val lock = Any()
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Download>> = _all.asStateFlow()

    fun get(itemId: String): Download? = _all.value.firstOrNull { it.itemId == itemId }

    /** The whole copy of [itemId], if one is on the headset. */
    fun localCopy(itemId: String): File? = get(itemId)?.takeIf { it.isDone }?.let { File(it.localPath) }?.takeIf { it.isFile }

    /** Queues [entries] not already here (a finished or running one is left as it is); returns how many were added. */
    fun enqueue(entries: List<Download>): Int = synchronized(lock) {
        val have = _all.value.mapTo(HashSet()) { it.itemId }
        val fresh = entries.filter { it.itemId !in have }
        if (fresh.isNotEmpty()) save(_all.value + fresh)
        fresh.size
    }

    fun update(itemId: String, change: (Download) -> Download) = synchronized(lock) {
        save(_all.value.map { if (it.itemId == itemId) change(it) else it })
    }

    /** Takes [itemId] off the list; its copy (and any part of one) is the caller's to delete. */
    fun remove(itemId: String): Download? = synchronized(lock) {
        val gone = get(itemId) ?: return null
        save(_all.value.filterNot { it.itemId == itemId })
        gone
    }

    /**
     * Entries carried to their items' new ids ([old] → new: a share added again took its files over).
     * One already there under the new id stays; a download running now keeps the id its job writes to.
     */
    fun renamed(ids: Map<String, String>) = synchronized(lock) {
        val have = _all.value.mapTo(HashSet()) { it.itemId }
        var changed = false
        val list = _all.value.map { d ->
            val to = ids[d.itemId]
            if (to == null || to in have || d.state == Download.State.RUNNING) d else d.copy(itemId = to).also { changed = true }
        }
        if (changed) save(list)
    }

    /** What to download next: the oldest queued, or one left running when the app last stopped. */
    fun next(): Download? = DownloadPlan.next(_all.value)

    private fun save(list: List<Download>) {
        _all.value = list
        DurableFile.write(file, encode(list))
    }

    private fun load(): List<Download> =
        DurableFile.read(file) { JSONArray(it); true }?.let { runCatching { decode(it) }.getOrNull() }.orEmpty()

    companion object {
        fun encode(list: List<Download>): String = JSONArray().apply {
            list.forEach { d ->
                put(
                    JSONObject()
                        .put("id", d.itemId).put("name", d.fileName).put("remote", d.remotePath).put("local", d.localPath)
                        .put("total", d.totalBytes).put("done", d.doneBytes).put("state", d.state.name)
                        .put("added", d.addedAt).apply { d.error?.let { put("error", it) } },
                )
            }
        }.toString()

        fun decode(text: String): List<Download> {
            val arr = JSONArray(text)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Download(
                    itemId = o.getString("id"),
                    fileName = o.optString("name"),
                    remotePath = o.optString("remote"),
                    localPath = o.getString("local"),
                    totalBytes = o.optLong("total"),
                    doneBytes = o.optLong("done"),
                    state = runCatching { Download.State.valueOf(o.optString("state")) }.getOrDefault(Download.State.QUEUED),
                    addedAt = o.optLong("added"),
                    error = o.optString("error").ifEmpty { null },
                )
            }
        }
    }
}

/** The rules a download goes by, pure so they are tested. */
object DownloadPlan {

    /** The next to run: one RUNNING when the app stopped (it resumes), else the oldest QUEUED. */
    fun next(all: List<Download>): Download? =
        all.firstOrNull { it.state == Download.State.RUNNING }
            ?: all.filter { it.state == Download.State.QUEUED }.minByOrNull { it.addedAt }

    /**
     * Where [fileName] of [itemId] is kept in [dir]: its own name — the name matters on this page as
     * on every other — in a folder of the item's own, so two files called `01.mkv` never meet.
     *
     * A name too long for the headset's file system (255 bytes; a Chinese title reaches that in 85
     * characters) is cut inside the video's stem, never through its extension. [videoName] is the
     * video a subtitle file belongs to: the subtitle keeps the cut stem and its own tail
     * (`.chs.ass`), so it still finds the copy the way it found the original.
     */
    fun localFile(dir: File, itemId: String, fileName: String, videoName: String = fileName): File {
        val name = safe(fileName)
        val stem = safe(videoName).let { it.substringBeforeLast('.', it) }
        val (head, tail) = if (stem.isNotEmpty() && name.startsWith(stem)) stem to name.substring(stem.length)
        else name.substringBeforeLast('.', name) to name.substring(name.substringBeforeLast('.', name).length)
        val folder = itemId.hashCode().toUInt().toString(16)
        return File(File(dir, folder), fitBytes(head, STEM_BYTES) + fitBytes(tail, TAIL_BYTES))
    }

    private fun safe(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().ifEmpty { "video" }

    /** [s] cut to at most [max] bytes of UTF-8, at a character's edge. */
    private fun fitBytes(s: String, max: Int): String {
        if (s.toByteArray().size <= max) return s
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray().size
            if (used + n > max) break
            out.appendCodePoint(cp)
            used += n
            i += Character.charCount(cp)
        }
        return out.toString().trimEnd()
    }

    /** A copy's name in bytes: the stem's share and the tail's (`.mkv`, `.chs&eng.ass`), within 255. */
    private const val STEM_BYTES = 180
    private const val TAIL_BYTES = 60

    /** Whether a write's failure [message] says the headset's storage is full (Linux's ENOSPC, as Java words it). */
    fun isDiskFull(message: String?): Boolean =
        message != null && (message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true))

    /** Whether [bytes] more fit in [freeBytes], leaving [reserve] for everything else on the headset. */
    fun fits(bytes: Long, freeBytes: Long, reserve: Long = RESERVE_BYTES): Boolean = bytes <= 0 || freeBytes - bytes >= reserve

    /** What the headset keeps free beside the downloads: 2 GB. */
    const val RESERVE_BYTES = 2L * 1024 * 1024 * 1024
}
