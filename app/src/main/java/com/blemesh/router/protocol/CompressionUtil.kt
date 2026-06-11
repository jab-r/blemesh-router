package com.blemesh.router.protocol

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Zlib compression utility ported from loxation-android.
 * Compression is only applied when payload > 100 bytes and entropy < 90%.
 */
object CompressionUtil {
    private const val TAG = "CompressionUtil"
    private const val COMPRESSION_THRESHOLD = 100

    // Safety bound when no size hint is available (or a hostile packet claims
    // expectedSize=0): no legitimate BLE-mesh payload approaches this.
    private const val MAX_DECOMPRESSED_BYTES = 1L shl 20 // 1 MiB

    fun shouldCompress(data: ByteArray): Boolean {
        if (data.size < COMPRESSION_THRESHOLD) return false
        val sampleSize = minOf(data.size, 256)
        if (sampleSize == 0) return false
        val seen = HashSet<Byte>(sampleSize)
        for (i in 0 until sampleSize) {
            seen.add(data[i])
        }
        val uniqueRatio = seen.size.toDouble() / sampleSize.toDouble()
        return uniqueRatio < 0.9
    }

    fun compress(data: ByteArray): ByteArray? {
        if (data.size < COMPRESSION_THRESHOLD) return null
        // Raw DEFLATE (nowrap), NOT zlib-wrapped: Apple's Compression framework
        // (COMPRESSION_ZLIB) only decodes raw DEFLATE, and iOS silently
        // delivers corrupt bytes when handed a zlib stream. Both Kotlin
        // decoders (ours and loxation-android's) try zlib first then fall back
        // to raw, so raw is the only encoding every platform can read.
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        return try {
            deflater.setInput(data)
            deflater.finish()
            val buffer = ByteArray(1024)
            val output = ByteArrayOutputStream(data.size)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count <= 0) break
                output.write(buffer, 0, count)
            }
            val compressed = output.toByteArray()
            if (compressed.isEmpty() || compressed.size >= data.size) null else compressed
        } finally {
            deflater.end()
        }
    }

    fun decompress(data: ByteArray, expectedSize: Int): ByteArray? {
        fun inflate(nowrap: Boolean): ByteArray? {
            val inflater = Inflater(nowrap)
            return try {
                inflater.setInput(data)
                val output = ByteArrayOutputStream(if (expectedSize > 0) expectedSize else data.size * 2)
                val buffer = ByteArray(1024)
                val maxOutput = if (expectedSize > 0) expectedSize.toLong() * 4 else MAX_DECOMPRESSED_BYTES
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > maxOutput) {
                        Log.w(TAG, "inflate(nowrap=$nowrap) exceeded bound expected=$expectedSize actual=${output.size()}")
                        return null
                    }
                }
                if (!inflater.finished()) {
                    // Truncated/corrupt stream: the loop ended before the final
                    // block. Partial plaintext must fail here, not get re-encoded,
                    // relayed, and gossip-stored downstream as a "valid" packet.
                    return null
                }
                val result = output.toByteArray()
                if (result.isEmpty()) null else result
            } catch (_: Exception) {
                null
            } finally {
                inflater.end()
            }
        }

        // Try zlib (RFC1950 header) first, then raw DEFLATE
        inflate(false)?.let { return it }
        return inflate(true)
    }
}
