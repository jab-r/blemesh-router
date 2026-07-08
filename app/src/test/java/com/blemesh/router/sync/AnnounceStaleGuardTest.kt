package com.blemesh.router.sync

import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.MessageType
import com.blemesh.router.model.PeerID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ANNOUNCE stale-peer branch carries the removeState side effect
 * (purging a peer's presence + profile + position state). That departure
 * signal must be BOUNDED (within the announce horizon) and MONOTONIC (at
 * least as new as the announce we already hold): a replayed old announce —
 * re-emitted from a restarting node's store or injected over the backbone —
 * must never erase a live peer's fresher state.
 */
class AnnounceStaleGuardTest {

    private val announceTypes = SyncTypeFlags.fromTypes(MessageType.ANNOUNCE)
    private val loxationTypes = SyncTypeFlags.fromTypes(MessageType.LOXATION_ANNOUNCE)

    private fun packet(type: MessageType, sender: Long, ts: Long): BlemeshPacket = BlemeshPacket(
        version = BlemeshPacket.PROTOCOL_VERSION,
        type = type.value,
        ttl = BlemeshPacket.MAX_TTL.toByte(),
        timestamp = ts,
        flags = 0,
        senderId = sender,
        recipientId = BlemeshPacket.BROADCAST_ADDRESS,
        payload = byteArrayOf(1, 2, 3),
        signature = null
    )

    /** An empty-store filter for [types], to count what a manager holds. */
    private fun emptyReq(scope: CoroutineScope, types: SyncTypeFlags): RequestSyncPacket =
        RequestSyncPacket.decode(
            GossipSyncManager(PeerID.fromLongBE(0x99L)!!, scope).buildBackboneFilter(types)
        )!!

    /** onPublicPacketSeen dispatches to Dispatchers.Default; poll until settled. */
    private fun awaitCount(mgr: GossipSyncManager, req: RequestSyncPacket, expected: Int) {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (mgr.collectMissing(req).size == expected) return
            Thread.sleep(20)
        }
        assertEquals("store did not settle to expected count", expected, mgr.collectMissing(req).size)
    }

    @Test
    fun ancientReplayedAnnounceDoesNotPurgeLivePeerState() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val mgr = GossipSyncManager(PeerID.fromLongBE(0x0102030405060708L)!!, scope)
            val sender = 0xAAL
            val now = System.currentTimeMillis()

            mgr.onPublicPacketSeen(packet(MessageType.ANNOUNCE, sender, now))
            mgr.onPublicPacketSeen(packet(MessageType.LOXATION_ANNOUNCE, sender, now))
            val announceReq = emptyReq(scope, announceTypes)
            val loxationReq = emptyReq(scope, loxationTypes)
            awaitCount(mgr, announceReq, 1)
            awaitCount(mgr, loxationReq, 1)

            // A one-hour-old replayed announce (way past the 120s announce
            // horizon) must be inert — not a departure signal.
            mgr.onPublicPacketSeen(packet(MessageType.ANNOUNCE, sender, now - 3_600_000))
            Thread.sleep(300)
            assertEquals(1, mgr.collectMissing(announceReq).size)
            assertEquals(1, mgr.collectMissing(loxationReq).size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun staleBandReplayOlderThanHeldAnnounceDoesNotPurge() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val mgr = GossipSyncManager(PeerID.fromLongBE(0x0102030405060708L)!!, scope)
            val sender = 0xBBL
            val now = System.currentTimeMillis()

            mgr.onPublicPacketSeen(packet(MessageType.ANNOUNCE, sender, now))
            mgr.onPublicPacketSeen(packet(MessageType.LOXATION_ANNOUNCE, sender, now))
            val announceReq = emptyReq(scope, announceTypes)
            val loxationReq = emptyReq(scope, loxationTypes)
            awaitCount(mgr, announceReq, 1)
            awaitCount(mgr, loxationReq, 1)

            // 90s old: stale (>60s) and inside the 120s horizon, but OLDER
            // than the announce we hold — an out-of-order replay, not a
            // departure signal.
            mgr.onPublicPacketSeen(packet(MessageType.ANNOUNCE, sender, now - 90_000))
            Thread.sleep(300)
            assertEquals(1, mgr.collectMissing(announceReq).size)
            assertEquals(1, mgr.collectMissing(loxationReq).size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun genuinelyStaleNewestAnnounceStillPurges() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // 1s stale timeout so the test can cross it with a short sleep.
            val mgr = GossipSyncManager(
                PeerID.fromLongBE(0x0102030405060708L)!!,
                scope,
                GossipSyncManager.Config(stalePeerTimeoutSeconds = 1)
            )
            val sender = 0xCCL
            val ts = System.currentTimeMillis()

            mgr.onPublicPacketSeen(packet(MessageType.ANNOUNCE, sender, ts))
            mgr.onPublicPacketSeen(packet(MessageType.LOXATION_ANNOUNCE, sender, ts))
            val announceReq = emptyReq(scope, announceTypes)
            val loxationReq = emptyReq(scope, loxationTypes)
            awaitCount(mgr, announceReq, 1)
            awaitCount(mgr, loxationReq, 1)

            // Cross the stale timeout, then replay the same announce: now
            // stale, within the horizon, and as new as anything we hold —
            // the genuine departure signal must still retire the peer.
            Thread.sleep(1200)
            mgr.onPublicPacketSeen(packet(MessageType.ANNOUNCE, sender, ts))
            awaitCount(mgr, announceReq, 0)
            awaitCount(mgr, loxationReq, 0)
        } finally {
            scope.cancel()
        }
    }
}
