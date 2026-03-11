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

    // Loxation custom messages
    LOXATION_ANNOUNCE(0x40),
    LOXATION_QUERY(0x41),
    LOXATION_CHUNK(0x42),
    LOXATION_COMPLETE(0x43),

    // Location and proximity
    LOCATION_UPDATE(0x44),
    UWB_RANGING(0x45),

    // MLS over BLE
    MLS_MESSAGE(0x48),

    // Gossip sync
    REQUEST_SYNC(0x60);

    companion object {
        fun from(value: Byte): MessageType? = entries.firstOrNull { it.value == value }
    }
}
