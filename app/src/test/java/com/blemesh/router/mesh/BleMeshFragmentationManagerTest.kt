package com.blemesh.router.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM tests for fragment reassembly bounds. */
class BleMeshFragmentationManagerTest {

    private val fragId = ByteArray(8) { it.toByte() }

    @Test
    fun reassemblesInOrderFragments() {
        val buffer = BleMeshFragmentationManager.ReassemblyBuffer()
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5)
        assertNull(buffer.addFragment(1L, fragId, 0, 2, 0x04, a))
        val result = buffer.addFragment(1L, fragId, 1, 2, 0x04, b)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), result)
    }

    @Test
    fun rejectsImplausiblyLargeTotal() {
        // The 2-byte wire `total` is attacker-controlled and sizes the
        // reassembly array before any other fragment arrives; a claimed
        // total of 65535 must be rejected, not allocated and held for 30s.
        val buffer = BleMeshFragmentationManager.ReassemblyBuffer()
        assertNull(buffer.addFragment(1L, fragId, 0, 65535, 0x04, byteArrayOf(1)))
    }

    @Test
    fun rejectsOutOfRangeIndex() {
        val buffer = BleMeshFragmentationManager.ReassemblyBuffer()
        assertNull(buffer.addFragment(1L, fragId, 2, 2, 0x04, byteArrayOf(1)))
        assertNull(buffer.addFragment(1L, fragId, -1, 2, 0x04, byteArrayOf(1)))
        assertNull(buffer.addFragment(1L, fragId, 0, 0, 0x04, byteArrayOf(1)))
    }
}
