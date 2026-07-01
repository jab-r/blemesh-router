package com.blemesh.router.router

import com.blemesh.router.model.PeerID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire framing for ROUTER_HOME: peers + their sighting age must round-trip
 * byte-exact (they drive home-router routing and the anti-flap freshness
 * compare), and malformed/truncated frames must decode to null.
 */
class RouterHomeFrameTest {

    private fun peer(v: Long) = PeerID.fromLongBE(v)!!

    @Test
    fun roundTrip_preservesPeersAndAges() {
        val entries = listOf(
            RouterHomeFrame.Entry(peer(0x1122334455667788L), 0),
            RouterHomeFrame.Entry(peer(-0x0102030405060708L), 42),   // high-bit peer id
            RouterHomeFrame.Entry(peer(7L), 89),
        )
        val decoded = RouterHomeFrame.decode(RouterHomeFrame.encode(entries))!!
        assertEquals(entries, decoded)
    }

    @Test
    fun emptyList_roundTripsToEmpty() {
        val decoded = RouterHomeFrame.decode(RouterHomeFrame.encode(emptyList()))!!
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun age_isClampedToU16Range() {
        val encoded = RouterHomeFrame.encode(listOf(RouterHomeFrame.Entry(peer(1L), 70_000)))
        val decoded = RouterHomeFrame.decode(encoded)!!
        assertEquals(0xFFFF, decoded.single().ageSeconds)
    }

    @Test
    fun encode_hasExpectedSize() {
        // 2-byte count + 10 bytes per entry (8 peer + 2 age).
        val n = 3
        val bytes = RouterHomeFrame.encode((0 until n).map { RouterHomeFrame.Entry(peer(it.toLong()), it) })
        assertEquals(2 + n * 10, bytes.size)
    }

    @Test
    fun decode_tooShortForHeader_isNull() {
        assertNull(RouterHomeFrame.decode(byteArrayOf(0x00)))
    }

    @Test
    fun decode_countExceedsBody_isNull() {
        // count=2 but only one entry's worth of body → reject, don't over-read.
        val bad = ByteArray(2 + 10)
        bad[1] = 2
        assertNull(RouterHomeFrame.decode(bad))
    }

    @Test
    fun decode_trailingGarbage_isNull() {
        // count=1, one valid entry, plus an extra stray byte → strict length check rejects.
        val ok = RouterHomeFrame.encode(listOf(RouterHomeFrame.Entry(peer(9L), 3)))
        val withGarbage = ok + byteArrayOf(0x7F)
        assertNull(RouterHomeFrame.decode(withGarbage))
    }
}
