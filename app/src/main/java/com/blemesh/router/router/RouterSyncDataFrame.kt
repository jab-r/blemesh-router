package com.blemesh.router.router

import com.blemesh.router.model.PeerID
import java.nio.ByteBuffer

/**
 * Wire framing for a ROUTER_SYNC_DATA payload (backbone GCS anti-entropy).
 *
 * Layout: `[flags:1][originRouter:8 if FLAG_HAS_ORIGIN][encodedPacket:var]`
 *
 * The optional origin-router is the replying router's *authoritative* claim that
 * the reconciled packet's sender sits in its own BLE region — i.e. "route DMs
 * for this sender to me." It is set ONLY when the replier is that sender's home
 * router (isRegionMember), so the receiver can update its peer→home-router map
 * from a re-seeded announce even when the real-time announce push was lost. When
 * absent (FLAG_HAS_ORIGIN clear), the frame is content-only: the packet still
 * re-seeds the gossip store, but no routing claim is made.
 *
 * Why the origin can't be inferred from the packet or the transport (see the
 * MeshRouterService gossip section): the home router lived in the backbone
 * frame's visited path, which is discarded when a router stores the packet, and
 * the sync frame's sender is only "who held the content," not "whose region the
 * peer is in." So it must be carried explicitly, and the receiver only trusts a
 * self-claim it can independently reach (origin == the frame's sender router).
 *
 * Pure/self-contained (no Android deps) so it is unit-testable on the JVM.
 */
object RouterSyncDataFrame {

    private const val FLAG_HAS_ORIGIN = 0x01

    data class Decoded(val originRouter: PeerID?, val encodedPacket: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return originRouter == other.originRouter &&
                encodedPacket.contentEquals(other.encodedPacket)
        }

        override fun hashCode(): Int =
            31 * (originRouter?.hashCode() ?: 0) + encodedPacket.contentHashCode()
    }

    /** Frame [encodedPacket], prefixing the [originRouter] claim when non-null. */
    fun encode(originRouter: PeerID?, encodedPacket: ByteArray): ByteArray {
        return if (originRouter != null) {
            ByteBuffer.allocate(1 + 8 + encodedPacket.size)
                .put(FLAG_HAS_ORIGIN.toByte())
                .putLong(originRouter.toLongBE())
                .put(encodedPacket)
                .array()
        } else {
            ByteBuffer.allocate(1 + encodedPacket.size)
                .put(0)
                .put(encodedPacket)
                .array()
        }
    }

    /** Parse a framed payload, or null if truncated/empty. */
    fun decode(payload: ByteArray): Decoded? {
        if (payload.isEmpty()) return null
        val flags = payload[0].toInt() and 0xFF
        return if ((flags and FLAG_HAS_ORIGIN) != 0) {
            if (payload.size < 9) return null
            val originLong = ByteBuffer.wrap(payload, 1, 8).long
            Decoded(PeerID.fromLongBE(originLong), payload.copyOfRange(9, payload.size))
        } else {
            Decoded(null, payload.copyOfRange(1, payload.size))
        }
    }
}
