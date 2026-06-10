package com.blemesh.router.util

import android.util.Log

/**
 * Lightweight in-memory deduplicator for BLE mesh packets.
 * Ported from loxation-android MessageDeduplicator, with insertion-ordered
 * storage: entries are only ever (re)inserted with a fresh timestamp, so
 * iteration order == age order, the eldest entry is always first, and
 * eviction is O(1) per entry with no sorting on the receive hot path.
 */
class MessageDeduplicator(
    private val maxAgeMillis: Long = 5 * 60 * 1000L, // 5 minutes
    private val maxEntries: Int = 5000
) {
    private companion object {
        const val TAG = "MessageDeduplicator"
    }

    private val seen = LinkedHashMap<String, Long>()

    /**
     * Returns true if this ID has been seen before (within the age window).
     * Marks it as seen if not already.
     */
    @Synchronized
    fun isDuplicate(id: String): Boolean {
        val now = System.currentTimeMillis()
        val prev = seen[id]
        if (prev != null) {
            if (now - prev < maxAgeMillis) return true
            // Expired: remove so the re-insert lands at the tail and
            // insertion order keeps matching age order.
            seen.remove(id)
        }
        seen[id] = now
        trim(now)
        return false
    }

    @Synchronized
    fun contains(id: String): Boolean {
        val ts = seen[id] ?: return false
        return System.currentTimeMillis() - ts < maxAgeMillis
    }

    @Synchronized
    fun reset() {
        seen.clear()
    }

    private fun trim(now: Long) {
        val cutoff = now - maxAgeMillis
        val ageIter = seen.entries.iterator()
        while (ageIter.hasNext() && ageIter.next().value < cutoff) {
            ageIter.remove()
        }
        // Hard cap so memory stays bounded under sustained load. Evicting a
        // still-live entry weakens dedup — its echo would pass as new and be
        // re-relayed — so the cap is sized generously and crossing it is
        // worth a log line.
        var evicted = 0
        val capIter = seen.entries.iterator()
        while (seen.size > maxEntries && capIter.hasNext()) {
            capIter.next()
            capIter.remove()
            evicted++
        }
        if (evicted > 0) {
            Log.w(TAG, "Hard-evicted $evicted live dedup entries (cap=$maxEntries) — dedup window degraded under load")
        }
    }
}
