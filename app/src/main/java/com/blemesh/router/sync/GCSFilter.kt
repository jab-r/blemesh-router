package com.blemesh.router.sync

import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.ln

/**
 * Golomb-Coded Set (GCS) filter for gossip sync.
 * Ported from loxation-android for cross-platform interop with iOS.
 *
 * Hashing: h64(id) = first 8 bytes of SHA-256 (unsigned, big-endian)
 * Encoding: Sorted deltas as Golomb-Rice codes with parameter P, MSB-first bitstream.
 */
object GCSFilter {
    data class Params(
        val p: Int,
        val m: Long,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Params) return false
            return p == other.p && m == other.m && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = p
            result = 31 * result + m.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    fun deriveP(targetFpr: Double): Int {
        val f = targetFpr.coerceIn(0.000001, 0.25)
        return ceil(ln(1.0 / f) / ln(2.0)).toInt().coerceAtLeast(1)
    }

    fun estimateMaxElementsForSize(bytes: Int, p: Int): Int {
        val bits = (bytes * 8).coerceAtLeast(8)
        val per = (p + 2).coerceAtLeast(3)
        return (bits / per).coerceAtLeast(1)
    }

    fun buildFilter(ids: List<ByteArray>, maxBytes: Int, targetFpr: Double): Params {
        val p = deriveP(targetFpr)
        val nCap = estimateMaxElementsForSize(maxBytes, p)
        val n = ids.size.coerceAtMost(nCap)
        val selected = ids.take(n)
        val m = (n.toLong() shl p)
        val mapped = selected.map { id -> (h64(id) % m) }.sorted()
        var encoded = encode(mapped, p)
        var trimmedN = n
        while (encoded.size > maxBytes && trimmedN > 0) {
            trimmedN = (trimmedN * 9) / 10
            val mapped2 = mapped.take(trimmedN)
            encoded = encode(mapped2, p)
        }
        val finalM = (trimmedN.toLong() shl p)
        return Params(p = p, m = finalM, data = encoded)
    }

    fun decodeToSortedSet(p: Int, m: Long, data: ByteArray): LongArray {
        val values = ArrayList<Long>()
        val reader = BitReader(data)
        var acc = 0L
        while (!reader.eof()) {
            var q = 0L
            while (true) {
                val b = reader.readBit() ?: break
                if (b == 1) q++ else break
            }
            if (reader.lastWasEOF) break
            val r = reader.readBits(p) ?: break
            val x = (q shl p) + r + 1
            acc += x
            if (acc >= m) break
            values.add(acc)
        }
        return values.toLongArray()
    }

    fun contains(sortedValues: LongArray, candidate: Long): Boolean {
        var lo = 0
        var hi = sortedValues.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = sortedValues[mid]
            if (v == candidate) return true
            if (v < candidate) lo = mid + 1 else hi = mid - 1
        }
        return false
    }

    fun bucket(id: ByteArray, modulus: Long): Long {
        if (modulus <= 0) return 0
        return h64(id) % modulus
    }

    internal fun h64(id16: ByteArray): Long {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(id16)
        val d = md.digest()
        var x = 0L
        for (i in 0 until 8) {
            x = (x shl 8) or ((d[i].toLong() and 0xFF))
        }
        return x and 0x7fff_ffff_ffff_ffffL
    }

    private fun encode(sorted: List<Long>, p: Int): ByteArray {
        val bw = BitWriter()
        var prev = 0L
        val mask = (1L shl p) - 1L
        for (v in sorted) {
            val delta = v - prev
            prev = v
            val q = (delta - 1) ushr p
            val r = (delta - 1) and mask
            repeat(q.toInt()) { bw.writeBit(1) }
            bw.writeBit(0)
            bw.writeBits(r, p)
        }
        return bw.toByteArray()
    }

    private class BitWriter {
        private val buf = ArrayList<Byte>()
        private var cur = 0
        private var nbits = 0
        fun writeBit(bit: Int) {
            cur = (cur shl 1) or (bit and 1)
            nbits++
            if (nbits == 8) {
                buf.add(cur.toByte())
                cur = 0; nbits = 0
            }
        }
        fun writeBits(value: Long, count: Int) {
            if (count <= 0) return
            for (i in count - 1 downTo 0) {
                writeBit(((value ushr i) and 1L).toInt())
            }
        }
        fun toByteArray(): ByteArray {
            if (nbits > 0) {
                buf.add((cur shl (8 - nbits)).toByte())
                cur = 0; nbits = 0
            }
            return buf.toByteArray()
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var i = 0
        private var nleft = 8
        private var cur = if (data.isNotEmpty()) (data[0].toInt() and 0xFF) else 0
        var lastWasEOF: Boolean = false
            private set
        fun eof() = i >= data.size
        fun readBit(): Int? {
            if (i >= data.size) { lastWasEOF = true; return null }
            val bit = (cur ushr 7) and 1
            cur = (cur shl 1) and 0xFF
            nleft--
            if (nleft == 0) {
                i++
                if (i < data.size) {
                    cur = data[i].toInt() and 0xFF
                    nleft = 8
                }
            }
            return bit
        }
        fun readBits(count: Int): Long? {
            var v = 0L
            for (k in 0 until count) {
                val b = readBit() ?: return null
                v = (v shl 1) or b.toLong()
            }
            return v
        }
    }
}
