package com.blemesh.router.sync

import com.blemesh.router.mesh.BleMeshService
import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.MessageType
import com.blemesh.router.model.PeerID
import com.blemesh.router.protocol.BinaryProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #4233 Option A (cap-to-fit) contract points, mirroring the iOS
 * MeshScalingPolicyTests additions on loxation-sw branch
 * fix/gossip-cap-to-fit-4233: a REQUEST_SYNC must be sized to the target
 * link's single-frame budget (its ttl=0 FRAGMENT wrappers die at reference
 * phones' RSR flood-gates when fragmented), and degenerate links skip the
 * round. The iOS `fitsSingleFrame` (oversized-RSR-response skip) has NO
 * router equivalent by design: the router keeps response-direction
 * fragmentation — the requester's RSR window is open when the response
 * arrives, so those fragments pass the gate.
 */
class GossipCapToFitTest {

    @Test
    fun effectiveGcsBytes_capToFit() {
        // No link info → config budget unchanged (sendPacketToConnection's
        // fragmentation warning is the visibility backstop).
        assertEquals(400, GossipSyncManager.effectiveGcsBytes(400, null))
        // Big link → config budget is the binding constraint.
        assertEquals(400, GossipSyncManager.effectiveGcsBytes(400, 512))
        // Router BLE links cap at a 185-byte single write → link minus the
        // non-filter overhead is binding.
        assertEquals(
            185 - GossipSyncManager.REQUEST_SYNC_NON_FILTER_OVERHEAD_BYTES,
            GossipSyncManager.effectiveGcsBytes(400, 185)
        )
        // Degenerate link (23-byte default ATT → 20B budget) → negative; the
        // caller's minGcsBytesForSync floor (default 64) skips the round.
        assertTrue(GossipSyncManager.effectiveGcsBytes(400, 20) < 0)
        assertEquals(64, GossipSyncManager.Config().minGcsBytesForSync)
    }

    @Test
    fun maxSingleWriteBytes_attBudgetClamped() {
        // Pre-negotiation default ATT MTU (23) → 20-byte floor.
        assertEquals(20, BleMeshService.maxSingleWriteBytes(23))
        assertEquals(20, BleMeshService.maxSingleWriteBytes(0))
        // Common iOS-negotiated MTU.
        assertEquals(182, BleMeshService.maxSingleWriteBytes(185))
        // REQUEST_MTU (247) and anything larger hit the 185-byte cap.
        assertEquals(185, BleMeshService.maxSingleWriteBytes(247))
        assertEquals(185, BleMeshService.maxSingleWriteBytes(517))
    }

    @Test
    fun advertisedFilterElementsRoundTrip() {
        // The responder bounds its window by the requester's advertised element
        // count (n = m >> p, since buildFilter sets m = count << p). Round-trip
        // through the real filter builder so an encoding change breaks this pin.
        // Mirrors iOS testAdvertisedFilterElementsRoundTrip.
        for (k in listOf(1, 20, 57, 355)) {
            val ids = (0 until k).map { "packet-id-$it".toByteArray(Charsets.UTF_8) }
            val params = GCSFilter.buildFilter(ids, 4096, 0.01)
            assertEquals(
                "advertised element count must round-trip for k=$k",
                k,
                GossipSyncManager.advertisedFilterElements(params.p, params.m, params.data.isEmpty())
            )
        }
        // Empty filter = initial sync ("send me your window") — no size signal, no cap.
        assertNull(GossipSyncManager.advertisedFilterElements(7, 1L, dataIsEmpty = true))
        // Nonsense params → null (no cap; responder stays config-bounded).
        assertNull(GossipSyncManager.advertisedFilterElements(0, 128L, dataIsEmpty = false))
        assertNull(GossipSyncManager.advertisedFilterElements(32, 128L, dataIsEmpty = false))
        // Saturated m only makes the derived n large — min(ownWindow, n) keeps the
        // responder bounded by its own config; no amplification lever.
        assertNotNull(GossipSyncManager.advertisedFilterElements(7, 0xFFFF_FFFFL, dataIsEmpty = false))
    }

