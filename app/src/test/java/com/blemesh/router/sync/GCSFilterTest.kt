package com.blemesh.router.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the GCS filter (ported regressions from loxation-android). */
class GCSFilterTest {

    /**
     * Regression (ported from the reference): building a filter over no ids
     * must yield m = 1, not m = 0 — RequestSyncPacket.decode rejects m <= 0,
     * so an m = 0 filter would be undecodable on the wire.
     */
    @Test
    fun emptyInputProducesWireValidEmptyFilter() {
        val params = GCSFilter.buildFilter(emptyList(), maxBytes = 400, targetFpr = 0.01)
        assertTrue("m must be wire-valid (RequestSyncPacket rejects m <= 0)", params.m > 0)
        assertEquals(1L, params.m)
        assertEquals(0, params.data.size)
        val sorted = GCSFilter.decodeToSortedSet(params.p, params.m, params.data)
        assertEquals("empty filter must decode to the empty set", 0, sorted.size)
    }

    @Test
    fun buildFilterRoundTripsMembership() {
        val ids = (0 until 50).map { i -> ByteArray(16) { (i + it).toByte() } }
        val params = GCSFilter.buildFilter(ids, maxBytes = 400, targetFpr = 0.01)
        val sorted = GCSFilter.decodeToSortedSet(params.p, params.m, params.data)
        for (id in ids) {
            val bucket = GCSFilter.bucket(id, params.m)
            assertTrue("every built id must test as a member", GCSFilter.contains(sorted, bucket))
        }
    }
}
