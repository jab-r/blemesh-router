package com.blemesh.router.protocol

import android.util.Log
import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.MessageType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Binary wire protocol for BLE mesh packets.
 * Ported from loxation-android BinaryProtocol for full interop with iOS/Android.
 *
 * Header format (14 bytes):
 *   [version:1][type:1][ttl:1][timestamp:8][flags:1][payloadLength:2]
 * Followed by:
 *   [senderId:8][recipientId?:8][payload:var][signature?:64]
 */
object BinaryProtocol {
    private const val TAG = "BinaryProtocol"
    const val HEADER_SIZE = 14
    const val SENDER_ID_SIZE = 8
    const val RECIPIENT_ID_SIZE = 8
    const val SIGNATURE_SIZE = 64
    private const val MAX_REASONABLE_ORIGINAL_SIZE = 1_048_576

    /** Types that should never be compressed (high entropy or protocol-critical). */
    private val NO_COMPRESS_TYPES: Set<Int> = setOf(
        MessageType.NOISE_HANDSHAKE.value.toInt() and 0xFF,
        MessageType.NOISE_ENCRYPTED.value.toInt() and 0xFF,
        MessageType.LOXATION_ANNOUNCE.value.toInt() and 0xFF,
        MessageType.LOXATION_CHUNK.value.toInt() and 0xFF,
        MessageType.LOXATION_QUERY.value.toInt() and 0xFF,
        MessageType.LOXATION_COMPLETE.value.toInt() and 0xFF,
        MessageType.MLS_MESSAGE.value.toInt() and 0xFF,
        MessageType.REQUEST_SYNC.value.toInt() and 0xFF,
        MessageType.LOCATION_UPDATE.value.toInt() and 0xFF,
    )

