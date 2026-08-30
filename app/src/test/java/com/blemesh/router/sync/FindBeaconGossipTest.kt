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
import org.junit.Assert.fail
import org.junit.Test

/**
 * The find-beacon DTN store (FIND_MODE_SPEC.md §5): newest beacon per sender,
 * ~900s horizon, small capacity, its own RSR bucket at the END of the fixed
 * order — and, unlike every other position store, it survives its sender's
 * departure.
 *
 * `onPublicPacketSeen` dispatches to Dispatchers.Default, so ingest is
 * asynchronous and unordered. Because this store is keep-latest-per-SENDER its
 * row count does not change as a sender's beacons land, which makes a
 * count-based wait useless (it returns after the first insert and reads a
 * half-applied state). So the helpers below split by claim shape:
 * [awaitStore] polls until something MUST appear, and [assertStaysTrue] holds
 * an invariant across a settle window for claims that something must NEVER
 * happen — a violation lands within milliseconds of being dispatched, so the
 * window catches it, while a correct implementation can never break it.
 */
class FindBeaconGossipTest {

    private val settleMs = 500L
    private val timeoutMs = 5_000L

    private fun manager(scope: CoroutineScope, config: GossipSyncManager.Config = GossipSyncManager.Config()) =
        GossipSyncManager(PeerID.fromLongBE(0x0102030405060708L)!!, scope, config)

    private fun beacon(sender: Long, ts: Long): BlemeshPacket = BlemeshPacket(
        version = BlemeshPacket.PROTOCOL_VERSION,
        type = MessageType.FIND_BEACON.value,
        ttl = BlemeshPacket.MAX_TTL.toByte(),
        timestamp = ts,
        flags = 0,
        senderId = sender,
        recipientId = BlemeshPacket.BROADCAST_ADDRESS,
        payload = ByteArray(48) { (ts + sender).toByte() },
        signature = null
    )

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

    /** An EMPTY filter asks for the whole window, so collectMissing == the store. */
    private fun emptyRequest(types: SyncTypeFlags) =
        RequestSyncPacket(p = 19, m = 1, data = ByteArray(0), types = types)

    private fun describe(served: List<BlemeshPacket>): String =
        served.joinToString { "sender=${it.senderId} ts=${it.timestamp}" }.ifEmpty { "<empty>" }