    @Test
    fun requestSyncEnvelopeOverheadPinned() {
        // End-to-end pin of REQUEST_SYNC_NON_FILTER_OVERHEAD_BYTES: for every
        // production flag set, a REQUEST_SYNC whose GCS data fills the
        // effective budget must encode (TLV envelope + outer BinaryProtocol
        // packet) within the link frame. If either encoding grows past the
        // constant, this fails before a frame can silently exceed a link on
        // hardware. (REQUEST_SYNC is on the NO_COMPRESS list, so the zero
        // filler cannot shrink via compression.)
        val flagSets = listOf(
            SyncTypeFlags.PUBLIC_MESSAGES,
            SyncTypeFlags.FRAGMENT,
            SyncTypeFlags.LOXATION_ANNOUNCE,
            SyncTypeFlags.LOCATION_UPDATE
        )
        for (link in listOf(120, 182, 185, 400, 512)) {
            val gcs = GossipSyncManager.effectiveGcsBytes(400, link)
            if (gcs < GossipSyncManager.Config().minGcsBytesForSync) continue
            for (flags in flagSets) {
                val req = RequestSyncPacket(p = 20, m = 0xFFFF_FFFFL, data = ByteArray(gcs), types = flags)
                val pkt = BlemeshPacket(
                    version = BlemeshPacket.PROTOCOL_VERSION,
                    type = MessageType.REQUEST_SYNC.value,
                    ttl = 0,
                    timestamp = System.currentTimeMillis(),
                    flags = BlemeshPacket.FLAG_HAS_RECIPIENT,
                    senderId = 0x0102030405060708L,
                    recipientId = 0x1112131415161718L,
                    payload = req.encode(),
                    signature = null
                )
                val encoded = BinaryProtocol.encode(pkt)
                assertNotNull("encode failed (flags ${flags.rawValue}, gcs $gcs)", encoded)
                assertTrue(
                    "REQUEST_SYNC (flags ${flags.rawValue}, gcs $gcs) must fit a ${link}B frame; got ${encoded!!.size}",
                    encoded.size <= link
                )
            }
        }
    }

    // --- Behavioral: sendRequestSync sizes to the delegate's link budget ---

