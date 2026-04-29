package com.blemesh.router.mesh

import java.security.SecureRandom
import kotlin.math.ceil
import kotlin.math.min

/**
 * Fragmentation helper for BLE mesh.
 * Splits encoded packets into MTU-sized fragments with 8-byte fragment ID for reassembly.
 * Ported from loxation-android BleMeshFragmentationManager.
 *
 * This class only produces `Fragment` slices; the caller writes them on the wire
 * as the payload of a FRAGMENT (0x05) BlemeshPacket with layout:
 *   [fragmentId:8][index:2 BE][total:2 BE][originalType:1][data:var]
 * (13-byte fragment header, matching loxation-android / loxation-sw.)
 */
class BleMeshFragmentationManager {

    data class Fragment(
        val id: ByteArray,      // 8 bytes
        val index: Int,         // 0-based
        val total: Int,         // total fragments
        val type: Byte,         // original (unfragmented) MessageType
        val payload: ByteArray  // fragment data slice
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Fragment) return false
            return id.contentEquals(other.id) && index == other.index &&
                    total == other.total && type == other.type &&
                    payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = id.contentHashCode()
            result = 31 * result + index
            result = 31 * result + total
            result = 31 * result + type
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    private val fragmentRandom = SecureRandom()

    /**
     * Split a full encoded packet into fragments sized for BLE MTU.
     */
    fun split(data: ByteArray, type: Byte, maxFragmentSize: Int): List<Fragment> {
        if (maxFragmentSize <= 0 || data.size <= maxFragmentSize) {
            return listOf(
                Fragment(
                    id = generateFragmentId(),
                    index = 0,
                    total = 1,
                    type = type,
                    payload = data
                )
            )
        }

        val id = generateFragmentId()
        val total = ceil(data.size.toDouble() / maxFragmentSize).toInt()
        val fragments = ArrayList<Fragment>(total)

        for (index in 0 until total) {
            val start = index * maxFragmentSize
            val end = min(start + maxFragmentSize, data.size)
            fragments.add(Fragment(id, index, total, type, data.copyOfRange(start, end)))
        }

        return fragments
    }

    /**
     * Reassembly buffer for incoming fragments.
     */
    class ReassemblyBuffer {
        private data class PendingTransfer(
            val fragments: Array<ByteArray?>,
            val total: Int,
            val type: Byte,
            val createdAt: Long = System.currentTimeMillis()
        )

        private val pending = HashMap<String, PendingTransfer>()
        private val timeoutMs = 30_000L // 30 second timeout
        private val maxTransfers = 128

        /**
         * Add a fragment and return the reassembled data if all fragments received.
         */
        @Synchronized
        fun addFragment(
            fragmentId: ByteArray,
            index: Int,
            total: Int,
            type: Byte,
            data: ByteArray
        ): ByteArray? {
            if (total <= 0 || index < 0 || index >= total) return null

            val key = fragmentId.joinToString("") { "%02x".format(it) }

            val transfer = pending.getOrPut(key) {
                if (pending.size >= maxTransfers) cleanup()
                PendingTransfer(
                    fragments = arrayOfNulls(total),
                    total = total,
                    type = type
                )
            }

            if (transfer.total != total || index >= transfer.fragments.size) return null
            transfer.fragments[index] = data

            // Check if complete
            if (transfer.fragments.all { it != null }) {
                pending.remove(key)
                val totalSize = transfer.fragments.sumOf { it!!.size }
                val result = ByteArray(totalSize)
                var offset = 0
                for (fragment in transfer.fragments) {
                    System.arraycopy(fragment!!, 0, result, offset, fragment.size)
                    offset += fragment.size
                }
                return result
            }

            return null
        }

        @Synchronized
        fun cleanup() {
            val now = System.currentTimeMillis()
            val expired = pending.entries.filter { now - it.value.createdAt > timeoutMs }
            expired.forEach { pending.remove(it.key) }
        }
    }

    private fun generateFragmentId(): ByteArray {
        val bytes = ByteArray(8)
        fragmentRandom.nextBytes(bytes)
        return bytes
    }
}
