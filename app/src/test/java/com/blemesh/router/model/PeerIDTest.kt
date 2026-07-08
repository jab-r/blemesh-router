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
    fun fromHexString_rejectsNonHex() {
        // isLetterOrDigit()-style validation admitted g–z and Unicode letters;
        // such an id has a null toBytes() and silently aliases to peer 0 via
        // toLongBE(). Strictly hex only.
        assertNull(PeerID.fromHexString("zzzzzzzzzzzzzzzz"))
        assertNull(PeerID.fromHexString("0123456789abcdeg"))
        assertNotNull(PeerID.fromHexString("0123456789abcdef"))
        assertNotNull(PeerID.fromHexString("0123 4567 89AB CDEF")) // spaces + case normalized
    }

    @Test
    fun toBytes_returnsNullForNonHexPeerId() {
        // The primary constructor is public, so a 16-char non-hex id is still
        // representable. toBytes must reject it wholesale, NOT yield a short
        // array (which would crash BackboneFrame.encode's fixed-length copy).
        assertNull(PeerID("zzzzzzzzzzzzzzzz").toBytes())
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
