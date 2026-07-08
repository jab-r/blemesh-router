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
     *
     * [visited] is the backbone visited-router path (BACKBONE_PATH_ROUTING_SPEC.md).
     * When non-empty it is written as a tagged frame to every peer that has
     * advertised path-tag support ([setPeerBackboneTag]); peers already on the
     * path are skipped (split-horizon) and legacy peers receive a plain frame.
     * Pass an empty list (the default) for an untagged broadcast.
     *
     * [taggedPeersOnly] restricts delivery to tag-aware peers, skipping everyone
     * else entirely (not even a plain frame). Use it for a *forwarded* (multi-hop)
     * broadcast: a plain forwarded frame would arrive with an empty path, so the
     * receiver would mis-learn the *forwarder* as the sender's home router
     * instead of the true origin. Legacy peers can't forward further anyway, and
     * they still learn the correct home router from the origin's direct broadcast
     * (where forwarder == origin). Leave it false (the default) for an origin
     * broadcast, which still reaches legacy peers as plain frames.
     */
    fun broadcast(
        packet: BlemeshPacket,
        visited: List<PeerID> = emptyList(),
        taggedPeersOnly: Boolean = false
    )

    /**
     * Send to a specific connected router peer. No-op if [peerID] is not
     * currently connected via this transport. A non-empty [visited] is written
     * as a tagged frame only when [peerID] supports the path tag; otherwise a
     * plain frame is sent.
     */
    fun sendToPeer(peerID: PeerID, packet: BlemeshPacket, visited: List<PeerID> = emptyList())

    /** PeerIDs of router peers currently connected via this transport. */
    fun connectedPeerIDs(): List<PeerID>

    /**
     * Force-close the connection to [peerID] and fire
     * [Listener.onTransportPeerDisconnected]. Used by the router's liveness
     * probe when a peer stops answering pings (half-open socket after radio
     * loss). No-op if the peer is not connected. The transport's own
     * discovery/reconnect machinery may re-establish the link later.
     */
    fun disconnectPeer(peerID: PeerID)

    /**
     * Whether this transport's underlying capability is supported and
     * currently usable on this device. Call before [start]; if false, the
     * transport should be skipped.
     */
    fun isAvailable(): Boolean

    /**
     * Record whether a connected router [peerID] understands the backbone
     * path-tag frame format (learned from its ROUTER_CAPS). Only tag-aware
     * peers are sent tagged frames; others get plain frames (dedup remains the
     * loop backstop for those hops). Safe to call for an unknown peer.
     */
    fun setPeerBackboneTag(peerID: PeerID, supported: Boolean)

    interface Listener {
        fun onTransportPeerConnected(transport: RouterTransport, peer: PeerID)
        fun onTransportPeerDisconnected(transport: RouterTransport, peer: PeerID)
        /**
         * [visited] is the decoded backbone visited-router path, or empty for a
         * plain (untagged / legacy) frame.
         */
        fun onTransportPacketReceived(
            transport: RouterTransport,
            packet: BlemeshPacket,
            fromPeer: PeerID,
            visited: List<PeerID>
        )
    }
}
