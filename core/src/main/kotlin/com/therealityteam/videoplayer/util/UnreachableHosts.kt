package com.therealityteam.videoplayer.util

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The hosts this network could not reach, remembered between launches in [file] (set by the platform).
 *
 * A headset at home in China never reached Jikan — 43 calls of 43 failed over a night's scrape, 16 s each —
 * and [Http] only knew it for the launch: each launch's first scrape spent three such calls learning it
 * again, 45 s of a 55 s pass of 55 films. A host remembered here is asked one call at a time in a new launch
 * until it answers, and its first failure sets it aside as three do for a host not remembered. Any answer
 * forgets it, so a network that reaches it again costs one call's wait at most.
 */
object UnreachableHosts {

    @Volatile var file: File? = null

    private val hosts = ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val now = System.currentTimeMillis()
            runCatching { file?.takeIf { it.isFile }?.readLines() }.getOrNull()?.forEach { line ->
                val host = line.substringBefore('\t')
                val at = line.substringAfter('\t', "").trim().toLongOrNull()
                if (host.isNotBlank() && at != null && now - at < REMEMBER_MS) hosts[host] = at
            }
            loaded = true
        }
    }

    fun contains(host: String): Boolean {
        load()
        return hosts.containsKey(host)
    }

    /** [host] did not answer, again and again: remembered for the next launch. */
    fun remember(host: String) {
        load()
        hosts[host] = System.currentTimeMillis()
        save()
    }

    /** [host] answered: it is reachable from here after all. */
    fun forget(host: String) {
        load()
        if (hosts.remove(host) != null) save()
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(hosts.entries.joinToString("") { "${it.key}\t${it.value}\n" })
        }
    }

    /** Test seam: what was read is read again from [file]. */
    fun reload() {
        synchronized(this) {
            hosts.clear()
            loaded = false
        }
    }

    /** A host not seen failing for this long is asked as any other is. */
    private const val REMEMBER_MS = 30L * 24 * 3600 * 1000
}
