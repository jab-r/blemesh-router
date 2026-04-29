package com.blemesh.router.transport

import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.PeerID

/**
 * A pluggable router-to-router transport (TCP-over-LAN, Wi-Fi Aware, Wi-Fi Direct, …).
 *
 * Each transport is responsible for discovery, peer identity exchange (so
 * the [Listener] always sees connected peers keyed by [PeerID]), and the
 * length-prefixed wire framing of [BlemeshPacket] data.
 *
 * Multiple transports may be active concurrently; [com.blemesh.router.router.MeshRouterService]
 * dedupes packets crossing the bridge so a packet arriving on more than one
 * transport is delivered locally only once.
 */
interface RouterTransport {

    /** Short identifier used in logs / diagnostics: "tcp", "aware", "direct". */
    val name: String

    var listener: Listener?

    /** Start discovery, accept inbound connections, etc. Idempotent. */
    fun start()

    /** Tear down all connections and stop discovery. Idempotent. */
    fun stop()

    /**
     * Send to every currently connected router peer. Encoding is the
     * transport's responsibility; callers pass the unencoded packet.
     */
    fun broadcast(packet: BlemeshPacket)

    /**
     * Send to a specific connected router peer. No-op if [peerID] is not
     * currently connected via this transport.
     */
    fun sendToPeer(peerID: PeerID, packet: BlemeshPacket)

    /** PeerIDs of router peers currently connected via this transport. */
    fun connectedPeerIDs(): List<PeerID>

    /**
     * Whether this transport's underlying capability is supported and
     * currently usable on this device. Call before [start]; if false, the
     * transport should be skipped.
     */
    fun isAvailable(): Boolean

    interface Listener {
        fun onTransportPeerConnected(transport: RouterTransport, peer: PeerID)
        fun onTransportPeerDisconnected(transport: RouterTransport, peer: PeerID)
        fun onTransportPacketReceived(transport: RouterTransport, packet: BlemeshPacket, fromPeer: PeerID)
    }
}
