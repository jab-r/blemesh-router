package com.blemesh.router.protocol

import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.MessageType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relay-policy tests. Guards the cross-platform parity fix: NOISE_ENCRYPTED
 * (0x12) MUST be relayable — iOS (loxation-sw) relays directed 0x12 hop-by-hop,
 * and the old loxation-android exclusion was a bug this router had inherited.
 */
class BlemeshProtocolTest {

    private fun packet(type: Byte, ttl: Int): BlemeshPacket = BlemeshPacket(
        version = BlemeshPacket.PROTOCOL_VERSION,
        type = type,
        ttl = ttl.toByte(),
        timestamp = 1L,
        flags = BlemeshPacket.FLAG_HAS_RECIPIENT,
        senderId = 1L,
        recipientId = 2L,
        payload = ByteArray(0),
        signature = null
    )

    @Test
    fun noiseEncrypted_isRelayable_iosParity() {
        assertTrue(BlemeshProtocol.isRelayablePacketType(MessageType.NOISE_ENCRYPTED.value))
    }

    @Test
    fun everyKnownType_isRelayable() {
        for (t in MessageType.entries) {
            assertTrue("type ${t.name} (0x%02x) should be relayable".format(t.value.toInt() and 0xFF),
                BlemeshProtocol.isRelayablePacketType(t.value))
        }
    }

    @Test
    fun shouldRelay_noiseEncrypted_gatedOnTtlOnly() {
        val type = MessageType.NOISE_ENCRYPTED.value
        assertTrue(BlemeshProtocol.shouldRelay(packet(type, BlemeshPacket.MAX_TTL)))
        assertTrue(BlemeshProtocol.shouldRelay(packet(type, 2)))
        assertFalse(BlemeshProtocol.shouldRelay(packet(type, 1)))
        assertFalse(BlemeshProtocol.shouldRelay(packet(type, 0)))
    }
}
