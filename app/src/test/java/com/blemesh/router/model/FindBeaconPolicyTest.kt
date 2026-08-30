package com.blemesh.router.model

import com.blemesh.router.protocol.CompressionUtil
import com.blemesh.router.sync.SyncTypeFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The router's per-type policy contract for find beacons (FIND_MODE_SPEC.md
 * §2/§5/§6). Every one of these is a cross-repo or cross-subsystem agreement
 * that fails silently on hardware if it drifts, so each is pinned here.
 */
class FindBeaconPolicyTest {

    @Test
    fun findBeaconIs0x4A() {
        // Interop-locked with loxation-sw and loxation-android. 0x4A was
        // verified free in all three before assignment; renumbering it silently
        // breaks decode on the platform that didn't move.
        assertEquals(0x4A.toByte(), MessageType.FIND_BEACON.value)
        assertEquals(MessageType.FIND_BEACON, MessageType.from(0x4A))
    }

    @Test
    fun typeCodeCollidesWithNothing() {
        val byValue = MessageType.entries.groupBy { it.value }
        val collisions = byValue.filterValues { it.size > 1 }
        assertTrue("duplicate type codes: $collisions", collisions.isEmpty())
    }

    @Test
    fun crossesTheBackboneAsBroadcast() {
        // §6: the venue-wide exception REGION_LOCAL_ROUTING_SPEC.md reserved.
        // Friend-finding that stops at the region boundary is the feature not
        // working.
        assertTrue(MessageType.crossesBackboneAsBroadcast(MessageType.FIND_BEACON.value))
    }

    @Test
    fun crossingIsRateLimited() {
        // §6: the crossing allowance is only affordable because it is metered.
        // These two must move together — allowlisted but unmetered puts every
        // FIND_REQ-boosted sender's ~5s cadence on the shared backbone.
        assertTrue(MessageType.isBackboneRateLimited(MessageType.FIND_BEACON.value))
        // Announces cross at their own ~30s cadence and every crossing feeds
        // home-router learning — metering them would break directed routing.
        assertFalse(MessageType.isBackboneRateLimited(MessageType.ANNOUNCE.value))
        assertFalse(MessageType.isBackboneRateLimited(MessageType.LOCATION_UPDATE.value))
    }

    @Test
    fun isGossipStoredSoTtlZeroReplaysAreServedNotPushed() {
        // Must stay in lockstep with the find-beacon bucket in
        // GossipSyncManager: a syncable type missing from GOSSIP_STORED is
        // pushed into BLE at ttl=0, where reference phones drop it at their RSR
        // flood-gate with a security warning.
        assertTrue(MessageType.isGossipStored(MessageType.FIND_BEACON.value))
    }

    @Test
    fun isBridgeable() {
        assertTrue(MessageType.isBridgeable(MessageType.FIND_BEACON.value))
    }

    @Test
    fun isNotStoreAndForwardEligible() {
        // Store-and-forward is a directed-only replay buffer; the beacon is
        // broadcast-only. Its carry story is the gossip store instead.
        assertFalse(MessageType.isStoreAndForwardEligible(MessageType.FIND_BEACON.value))
    }

    @Test
    fun isNotRetryTracked() {
        // A FIND_REQ-boosted sender emits every ~5s — six originations per 30s
        // window on one bucket, i.e. a permanent bogus RETRY-STORM warning.
        assertFalse(MessageType.isRetryTracked(MessageType.FIND_BEACON.value))
    }

    @Test
    fun isNotControlLane() {
        // Data, not router-internal control: it must not compete with
        // ping/pong/caps for the small high-priority send lane.
        assertFalse(MessageType.isControlLane(MessageType.FIND_BEACON.value))
    }

    @Test
    fun onTheLockstepNoCompressList() {
        // The shipped beacon is 110 payload bytes ([ver:1][keyId:4][nonce:12]
        // [ct:13][tag:16][sig:64], FIND_MODE_SPEC.md §2) — past
        // CompressionUtil's 100-byte threshold, so the size heuristic no longer
        // keeps it raw; only the type list does. Both phones pin 0x4A on their
        // noCompressTypes (iOS BinaryProtocol, Android
        // BitChatProtocol.NO_COMPRESS_TYPES); the router's list is the third
        // copy of that lockstep set and must match.
        assertTrue(CompressionUtil.shouldCompress(ByteArray(110)))
        assertFalse(MessageType.isCompressible(MessageType.FIND_BEACON.value))
    }

    @Test
    fun claimsSyncBit11AtTheEndOfTheFixedOrder() {
        // §5: a NEW bucket at the END. Bit 11 is the next free index in all
        // three implementations; renumbering breaks REQUEST_SYNC interop.
        assertEquals(1L shl 11, SyncTypeFlags.FIND_BEACON.rawValue)
        assertTrue(SyncTypeFlags.FIND_BEACON.contains(MessageType.FIND_BEACON))
        assertEquals(listOf(MessageType.FIND_BEACON), SyncTypeFlags.FIND_BEACON.toMessageTypes())
        // Round-trips over the wire encoding.
        val encoded = SyncTypeFlags.FIND_BEACON.toData()
        assertEquals(SyncTypeFlags.FIND_BEACON, SyncTypeFlags.decode(encoded!!))
    }

    @Test
    fun findBeaconIsNotInTheLegacyPublicMessagesBucket() {
        // A round that predates find mode must not implicitly request beacons:
        // an old peer's PUBLIC_MESSAGES filter carries no beacon ids, so
        // including them in that bucket would re-send the whole beacon store
        // every round forever.
        assertFalse(SyncTypeFlags.PUBLIC_MESSAGES.contains(MessageType.FIND_BEACON))
    }

    @Test
    fun unknownNeighbouringCodesStayUnassigned() {
        // Guards against a future type quietly landing on 0x4A's neighbours in
        // one repo only.
        assertNull(MessageType.from(0x4B))
        assertNull(MessageType.from(0x4C))
    }
}
