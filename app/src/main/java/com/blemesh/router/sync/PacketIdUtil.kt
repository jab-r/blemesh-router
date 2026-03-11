package com.blemesh.router.sync

import com.blemesh.router.model.BlemeshPacket
import java.security.MessageDigest

/**
 * Deterministic packet ID computation for gossip sync membership.
 * ID = first 16 bytes of SHA-256 over: [type | senderID(8 bytes BE) | timestamp(8 bytes BE) | payload]
 *
 * Must match iOS PacketIdUtil.computeId() exactly for cross-platform GCS interop.
 */
object PacketIdUtil {

    fun computeIdBytes(packet: BlemeshPacket): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(packet.type)
        val sid = packet.senderId
        for (i in 7 downTo 0) {
            md.update(((sid ushr (i * 8)) and 0xFF).toByte())
        }
        val ts = packet.timestamp
        for (i in 7 downTo 0) {
            md.update(((ts ushr (i * 8)) and 0xFF).toByte())
        }
        md.update(packet.payload)
        return md.digest().copyOf(16)
    }

    fun computeIdHex(packet: BlemeshPacket): String {
        return computeIdBytes(packet).joinToString("") { "%02x".format(it) }
    }
}
