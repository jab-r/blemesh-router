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
    ROUTER_PONG(0x71);

    companion object {
        fun from(value: Byte): MessageType? = entries.firstOrNull { it.value == value }
    }
}
