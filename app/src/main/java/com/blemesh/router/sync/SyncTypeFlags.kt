package com.blemesh.router.sync

import com.blemesh.router.model.MessageType

/**
 * Bitfield describing which message types are covered by a REQUEST_SYNC round.
 * Bit indices map to message types, matching iOS SyncTypeFlags.swift.
 */
data class SyncTypeFlags(val rawValue: Long) {

    companion object {
        private fun bitIndex(type: MessageType): Int? = when (type) {
            MessageType.ANNOUNCE -> 0
            MessageType.MESSAGE -> 1
            MessageType.LEAVE -> 2
            MessageType.NOISE_HANDSHAKE -> 3
            MessageType.NOISE_ENCRYPTED -> 4
            MessageType.FRAGMENT -> 5
            MessageType.LOXATION_ANNOUNCE -> 6
            MessageType.LOXATION_QUERY -> 7
            MessageType.LOXATION_CHUNK -> 8
            MessageType.LOXATION_COMPLETE -> 9
            else -> null
        }

        private fun typeForBit(index: Int): MessageType? = when (index) {
            0 -> MessageType.ANNOUNCE
            1 -> MessageType.MESSAGE
            2 -> MessageType.LEAVE
            3 -> MessageType.NOISE_HANDSHAKE
            4 -> MessageType.NOISE_ENCRYPTED
            5 -> MessageType.FRAGMENT
            6 -> MessageType.LOXATION_ANNOUNCE
            7 -> MessageType.LOXATION_QUERY
            8 -> MessageType.LOXATION_CHUNK
            9 -> MessageType.LOXATION_COMPLETE
            else -> null
        }

        val ANNOUNCE = fromTypes(MessageType.ANNOUNCE)
        val MESSAGE = fromTypes(MessageType.MESSAGE)
        val FRAGMENT = fromTypes(MessageType.FRAGMENT)
        val LOXATION_ANNOUNCE = fromTypes(MessageType.LOXATION_ANNOUNCE)
        val PUBLIC_MESSAGES = fromTypes(MessageType.ANNOUNCE, MessageType.MESSAGE, MessageType.LOXATION_ANNOUNCE)

        fun fromTypes(vararg types: MessageType): SyncTypeFlags {
            var raw = 0L
            for (type in types) {
                val bit = bitIndex(type) ?: continue
                raw = raw or (1L shl bit)
            }
            return SyncTypeFlags(raw and 0x00FF_FFFF_FFFF_FFFFL)
        }

        fun decode(data: ByteArray): SyncTypeFlags? {
            if (data.isEmpty() || data.size > 8) return null
            var raw = 0L
            for ((index, byte) in data.withIndex()) {
                raw = raw or ((byte.toLong() and 0xFF) shl (index * 8))
            }
            return SyncTypeFlags(raw)
        }
    }

    fun contains(type: MessageType): Boolean {
        val bit = bitIndex(type) ?: return false
        return (rawValue and (1L shl bit)) != 0L
    }

    fun union(other: SyncTypeFlags): SyncTypeFlags = SyncTypeFlags(rawValue or other.rawValue)

    fun toMessageTypes(): List<MessageType> {
        if (rawValue == 0L) return emptyList()
        val types = mutableListOf<MessageType>()
        for (bit in 0 until 64) {
            if ((rawValue and (1L shl bit)) != 0L) {
                typeForBit(bit)?.let { types.add(it) }
            }
        }
        return types
    }

    fun toData(): ByteArray? {
        if (rawValue == 0L) return null
        var value = rawValue
        val bytes = mutableListOf<Byte>()
        while (value > 0 && bytes.size < 8) {
            bytes.add((value and 0xFF).toByte())
            value = value ushr 8
        }
        while (bytes.isNotEmpty() && bytes.last() == 0.toByte()) {
            bytes.removeAt(bytes.size - 1)
        }
        if (bytes.isEmpty() || bytes.size > 8) return null
        return bytes.toByteArray()
    }
}
