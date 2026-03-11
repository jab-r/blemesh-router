package com.blemesh.router.model

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Device announcement data for peer discovery.
 * TLV-encoded, matching iOS AnnouncementPacket for interop.
 *
 * TLV tags:
 *   0x01 nickname (UTF-8, max 255)
 *   0x02 noisePublicKey (exactly 32 bytes, X25519)
 *   0x03 signingPublicKey (exactly 32 bytes, Ed25519)
 *   0x04 deviceId (variable)
 *   0x05 locationId (UTF-8)
 *   0x06 uwbToken (UTF-8)
 *   0x07 timestamp (8 bytes big-endian)
 */
data class AnnouncementData(
    val nickname: String,
    val noisePublicKey: ByteArray,
    val signingPublicKey: ByteArray,
    val deviceId: ByteArray? = null,
    val locationId: String? = null,
    val uwbToken: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isValid: Boolean
        get() = nickname.isNotBlank() &&
                noisePublicKey.size == 32 &&
                signingPublicKey.size == 32

    fun encode(): ByteArray {
        val baos = ByteArrayOutputStream()

        fun put(tag: Int, value: ByteArray?) {
            if (value == null || value.isEmpty()) return
            if (value.size > 255) return
            baos.write(tag and 0xFF)
            baos.write(value.size and 0xFF)
            baos.write(value)
        }

        val nicknameBytes = nickname.toByteArray(Charsets.UTF_8)
        if (nicknameBytes.isEmpty() || nicknameBytes.size > 255) return byteArrayOf()
        put(0x01, nicknameBytes)

        if (noisePublicKey.size != 32) return byteArrayOf()
        put(0x02, noisePublicKey)

        if (signingPublicKey.size != 32) return byteArrayOf()
        put(0x03, signingPublicKey)

        return baos.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): AnnouncementData? {
            try {
                var idx = 0
                var nickname: String? = null
                var noisePublicKey: ByteArray? = null
                var signingPublicKey: ByteArray? = null
                var deviceId: ByteArray? = null
                var locationId: String? = null
                var uwbToken: String? = null
                var timestamp: Long? = null

                while (idx + 2 <= data.size) {
                    val tag = data[idx].toInt() and 0xFF
                    val len = data[idx + 1].toInt() and 0xFF
                    idx += 2
                    if (idx + len > data.size) break
                    val value = data.copyOfRange(idx, idx + len)
                    idx += len

                    when (tag) {
                        0x01 -> nickname = String(value, Charsets.UTF_8)
                        0x02 -> if (value.size == 32) noisePublicKey = value
                        0x03 -> if (value.size == 32) signingPublicKey = value
                        0x04 -> if (value.isNotEmpty()) deviceId = value
                        0x05 -> locationId = String(value, Charsets.UTF_8)
                        0x06 -> uwbToken = String(value, Charsets.UTF_8)
                        0x07 -> if (value.size == 8) {
                            timestamp = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).long
                        }
                    }
                }

                val nn = nickname ?: return null
                val nkey = noisePublicKey ?: return null
                val skey = signingPublicKey ?: return null
                if (nkey.size != 32 || skey.size != 32) return null

                return AnnouncementData(
                    nickname = nn,
                    noisePublicKey = nkey,
                    signingPublicKey = skey,
                    deviceId = deviceId,
                    locationId = locationId,
                    uwbToken = uwbToken,
                    timestamp = timestamp ?: System.currentTimeMillis()
                ).takeIf { it.isValid }
            } catch (_: Exception) {
                return null
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnnouncementData) return false
        return nickname == other.nickname &&
                noisePublicKey.contentEquals(other.noisePublicKey) &&
                signingPublicKey.contentEquals(other.signingPublicKey)
    }

    override fun hashCode(): Int {
        var result = nickname.hashCode()
        result = 31 * result + noisePublicKey.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        return result
    }
}
