package com.blemesh.router.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * PeerID type for type safety.
 * Matches iOS/Android PeerID format: 16 hex characters (8 bytes).
 * PeerID = first 8 bytes of hex(SHA-256(noise_static_public_key))
 */
data class PeerID(val rawValue: String) {
    companion object {
        const val HEX_LENGTH = 16

        fun fromHexString(hexString: String): PeerID? {
            val normalized = hexString.lowercase().replace(" ", "")
            return if (normalized.length == HEX_LENGTH && normalized.all { it.isLetterOrDigit() }) {
                PeerID(normalized)
            } else {
                null
            }
        }

        fun fromBytes(bytes: ByteArray): PeerID? {
            if (bytes.size != 8) return null
            val hex = bytes.joinToString("") { "%02x".format(it) }
            return PeerID(hex)
        }

        fun generate(): PeerID {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            return fromBytes(bytes)!!
        }

        fun fromLongBE(value: Long): PeerID? {
            val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
            return fromBytes(bb)
        }
    }

    fun toBytes(): ByteArray? {
        if (rawValue.length != HEX_LENGTH) return null
        return rawValue.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
    }

    fun toHexString(): String = rawValue.lowercase()

    fun toLongBE(): Long {
        val bytes = toBytes() ?: return 0L
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).long
    }
}
