package com.blemesh.router.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fixed-window semantics of the retry-storm counter. The load-bearing
 * case is the first one: the phones' Noise keepalive plan (loxation-sw
 * HANDSHAKE_CHURN_PLAN.md H1) sends a directed 0x12 every ~20s per idle
 * session, and MeshRouterService warns at count 3 — a steady 20s cadence must
 * NEVER reach that threshold, while a genuine retry storm (≥3 originations
 * inside one 30s window) must, repeatedly for as long as it persists.
 */
class RetryStormTrackerTest {

    private val windowMs = 30_000L
    private val key = "aabbccdd→eeff0011:0x12"

    @Test
    fun steady20sKeepaliveCadence_neverReachesWarnThreshold() {
        val tracker = RetryStormTracker(windowMs)
        // 5 minutes of keepalives at exactly 20s cadence.
        var maxCount = 0
        for (t in 0L..300_000L step 20_000L) {
            maxCount = maxOf(maxCount, tracker.record(key, t).count)
        }
        assertTrue("keepalive cadence must stay below the warn threshold (max=$maxCount)", maxCount < 3)
    }

    @Test
    fun threeOriginationsInsideOneWindow_reachWarnThreshold() {
        val tracker = RetryStormTracker(windowMs)
        tracker.record(key, 0L)
        tracker.record(key, 4_000L)
        val snap = tracker.record(key, 8_000L)
        assertEquals(3, snap.count)
        assertEquals(8_000L, snap.windowAgeMs)
    }

    @Test
    fun persistentStorm_reCrossesThresholdEveryWindow() {
        val tracker = RetryStormTracker(windowMs)
        // A stack re-driving the same handshake every 5s for 2 minutes: the
        // count must cross 3 in EACH successive window, not just the first —
        // that's what keeps a long-running storm visible in router.log.
        var crossings = 0
        for (t in 0L..120_000L step 5_000L) {
            if (tracker.record(key, t).count == 3) crossings++
        }
        assertTrue("persistent storm should re-cross the threshold each window (crossings=$crossings)", crossings >= 2)
    }

    @Test
    fun reap_dropsIdleBuckets_keepsActiveOnes() {
        val tracker = RetryStormTracker(windowMs)
        tracker.record("idle", 0L)
        tracker.record("active", 0L)
        tracker.record("active", 25_000L)
        tracker.reap(35_000L) // idle last seen 35s ago (> window); active 10s ago
        assertEquals(1, tracker.size)
        tracker.clear()
        assertEquals(0, tracker.size)
    }
}
