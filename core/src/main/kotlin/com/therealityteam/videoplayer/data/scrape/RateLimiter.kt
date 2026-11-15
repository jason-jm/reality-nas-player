package com.therealityteam.videoplayer.data.scrape

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spaces calls to a service at least [minIntervalMs] apart so we stay under its per-IP rate limit
 * (e.g. Jikan's ~1/sec sustained). Coroutine-friendly: concurrent callers reserve successive slots
 * and each suspends until its slot, so a burst from the 4-way scrape gate is smoothed, not dropped.
 */
class RateLimiter(private val minIntervalMs: Long) {

    private val mutex = Mutex()
    private var nextSlot = 0L

    suspend fun <T> withPermit(block: suspend () -> T): T {
        val slot = mutex.withLock {
            val now = System.currentTimeMillis()
            val at = maxOf(now, nextSlot)
            nextSlot = at + minIntervalMs
            at
        }
        val wait = slot - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        return block()
    }
}
