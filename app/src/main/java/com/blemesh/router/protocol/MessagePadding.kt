package com.blemesh.router.protocol

/**
 * Message padding/unpadding for BLE transport.
 * Ported from loxation-android MessagePadding.
 */
object MessagePadding {
    private val BLOCK_SIZES = intArrayOf(256, 512, 1024, 2048, 4096)

    fun optimalBlockSize(rawSize: Int): Int {
        for (size in BLOCK_SIZES) {
            if (rawSize <= size) return size
        }
        return rawSize
    }

    fun pad(data: ByteArray, targetSize: Int): ByteArray {
        if (targetSize <= data.size) return data
        val paddingNeeded = targetSize - data.size
        if (paddingNeeded <= 0) return data

        val padded = ByteArray(targetSize)
        System.arraycopy(data, 0, padded, 0, data.size)

        if (paddingNeeded <= 256) {
            // Single-byte length encoding
            padded[data.size] = (paddingNeeded - 1).toByte()
        } else {
            // 3-byte header: 0xFF marker + 2-byte length
            padded[data.size] = 0xFF.toByte()
            val remaining = paddingNeeded - 1
            padded[data.size + 1] = ((remaining shr 8) and 0xFF).toByte()
            padded[data.size + 2] = (remaining and 0xFF).toByte()
        }

        return padded
    }

    fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data

        // Check if the last byte could indicate padding
        val lastByte = data[data.size - 1].toInt() and 0xFF
        if (lastByte != 0) return data

        // Walk backwards to find padding start
        var i = data.size - 1
        while (i >= 0 && data[i].toInt() == 0) {
            i--
        }
        if (i < 0) return data

        val marker = data[i].toInt() and 0xFF
        val paddingLen: Int
        val headerSize: Int

        if (marker == 0xFF && i >= 2) {
            // 3-byte header
            val lenHi = data[i - 2].toInt() and 0xFF
            val lenLo = data[i - 1].toInt() and 0xFF
            paddingLen = (lenHi shl 8) or lenLo
            headerSize = 3
        } else {
            paddingLen = marker
            headerSize = 1
        }

        val totalPadding = paddingLen + headerSize
        if (totalPadding <= 0 || totalPadding > data.size) return data

        val contentEnd = data.size - totalPadding
        if (contentEnd <= 0 || contentEnd >= data.size) return data

        return data.copyOfRange(0, contentEnd)
    }
}
