package com.blemesh.router.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM tests for PeerID byte conversions used by the backbone path tag. */
class PeerIDTest {

    @Test
    fun toBytes_roundTripsValidHex() {
        val bytes = ByteArray(8) { (0x10 + it).toByte() }
        val peer = PeerID.fromBytes(bytes)!!
        assertArrayEquals(bytes, peer.toBytes())
    }

    @Test
    fun toBytes_handlesHighBits() {
        val bytes = byteArrayOf(-1, -128, 0x7f, 0, -1, 0x0a, -16, 0x55)
        val peer = PeerID.fromBytes(bytes)!!
        assertArrayEquals(bytes, peer.toBytes())
    }

    @Test
    fun toBytes_returnsNullForNonHexPeerId() {
        // fromHexString accepts any letter/digit, so a 16-char non-hex id is
        // representable. toBytes must reject it wholesale, NOT yield a short
        // array (which would crash BackboneFrame.encode's fixed-length copy).
        val peer = PeerID.fromHexString("zzzzzzzzzzzzzzzz")
        assertNotNull(peer)
        assertNull(peer!!.toBytes())
    }

    @Test
    fun toBytes_returnsNullForWrongLength() {
        assertNull(PeerID("abcd").toBytes())
    }

    @Test
    fun toLongBE_roundTrips() {
        val peer = PeerID.fromLongBE(0x0011223344556677L)!!
        assertEquals(0x0011223344556677L, peer.toLongBE())
    }
}
