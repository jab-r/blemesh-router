package com.blemesh.router.model

/**
 * BLE mesh message types aligned with iOS/Android BlemeshProtocol for wire parity.
 */
enum class MessageType(val value: Byte) {
    // Core user/system messages
    ANNOUNCE(0x01),
    LEAVE(0x03),
    MESSAGE(0x04),
    FRAGMENT(0x05),
    DELIVERY_ACK(0x0A),
    DELIVERY_STATUS_REQUEST(0x0B),
    READ_RECEIPT(0x0C),

    // Noise protocol messages
    NOISE_HANDSHAKE(0x10),
    NOISE_ENCRYPTED(0x12),
    NOISE_IDENTITY_ANNOUNCE(0x13),
    PRIVATE_MESSAGE(0x1A),

    // Protocol negotiation / reliability (loxation-android + loxation-sw)
    VERSION_HELLO(0x20),
    VERSION_ACK(0x21),
    PROTOCOL_ACK(0x22),
    PROTOCOL_NACK(0x23),
    SYSTEM_VALIDATION(0x24),
    HANDSHAKE_REQUEST(0x25),

    // Social
    FAVORITED(0x30),
    UNFAVORITED(0x31),

    // Loxation custom messages
    LOXATION_ANNOUNCE(0x40),
    LOXATION_QUERY(0x41),
    LOXATION_CHUNK(0x42),
    LOXATION_COMPLETE(0x43),

    // Location and proximity
    LOCATION_UPDATE(0x44),
    UWB_RANGING(0x45),
    PROXIMITY_ALERT(0x46),
    BEACON_CONTEXT(0x47),

    // MLS over BLE
    MLS_MESSAGE(0x48),
    UWB_TOKEN_EXCHANGE(0x49),

    // WebRTC signaling
    WEBRTC_SDP(0x50),
    WEBRTC_ICE(0x51),

    // Gossip sync
    REQUEST_SYNC(0x60),

    // Router-to-router only (never enters the BLE mesh)
    ROUTER_PING(0x70),
    ROUTER_PONG(0x71),
    // Backbone capability advertisement (BACKBONE_PATH_ROUTING_SPEC.md): sent
    // directed to a freshly-connected router peer to negotiate path-tag support.
    ROUTER_CAPS(0x72),
    // Backbone GCS anti-entropy (router-to-router gossip). ROUTER_SYNC carries a
    // RequestSync TLV (the sender's GCS filter of crossable content it holds);
    // ROUTER_SYNC_DATA carries one packet the peer was missing, framed with an
    // optional origin-router claim (RouterSyncDataFrame) so a re-seeded announce
    // can also rebuild the receiver's peer→home-router map for reliable DMs.
    // Both are directed control frames, never injected into BLE.
    ROUTER_SYNC(0x73),
    ROUTER_SYNC_DATA(0x74);

