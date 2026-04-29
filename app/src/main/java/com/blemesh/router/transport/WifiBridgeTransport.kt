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
 * WiFi bridge transport for forwarding BLE mesh packets between router access points.
 *
 * Protocol: Simple length-prefixed framing over TCP.
 *   [length:4 bytes big-endian][blemesh packet data: length bytes]
 *
 * Each router runs a TCP server and connects to other routers as clients.
 * Packets received from the BLE mesh that are destined for non-local peers
 * are forwarded over WiFi to connected router peers.
 */
class WifiBridgeTransport(
    private val scope: CoroutineScope,
    private val port: Int = DEFAULT_PORT
) {
    companion object {
        private const val TAG = "WifiBridgeTransport"
        const val DEFAULT_PORT = 9742
        private const val MAX_PACKET_SIZE = 65536
        private const val RECONNECT_DELAY_MS = 5000L
        private const val HEARTBEAT_INTERVAL_MS = 15000L
        private const val HEARTBEAT_TIMEOUT_MS = 45000L
    }

    /**
     * Callback for packets received from WiFi peers.
     */
    interface PacketListener {
        fun onWifiPacketReceived(packet: BlemeshPacket, fromRouter: String)
        fun onRouterConnected(address: String)
        fun onRouterDisconnected(address: String)
    }

    var packetListener: PacketListener? = null

    // Connected WiFi router peers: address -> connection
    private val connections = ConcurrentHashMap<String, RouterConnection>()
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var isRunning = false

    data class RouterConnection(
        val address: String,
        val socket: Socket,
        val output: DataOutputStream,
        val input: DataInputStream,
        var lastActivity: Long = System.currentTimeMillis(),
        var readJob: Job? = null
    )

    // --- Lifecycle ---

    fun start() {
        if (isRunning) return
        isRunning = true
        startServer()
        startHeartbeatMonitor()
        Log.i(TAG, "WiFi bridge transport started on port $port")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        serverJob?.cancel()
        for ((address, conn) in connections) {
            disconnectRouter(address)
        }
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        Log.i(TAG, "WiFi bridge transport stopped")
    }

    // --- Public API ---

    /** Connect to a remote router by IP address. */
    fun connectToRouter(host: String, remotePort: Int = port) {
        val address = "$host:$remotePort"
        if (connections.containsKey(address)) return

        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Connecting to router at $address")
                val socket = Socket()
                socket.connect(InetSocketAddress(host, remotePort), 5000)
                socket.tcpNoDelay = true
                // OS-level keep-alive detects silently-dead TCP sessions (NAT timeouts,
                // WiFi drops) without us needing to ship heartbeat frames.
                socket.keepAlive = true

                val conn = RouterConnection(
                    address = address,
                    socket = socket,
                    output = DataOutputStream(socket.getOutputStream()),
                    input = DataInputStream(socket.getInputStream())
                )
                connections[address] = conn
                conn.readJob = startReading(conn)

                Log.i(TAG, "Connected to router at $address")
                packetListener?.onRouterConnected(address)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to connect to router at $address: ${e.message}")
                // Schedule reconnect
                if (isRunning) {
                    delay(RECONNECT_DELAY_MS)
                    connectToRouter(host, remotePort)
                }
            }
        }
    }

    /** Disconnect from a remote router. */
    fun disconnectRouter(address: String) {
        val conn = connections.remove(address) ?: return
        conn.readJob?.cancel()
        try { conn.socket.close() } catch (_: Exception) { }
        packetListener?.onRouterDisconnected(address)
    }

    /** Get list of connected router addresses. */
    fun getConnectedRouters(): List<String> {
        return connections.keys.toList()
    }

    /** Send a BLE mesh packet to all connected WiFi routers. */
    fun broadcastToRouters(packet: BlemeshPacket) {
        val encoded = BinaryProtocol.encode(packet) ?: return
        for ((address, conn) in connections) {
            sendRawToRouter(conn, encoded)
        }
    }

    /** Send a BLE mesh packet to a specific router. */
    fun sendToRouter(address: String, packet: BlemeshPacket) {
        val conn = connections[address] ?: return
        val encoded = BinaryProtocol.encode(packet) ?: return
        sendRawToRouter(conn, encoded)
    }

    // --- Server ---

    private fun startServer() {
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "TCP server listening on port $port")
                while (isActive && isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    handleIncomingConnection(socket)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Server error: ${e.message}")
                }
            }
        }
    }

    private fun handleIncomingConnection(socket: Socket) {
        val remoteAddress = "${socket.inetAddress.hostAddress}:${socket.port}"
        Log.d(TAG, "Incoming connection from $remoteAddress")

        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val conn = RouterConnection(
                address = remoteAddress,
                socket = socket,
                output = DataOutputStream(socket.getOutputStream()),
                input = DataInputStream(socket.getInputStream())
            )
            connections[remoteAddress] = conn
            conn.readJob = startReading(conn)

            packetListener?.onRouterConnected(remoteAddress)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle incoming connection: ${e.message}")
            try { socket.close() } catch (_: Exception) { }
        }
    }

    // --- Reading ---

    private fun startReading(conn: RouterConnection): Job {
        return scope.launch(Dispatchers.IO) {
            try {
                while (isActive && isRunning && !conn.socket.isClosed) {
                    val length = conn.input.readInt()
                    if (length <= 0 || length > MAX_PACKET_SIZE) {
                        Log.w(TAG, "Invalid packet length $length from ${conn.address}")
                        break
                    }

                    val data = ByteArray(length)
                    conn.input.readFully(data)
                    conn.lastActivity = System.currentTimeMillis()

                    // Decode and deliver
                    val packet = BinaryProtocol.decode(data)
                    if (packet != null) {
                        packetListener?.onWifiPacketReceived(packet, conn.address)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.d(TAG, "Connection to ${conn.address} lost: ${e.message}")
                }
            } finally {
                disconnectRouter(conn.address)
            }
        }
    }

    // --- Writing ---

    private fun sendRawToRouter(conn: RouterConnection, data: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                synchronized(conn.output) {
                    conn.output.writeInt(data.size)
                    conn.output.write(data)
                    conn.output.flush()
                }
                conn.lastActivity = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send to ${conn.address}: ${e.message}")
                disconnectRouter(conn.address)
            }
        }
    }

    // --- Heartbeat ---

    private fun startHeartbeatMonitor() {
        scope.launch {
            while (isActive && isRunning) {
                delay(HEARTBEAT_INTERVAL_MS)
                val now = System.currentTimeMillis()
                for ((address, conn) in connections) {
                    if (now - conn.lastActivity > HEARTBEAT_TIMEOUT_MS) {
                        Log.w(TAG, "Router $address timed out, disconnecting")
                        disconnectRouter(address)
                    }
                }
            }
        }
    }
}
