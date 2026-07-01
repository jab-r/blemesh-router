package com.blemesh.router.sync

import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.MessageType
import com.blemesh.router.model.PeerID
import com.blemesh.router.protocol.BinaryProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reusable GossipSyncManager ops the WiFi-backbone anti-entropy relies on:
 * buildBackboneFilter (advertise what I hold) and collectMissing (what a peer
 * lacks). Pure over the packet stores — no delegate, no BLE.
 */
class BackboneGossipTest {

    private val types = SyncTypeFlags.fromTypes(
        MessageType.ANNOUNCE, MessageType.LOXATION_ANNOUNCE, MessageType.LOCATION_UPDATE
    )

    private fun manager(scope: CoroutineScope): GossipSyncManager =
        GossipSyncManager(PeerID.fromLongBE(0x0102030405060708L)!!, scope)

    private fun announce(sender: Long, ts: Long): BlemeshPacket = BlemeshPacket(
        version = BlemeshPacket.PROTOCOL_VERSION,
        type = MessageType.ANNOUNCE.value,
        ttl = BlemeshPacket.MAX_TTL.toByte(),
        timestamp = ts,
        flags = 0,
        senderId = sender,
        recipientId = BlemeshPacket.BROADCAST_ADDRESS,
        payload = byteArrayOf(1, 2, 3),
        signature = null
    )

    private fun locationUpdate(sender: Long, ts: Long): BlemeshPacket = BlemeshPacket(
        version = BlemeshPacket.PROTOCOL_VERSION,
        type = MessageType.LOCATION_UPDATE.value,
        ttl = BlemeshPacket.MAX_TTL.toByte(),
        timestamp = ts,
        flags = 0,
        senderId = sender,
        recipientId = BlemeshPacket.BROADCAST_ADDRESS,
        payload = byteArrayOf(9, 8, 7, 6),
        signature = null
    )

    /** onPublicPacketSeen dispatches to Dispatchers.Default; poll until the store settles. */
    private fun awaitCount(mgr: GossipSyncManager, emptyReq: RequestSyncPacket, expected: Int) {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (mgr.collectMissing(emptyReq).size == expected) return
            Thread.sleep(20)
        }
        assertEquals("store did not settle to expected count", expected, mgr.collectMissing(emptyReq).size)
    }

    @Test
    fun collectMissing_returnsEverythingForEmptyPeerFilter_andNothingForSelfFilter() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val holder = manager(scope)
            val empty = manager(scope)
            val now = System.currentTimeMillis()

            holder.onPublicPacketSeen(announce(0xAAL, now))
            holder.onPublicPacketSeen(locationUpdate(0xBBL, now))

            // A peer that holds none of them (empty GCS filter) is missing both.
            val emptyReq = RequestSyncPacket.decode(empty.buildBackboneFilter(types))!!
            awaitCount(holder, emptyReq, 2)

            val missing = holder.collectMissing(emptyReq)
            assertEquals(2, missing.size)
            // Replies are reset to ttl=0 (matches the BLE gossip reply).
            assertTrue(missing.all { (it.ttl.toInt() and 0xFF) == 0 })

            // A peer whose filter equals the holder's own set is missing nothing.
            val selfReq = RequestSyncPacket.decode(holder.buildBackboneFilter(types))!!
            assertEquals(0, holder.collectMissing(selfReq).size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun collectedPacket_survivesBinaryProtocolRoundTrip_withStableGcsId() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val holder = manager(scope)
            val empty = manager(scope)
            holder.onPublicPacketSeen(locationUpdate(0xCCL, System.currentTimeMillis()))

            val emptyReq = RequestSyncPacket.decode(empty.buildBackboneFilter(types))!!
            awaitCount(holder, emptyReq, 1)

            val pkt = holder.collectMissing(emptyReq).single()
            val encoded = BinaryProtocol.encode(pkt)!!
            val decoded = BinaryProtocol.decode(encoded)!!

            // GCS id must be identical across the WiFi hop or content can never
            // reconcile (id = SHA256 over type|senderId|wireTimestamp|payload).
            assertArrayEquals(
                PacketIdUtil.computeIdBytes(pkt),
                PacketIdUtil.computeIdBytes(decoded)
            )
        } finally {
            scope.cancel()
        }
    }
}
