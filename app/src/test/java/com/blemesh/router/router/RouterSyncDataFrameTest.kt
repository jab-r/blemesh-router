package com.blemesh.router.router

import com.blemesh.router.model.PeerID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire framing for ROUTER_SYNC_DATA: the optional origin-router claim must
 * survive a round-trip byte-exact (it drives home-router learning), and
 * truncated/empty frames must decode to null rather than a corrupt claim.
 */
class RouterSyncDataFrameTest {

    private val inner = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

    @Test
    fun roundTrip_withOrigin_preservesOriginAndPacket() {
        val origin = PeerID.fromLongBE(0x1122334455667788L)!!
        val decoded = RouterSyncDataFrame.decode(RouterSyncDataFrame.encode(origin, inner))!!
        assertEquals(origin, decoded.originRouter)
        assertArrayEquals(inner, decoded.encodedPacket)
    }

    @Test
    fun roundTrip_withoutOrigin_isContentOnly() {
        val decoded = RouterSyncDataFrame.decode(RouterSyncDataFrame.encode(null, inner))!!
        assertNull(decoded.originRouter)
        assertArrayEquals(inner, decoded.encodedPacket)
    }

    @Test
    fun roundTrip_withOrigin_preservesHighBitPeerId() {
        // A PeerID whose bytes have the high bit set must round-trip (putLong /
        // getLong are signed) so the learned home router matches the real one.
        val origin = PeerID.fromLongBE(-0x0102030405060708L)!!
        val decoded = RouterSyncDataFrame.decode(RouterSyncDataFrame.encode(origin, inner))!!
        assertEquals(origin, decoded.originRouter)
    }

    @Test
    fun encode_withOrigin_hasFlagAndNineByteHeader() {
        val origin = PeerID.fromLongBE(1L)!!
        val bytes = RouterSyncDataFrame.encode(origin, inner)
        assertEquals(0x01, bytes[0].toInt() and 0xFF)
        assertEquals(1 + 8 + inner.size, bytes.size)
    }

    @Test
    fun decode_empty_isNull() {
        assertNull(RouterSyncDataFrame.decode(ByteArray(0)))
    }

    @Test
    fun decode_originFlagButTruncatedHeader_isNull() {
        // Flag says an 8-byte origin follows, but only 4 bytes are present:
        // must reject, not read past the buffer or fabricate a claim.
        assertNull(RouterSyncDataFrame.decode(byteArrayOf(0x01, 0, 0, 0, 0)))
    }

    @Test
    fun decode_contentOnly_emptyPacketBody_isEmptyNotNull() {
        val decoded = RouterSyncDataFrame.decode(byteArrayOf(0x00))!!
        assertNull(decoded.originRouter)
        assertEquals(0, decoded.encodedPacket.size)
    }
}
