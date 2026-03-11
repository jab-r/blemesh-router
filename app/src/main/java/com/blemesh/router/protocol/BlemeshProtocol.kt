package com.blemesh.router.protocol

import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.MessageType
import kotlin.math.abs
import kotlin.math.max

/**
 * Relay policy for BLE mesh packets.
 * Mirrors iOS/Rust relay policy from loxation-sw/loxation-android.
 *
 * Rules:
 *   - NOISE_ENCRYPTED (0x12) is NEVER relayed (end-to-end only)
 *   - All other types may be relayed if TTL > 1
 *   - Relay includes random 50-200ms jitter to avoid thundering herd
 */
object BlemeshProtocol {
    private const val DEFAULT_MIN_JITTER_MS = 50
    private const val DEFAULT_MAX_JITTER_MS = 200

    private val noiseEncryptedType = MessageType.NOISE_ENCRYPTED.value.toInt() and 0xFF

    fun isRelayablePacketType(packetType: Byte): Boolean {
        val normalized = packetType.toInt() and 0xFF
        return normalized != noiseEncryptedType
    }

    fun shouldRelay(packet: BlemeshPacket): Boolean {
        if (!isRelayablePacketType(packet.type)) return false
        return packet.ttl.toInt() and 0xFF > 1
    }

    fun getRelayJitterMs(): Int = jitterMs(DEFAULT_MIN_JITTER_MS, DEFAULT_MAX_JITTER_MS)

    fun getRelayJitterMs(minMs: Int, maxMs: Int): Int = jitterMs(minMs, maxMs)

    private fun jitterMs(minMs: Int, maxMs: Int): Int {
        val effectiveMin = max(0, minMs)
        val effectiveMax = max(effectiveMin, maxMs)
        val range = effectiveMax - effectiveMin
        if (range == 0) return effectiveMin
        val timestamp = abs(System.nanoTime())
        return effectiveMin + ((timestamp % range.toLong()).toInt())
    }
}
