package com.blemesh.router.protocol

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Message padding/unpadding for BLE transport.
 * Verbatim port of loxation-android MessagePadding (byte-compatible with
 * loxation-sw): random fill, PKCS#7-style trailing length byte for padding
 * up to 255 bytes, and a trailing [lenHi][lenLo][0x00] sentinel beyond that.
 */
object MessagePadding {
    private val blockSizes = intArrayOf(256, 512, 1024, 2048, 4096)
    private val random = SecureRandom()

    fun pad(data: ByteArray, targetSize: Int): ByteArray {
        if (data.size >= targetSize) return data
        val paddingNeeded = targetSize - data.size
        if (paddingNeeded <= 0) return data

        val output = ByteArrayOutputStream(targetSize)
        output.write(data)

        if (paddingNeeded <= 255) {
            if (paddingNeeded > 1) {
                val randomBytes = ByteArray(paddingNeeded - 1)
                random.nextBytes(randomBytes)
                output.write(randomBytes)
            }
            output.write(paddingNeeded)
            return output.toByteArray()
        }

        val randomBytesCount = paddingNeeded - 3
        if (randomBytesCount > 0) {
            val randomBytes = ByteArray(randomBytesCount)
            random.nextBytes(randomBytes)
            output.write(randomBytes)
        }

        val high = (paddingNeeded shr 8) and 0xFF
        val low = paddingNeeded and 0xFF
        output.write(high)
        output.write(low)
        output.write(0)

        return output.toByteArray()
    }

    fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val last = data.last()
        if (last.toInt() == 0) {
            if (data.size < 3) return data
            val high = data[data.size - 3].toInt() and 0xFF
            val low = data[data.size - 2].toInt() and 0xFF
            val paddingLength = (high shl 8) or low
            if (paddingLength <= 0 || paddingLength > data.size) return data
            return data.copyOf(data.size - paddingLength)
        }

        val paddingLength = last.toInt() and 0xFF
        if (paddingLength <= 0 || paddingLength > data.size) return data
        return data.copyOf(data.size - paddingLength)
    }

    fun optimalBlockSize(dataSize: Int): Int {
        // +16 mirrors the references' allowance for encoding overhead.
        val totalSize = dataSize + 16
        for (block in blockSizes) {
            if (totalSize <= block) return block
        }
        return dataSize
    }
}
