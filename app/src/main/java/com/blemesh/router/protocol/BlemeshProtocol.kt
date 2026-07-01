package com.blemesh.router.protocol

import com.blemesh.router.model.BlemeshPacket
import kotlin.math.abs
import kotlin.math.max

/**
 * Relay policy for BLE mesh packets. Mirrors the iOS/loxation-sw relay policy.
 *
 * Rules:
 *   - Any type may be relayed if TTL > 1 (default-allow, matching iOS). This
 *     INCLUDES NOISE_ENCRYPTED (0x12): iOS relays directed 0x12 hop-by-hop
 *     (loxation-sw BLEMeshService.swift relay gate has no 0x12 exclusion), so a
 *     private DM propagates multi-hop. loxation-android historically dropped
 *     0x12 from relay — a cross-platform parity bug this router had inherited.
 *     Relaying opaque ciphertext is just byte forwarding (the router is a
 *     non-endpoint). Store-and-forward of 0x12 stays excluded separately
 *     (MessageType.SNF_ELIGIBLE): relay (forward now) != replay later.
 *   - Relay includes random 50-200ms jitter to avoid thundering herd.
 */
object BlemeshProtocol {
    private const val DEFAULT_MIN_JITTER_MS = 50
    private const val DEFAULT_MAX_JITTER_MS = 200

    /**
     * Whether a packet type is eligible for BLE mesh relay. Default-allow for
     * every type, matching iOS (its relay gate has no per-type exclusion). The
     * caller still gates on TTL (`shouldRelay`) and on not-addressed-to-us.
     */
    fun isRelayablePacketType(packetType: Byte): Boolean = true

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
