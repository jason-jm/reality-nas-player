package com.therealityteam.videoplayer.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Walking a tree of directories, with the listings overlapped instead of queued behind each other.
 *
 * A NAS walk is almost entirely waiting: one request per directory, and nothing else happens while
 * it is in flight. A real 9,597-file library sits under 1,134 directories, so a walk that asks for
 * one at a time makes 1,134 round trips end to end — at 20ms each that is 23 seconds in which the
 * link is idle almost all the time.
 *
 * The traversal is level by level rather than depth-first: every directory at one depth is listed
 * at once, across [lanes] lanes, and the directories they name become the next level. A lane owns
 * its own [Lister] — for SMB that means its own connection, so nothing is shared across threads
 * that was not built to be.
 *
 * The order files come back in is NOT the depth-first order it used to be, which is why nothing
 * downstream may depend on it: discovery sorts by addedAt, which it assigns itself.
 */
internal object DirectoryWalk {

    /** One entry in a directory. */
    data class Entry(val name: String, val isDirectory: Boolean, val size: Long? = null)

    /** Listing one directory — all the walk needs of a share. Null means "could not be listed". */
    fun interface Lister {
        fun list(dir: String): List<Entry>?
    }

    /** A file the walk found, with the directory it was found in. */
    data class Found(val dir: String, val name: String, val size: Long?)

    /**
     * Walk from [root], returning every file [keep] accepts.
     *
     * Throws whatever [onRootFailure] throws when the ROOT cannot be listed: a configured path that
     * is gone must not look like an empty folder — that is how a renamed share reported "0 files"
     * for months. Deeper directories that cannot be read are skipped, as before.
     */
    suspend fun walk(
        lanes: List<Lister>,
        root: String,
        maxDepth: Int,
        maxFiles: Int,
        separator: Char,
        keep: (String) -> Boolean,
        onRootFailure: () -> Nothing,
        /** A directory below the root that could not be listed: what was in it is unknown, not gone. */
        onUnread: (String) -> Unit = {},
    ): List<Found> {
        require(lanes.isNotEmpty()) { "a walk needs at least one lane" }
        val found = ConcurrentLinkedQueue<Found>()
        val count = AtomicInteger(0)
        var level = listOf(root)
        var depth = 0
        while (level.isNotEmpty() && depth <= maxDepth && count.get() < maxFiles) {
            val next = ConcurrentLinkedQueue<String>()
            val rootLevel = depth == 0
            coroutineScope {
                // Hand the level out in stripes so each lane gets a share of it, and each lane
                // touches only its own lister.
                lanes.indices.map { lane ->
                    async(Dispatchers.IO) {
                        var i = lane
                        while (i < level.size) {
                            val dir = level[i]
                            i += lanes.size
                            if (count.get() >= maxFiles) return@async
                            val entries = lanes[lane].list(dir)
                            if (entries == null) {
                                if (rootLevel) onRootFailure()
                                onUnread(dir)
                                continue
                            }
                            for (e in entries) {
                                if (e.name == "." || e.name == "..") continue
                                val child = if (dir.isEmpty()) e.name else "$dir$separator${e.name}"
                                if (e.isDirectory) {
                                    if (!SkippedFolders.skip(e.name)) next += child
                                } else if (keep(e.name)) {
                                    if (count.incrementAndGet() > maxFiles) return@async
                                    found += Found(dir, e.name, e.size)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            level = next.toList()
            depth++
        }
        return found.toList().take(maxFiles)
    }
}
