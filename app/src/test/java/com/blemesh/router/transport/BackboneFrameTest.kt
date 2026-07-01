package com.blemesh.router.transport

import com.blemesh.router.model.PeerID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the backbone visited-router path-tag wire format
 * (BACKBONE_PATH_ROUTING_SPEC.md). BackboneFrame has no Android dependency.
 */
class BackboneFrameTest {

    /** A distinct, well-formed PeerID for index [i]. */
    private fun peer(i: Int): PeerID =
        PeerID.fromBytes(ByteArray(8) { (i + it).toByte() })!!

    /** Stand-in for an encoded BlemeshPacket: first byte is the version 0x01. */
    private fun packetBytes(vararg tail: Int): ByteArray =
        byteArrayOf(0x01, *tail.map { it.toByte() }.toByteArray())

    @Test
    fun roundTrip_emptyPath() {
        val body = packetBytes(0x04, 0x2A)
        val frame = BackboneFrame.encode(emptyList(), body)
        val decoded = BackboneFrame.decode(frame)
        assertNotNull(decoded)
        assertEquals(emptyList<PeerID>(), decoded!!.visited)
        assertArrayEquals(body, decoded.packetBytes)
    }

    @Test
    fun roundTrip_singleOrigin() {
        val visited = listOf(peer(1))
        val body = packetBytes(0x01, 0x02, 0x03)
        val decoded = BackboneFrame.decode(BackboneFrame.encode(visited, body))
        assertNotNull(decoded)
        assertEquals(visited, decoded!!.visited)
        assertArrayEquals(body, decoded.packetBytes)
    }

    @Test
    fun roundTrip_maxVisited() {
        val visited = (0 until BackboneFrame.MAX_VISITED).map { peer(it) }
        val body = packetBytes(0x40)
        val decoded = BackboneFrame.decode(BackboneFrame.encode(visited, body))
        assertNotNull(decoded)
        assertEquals(BackboneFrame.MAX_VISITED, decoded!!.visited.size)
        assertEquals(visited, decoded.visited) // identity preserved → drop-on-self works
        assertArrayEquals(body, decoded.packetBytes)
    }

    @Test
    fun encode_truncatesOverLengthPathDefensively() {
        val visited = (0 until BackboneFrame.MAX_VISITED + 5).map { peer(it) }
        val decoded = BackboneFrame.decode(BackboneFrame.encode(visited, packetBytes()))
        assertNotNull(decoded)
        assertEquals(BackboneFrame.MAX_VISITED, decoded!!.visited.size)
        assertEquals(visited.take(BackboneFrame.MAX_VISITED), decoded.visited)
    }

    @Test
    fun plainFrame_isNotTagged_andDecodesToNull() {
        // A plain BlemeshPacket encoding starts with version 0x01, never 0xBB.
        val plain = packetBytes(0x01, 0x07, 0x00)
        assertFalse(BackboneFrame.isTagged(plain))
        assertNull(BackboneFrame.decode(plain))
    }

    @Test
    fun taggedFrame_isTagged() {
        val frame = BackboneFrame.encode(listOf(peer(3)), packetBytes())
        assertTrue(BackboneFrame.isTagged(frame))
        assertEquals(BackboneFrame.MARKER, frame[0])
    }

    @Test
    fun decode_rejectsShortFrame() {
        assertNull(BackboneFrame.decode(ByteArray(0)))
        assertNull(BackboneFrame.decode(byteArrayOf(BackboneFrame.MARKER)))
        assertNull(BackboneFrame.decode(byteArrayOf(BackboneFrame.MARKER, BackboneFrame.FMT_VERSION)))
    }

    @Test
    fun decode_rejectsWrongMarker() {
        val frame = BackboneFrame.encode(listOf(peer(1)), packetBytes()).copyOf()
        frame[0] = 0x01 // masquerade as a plain frame
        assertNull(BackboneFrame.decode(frame))
    }

    @Test
    fun decode_rejectsWrongFmtVersion() {
        val frame = BackboneFrame.encode(listOf(peer(1)), packetBytes()).copyOf()
        frame[1] = 0x02 // unknown layout version
        assertNull(BackboneFrame.decode(frame))
    }

    @Test
    fun decode_rejectsCountOverMax() {
        // marker, fmtVersion, count = MAX_VISITED + 1, then nothing else.
        val frame = byteArrayOf(
            BackboneFrame.MARKER,
            BackboneFrame.FMT_VERSION,
            (BackboneFrame.MAX_VISITED + 1).toByte()
        )
        assertNull(BackboneFrame.decode(frame))
    }

    @Test
    fun decode_rejectsTruncatedHeader() {
        // Claims 2 visited entries (needs 16 payload bytes) but supplies only 4.
        val frame = byteArrayOf(
            BackboneFrame.MARKER,
            BackboneFrame.FMT_VERSION,
            0x02,
            0x00, 0x00, 0x00, 0x00
        )
        assertNull(BackboneFrame.decode(frame))
    }
}
