package com.blemesh.router.transport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The shared two-lane send queue + writer the backbone transports drain
 * through: control frames outrun bulk backlog, overflow evicts oldest within
 * a lane, oversize frames are refused at enqueue (mixed-version fleets), and
 * close() refuses further traffic.
 */
class LinkWriterTest {

    /** 4-byte payload encoding [n] so frames are distinguishable. */
    private fun frame(n: Int): ByteArray = byteArrayOf(
        (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()
    )

    private fun frameValue(payload: ByteArray): Int =
        ((payload[0].toInt() and 0xFF) shl 24) or
            ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or
            (payload[3].toInt() and 0xFF)

    /** Decode complete [len:4 BE][payload] frames; ignores a trailing partial. */
    private fun decodeFrames(bytes: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var i = 0
        while (i + 4 <= bytes.size) {
            val len = ((bytes[i].toInt() and 0xFF) shl 24) or
                ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                (bytes[i + 3].toInt() and 0xFF)
            if (i + 4 + len > bytes.size) break
            frames.add(bytes.copyOfRange(i + 4, i + 4 + len))
            i += 4 + len
        }
        return frames
    }

    private fun awaitFrameCount(out: ByteArrayOutputStream, expected: Int) {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (decodeFrames(out.toByteArray()).size >= expected) return
            Thread.sleep(10)
        }
        assertEquals("writer did not emit expected frames", expected, decodeFrames(out.toByteArray()).size)
    }

    @Test
    fun controlLaneOutrunsQueuedBulk() = runBlocking {
        val writer = LinkWriter("test")
        assertTrue(writer.enqueue(frame(1), controlLane = false))
        assertTrue(writer.enqueue(frame(2), controlLane = false))
        assertTrue(writer.enqueue(frame(100), controlLane = true))

        val out = ByteArrayOutputStream()
        var writeError: Exception? = null
        val job = writer.startWriter(this, out) { writeError = it }
        awaitFrameCount(out, 3)
        writer.close()
        job.join()

        assertNull(writeError)
        // The control frame was enqueued last but written first.
        assertEquals(listOf(100, 1, 2), decodeFrames(out.toByteArray()).map(::frameValue))
    }

    @Test
    fun bulkOverflowEvictsOldestAndKeepsNewest() = runBlocking {
        val writer = LinkWriter("test")
        val extra = 3
        val total = LinkWriter.BULK_QUEUE_CAPACITY + extra
        for (n in 0 until total) {
            // Overflow still reports success — the oldest frame is evicted.
            assertTrue(writer.enqueue(frame(n), controlLane = false))
        }

        val out = ByteArrayOutputStream()
        val job = writer.startWriter(this, out) { }
        awaitFrameCount(out, LinkWriter.BULK_QUEUE_CAPACITY)
        writer.close()
        job.join()

        val values = decodeFrames(out.toByteArray()).map(::frameValue)
        assertEquals(LinkWriter.BULK_QUEUE_CAPACITY, values.size)
        assertEquals(extra, values.first())      // oldest `extra` frames evicted
        assertEquals(total - 1, values.last())   // newest survived
    }

    @Test
    fun controlOverflowEvictsOldestControl() = runBlocking {
        val writer = LinkWriter("test")
        val total = LinkWriter.CONTROL_QUEUE_CAPACITY + 1
        for (n in 0 until total) {
            assertTrue(writer.enqueue(frame(n), controlLane = true))
        }

        val out = ByteArrayOutputStream()
        val job = writer.startWriter(this, out) { }
        awaitFrameCount(out, LinkWriter.CONTROL_QUEUE_CAPACITY)
        writer.close()
        job.join()

        val values = decodeFrames(out.toByteArray()).map(::frameValue)
        assertEquals(LinkWriter.CONTROL_QUEUE_CAPACITY, values.size)
        assertEquals(1, values.first())
        assertEquals(total - 1, values.last())
    }

    @Test
    fun oversizeFrameRefusedButLinkStaysUsable() {
        val writer = LinkWriter("test")
        assertFalse(writer.enqueue(ByteArray(LinkWriter.MAX_SEND_FRAME + 1), controlLane = false))
        assertTrue(writer.enqueue(ByteArray(LinkWriter.MAX_SEND_FRAME), controlLane = false))
    }

    @Test
    fun enqueueAfterCloseIsRefused() {
        val writer = LinkWriter("test")
        writer.close()
        assertFalse(writer.enqueue(frame(1), controlLane = false))
        assertFalse(writer.enqueue(frame(1), controlLane = true))
    }
}
