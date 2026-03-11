package com.blemesh.router.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight in-memory deduplicator for BLE mesh packets.
 * Ported from loxation-android MessageDeduplicator.
 */
class MessageDeduplicator(
    private val maxAgeMillis: Long = 5 * 60 * 1000L, // 5 minutes
    private val maxEntries: Int = 1000,
    private val trimBatchSize: Int = 100
) {
    private val seen = ConcurrentHashMap<String, Long>()

    /**
     * Returns true if this ID has been seen before (within the age window).
     * Marks it as seen if not already.
     */
    fun isDuplicate(id: String): Boolean {
        val now = System.currentTimeMillis()
        val prev = seen.putIfAbsent(id, now)
        if (prev != null) {
            if (now - prev < maxAgeMillis) return true
            // Expired entry, update timestamp
            seen[id] = now
            return false
        }
        // New entry
        if (seen.size > maxEntries) trimOldEntries(now)
        return false
    }

    fun contains(id: String): Boolean {
        val ts = seen[id] ?: return false
        return System.currentTimeMillis() - ts < maxAgeMillis
    }

    fun reset() {
        seen.clear()
    }

    private fun trimOldEntries(now: Long) {
        val cutoff = now - maxAgeMillis
        var removed = 0
        val iter = seen.entries.iterator()
        while (iter.hasNext() && removed < trimBatchSize) {
            val entry = iter.next()
            if (entry.value < cutoff) {
                iter.remove()
                removed++
            }
        }
    }
}