    /** Poll until [predicate] holds. For claims that something MUST appear. */
    private fun awaitStore(
        mgr: GossipSyncManager,
        req: RequestSyncPacket,
        desc: String,
        predicate: (List<BlemeshPacket>) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(mgr.collectMissing(req))) return
            Thread.sleep(10)
        }
        fail("$desc — store settled to [${describe(mgr.collectMissing(req))}]")
    }

    /**
     * Hold [predicate] across a settle window. For claims that something must
     * NEVER happen: the offending write is dispatched already, so it lands
     * inside the window, while a correct implementation holds for any window.
     */
    private fun assertStaysTrue(
        mgr: GossipSyncManager,
        req: RequestSyncPacket,
        desc: String,
        predicate: (List<BlemeshPacket>) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + settleMs
        while (System.currentTimeMillis() < deadline) {
            val served = mgr.collectMissing(req)
            if (!predicate(served)) fail("$desc — store held [${describe(served)}]")
            Thread.sleep(10)
        }
    }

    private fun <T> withManager(
        config: GossipSyncManager.Config = GossipSyncManager.Config(),
        body: (GossipSyncManager) -> T
    ): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            return body(manager(scope, config))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun keepsOnlyTheNewestBeaconPerSender() = withManager { mgr ->
        val now = System.currentTimeMillis()
        val req = emptyRequest(SyncTypeFlags.FIND_BEACON)
        mgr.onPublicPacketSeen(beacon(0xAAL, now - 60_000))
        mgr.onPublicPacketSeen(beacon(0xAAL, now - 30_000))
        mgr.onPublicPacketSeen(beacon(0xAAL, now - 1_000))

        // Three beacons, one sender, one stored row — the newest supersedes.
        awaitStore(mgr, req, "newest beacon never became the stored one") {
            it.size == 1 && it[0].timestamp == now - 1_000
        }
        // ...and stays that way once all three have landed: a stream buffer
        // would grow to three rows as the rest arrive.
        assertStaysTrue(mgr, req, "store did not stay newest-per-sender") {
            it.size == 1 && it[0].timestamp == now - 1_000
        }
    }

    @Test
    fun insertionIsMonotonic_replayedOlderBeaconIsIgnored() = withManager { mgr ->
        val now = System.currentTimeMillis()
        val req = emptyRequest(SyncTypeFlags.FIND_BEACON)
        mgr.onPublicPacketSeen(beacon(0xAAL, now - 1_000))
        awaitStore(mgr, req, "fresh beacon was never stored") { it.size == 1 }

        // A replay out of another router's store arrives out of order. It must
        // never surface, at any instant.
        mgr.onPublicPacketSeen(beacon(0xAAL, now - 300_000))
        assertStaysTrue(mgr, req, "replayed older beacon displaced a fresher one") {
            it.size == 1 && it[0].timestamp == now - 1_000
        }
    }

    @Test
    fun distinctSendersEachKeepARow() = withManager { mgr ->
        val now = System.currentTimeMillis()
        val req = emptyRequest(SyncTypeFlags.FIND_BEACON)
        for (s in 1L..10L) mgr.onPublicPacketSeen(beacon(s, now - s * 100))
        awaitStore(mgr, req, "expected one row per sender") { it.size == 10 }
    }

    @Test
    fun horizonIsNineHundredSeconds() = withManager { mgr ->
        val now = System.currentTimeMillis()
        val req = emptyRequest(SyncTypeFlags.FIND_BEACON)
        assertEquals(900L, GossipSyncManager.Config().findBeaconMaxAgeSeconds)

        // A ten-minute-old friend position is the product (a DTN mule carry),
        // NOT the 60s "a stale position misleads" case that governs public 0x44.
        mgr.onPublicPacketSeen(beacon(0xAAL, now - 600_000))
        awaitStore(mgr, req, "10-minute-old beacon should still be carried") { it.size == 1 }

        // Past the horizon it is never offered.
        mgr.onPublicPacketSeen(beacon(0xBBL, now - 901_000))
        assertStaysTrue(mgr, req, "beacon past the 900s horizon was served") {
            it.none { pkt -> pkt.senderId == 0xBBL }
        }
    }

    @Test
    fun capacityIsAHardBoundOnDistinctSenders() {
        withManager(GossipSyncManager.Config(findBeaconCapacity = 5)) { mgr ->
            val now = System.currentTimeMillis()
            val req = emptyRequest(SyncTypeFlags.FIND_BEACON)
            // Fire 20 senders concurrently: the bound must hold at EVERY
            // instant a sync round could read the store, not merely once
            // ingest settles. (An unsynchronized reader used to observe the
            // moment between an insert's merge and its eviction, and would
            // have put capacity+1 ids into a round.)
            for (s in 1L..20L) mgr.onPublicPacketSeen(beacon(s, now - s * 1_000))
            assertStaysTrue(mgr, req, "capacity was exceeded") { it.size <= 5 }
            awaitStore(mgr, req, "store did not fill to capacity") { it.size == 5 }
        }
    }

    @Test
    fun evictionIsOldestFixFirst() {
        withManager(GossipSyncManager.Config(findBeaconCapacity = 5)) { mgr ->
            val now = System.currentTimeMillis()
            val req = emptyRequest(SyncTypeFlags.FIND_BEACON)
            // Ingest is concurrent and unordered, and eviction is an ONLINE
            // policy — which senders survive genuinely depends on arrival
            // order. So drive arrivals one at a time to pin the policy itself.
            // Sender n has a fix n seconds old, so a higher n is older.
            for (s in 1L..20L) {
                mgr.onPublicPacketSeen(beacon(s, now - s * 1_000))
                awaitStore(mgr, req, "beacon from sender $s never landed") { served ->
                    served.size == minOf(s.toInt(), 5) &&
                        (s > 5 || served.any { it.senderId == s })
                }
            }
            // The five freshest fixes survive; a burst of stale one-off senders
            // cannot push out an actively-tracked peer.
            assertStaysTrue(mgr, req, "eviction was not oldest-fix-first") {
                it.map { pkt -> pkt.senderId }.toSet() == setOf(1L, 2L, 3L, 4L, 5L)
            }
        }
    }

    @Test
    fun beaconSurvivesItsSendersDeparture() = withManager { mgr ->
        val now = System.currentTimeMillis()
        val beaconReq = emptyRequest(SyncTypeFlags.FIND_BEACON)
        val announceReq = emptyRequest(SyncTypeFlags.ANNOUNCE)
        val sender = PeerID.fromLongBE(0xAAL)!!

        mgr.onPublicPacketSeen(announce(0xAAL, now))
        mgr.onPublicPacketSeen(beacon(0xAAL, now - 5_000))
        awaitStore(mgr, beaconReq, "beacon was never stored") { it.size == 1 }
        awaitStore(mgr, announceReq, "announce was never stored") { it.size == 1 }

        // The peer walks out of announce range and the reaper retires it.
        mgr.removeAnnouncementForPeer(sender)

        // Presence goes...
        awaitStore(mgr, announceReq, "announce should be retired on departure") { it.isEmpty() }
        // ...but the beacon is KEPT. "Which way did my people go" is exactly the
        // question departure raises; purging would defeat the 900s horizon for
        // precisely the peers a DTN mule carry exists to cover.
        assertStaysTrue(mgr, beaconReq, "beacon was purged on its sender's departure") {
            it.size == 1 && it[0].senderId == 0xAAL
        }
    }

    @Test
    fun beaconsAreNotServedToALegacyPublicMessagesRound() = withManager { mgr ->
        val now = System.currentTimeMillis()
        mgr.onPublicPacketSeen(beacon(0xAAL, now))
        awaitStore(mgr, emptyRequest(SyncTypeFlags.FIND_BEACON), "beacon was never stored") { it.size == 1 }
        // An un-updated peer's round must not receive beacons it never asked
        // for — it cannot decrypt them and would never acknowledge their ids,
        // so they would re-send every round forever.
        assertStaysTrue(
            mgr,
            emptyRequest(SyncTypeFlags.PUBLIC_MESSAGES),
            "beacon leaked into a legacy publicMessages round"
        ) { it.isEmpty() }
    }

    @Test
    fun beaconBucketIsLastAndSharesTheWaterFilledBudget() = withManager { mgr ->
        val now = System.currentTimeMillis()
        // A dense cell: many announces AND many beacons. The per-bucket
        // water-fill must reserve room for the new last bucket rather than
        // letting the older buckets crowd it out.
        for (s in 1L..400L) mgr.onPublicPacketSeen(announce(s, now))
        for (s in 1L..400L) mgr.onPublicPacketSeen(beacon(1000L + s, now))
        val mixed = SyncTypeFlags.ANNOUNCE.union(SyncTypeFlags.FIND_BEACON)
        val req = emptyRequest(mixed)

        awaitStore(mgr, req, "mixed window never filled") { served ->
            served.count { it.type == MessageType.FIND_BEACON.value } > 0 &&
                served.count { it.type == MessageType.ANNOUNCE.value } > 0
        }
        val served = mgr.collectMissing(req)
        val beacons = served.count { it.type == MessageType.FIND_BEACON.value }
        val announces = served.count { it.type == MessageType.ANNOUNCE.value }
        assertTrue("announces crowded out: $announces", announces > 0)
        assertTrue("beacon bucket did not get a fair share: $beacons of ${served.size}",
            beacons >= served.size / 4)
    }

    @Test
    fun directedBeaconIsNeverStored() = withManager { mgr ->
        val now = System.currentTimeMillis()
        // A 0x4A with a recipient is malformed per §2 (the beacon is always a
        // broadcast). Storing one would serve a private frame to the whole
        // segment on the next sync round.
        val directed = beacon(0xAAL, now).copy(
            flags = BlemeshPacket.FLAG_HAS_RECIPIENT,
            recipientId = 0xBBL
        )
        mgr.onPublicPacketSeen(directed)
        assertStaysTrue(
            mgr,
            emptyRequest(SyncTypeFlags.FIND_BEACON),
            "a directed beacon was stored and would be served to the segment"
        ) { it.isEmpty() }
    }
}
