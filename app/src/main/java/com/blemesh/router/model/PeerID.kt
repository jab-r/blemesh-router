package com.blemesh.router.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
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
            // Strictly hex: isLetterOrDigit() would admit g–z and Unicode
            // letters, constructing a PeerID whose toBytes() is null and whose
            // toLongBE() silently aliases to peer 0.
            return if (normalized.length == HEX_LENGTH && normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
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

        /**
         * The interop-defining derivation: PeerID = first 8 bytes of
         * SHA-256(noise static public key). Every place that derives an
         * identity — ours or a remote peer's — must go through here.
         */
        fun fromNoisePublicKey(noisePublicKey: ByteArray): PeerID {
            val digest = MessageDigest.getInstance("SHA-256").digest(noisePublicKey)
            return fromBytes(digest.copyOfRange(0, 8))!!
        }
    }

    fun toBytes(): ByteArray? {
        if (rawValue.length != HEX_LENGTH) return null
        // map (not mapNotNull): a non-hex pair must fail the whole conversion,
        // not silently yield a short array. A short array would slip past
        // callers like BackboneFrame.encode that assume 8 bytes and arraycopy
        // a fixed length. (The primary constructor is public, so a
        // 16-char-but-non-hex PeerID is still representable.)
        val bytes = rawValue.chunked(2).map { (it.toIntOrNull(16) ?: return null).toByte() }
        return bytes.toByteArray()
    }

    fun toHexString(): String = rawValue.lowercase()

    fun toLongBE(): Long {
        val bytes = toBytes() ?: return 0L
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).long
    }
}
