package com.blemesh.router.sync

import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.MessageType
import com.blemesh.router.model.PeerID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adaptive-MPR / DTN gossip tuning contract points (docs/adaptive-mpr-fix.md,
 * mirroring the iOS MeshScalingPolicyTests): per-bucket water-filled sync
 * windows and departure semantics that keep DTN cargo.
 */
class AdaptiveSyncWindowTest {

    private fun manager(scope: CoroutineScope): GossipSyncManager =
        GossipSyncManager(PeerID.fromLongBE(0x0102030405060708L)!!, scope)

    private fun packet(type: MessageType, sender: Long, ts: Long): BlemeshPacket = BlemeshPacket(
        version = BlemeshPacket.PROTOCOL_VERSION,
        type = type.value,
        ttl = BlemeshPacket.MAX_TTL.toByte(),
        timestamp = ts,
        flags = 0,
        senderId = sender,
        recipientId = BlemeshPacket.BROADCAST_ADDRESS,
        payload = byteArrayOf(1, 2, 3, sender.toByte()),
        signature = null
    )

    /** onPublicPacketSeen dispatches to Dispatchers.Default; poll until the store settles. */
    private fun awaitCount(mgr: GossipSyncManager, emptyReq: RequestSyncPacket, expected: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (mgr.collectMissing(emptyReq).size == expected) return
            Thread.sleep(20)
        }
        assertEquals("store did not settle to expected count", expected, mgr.collectMissing(emptyReq).size)
    }

    private fun emptyFilterFor(scope: CoroutineScope, types: SyncTypeFlags): RequestSyncPacket =
        RequestSyncPacket.decode(manager(scope).buildBackboneFilter(types))!!

    @Test
    fun bucketBudgets_waterFillsMaxMinFair() {
        // Small presence buckets leave nearly everything to cargo.
        assertEquals(listOf(20, 320, 15), GossipSyncManager.bucketBudgets(listOf(20, 5000, 15), 355))
        // Dense cell: every bucket saturated → each keeps a fair floor ≈ budget/3.
        val dense = GossipSyncManager.bucketBudgets(listOf(300, 6000, 200), 355)
        assertEquals(355, dense.sum())
        assertTrue("message bucket keeps a fair floor", dense[1] >= 355 / 3)
        assertTrue(dense[0] >= 355 / 3)
        assertTrue(dense[2] >= 355 / 3)
        assertEquals(listOf(119, 118, 118), dense) // leftover goes in fixed bucket order
        // Single bucket gets the whole budget (bounded by its own count).
        assertEquals(listOf(355), GossipSyncManager.bucketBudgets(listOf(6000), 355))
        assertEquals(listOf(40), GossipSyncManager.bucketBudgets(listOf(40), 355))
        // Empty buckets consume nothing; zero budget yields nothing.
        assertEquals(listOf(0, 10, 0), GossipSyncManager.bucketBudgets(listOf(0, 10, 0), 355))
        assertEquals(listOf(0, 0), GossipSyncManager.bucketBudgets(listOf(5, 5), 0))
        assertEquals(emptyList<Int>(), GossipSyncManager.bucketBudgets(emptyList(), 355))
    }

    @Test
    fun mixedSyncWindowNeverStarvesTextCargo() {
        // End-to-end pin of the bucket-split: flood the store with announces
        // NEWER than every stored text message, then check the publicMessages
        // window still reconciles the messages. With a single mixed
        // newest-first window (~355 ids) they were crowded out entirely.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val holder = manager(scope)
            val now = System.currentTimeMillis()

            for (n in 0 until 400) {
                holder.onPublicPacketSeen(packet(MessageType.ANNOUNCE, 0x1000L + n, now))
            }
            for (n in 0 until 10) {
                // Older than every announce but well inside the 30-min horizon.
                holder.onPublicPacketSeen(packet(MessageType.MESSAGE, 0x2000L + n, now - 60_000))
            }

            val emptyReq = emptyFilterFor(scope, SyncTypeFlags.PUBLIC_MESSAGES)
            // Window budget is ~355 (400B GCS, p=7): message bucket takes its
            // full 10, announces fill the rest.
            awaitCount(holder, emptyReq, 355)
            val missing = holder.collectMissing(emptyReq)
            val messageCount = missing.count { it.type == MessageType.MESSAGE.value }
            assertEquals("all text cargo must survive the announce flood", 10, messageCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun responseDiffIsBoundedToTheWindowBudget() {
        // An empty-filter (initial) sync must elicit at most the shared window
        // (~355), not the whole store — the old full-store diff re-flooded
        // every packet outside the requester's window every round.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val holder = manager(scope)
            val now = System.currentTimeMillis()
            for (n in 0 until 500) {
                holder.onPublicPacketSeen(packet(MessageType.MESSAGE, 0x3000L + n, now - n))
            }
            val emptyReq = emptyFilterFor(scope, SyncTypeFlags.PUBLIC_MESSAGES)
            awaitCount(holder, emptyReq, 355)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun peerDeparturePurgesPresenceButKeepsCargo() {
        // Departure retires real-time state (announce, profile, positions)
        // only; message/fragment cargo must survive — it expires via its own
        // horizon. Purging cargo on departure silently defeated DTN carry.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val holder = manager(scope)
            val now = System.currentTimeMillis()
            val sender = 0xABCDL
            val types = SyncTypeFlags.fromTypes(
                MessageType.ANNOUNCE, MessageType.MESSAGE,
                MessageType.LOXATION_ANNOUNCE, MessageType.LOCATION_UPDATE
            )

            holder.onPublicPacketSeen(packet(MessageType.ANNOUNCE, sender, now))
            holder.onPublicPacketSeen(packet(MessageType.MESSAGE, sender, now))
            holder.onPublicPacketSeen(packet(MessageType.LOXATION_ANNOUNCE, sender, now))
            holder.onPublicPacketSeen(packet(MessageType.LOCATION_UPDATE, sender, now))

            val emptyReq = emptyFilterFor(scope, types)
            awaitCount(holder, emptyReq, 4)

            holder.removeAnnouncementForPeer(PeerID.fromLongBE(sender)!!)
            awaitCount(holder, emptyReq, 1)
            val remaining = holder.collectMissing(emptyReq).single()
            assertEquals("only the message cargo survives departure", MessageType.MESSAGE.value, remaining.type)
        } finally {
            scope.cancel()
        }
    }
}
