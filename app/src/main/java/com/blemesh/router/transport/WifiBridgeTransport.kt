package com.blemesh.router.transport

import android.util.Log
import com.blemesh.router.model.BlemeshPacket
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
        private const val MAX_PACKET_SIZE = 65536
        private const val HANDSHAKE_VERSION: Byte = 0x01
        private const val HANDSHAKE_TIMEOUT_MS = 5000
        private const val RECONNECT_DELAY_MS = 5000L
    }

    override val name: String = "tcp"
    override var listener: RouterTransport.Listener? = null

    private val connections = ConcurrentHashMap<PeerID, RouterConnection>()
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var isRunning = false

    private data class RouterConnection(
        val peerID: PeerID,
        val socket: Socket,
        val output: DataOutputStream,
        val input: DataInputStream,
        var readJob: Job? = null
    )

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
        for (peerID in connections.keys.toList()) {
            disconnectPeer(peerID)
        }
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        Log.i(TAG, "TCP bridge transport stopped")
    }

    override fun broadcast(packet: BlemeshPacket) {
        val encoded = BinaryProtocol.encode(packet) ?: return
        for (conn in connections.values) {
            sendRaw(conn, encoded)
        }
    }

    override fun sendToPeer(peerID: PeerID, packet: BlemeshPacket) {
        val conn = connections[peerID] ?: return
        val encoded = BinaryProtocol.encode(packet) ?: return
        sendRaw(conn, encoded)
    }

    override fun connectedPeerIDs(): List<PeerID> = connections.keys.toList()

    // --- Public manual-config API ---

    /**
     * Dial a remote router by host:port. The peer's PeerID is learned via
     * the post-connect handshake; the connection is then registered.
     */
    fun connectToHost(host: String, remotePort: Int = port) {
        scope.launch(Dispatchers.IO) {
            val target = "$host:$remotePort"
            try {
                Log.d(TAG, "Connecting to router at $target")
                val socket = Socket().apply {
                    connect(InetSocketAddress(host, remotePort), 5000)
                    tcpNoDelay = true
                    keepAlive = true
                    soTimeout = HANDSHAKE_TIMEOUT_MS
                }
                val output = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())

                writeHandshake(output)
                val remotePeer = readHandshake(input)
                if (remotePeer == null) {
                    Log.w(TAG, "Handshake failed with $target")
                    try { socket.close() } catch (_: Exception) { }
                    return@launch
                }
                socket.soTimeout = 0
                registerConnection(remotePeer, socket, output, input)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to connect to $target: ${e.message}")
                if (isRunning) {
                    delay(RECONNECT_DELAY_MS)
                    connectToHost(host, remotePort)
                }
            }
        }
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
                socket.soTimeout = 0
                registerConnection(remotePeer, socket, output, input)
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

    private fun registerConnection(
        remotePeer: PeerID,
        socket: Socket,
        output: DataOutputStream,
        input: DataInputStream
    ) {
        if (remotePeer == localPeerID) {
            Log.w(TAG, "Refusing self-connection ${remotePeer.rawValue.take(8)}")
            try { socket.close() } catch (_: Exception) { }
            return
        }
        // If we already have a connection to this peer, drop the duplicate.
        // Deterministic tie-break: keep the one initiated by the smaller PeerID.
        val existing = connections[remotePeer]
        if (existing != null) {
            val keepNew = localPeerID.rawValue < remotePeer.rawValue
            if (!keepNew) {
                Log.d(TAG, "Duplicate connection to ${remotePeer.rawValue.take(8)}; keeping existing")
                try { socket.close() } catch (_: Exception) { }
                return
            }
            disconnectPeer(remotePeer)
        }

        val conn = RouterConnection(remotePeer, socket, output, input)
        connections[remotePeer] = conn
        conn.readJob = startReading(conn)
        listener?.onTransportPeerConnected(this, remotePeer)
        Log.i(TAG, "Connected to router peer ${remotePeer.rawValue.take(8)}")
    }

    private fun disconnectPeer(peerID: PeerID) {
        val conn = connections.remove(peerID) ?: return
        conn.readJob?.cancel()
        try { conn.socket.close() } catch (_: Exception) { }
        listener?.onTransportPeerDisconnected(this, peerID)
    }

    // --- Read loop ---

    private fun startReading(conn: RouterConnection): Job {
        return scope.launch(Dispatchers.IO) {
            try {
                while (isActive && isRunning && !conn.socket.isClosed) {
                    val length = conn.input.readInt()
                    if (length <= 0 || length > MAX_PACKET_SIZE) {
                        Log.w(TAG, "Invalid frame length $length from ${conn.peerID.rawValue.take(8)}")
                        break
                    }
                    val data = ByteArray(length)
                    conn.input.readFully(data)
                    val packet = BinaryProtocol.decode(data)
                    if (packet != null) {
                        listener?.onTransportPacketReceived(this@WifiBridgeTransport, packet, conn.peerID)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.d(TAG, "Read loop ended for ${conn.peerID.rawValue.take(8)}: ${e.message}")
            } finally {
                disconnectPeer(conn.peerID)
            }
        }
    }

    // --- Write ---

    private fun sendRaw(conn: RouterConnection, data: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                synchronized(conn.output) {
                    conn.output.writeInt(data.size)
                    conn.output.write(data)
                    conn.output.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Send to ${conn.peerID.rawValue.take(8)} failed: ${e.message}")
                disconnectPeer(conn.peerID)
            }
        }
    }
}