    fun encode(packet: BlemeshPacket, padding: Boolean = false): ByteArray? {
        return try {
            val hasRecipient = packet.recipientId != BlemeshPacket.BROADCAST_ADDRESS
            val hasSignature = packet.signature?.size == SIGNATURE_SIZE
            val typeInt = packet.type.toInt() and 0xFF
            val allowCompression = typeInt !in NO_COMPRESS_TYPES

            var payload = packet.payload
            var isCompressed = false
            var originalPayloadSize: Int? = null

            if (allowCompression && CompressionUtil.shouldCompress(payload)) {
                CompressionUtil.compress(payload)?.let { compressed ->
                    if (compressed.isNotEmpty() && compressed.size < payload.size) {
                        payload = compressed
                        isCompressed = true
                        originalPayloadSize = packet.payload.size
                    }
                }
            }

            var flags = 0
            if (hasRecipient) flags = flags or BlemeshPacket.FLAG_HAS_RECIPIENT
            if (hasSignature) flags = flags or BlemeshPacket.FLAG_HAS_SIGNATURE
            if (isCompressed) flags = flags or BlemeshPacket.FLAG_IS_COMPRESSED

            val payloadLength = payload.size + if (isCompressed) 2 else 0
            if (payloadLength > 0xFFFF) return null

            val output = ByteArrayOutputStream()
            output.write(1) // version
            output.write(packet.type.toInt() and 0xFF)
            output.write(packet.ttl.toInt() and 0xFF)
            output.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(packet.timestamp).array())
            output.write(flags and 0xFF)
            output.write((payloadLength shr 8) and 0xFF)
            output.write(payloadLength and 0xFF)

            val senderBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(packet.senderId).array()
            output.write(senderBytes)

            if (hasRecipient) {
                val recipientBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(packet.recipientId).array()
                output.write(recipientBytes)
            }

            if (isCompressed) {
                val originalSize = originalPayloadSize ?: packet.payload.size
                output.write((originalSize shr 8) and 0xFF)
                output.write(originalSize and 0xFF)
            }
            output.write(payload)

            if (hasSignature) {
                val signature = packet.signature!!
                val length = min(signature.size, SIGNATURE_SIZE)
                output.write(signature, 0, length)
            }

            val raw = output.toByteArray()
            if (padding) {
                val targetSize = MessagePadding.optimalBlockSize(raw.size)
                MessagePadding.pad(raw, targetSize)
            } else {
                raw
            }
        } catch (e: Exception) {
            Log.w(TAG, "encode failed", e)
            null
        }
    }

    fun decode(data: ByteArray): BlemeshPacket? {
        val unpadded = MessagePadding.unpad(data)
        val candidates =
            if (unpadded === data || unpadded.contentEquals(data)) listOf(data) else listOf(data, unpadded)

        for (candidate in candidates) {
            decodeCore(candidate)?.let { return it }
        }
        return null
    }

    private fun decodeCore(data: ByteArray): BlemeshPacket? {
        if (data.size < HEADER_SIZE + SENDER_ID_SIZE) return null

        var offset = 0
        val version = data[offset].toInt() and 0xFF
        if (version != 1) return null
        offset += 1

        val type = data[offset]
        offset += 1

        val ttl = data[offset]
        offset += 1

        if (offset + 8 > data.size) return null
        val rawTimestamp = readUInt64(data, offset)
        offset += 8
        val timestamp = normalizeTimestamp(rawTimestamp)

        val flags = data[offset].toInt() and 0xFF
        offset += 1

        if (offset + 2 > data.size) return null
        val payloadLength = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2

        if (offset + SENDER_ID_SIZE > data.size) return null
        val senderBytes = data.copyOfRange(offset, offset + SENDER_ID_SIZE)
        offset += SENDER_ID_SIZE
        val senderId = ByteBuffer.wrap(senderBytes).order(ByteOrder.BIG_ENDIAN).long

        var recipientId = BlemeshPacket.BROADCAST_ADDRESS
        val hasRecipient = (flags and BlemeshPacket.FLAG_HAS_RECIPIENT) != 0
        if (hasRecipient) {
            if (offset + RECIPIENT_ID_SIZE > data.size) return null
            val recipientBytes = data.copyOfRange(offset, offset + RECIPIENT_ID_SIZE)
            offset += RECIPIENT_ID_SIZE
            recipientId = ByteBuffer.wrap(recipientBytes).order(ByteOrder.BIG_ENDIAN).long
        }

        var effectivePayloadLength = payloadLength
        val remainingAfterHeader = data.size - offset
        if (effectivePayloadLength > remainingAfterHeader) {
            effectivePayloadLength = remainingAfterHeader
        }

        val isCompressed = (flags and BlemeshPacket.FLAG_IS_COMPRESSED) != 0
        val payload = if (isCompressed) {
            if (effectivePayloadLength < 2 || offset + 2 > data.size) return null

            val sizePrefixOffset = offset
            var originalSize = readUInt16(data, offset)
            offset += 2

            var compressedPayloadSize = effectivePayloadLength - 2
            val remainingAfterSize = data.size - offset
            if (compressedPayloadSize > remainingAfterSize) {
                compressedPayloadSize = remainingAfterSize
            }

            var compressedPayload = data.copyOfRange(offset, offset + compressedPayloadSize)
            offset += compressedPayloadSize

            if (originalSize <= 0 || originalSize > MAX_REASONABLE_ORIGINAL_SIZE) {
                // Try 4-byte size fallback
                offset = sizePrefixOffset
                if (effectivePayloadLength < 4 || offset + 4 > data.size) return null
                originalSize = readUInt32(data, offset)
                offset += 4
                compressedPayloadSize = minOf(data.size - offset, effectivePayloadLength - 4)
                compressedPayload = data.copyOfRange(offset, offset + compressedPayloadSize)
                offset += compressedPayloadSize
            }

            if (originalSize <= 0) return null

            val decompressed = CompressionUtil.decompress(compressedPayload, originalSize)
            decompressed ?: return null
        } else {
            if (effectivePayloadLength < 0 || offset + effectivePayloadLength > data.size) return null
            val payloadBytes = data.copyOfRange(offset, offset + effectivePayloadLength)
            offset += effectivePayloadLength
            payloadBytes
        }

        var signature: ByteArray? = null
        val hasSignature = (flags and BlemeshPacket.FLAG_HAS_SIGNATURE) != 0
        if (hasSignature) {
            if (offset + SIGNATURE_SIZE <= data.size) {
                signature = data.copyOfRange(offset, offset + SIGNATURE_SIZE)
                offset += SIGNATURE_SIZE
            }
        }

        if (offset > data.size) return null

        return BlemeshPacket(
            version = version,
            type = type,
            ttl = ttl,
            timestamp = timestamp,
            flags = flags,
            senderId = senderId,
            recipientId = recipientId,
            payload = payload,
            signature = signature
        )
    }

    private fun readUInt64(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or ((data[offset + i].toInt() and 0xFF).toLong())
        }
        return value
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return -1
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun readUInt32(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return -1
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    private fun normalizeTimestamp(timestamp: Long): Long {
        val now = System.currentTimeMillis()
        val oneYearMillis = 365L * 24 * 3600 * 1000
        val appleEpochMs = 978_307_200_000L

        fun withinRange(value: Long): Boolean =
            value in (now - oneYearMillis)..(now + oneYearMillis)

        var normalized = timestamp
        if (!withinRange(normalized)) {
            normalized = when {
                normalized > 10_000_000_000_000L -> normalized / 1000
                normalized in 0..1_000_000_000_000L -> normalized * 1000
                else -> normalized
            }
        }

        if (!withinRange(normalized)) {
            val candidate = safeAdd(timestamp, appleEpochMs)
            normalized = if (candidate != null && withinRange(candidate)) {
                candidate
            } else {
                val scaled = if (timestamp in 0..1_000_000_000_000L) {
                    safeMultiply(timestamp, 1000)
                } else {
                    safeDivide(timestamp, 1000)
                }
                val shifted = if (scaled != null) safeAdd(scaled, appleEpochMs) else null
                if (shifted != null && withinRange(shifted)) shifted else now
            }
        }

        return normalized
    }

    private fun safeAdd(a: Long, b: Long): Long? =
        try { Math.addExact(a, b) } catch (_: ArithmeticException) { null }

    private fun safeMultiply(a: Long, b: Long): Long? =
        try { Math.multiplyExact(a, b) } catch (_: ArithmeticException) { null }

    private fun safeDivide(a: Long, b: Long): Long? =
        if (b == 0L) null else try { a / b } catch (_: ArithmeticException) { null }
}