    private fun message(sender: Long, ts: Long): BlemeshPacket = BlemeshPacket(
        version = BlemeshPacket.PROTOCOL_VERSION,
        type = MessageType.MESSAGE.value,
        ttl = BlemeshPacket.MAX_TTL.toByte(),
        timestamp = ts,
        flags = 0,
        senderId = sender,
        recipientId = BlemeshPacket.BROADCAST_ADDRESS,
        payload = byteArrayOf(1, 2, sender.toByte(), (sender shr 8).toByte()),
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

    @Test
    fun requestSyncIsLinkSizedAndSkipsDegenerateLinks() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val mgr = GossipSyncManager(PeerID.fromLongBE(0x0102030405060708L)!!, scope)
            val peer = PeerID.fromLongBE(0x1112131415161718L)!!
            val sent = mutableListOf<BlemeshPacket>()
            var linkMax: Int? = 185
            mgr.delegate = object : GossipSyncManager.Delegate {
                override fun sendPacketToPeer(peerID: PeerID, packet: BlemeshPacket) {
                    sent.add(packet)
                }
                override fun maxSingleFrameBytes(peerID: PeerID): Int? = linkMax
                override fun getConnectedPeers(): List<PeerID> = listOf(peer)
            }

            // Seed enough distinct messages that an unsized (400B-budget)
            // filter would exceed one 185B frame: 200 ids need ~240B of GCS
            // data at P=7, while the link-capped budget is 129B.
            val now = System.currentTimeMillis()
            for (n in 0 until 200) {
                mgr.onPublicPacketSeen(message(sender = 0x2000L + n, ts = now - n))
            }
            val emptyReq = RequestSyncPacket(
                p = 7, m = 1, data = ByteArray(0), types = SyncTypeFlags.PUBLIC_MESSAGES
            )
            awaitCount(mgr, emptyReq, 200)

            // Healthy link: exactly one REQUEST_SYNC, and the WHOLE encoded
            // frame fits the advertised link budget (the router's true
            // fits-single-frame equivalent — requests never fragment).
            mgr.sendRequestSync(peer, SyncTypeFlags.PUBLIC_MESSAGES)
            assertEquals(1, sent.size)
            val pkt = sent[0]
            assertEquals(MessageType.REQUEST_SYNC.value, pkt.type)
            assertEquals(0, pkt.ttl.toInt())
            val encoded = BinaryProtocol.encode(pkt)
            assertNotNull(encoded)
            assertTrue("encoded REQUEST_SYNC ${encoded!!.size}B must fit 185B link", encoded.size <= 185)
            val decoded = RequestSyncPacket.decode(pkt.payload)
            assertNotNull(decoded)
            assertTrue(
                "filter data ${decoded!!.data.size}B must fit the link-capped budget",
                decoded.data.size <= 185 - GossipSyncManager.REQUEST_SYNC_NON_FILTER_OVERHEAD_BYTES
            )

            // Degenerate link (still at default ATT): round skipped entirely —
            // no packet handed to the delegate, so nothing can fragment.
            linkMax = 20
            mgr.sendRequestSync(peer, SyncTypeFlags.PUBLIC_MESSAGES)
            assertEquals("degenerate link must skip the round", 1, sent.size)

            // No link info: config budget (400B) is used — the request may
            // exceed a single BLE frame, which sendPacketToConnection's
            // fragmentation warning makes visible.
            linkMax = null
            mgr.sendRequestSync(peer, SyncTypeFlags.PUBLIC_MESSAGES)
            assertEquals(2, sent.size)
            val wide = RequestSyncPacket.decode(sent[1].payload)
            assertNotNull(wide)
            assertTrue(
                "unsized filter should use the config budget (got ${wide!!.data.size}B)",
                wide.data.size > 185 - GossipSyncManager.REQUEST_SYNC_NON_FILTER_OVERHEAD_BYTES &&
                    wide.data.size <= 400
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun responderWindowCappedByAdvertisedFilterSize() {
        // Codex P2 on iOS PR #85, mirrored: a requester whose (link-capped)
        // filter advertises only n ids can never acknowledge more than its
        // newest n, so the responder must not diff a wider window against it —
        // pre-fix this returned ~200 packets (the full config window) every
        // round forever. An empty filter (initial sync) stays uncapped.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val mgr = GossipSyncManager(PeerID.fromLongBE(0x0102030405060708L)!!, scope)
            val now = System.currentTimeMillis()
            for (n in 0 until 200) {
                mgr.onPublicPacketSeen(message(sender = 0x3000L + n, ts = now - n))
            }
            val emptyReq = RequestSyncPacket(
                p = 7, m = 1, data = ByteArray(0), types = SyncTypeFlags.PUBLIC_MESSAGES
            )
            awaitCount(mgr, emptyReq, 200) // empty filter → uncapped window

            // A filter advertising 5 ids (none of which we hold) caps the
            // response window at 5 — not the ~200 the config window allows.
            val ids = (0 until 5).map { "foreign-id-$it".toByteArray(Charsets.UTF_8) }
            val params = GCSFilter.buildFilter(ids, 400, 0.01)
            val capped = RequestSyncPacket(
                p = params.p, m = params.m, data = params.data,
                types = SyncTypeFlags.PUBLIC_MESSAGES
            )
            val missing = mgr.collectMissing(capped)
            assertTrue(
                "responder window must be capped at the advertised 5 ids (got ${missing.size})",
                missing.size in 1..5
            )
        } finally {
            scope.cancel()
        }
    }
}
