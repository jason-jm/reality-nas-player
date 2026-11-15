package com.therealityteam.videoplayer.data.repository

import com.therealityteam.videoplayer.data.playback.FrameCrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 去黑边: the black bars found in each file's frame (Letterbox), by item id — so a film found once to
 * be scope opens as a scope panel from its first frame on, and one found to have none is not looked
 * at again. Stored beside the library like [SkipStore], written at once.
 */
class CropStore(filesDir: File) {

    private val file = File(filesDir, "aura/crops.json")
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val all = MutableStateFlow(runCatching { if (file.exists()) decode(file.readText()) else emptyMap() }.getOrDefault(emptyMap()))

    /** What was found in [id]'s frame — [FrameCrop.NONE] for no bars — or null when it has not been looked at. */
    fun of(id: String): FrameCrop? = all.value[id]

    fun put(id: String, crop: FrameCrop) {
        all.value = all.value + (id to crop)
        save()
    }

    /** Crops carried to their files' new ids (a share added again); one already found under a new id stays. */
    fun renamed(ids: Map<String, String>) {
        val moved = all.value.entries.filter { (id, _) -> ids[id]?.let { it !in all.value } == true }
        if (moved.isEmpty()) return
        all.value = all.value - moved.map { it.key }.toSet() + moved.associate { ids.getValue(it.key) to it.value }
        save()
    }

    fun forget(id: String) {
        if (id !in all.value) return
        all.value = all.value - id
        save()
    }

    /** Whole or not at all, through a temp file — forget used to write in place. */
    private fun save() {
        writes.launch {
            writeLock.withLock {
                // The state as it is when the lock is held — see BookmarkStore for why.
                val latest = all.value
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
        internal fun encode(crops: Map<String, FrameCrop>): String = JSONObject().apply {
            crops.forEach { (id, c) -> put(id, JSONArray(listOf(c.left, c.top, c.right, c.bottom).map { it.toDouble() })) }
        }.toString()

        internal fun decode(text: String): Map<String, FrameCrop> {
            val o = JSONObject(text)
            return o.keys().asSequence().mapNotNull { id ->
                val a = o.optJSONArray(id) ?: return@mapNotNull null
                if (a.length() != 4) return@mapNotNull null
                id to FrameCrop(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
            }.toMap()
        }
    }
}
