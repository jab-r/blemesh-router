package com.blemesh.router.transport

import android.util.Log
import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.MessageType
import com.blemesh.router.model.PeerID
import com.blemesh.router.protocol.BinaryProtocol
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * TCP-over-LAN router-to-router transport.
 *
 * Frame: [length:4 BE][BlemeshPacket bytes].
 *
 * Connection handshake (immediately after TCP connect, both directions):
 *   [version:1][peerID:8]
 *
 * The handshake lets us key connections by [PeerID] so the bridge can route
 * directed packets to a specific remote router without tracking host:port.
 *
 * Each router runs a TCP server on [port] and may also dial out to peer
 * routers via [connectToHost].
 */
class WifiBridgeTransport(
    private val scope: CoroutineScope,
    private val localPeerID: PeerID,
    private val port: Int = DEFAULT_PORT
) : RouterTransport {

    companion object {
        private const val TAG = "WifiBridgeTransport"
        const val DEFAULT_PORT = 9742
        // Frame size bounds and send-queue sizing live in LinkWriter, shared
        // by all three backbone transports so they can't drift apart.
        private const val HANDSHAKE_VERSION: Byte = 0x01
        private const val HANDSHAKE_TIMEOUT_MS = 5000
        // Read-idle liveness bound. RTT pings flow every 5s on a healthy link,
        // so a read that sits idle this long means the peer is gone without a
        // FIN (radio loss). SO_TIMEOUT=0 (the old behavior) kept half-open
        // connections in the routing table indefinitely, black-holing traffic.
        private const val READ_IDLE_TIMEOUT_MS = 45_000
        private const val INITIAL_RECONNECT_DELAY_MS = 5_000L
        private const val MAX_RECONNECT_DELAY_MS = 300_000L
    }

    override val name: String = "tcp"
    override var listener: RouterTransport.Listener? = null

    private val connections = ConcurrentHashMap<PeerID, RouterConnection>()
    // Peers that advertised backbone path-tag support (ROUTER_CAPS). Only these
    // are sent tagged frames; everyone else gets plain frames.
    private val tagAwarePeers = ConcurrentHashMap.newKeySet<PeerID>()
    private val dialTargets = ConcurrentHashMap<String, Job>()
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var isRunning = false

    private class RouterConnection(
        val peerID: PeerID,
        val socket: Socket,
        val output: DataOutputStream,
        val input: DataInputStream,
        val isOutbound: Boolean
    ) {
        var readJob: Job? = null
        var writeJob: Job? = null
        // Two-lane bounded send queue + single writer (shared helper): control
        // frames (ping/pong/caps/sync adverts) can't be starved or evicted by
        // bulk gossip bursts, and bulk overflow drops-oldest with logging.
        val writer = LinkWriter("tcp:${peerID.rawValue.take(8)}")
    }

    // --- RouterTransport ---

    override fun isAvailable(): Boolean = true

    override fun start() {
        if (isRunning) return
        isRunning = true
        startServer()
        Log.i(TAG, "TCP bridge transport started on port $port (peer ${localPeerID.rawValue.take(8)})")
    }

    override fun stop() {
        if (!isRunning) return
        isRunning = false
        serverJob?.cancel()
        for (job in dialTargets.values) job.cancel()
        dialTargets.clear()
        for (peerID in connections.keys.toList()) {
            disconnectPeer(peerID)
        }
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        Log.i(TAG, "TCP bridge transport stopped")
    }

    override fun broadcast(packet: BlemeshPacket, visited: List<PeerID>, taggedPeersOnly: Boolean) {
        val plain = BinaryProtocol.encode(packet) ?: return
        val tagged = if (visited.isNotEmpty()) BackboneFrame.encode(visited, plain) else plain
        for ((peerID, conn) in connections) {
            if (peerID in visited) continue // split-horizon: already on the path
            val aware = peerID in tagAwarePeers
            if (taggedPeersOnly && !aware) continue // forwarded frames: tag-aware peers only
            val body = if (visited.isNotEmpty() && aware) tagged else plain
            conn.writer.enqueue(body, controlLane = false)
        }
    }

    override fun sendToPeer(peerID: PeerID, packet: BlemeshPacket, visited: List<PeerID>) {
        val conn = connections[peerID] ?: return
        val plain = BinaryProtocol.encode(packet) ?: return
        val body = if (visited.isNotEmpty() && peerID in tagAwarePeers) {
            BackboneFrame.encode(visited, plain)
        } else plain
        conn.writer.enqueue(body, MessageType.isControlLane(packet.type))
    }

    override fun connectedPeerIDs(): List<PeerID> = connections.keys.toList()

    override fun setPeerBackboneTag(peerID: PeerID, supported: Boolean) {
        if (supported) tagAwarePeers.add(peerID) else tagAwarePeers.remove(peerID)
    }

    // --- Public manual-config API ---

    /**
     * Dial a remote router by host:port. The peer's PeerID is learned via
     * the post-connect handshake; the connection is then registered.
     *
     * Persistent: if the connection is dropped (or lost to dedup), the loop
     * waits for whichever connection is still live to end and re-dials with
     * exponential backoff. A single dialer Job per `host:port` is kept in
     * [dialTargets].
     */
    fun connectToHost(host: String, remotePort: Int = port) {
        val target = "$host:$remotePort"
        if (!isRunning) return
        if (dialTargets.containsKey(target)) return
        val job = scope.launch(Dispatchers.IO) {
            var delayMs = INITIAL_RECONNECT_DELAY_MS
            try {
                while (isRunning && isActive) {
                    val waited = dialOnce(host, remotePort, target)
                    if (waited) {
                        // Connection lived for at least one read cycle; reset backoff.
                        delayMs = INITIAL_RECONNECT_DELAY_MS
                    }
                    if (!isRunning) break
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                }
            } finally {
                dialTargets.remove(target)
            }
        }
        dialTargets[target] = job
    }

    /** Returns true if a connection was successfully established (and later torn down). */
    private suspend fun dialOnce(host: String, remotePort: Int, target: String): Boolean {
        val socket = try {
            Log.d(TAG, "Connecting to router at $target")
            Socket().apply {
                connect(InetSocketAddress(host, remotePort), 5000)
                tcpNoDelay = true
                keepAlive = true
                soTimeout = HANDSHAKE_TIMEOUT_MS
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to connect to $target: ${e.message}")
            return false
        }
        val output: DataOutputStream
        val input: DataInputStream
        val remotePeer: PeerID?
        try {
            output = DataOutputStream(socket.getOutputStream())
            input = DataInputStream(socket.getInputStream())
            writeHandshake(output)
            remotePeer = readHandshake(input)
        } catch (e: Exception) {
            Log.w(TAG, "Handshake error with $target: ${e.message}")
            try { socket.close() } catch (_: Exception) { }
            return false
        }
        if (remotePeer == null) {
            Log.w(TAG, "Handshake failed with $target")
            try { socket.close() } catch (_: Exception) { }
            return false
        }
        try { socket.soTimeout = READ_IDLE_TIMEOUT_MS } catch (_: Exception) { }

        // registerConnection either accepts this socket (returns its readJob)
        // or rejects it on dedup (returns the kept connection's readJob, if any).
        // Either way, block until that connection ends so we don't immediately
        // re-dial while the survivor is still alive.
        val activeJob = registerConnection(remotePeer, socket, output, input, isOutbound = true)
        activeJob?.join()
        return true
    }

    // --- Server ---

    private fun startServer() {
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "TCP server listening on port $port")
                while (isActive && isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    handleIncoming(socket)
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    private fun handleIncoming(socket: Socket) {
        scope.launch(Dispatchers.IO) {
            val remoteAddr = "${socket.inetAddress.hostAddress}:${socket.port}"
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val output = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())

                writeHandshake(output)
                val remotePeer = readHandshake(input)
                if (remotePeer == null) {
                    Log.w(TAG, "Inbound handshake failed from $remoteAddr")
                    try { socket.close() } catch (_: Exception) { }
                    return@launch
                }
                socket.soTimeout = READ_IDLE_TIMEOUT_MS
                registerConnection(remotePeer, socket, output, input, isOutbound = false)
            } catch (e: Exception) {
                Log.w(TAG, "Inbound connection from $remoteAddr failed: ${e.message}")
                try { socket.close() } catch (_: Exception) { }
            }
        }
    }

    // --- Handshake ---

    private fun writeHandshake(output: DataOutputStream) {
        val peerBytes = localPeerID.toBytes() ?: return
        synchronized(output) {
            output.writeByte(HANDSHAKE_VERSION.toInt())
            output.write(peerBytes)
            output.flush()
        }
    }

    private fun readHandshake(input: DataInputStream): PeerID? {
        val version = input.readByte()
        if (version != HANDSHAKE_VERSION) {
            Log.w(TAG, "Unsupported handshake version: $version")
            return null
        }
        val peerBytes = ByteArray(8)
        input.readFully(peerBytes)
        return PeerID.fromBytes(peerBytes)
    }

    /**
     * Register a freshly handshaken socket. Returns the read [Job] of the
     * connection that is alive after this call (whichever side won the
     * dedup tie-break), or `null` if no connection is alive (self-loop or
     * unrecoverable error).
     *
     * Tie-break rule (symmetric across both peers): when both ends dial each
     * other concurrently, both keep the socket where the **smaller PeerID
     * is the dialer**. Equivalently:
     *   - the side with the smaller PeerID keeps its outbound
     *   - the side with the larger  PeerID keeps its inbound
     * which is the same TCP socket. The other socket is closed by both ends.
     *
     * The previous logic was asymmetric ("smaller-PeerID side keeps the
     * 'existing' connection regardless of direction"), so under a true
     * simultaneous-dial the two sides closed different sockets and both
     * peers lost the bridge.
     */
    private fun registerConnection(
        remotePeer: PeerID,
        socket: Socket,
        output: DataOutputStream,
        input: DataInputStream,
        isOutbound: Boolean
    ): Job? {
        if (remotePeer == localPeerID) {
            Log.w(TAG, "Refusing self-connection ${remotePeer.rawValue.take(8)}")
            try { socket.close() } catch (_: Exception) { }
            return null
        }
        // Direction (inbound vs outbound) that wins the tie-break.
        val outboundWins = localPeerID.rawValue < remotePeer.rawValue
        val newWins = (isOutbound == outboundWins)

        synchronized(connections) {
            val existing = connections[remotePeer]
            if (existing != null) {
                if (!newWins) {
                    Log.d(TAG, "Duplicate connection to ${remotePeer.rawValue.take(8)} (new=${if (isOutbound) "out" else "in"}); keeping existing (${if (existing.isOutbound) "out" else "in"})")
                    try { socket.close() } catch (_: Exception) { }
                    return existing.readJob
                }
                Log.d(TAG, "Duplicate connection to ${remotePeer.rawValue.take(8)}; replacing existing (${if (existing.isOutbound) "out" else "in"}) with new (${if (isOutbound) "out" else "in"})")
                // Remove the existing entry now so the read-loop's finally
                // (running on cancel) doesn't see itself in the map and won't
                // fire onTransportPeerDisconnected -- the new conn replaces it.
                connections.remove(remotePeer)
                existing.readJob?.cancel()
                existing.writer.close()
                existing.writeJob?.cancel()
                try { existing.socket.close() } catch (_: Exception) { }
            }

            val conn = RouterConnection(remotePeer, socket, output, input, isOutbound)
            connections[remotePeer] = conn
            conn.readJob = startReading(conn)
            conn.writeJob = startWriting(conn)
            listener?.onTransportPeerConnected(this, remotePeer)
            Log.i(TAG, "Connected to router peer ${remotePeer.rawValue.take(8)} (${if (isOutbound) "outbound" else "inbound"})")
            return conn.readJob
        }
    }

    override fun disconnectPeer(peerID: PeerID) {
        val conn: RouterConnection
        synchronized(connections) {
            conn = connections.remove(peerID) ?: return
        }
        conn.readJob?.cancel()
        conn.writer.close()
        conn.writeJob?.cancel()
        try { conn.socket.close() } catch (_: Exception) { }
        tagAwarePeers.remove(peerID)
        listener?.onTransportPeerDisconnected(this, peerID)
    }

    /**
     * Identity-checked disconnect used by the read loop's `finally`: only
     * evicts the map entry if it still points at this specific connection,
     * so a connection that was already superseded by [registerConnection]
     * does not accidentally drop its replacement.
     */
    private fun disconnectConnection(conn: RouterConnection) {
        val wasActive: Boolean
        synchronized(connections) {
            wasActive = connections[conn.peerID] === conn
            if (wasActive) connections.remove(conn.peerID)
        }
        conn.writer.close()
        conn.writeJob?.cancel()
        try { conn.socket.close() } catch (_: Exception) { }
        if (wasActive) {
            tagAwarePeers.remove(conn.peerID)
            listener?.onTransportPeerDisconnected(this, conn.peerID)
        }
    }

    // --- Read loop ---

    private fun startReading(conn: RouterConnection): Job {
        return scope.launch(Dispatchers.IO) {
            try {
                while (isActive && isRunning && !conn.socket.isClosed) {
                    val length = conn.input.readInt()
                    if (length <= 0 || length > LinkWriter.MAX_RECEIVE_FRAME) {
                        Log.w(TAG, "Invalid frame length $length from ${conn.peerID.rawValue.take(8)}")
                        break
                    }
                    val data = ByteArray(length)
                    conn.input.readFully(data)
                    // A tagged frame carries the visited-router path ahead of the
                    // packet; a plain frame is the bare packet (empty path).
                    val tag = BackboneFrame.decode(data)
                    val visited = tag?.visited ?: emptyList()
                    val packet = BinaryProtocol.decode(tag?.packetBytes ?: data)
                    if (packet != null) {
                        listener?.onTransportPacketReceived(this@WifiBridgeTransport, packet, conn.peerID, visited)
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                if (isRunning) Log.w(TAG, "Read idle >${READ_IDLE_TIMEOUT_MS}ms from ${conn.peerID.rawValue.take(8)}; presuming dead link")
            } catch (e: Exception) {
                if (isRunning) Log.d(TAG, "Read loop ended for ${conn.peerID.rawValue.take(8)}: ${e.message}")
            } finally {
                disconnectConnection(conn)
            }
        }
    }

    // --- Write ---

    /**
     * Single writer per connection, draining [RouterConnection.writer].
     * Frames to a peer stay FIFO per lane, and a peer with a full TCP window
     * blocks exactly one IO thread instead of one per queued frame. Exits
     * when the writer is closed (disconnect) or a write fails.
     */
    private fun startWriting(conn: RouterConnection): Job =
        conn.writer.startWriter(scope, conn.output) { e ->
            Log.w(TAG, "Send to ${conn.peerID.rawValue.take(8)} failed: ${e.message}")
            disconnectConnection(conn)
        }
}
