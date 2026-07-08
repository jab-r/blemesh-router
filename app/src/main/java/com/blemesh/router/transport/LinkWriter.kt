package com.blemesh.router.transport

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared per-link send machinery for the router-to-router transports
 * (tcp / aware / direct): bounded send lanes drained FIFO by a single writer
 * coroutine that length-prefix-frames each payload onto the socket
 * (`[length:4 BE][payload]`). One writer per link keeps frames ordered and
 * caps blocked IO threads at one per connection.
 *
 * Two lanes, control before bulk. Router-internal control frames
 * ([com.blemesh.router.model.MessageType.isControlLane]) are low-volume but
 * liveness-critical: a ROUTER_PING/PONG queued behind (or evicted by) a
 * multi-hundred-frame ROUTER_SYNC_DATA burst trips the liveness reaper on a
 * healthy link, and a dropped ROUTER_CAPS silently downgrades path-tag
 * negotiation for the life of the connection. Bulk traffic overflows by
 * evicting its own oldest frame (DROP_OLDEST — when a peer's TCP window is
 * backed up hundreds of frames deep, stale gossip is the least useful thing
 * queued), and every bulk eviction is counted and logged: silent loss here
 * has previously read as delivered traffic.
 */
internal class LinkWriter(private val label: String) {

    companion object {
        private const val TAG = "LinkWriter"

        // Control traffic is intrinsically low-volume (pings every 5s, one
        // sync/home advert per 20s round, caps once per connect), so this
        // lane only fills when the link is wedged for minutes — at which
        // point evicting the oldest stale ping is correct.
        const val CONTROL_QUEUE_CAPACITY = 64

        // Sized so one capped backbone sync burst
        // (MeshRouterService.MAX_SYNC_DATA_FRAMES_PER_ROUND = half of this)
        // structurally cannot wrap the queue and evict unrelated earlier
        // frames (directed DMs included) even over a half-full queue.
        const val BULK_QUEUE_CAPACITY = 512

        // Liberal receive / conservative send (mixed-version fleets):
        // the receive bound comfortably admits the largest legal encoded
        // packet (~65.7 KB: header + sender + recipient + 0xFFFF payload +
        // signature + path tag), but a router that has not been updated yet
        // still enforces the old 64 KiB receive cap and KILLS the connection
        // on a bigger frame — and the sender's retry then flaps the link
        // forever. So we accept up to 128 KiB but refuse to *send* above
        // 64 KiB (refusal drops one frame with a log; a receive-side
        // violation tears down the whole link). In practice backbone frames
        // are far smaller (BLE-origin packets are MTU-fragmented). Raise
        // MAX_SEND_FRAME to MAX_RECEIVE_FRAME only once every router in the
        // fleet runs a build with the 128 KiB receive bound.
        const val MAX_RECEIVE_FRAME = 128 * 1024
        const val MAX_SEND_FRAME = 64 * 1024

        private const val BULK_DROP_LOG_EVERY = 100L
    }

    private val control = Channel<ByteArray>(CONTROL_QUEUE_CAPACITY)
    private val bulk = Channel<ByteArray>(BULK_QUEUE_CAPACITY)
    private val bulkDrops = AtomicLong()

    /**
     * Queue [data] for the writer. Returns false when refused outright
     * (oversize frame or link already closed). A full lane evicts its own
     * oldest frame (DROP_OLDEST) rather than blocking the caller — frames
     * are enqueued from transport read threads.
     */
    fun enqueue(data: ByteArray, controlLane: Boolean): Boolean {
        if (data.size > MAX_SEND_FRAME) {
            Log.w(TAG, "$label: refusing oversize frame (${data.size} > $MAX_SEND_FRAME bytes)")
            return false
        }
        val lane = if (controlLane) control else bulk
        while (true) {
            val result = lane.trySend(data)
            if (result.isSuccess) return true
            if (result.isClosed) {
                Log.d(TAG, "$label: dropped frame (link closed)")
                return false
            }
            // Lane full: evict the oldest queued frame and retry. A lost
            // race (another enqueuer evicted first) just retries.
            if (lane.tryReceive().getOrNull() == null) continue
            if (controlLane) {
                Log.w(TAG, "$label: control lane overflow — evicted oldest control frame")
            } else {
                val n = bulkDrops.incrementAndGet()
                if (n == 1L || n % BULK_DROP_LOG_EVERY == 0L) {
                    Log.w(TAG, "$label: bulk send-queue overflow — $n frame(s) evicted since connect")
                }
            }
        }
    }

    /**
     * Start the single writer coroutine draining this link onto [output].
     * Exits when [close] is called; any write failure invokes [onWriteFailed]
     * (which should tear the link down).
     */
    fun startWriter(
        scope: CoroutineScope,
        output: OutputStream,
        onWriteFailed: (Exception) -> Unit
    ): Job = scope.launch(Dispatchers.IO) {
        try {
            while (true) {
                val data = nextFrame() ?: break
                output.write(
                    byteArrayOf(
                        (data.size ushr 24).toByte(),
                        (data.size ushr 16).toByte(),
                        (data.size ushr 8).toByte(),
                        data.size.toByte()
                    )
                )
                output.write(data)
                output.flush()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onWriteFailed(e)
        }
    }

    /** Next frame to write, control lane first; null once the link closes. */
    private suspend fun nextFrame(): ByteArray? {
        control.tryReceive().getOrNull()?.let { return it }
        // select is biased to its first clause, preserving control priority
        // when both lanes are ready.
        return select {
            control.onReceiveCatching { it.getOrNull() }
            bulk.onReceiveCatching { it.getOrNull() }
        }
    }

    /** Close both lanes; the writer exits at its next dequeue. Idempotent. */
    fun close() {
        control.close()
        bulk.close()
    }
}
