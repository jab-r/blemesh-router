package com.blemesh.router.router

import java.util.concurrent.ConcurrentHashMap

/**
 * Counts distinct originations per bucket (the caller keys on
 * sender→recipient:type) inside a FIXED window anchored at the bucket's first
 * event. When a bucket's window ages out, the next event starts a fresh window
 * at count 1.
 *
 * Window semantics matter here: an earlier version reset only after a quiet
 * GAP of [windowMs] since the last event, which made any steady cadence faster
 * than the gap grow the count without bound. The phones' Noise keepalive plan
 * (loxation-sw HANDSHAKE_CHURN_PLAN.md H1) sends a directed 0x12 every ~20s
 * per idle session — under gap semantics that logged a bogus RETRY-STORM per
 * idle phone pair forever. With a fixed window, a 20s cadence yields at most
 * 2 events per 30s window (below the count-3 warn threshold), while a genuine
 * storm (≥3 fresh originations inside one window) still crosses it — and a
 * persistent storm re-crosses it every window, so it stays visible in the log.
 */
class RetryStormTracker(private val windowMs: Long) {

    /** The bucket's count within its current window, plus the window's age (for log context). */
    data class Snapshot(val count: Int, val windowAgeMs: Long)

    private class Counter(var count: Int, var firstSeenMs: Long, var lastSeenMs: Long)

    private val counters = ConcurrentHashMap<String, Counter>()

    /** Live bucket count — observability for tests and debugging (reap is otherwise invisible). */
    val size: Int get() = counters.size

    /** Record one origination for [key] at [nowMs]. */
    fun record(key: String, nowMs: Long): Snapshot {
        val c = counters.compute(key) { _, existing ->
            if (existing == null || nowMs - existing.firstSeenMs > windowMs) {
                Counter(1, nowMs, nowMs)
            } else {
                existing.count++
                existing.lastSeenMs = nowMs
                existing
            }
        }!!
        return Snapshot(c.count, nowMs - c.firstSeenMs)
    }

    /**
     * Drop buckets with no events for [windowMs]. Pure memory reaping — the
     * window reset itself happens in [record], so a bucket that survives a
     * sweep (steady traffic) still resets its window on schedule.
     */
    fun reap(nowMs: Long) {
        counters.entries.removeAll { nowMs - it.value.lastSeenMs > windowMs }
    }

    fun clear() {
        counters.clear()
    }
}