    companion object {
        fun from(value: Byte): MessageType? = entries.firstOrNull { it.value == value }

        // ------------------------------------------------------------------
        // Per-type policy table.
        //
        // Every behavior that branches on a message type is decided here, so
        // porting a new type from the references means answering each
        // question — compress? bridge? buffer? track? — in one place instead
        // of hunting through three files. (A stale include-list in the router
        // once silently broke cross-segment PROTOCOL_ACK, and a missed
        // compression exclusion corrupted payloads on iOS.)
        //
        // Predicates take the raw wire byte, not the enum: policy must also
        // cover type codes this build doesn't know by name, and each
        // predicate documents its default for unknown codes.
        // ------------------------------------------------------------------

        private fun bytes(vararg types: MessageType): Set<Byte> =
            types.mapTo(HashSet()) { it.value }

        /**
         * Payloads that are encrypted, already-compressed, or entropy-dense
         * binary — compression wastes cycles on them and a cross-platform
         * decode mismatch here has corrupted payloads on iOS before.
         * Unknown codes: compressible (the payload-size heuristic still gates).
         */
        private val NO_COMPRESS = bytes(
            NOISE_HANDSHAKE, NOISE_ENCRYPTED,
            LOXATION_ANNOUNCE, LOXATION_CHUNK, LOXATION_QUERY, LOXATION_COMPLETE,
            MLS_MESSAGE, REQUEST_SYNC, LOCATION_UPDATE,
        )
        fun isCompressible(type: Byte): Boolean = type !in NO_COMPRESS

        /**
         * Types that must NOT cross a Wi-Fi bridge in either direction:
         * ROUTER_PING/PONG/CAPS are router-internal control traffic; UWB_RANGING
         * is meaningful only within BLE radio range.
         * Unknown codes: bridgeable — an include-list here silently dropped
         * PROTOCOL_ACK / HANDSHAKE_REQUEST / version negotiation / WebRTC
         * signaling between reference peers.
         */
        private val NON_BRIDGEABLE = bytes(
            UWB_RANGING, ROUTER_PING, ROUTER_PONG, ROUTER_CAPS, ROUTER_SYNC, ROUTER_SYNC_DATA
        )
        fun isBridgeable(type: Byte): Boolean = type !in NON_BRIDGEABLE

        /**
         * Worth holding for store-and-forward replay while a known local peer
         * is briefly off-air. ANNOUNCE / FRAGMENT / sync traffic is excluded:
         * heartbeats regenerate on the next interval and fragments carry
         * their own re-assembly state. NOISE_HANDSHAKE / NOISE_ENCRYPTED are
         * excluded too: both apps treat an incoming handshake m1 on an
         * established session as "peer restarted" and tear the session down,
         * so a stale m1 replayed minutes later churns a session the phones
         * already re-established over another path (and a replayed 0x12 is
         * useless after any session reset anyway).
         * Unknown codes: NOT eligible — only buffer types whose replay
         * semantics we understand.
         */
        private val SNF_ELIGIBLE = bytes(
            MESSAGE, DELIVERY_ACK, DELIVERY_STATUS_REQUEST, READ_RECEIPT,
            NOISE_IDENTITY_ANNOUNCE,
            LOXATION_ANNOUNCE, LOXATION_QUERY, LOXATION_CHUNK, LOXATION_COMPLETE,
            LOCATION_UPDATE, MLS_MESSAGE,
        )
        fun isStoreAndForwardEligible(type: Byte): Boolean = type in SNF_ELIGIBLE

        /**
         * Types the router's gossip stores hold and can replay to a peer's
         * REQUEST_SYNC (mirrors GossipSyncManager.onPublicPacketSeen). Used
         * to pick store-and-serve over BLE broadcast for bridged ttl=0
         * gossip replays: reference phones (iOS now, Android Batch3-E) drop
         * unsolicited syncable packets at ttl == 0, so a spontaneous push
         * is dead airtime, while a stored copy is delivered on the phone's
         * next registered sync — which passes its gate.
         * Unknown codes: NOT stored — the gossip stores are an include-list
         * by construction, and a push at least reaches open sync windows.
         */
        private val GOSSIP_STORED = bytes(ANNOUNCE, MESSAGE, FRAGMENT, LOXATION_ANNOUNCE, LOCATION_UPDATE)
        fun isGossipStored(type: Byte): Boolean = type in GOSSIP_STORED

        /**
         * Broadcast types that still cross the Wi-Fi backbone under region-local
         * routing. Announce/presence broadcasts must propagate for cross-router
         * discovery (a router learns which router a peer sits behind from that
         * peer's crossing announce, and phones learn remote peers exist). The
         * unencrypted public MESSAGE broadcast is deliberately NOT here — public
         * chat stays region-local; it is the bulk broadcast traffic we cut off
         * the backbone. A FRAGMENT is classified by its inner reassembled type
         * (see MeshRouterService), not by 0x05 itself.
         * Unknown codes: NOT crossed — only propagate presence types we know.
         */
        private val CROSSES_BACKBONE = bytes(ANNOUNCE, LOXATION_ANNOUNCE, LEAVE, LOCATION_UPDATE)
        fun crossesBackboneAsBroadcast(type: Byte): Boolean = type in CROSSES_BACKBONE

        /**
         * Counted by the retry-storm diagnostic. ANNOUNCE is a periodic
         * heartbeat (very chatty); FRAGMENT de-duplicates via its fragment
         * ID. Storms on NOISE_HANDSHAKE / NOISE_ENCRYPTED / MESSAGE /
         * DELIVERY_ACK are the real signal.
         * Unknown codes: tracked — an unknown chatty type is exactly what
         * the diagnostic should surface.
         */
        private val RETRY_TRACKING_EXEMPT = bytes(ANNOUNCE, FRAGMENT)
        fun isRetryTracked(type: Byte): Boolean = type !in RETRY_TRACKING_EXEMPT
    }
}
