package com.blemesh.router.router

import com.blemesh.router.model.PeerID
import java.nio.ByteBuffer

/**
 * Wire framing for a ROUTER_HOME advertisement (backbone home-router routing).
 *
 * Layout: `[count:2][ (peerID:8, ageSeconds:2) ]*count`, all big-endian.
 *
 * A router periodically advertises the peers currently in its own BLE region —
 * the peers it can authoritatively deliver a directed DM to — so every connected
 * router learns peer->home-router routes DIRECTLY, decoupled from content gossip.
 *
 * Why decoupled (see the review that motivated this): the earlier design carried
 * the home-router claim on the GCS-reconciled announce itself. Once any router
 * re-seeded that announce as content, the receiver "had" it, so its GCS filter
 * suppressed the true home router's copy and the route was never learned — a
 * content re-seed poisoned route learning on 3+ router topologies. Carrying
 * routes in their own frame removes that coupling entirely.
 *
 * [ageSeconds] is how long ago (clock-independent) the advertiser last saw the
 * peer over BLE. The receiver backdates the route's freshness by it, so a route
 * ages on the peer's real sighting time (not receipt time) and a fresher sighting
 * wins over a staler one during a roam (anti-flap). Clamped to the u16 range.
 *
 * Pure/self-contained (no Android deps) so it is unit-testable on the JVM.
 */
object RouterHomeFrame {

    /** One advertised route: [peerID] was last seen [ageSeconds] ago at the advertiser. */
    data class Entry(val peerID: PeerID, val ageSeconds: Int)

    private const val U16_MAX = 0xFFFF

    /** Encode [entries]; ageSeconds and count are clamped to the u16 range. */
    fun encode(entries: List<Entry>): ByteArray {
        val n = minOf(entries.size, U16_MAX)
        val buf = ByteBuffer.allocate(2 + n * 10)
        buf.putShort(n.toShort())
        for (i in 0 until n) {
            val e = entries[i]
            buf.putLong(e.peerID.toLongBE())
            buf.putShort(e.ageSeconds.coerceIn(0, U16_MAX).toShort())
        }
        return buf.array()
    }

    /** Parse a ROUTER_HOME payload, or null if malformed/truncated. */
    fun decode(payload: ByteArray): List<Entry>? {
        if (payload.size < 2) return null
        val buf = ByteBuffer.wrap(payload)
        val count = buf.short.toInt() and 0xFFFF
        if (payload.size != 2 + count * 10) return null
        val out = ArrayList<Entry>(count)
        for (i in 0 until count) {
            val peer = PeerID.fromLongBE(buf.long) ?: return null
            val age = buf.short.toInt() and 0xFFFF
            out.add(Entry(peer, age))
        }
        return out
    }
}
