package com.blemesh.router.transport

import com.blemesh.router.model.PeerID

/**
 * Codec for the visited-router path tag that rides the router-to-router WiFi
 * backbone frame (BACKBONE_PATH_ROUTING_SPEC.md, Tier 3).
 *
 * The backbone frame body is normally the bare [com.blemesh.router.protocol.BinaryProtocol]
 * encoding of a [com.blemesh.router.model.BlemeshPacket], whose first byte is
 * always the protocol version `0x01`. A *tagged* frame prepends a small,
 * router-private header:
 *
 * ```
 *   [marker:1 = 0xBB][fmtVersion:1][visitedCount:1][visited: 8*count][packet bytes]
 * ```
 *
 * - **Self-describing.** The marker `0xBB` can never collide with a plain frame
 *   (whose first byte is the packet version `0x01`), so a receiver distinguishes
 *   the two by the first byte alone — no negotiation is needed to *decode*.
 *   Negotiation (ROUTER_CAPS) only governs whether we *encode* a tagged frame
 *   for a given peer, so a legacy router is never sent a frame it would drop.
 * - **Visited list.** The ordered set of router PeerIDs a packet has traversed on
 *   the backbone. `visited[0]` is the origin (home) router; each forwarding
 *   router appends its own PeerID. A router drops any frame whose visited list
 *   already contains its own PeerID — loop-free by construction at any router
 *   count (drop-on-self), independent of TTL.
 * - **Bounded.** At most [MAX_VISITED] entries, to cap frame growth; a path that
 *   long signals a topology problem, so callers drop rather than truncate.
 *
 * This object is pure Kotlin (no Android / no BinaryProtocol dependency) so the
 * wire format is unit-testable in isolation.
 */
object BackboneFrame {
    /** First byte of a tagged frame. Distinct from the packet version (0x01). */
    const val MARKER: Byte = 0xBB.toByte()

    /** Header layout version, bumped only if the byte layout below changes. */
    const val FMT_VERSION: Byte = 0x01

    /** Maximum visited-router entries carried in a single frame. */
    const val MAX_VISITED = 16

    private const val PEER_ID_BYTES = 8
    private const val HEADER_FIXED_BYTES = 3 // marker + fmtVersion + count

    /** True if [frameBody] carries a backbone path-tag header. */
    fun isTagged(frameBody: ByteArray): Boolean =
        frameBody.isNotEmpty() && frameBody[0] == MARKER

    /**
     * Wrap [packetBytes] (an already-encoded BlemeshPacket) with a visited-router
     * header. [visited] must already be within [MAX_VISITED]; it is truncated
     * defensively but callers should have dropped an over-length path first.
     * PeerIDs that fail to serialize are skipped (never happens for well-formed
     * PeerIDs).
     */
    fun encode(visited: List<PeerID>, packetBytes: ByteArray): ByteArray {
        val idBytes = visited.asSequence()
            .take(MAX_VISITED)
            .mapNotNull { it.toBytes() }
            .toList()
        val out = ByteArray(HEADER_FIXED_BYTES + idBytes.size * PEER_ID_BYTES + packetBytes.size)
        out[0] = MARKER
        out[1] = FMT_VERSION
        out[2] = (idBytes.size and 0xFF).toByte()
        var off = HEADER_FIXED_BYTES
        for (b in idBytes) {
            System.arraycopy(b, 0, out, off, PEER_ID_BYTES)
            off += PEER_ID_BYTES
        }
        System.arraycopy(packetBytes, 0, out, off, packetBytes.size)
        return out
    }

    /** A decoded tagged frame: the visited path and the inner packet bytes. */
    data class Decoded(val visited: List<PeerID>, val packetBytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return visited == other.visited && packetBytes.contentEquals(other.packetBytes)
        }

        override fun hashCode(): Int = 31 * visited.hashCode() + packetBytes.contentHashCode()
    }

    /**
     * Split a tagged frame into (visited list, inner packet bytes). Returns null
     * when [frameBody] is not a well-formed tagged frame — either a plain frame
     * (no marker) or a malformed / unknown-version tagged frame. In both cases
     * the caller treats [frameBody] as a plain BlemeshPacket encoding; a
     * malformed tagged frame then fails packet decode and is dropped, keeping
     * the length-prefixed stream in sync.
     */
    fun decode(frameBody: ByteArray): Decoded? {
        if (frameBody.size < HEADER_FIXED_BYTES) return null
        if (frameBody[0] != MARKER) return null
        if (frameBody[1] != FMT_VERSION) return null
        val count = frameBody[2].toInt() and 0xFF
        if (count > MAX_VISITED) return null
        val headerEnd = HEADER_FIXED_BYTES + count * PEER_ID_BYTES
        if (headerEnd > frameBody.size) return null
        val visited = ArrayList<PeerID>(count)
        var off = HEADER_FIXED_BYTES
        repeat(count) {
            val id = PeerID.fromBytes(frameBody.copyOfRange(off, off + PEER_ID_BYTES)) ?: return null
            visited.add(id)
            off += PEER_ID_BYTES
        }
        return Decoded(visited, frameBody.copyOfRange(headerEnd, frameBody.size))
    }
}
