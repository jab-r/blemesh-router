package com.blemesh.router.model

/**
 * Core BLE mesh packet structure.
 * Ported from loxation-android BitChatProtocol.BlemeshPacket.
 *
 * Wire format (14-byte fixed header + variable):
 *   [version:1][type:1][ttl:1][timestamp:8][flags:1][payloadLength:2]
 *   [senderId:8][recipientId?:8][payload:var][signature?:64]
 */
data class BlemeshPacket(
    val version: Int,
    val type: Byte,
    val ttl: Byte,
    val timestamp: Long,
    val flags: Int,
    val senderId: Long,
    val recipientId: Long,
    val payload: ByteArray,
    val signature: ByteArray?
) {
    companion object {
        const val PROTOCOL_VERSION = 1
        const val MAX_TTL = 7
        const val BROADCAST_ADDRESS: Long = -1L // 0xFFFFFFFFFFFFFFFF

        const val FLAG_HAS_RECIPIENT = 0x01
        const val FLAG_HAS_SIGNATURE = 0x02
        const val FLAG_IS_COMPRESSED = 0x04
    }

    /** True if this packet is addressed to a specific recipient (not broadcast). */
    val isDirected: Boolean
        get() = recipientId != BROADCAST_ADDRESS

    /** True if this is a broadcast packet. */
    val isBroadcast: Boolean
        get() = recipientId == BROADCAST_ADDRESS

    /** Get the sender as a PeerID. */
    fun senderPeerID(): PeerID? = PeerID.fromLongBE(senderId)

    /** Get the recipient as a PeerID (null if broadcast). */
    fun recipientPeerID(): PeerID? {
        if (isBroadcast) return null
        return PeerID.fromLongBE(recipientId)
    }

    /** Create a copy with decremented TTL for relaying. */
    fun withDecrementedTTL(): BlemeshPacket = copy(ttl = ((ttl.toInt() and 0xFF) - 1).toByte())

    /** Create a copy with a specific TTL. */
    fun withTTL(newTtl: Byte): BlemeshPacket = copy(ttl = newTtl)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlemeshPacket) return false
        return version == other.version && type == other.type && ttl == other.ttl &&
                timestamp == other.timestamp && flags == other.flags &&
                senderId == other.senderId && recipientId == other.recipientId &&
                payload.contentEquals(other.payload) &&
                (signature contentEquals other.signature)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + type
        result = 31 * result + ttl
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + flags
        result = 31 * result + senderId.hashCode()
        result = 31 * result + recipientId.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        return result
    }
}
