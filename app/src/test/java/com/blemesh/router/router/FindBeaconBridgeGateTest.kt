package com.blemesh.router.router

import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.MessageType
import com.blemesh.router.model.PeerID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backbone rate limit + newest-per-sender coalescing contract
 * (FIND_MODE_SPEC.md §6). The gate is driven with an explicit clock so the
 * 30s window is exercised without sleeping.
 */
class FindBeaconBridgeGateTest {

    private val interval = 30_000L

    private fun peer(id: Long): PeerID = PeerID.fromLongBE(id)!!

    private fun beacon(sender: Long, ts: Long): BlemeshPacket = BlemeshPacket(
        version = BlemeshPacket.PROTOCOL_VERSION,
        type = MessageType.FIND_BEACON.value,
        ttl = BlemeshPacket.MAX_TTL.toByte(),
        timestamp = ts,
        flags = 0,
        senderId = sender,
        recipientId = BlemeshPacket.BROADCAST_ADDRESS,
        // Stand-in for [ver:1][keyId:4][nonce:12][ct+tag:31]; the gate never
        // reads it (the router holds no find key — it routes on the header).
        payload = ByteArray(48) { ts.toByte() },
        signature = null
    )

    @Test
    fun firstBeaconFromASenderCrossesImmediately() {
        val gate = FindBeaconBridgeGate(interval)
        assertTrue(gate.offer(peer(1), beacon(1, 1_000), 1_000))
    }

    @Test
    fun secondBeaconInsideTheWindowIsHeldNotCrossed() {
        val gate = FindBeaconBridgeGate(interval)
        assertTrue(gate.offer(peer(1), beacon(1, 0), 0))
        assertFalse(gate.offer(peer(1), beacon(1, 5_000), 5_000))
        assertEquals(1, gate.heldCount)
        // Nothing is released while the cooldown is still running.
        assertTrue(gate.drain(20_000).isEmpty())
        assertEquals(1, gate.heldCount)
    }

    @Test
    fun boostedCadenceCollapsesToOneCrossingPerWindow() {
        // §3's FIND_REQ boost: ~5s cadence. 30s of it must cost the backbone
        // one crossing, not six.
        val gate = FindBeaconBridgeGate(interval)
        var crossings = 0
        for (t in 0L until 30_000L step 5_000L) {
            if (gate.offer(peer(1), beacon(1, t), t)) crossings++
        }
        assertEquals(1, crossings)
    }

    @Test
    fun drainReleasesTheNEWESTHeldBeacon() {
        // The whole point of coalescing over dropping: the far region gets the
        // freshest fix this router saw, not an arbitrary one.
        val gate = FindBeaconBridgeGate(interval)
        gate.offer(peer(1), beacon(1, 0), 0)
        gate.offer(peer(1), beacon(1, 5_000), 5_000)
        gate.offer(peer(1), beacon(1, 10_000), 10_000)
        gate.offer(peer(1), beacon(1, 25_000), 25_000)

        val released = gate.drain(30_000)
        assertEquals(1, released.size)
        assertEquals(25_000L, released[0].timestamp)
        assertEquals(0, gate.heldCount)
    }

    @Test
    fun holdIsMonotonic_areplayedOlderBeaconNeverDisplacesAFresherOne() {
        val gate = FindBeaconBridgeGate(interval)
        gate.offer(peer(1), beacon(1, 0), 0)
        gate.offer(peer(1), beacon(1, 20_000), 20_000)
        // A reordered/replayed older copy arrives after the fresher one.
        gate.offer(peer(1), beacon(1, 8_000), 21_000)

        val released = gate.drain(30_000)
        assertEquals(1, released.size)
        assertEquals("older replay displaced a fresher held beacon", 20_000L, released[0].timestamp)
    }

    @Test
    fun releaseRearmsTheWindow() {
        val gate = FindBeaconBridgeGate(interval)
        gate.offer(peer(1), beacon(1, 0), 0)
        gate.offer(peer(1), beacon(1, 10_000), 10_000)
        assertEquals(1, gate.drain(30_000).size)
        // The release itself counts as a crossing: the next one waits a full
        // window, otherwise a held beacon would buy a free extra crossing.
        assertFalse(gate.offer(peer(1), beacon(1, 35_000), 35_000))
        assertTrue(gate.drain(60_000).isNotEmpty())
    }

    @Test
    fun aBeaconArrivingAfterTheWindowCrossesAndSupersedesTheHeldOne() {
        val gate = FindBeaconBridgeGate(interval)
        gate.offer(peer(1), beacon(1, 0), 0)
        gate.offer(peer(1), beacon(1, 10_000), 10_000) // held
        // Cooldown expired; this newer beacon crosses directly. The held copy
        // is older, so releasing it too would put a stale fix on the backbone
        // behind a fresher one.
        assertTrue(gate.offer(peer(1), beacon(1, 31_000), 31_000))
        assertEquals(0, gate.heldCount)
        assertTrue(gate.drain(31_000).isEmpty())
    }

    @Test
    fun sendersAreRateLimitedIndependently() {
        val gate = FindBeaconBridgeGate(interval)
        assertTrue(gate.offer(peer(1), beacon(1, 0), 0))
        assertTrue(gate.offer(peer(2), beacon(2, 0), 0))
        assertTrue(gate.offer(peer(3), beacon(3, 0), 0))
        // ...and each keeps its own window.
        assertFalse(gate.offer(peer(1), beacon(1, 1_000), 1_000))
        assertFalse(gate.offer(peer(2), beacon(2, 1_000), 1_000))
        assertEquals(3, gate.size)
    }

    @Test
    fun idleSlotsAreReapedSoTheGateIsBoundedByActiveSenders() {
        val gate = FindBeaconBridgeGate(interval)
        for (i in 1L..100L) gate.offer(peer(i), beacon(i, 0), 0)
        assertEquals(100, gate.size)
        // Long after every cooldown expired with nothing held.
        gate.drain(200_000)
        assertEquals(0, gate.size)
        // Reaping is semantically free: the next beacon would have crossed
        // immediately anyway, and it still does.
        assertTrue(gate.offer(peer(1), beacon(1, 201_000), 201_000))
    }

    @Test
    fun reapingDoesNotDropAHeldBeacon() {
        val gate = FindBeaconBridgeGate(interval)
        gate.offer(peer(1), beacon(1, 0), 0)
        gate.offer(peer(1), beacon(1, 1_000), 1_000)
        // A sweep far past the cooldown must RELEASE the held beacon, not reap
        // the slot out from under it.
        val released = gate.drain(500_000)
        assertEquals(1, released.size)
        assertEquals(1_000L, released[0].timestamp)
    }

    @Test
    fun clockSkewCannotBuyExtraBackboneShare() {
        // Cooldowns run on the router's clock, never on packet timestamps: a
        // sender stamping wildly future timestamps still gets one crossing per
        // window.
        val gate = FindBeaconBridgeGate(interval)
        assertTrue(gate.offer(peer(1), beacon(1, 0), 0))
        assertFalse(gate.offer(peer(1), beacon(1, 999_999_999), 1_000))
        assertFalse(gate.offer(peer(1), beacon(1, 999_999_999), 2_000))
    }

    @Test
    fun clearDropsEverything() {
        val gate = FindBeaconBridgeGate(interval)
        gate.offer(peer(1), beacon(1, 0), 0)
        gate.offer(peer(1), beacon(1, 1_000), 1_000)
        assertNotNull(gate.heldCount)
        gate.clear()
        assertEquals(0, gate.size)
        assertEquals(0, gate.heldCount)
    }
}
