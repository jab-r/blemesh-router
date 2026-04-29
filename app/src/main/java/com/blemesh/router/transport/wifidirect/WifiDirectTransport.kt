package com.blemesh.router.transport.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.PeerID
import com.blemesh.router.protocol.BinaryProtocol
import com.blemesh.router.transport.RouterTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi Direct (P2P) router-to-router transport — fallback for devices that
 * lack Wi-Fi Aware.
 *
 * Limitations vs Wi-Fi Aware:
 *  - One Wi-Fi Direct group per phone in standard mode → mesh of 3+ Direct-only
 *    nodes degrades to a star or to disconnected pair-groups.
 *  - This implementation supports a single group per phone. Two phones meet,
 *    one becomes Group Owner per framework intent negotiation, the other client.
 *
 * Lifecycle:
 *  start()
 *    -> WifiP2pManager.initialize -> Channel
 *    -> register BroadcastReceiver for P2P intents
 *    -> periodic discoverPeers() loop
 *  on peers-changed: pick a peer to connect to (lowest device address)
 *  on connection-changed (group formed):
 *    -> requestConnectionInfo -> WifiP2pInfo
 *    -> if isGroupOwner: ServerSocket(GROUP_OWNER_TCP_PORT), accept inbound
 *    -> else:            dial groupOwnerAddress:GROUP_OWNER_TCP_PORT
 *  on socket established:
 *    -> handshake [version:1][peerID:8] both directions
 *    -> bind socket -> peer PeerID; fire onTransportPeerConnected
 *    -> length-prefixed read loop
 *
 * Untested in this repo; ported from the (also untested) blemesh-android module.
 */
@SuppressLint("MissingPermission")
class WifiDirectTransport(
    private val context: Context,
    private val localPeerID: PeerID
) : RouterTransport {

    override val name: String = "direct"
    override var listener: RouterTransport.Listener? = null

    private val mgr: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    @Volatile private var discoveryJob: Job? = null

    private class PeerLink(
        val peerID: PeerID,
        val socket: Socket
    ) {
        @Volatile var readJob: Job? = null
    }

    private val peers = ConcurrentHashMap<PeerID, PeerLink>()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var serverAcceptJob: Job? = null
    @Volatile private var connectionInProgress: Boolean = false

    // --- RouterTransport ---

    override fun isAvailable(): Boolean = isAvailable(context)

    override fun start() {
        if (running) return
        if (mgr == null) {
            Log.i(TAG, "Wi-Fi Direct not available on this device")
            return
        }
        running = true
        channel = mgr.initialize(context, context.mainLooper, null)
        registerReceiver()
        startDiscoveryLoop()
    }

    override fun stop() {
        if (!running) return
        running = false
        discoveryJob?.cancel()
        serverAcceptJob?.cancel()
        for (link in peers.values.toList()) tearDownLink(link)
        peers.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { receiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        receiver = null
        try { mgr?.removeGroup(channel, null) } catch (_: Exception) {}
        scope.cancel()
    }

    override fun broadcast(packet: BlemeshPacket) {
        val data = BinaryProtocol.encode(packet) ?: return
        for (link in peers.values) writeFramed(link, data)
    }

    override fun sendToPeer(peerID: PeerID, packet: BlemeshPacket) {
        val link = peers[peerID] ?: return
        val data = BinaryProtocol.encode(packet) ?: return
        writeFramed(link, data)
    }

    override fun connectedPeerIDs(): List<PeerID> = peers.keys.toList()

    // --- BroadcastReceiver for P2P intents ---

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> handlePeersChanged()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> handleConnectionChanged()
                }
            }
        }
        receiver = r
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(r, filter)
        }
    }

    private fun startDiscoveryLoop() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            while (isActive && running) {
                kickDiscovery()
                delay(DISCOVERY_INTERVAL_MS)
            }
        }
    }

    private fun kickDiscovery() {
        val m = mgr ?: return
        val ch = channel ?: return
        try {
            m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        } catch (_: Exception) {}
    }

    private fun handlePeersChanged() {
        if (connectionInProgress) return
        if (peers.isNotEmpty()) return // single Direct group is the practical limit
        val m = mgr ?: return
        val ch = channel ?: return
        try {
            m.requestPeers(ch) { list ->
                val candidate = pickPeer(list.deviceList.toList()) ?: return@requestPeers
                connectionInProgress = true
                val cfg = WifiP2pConfig().apply {
                    deviceAddress = candidate.deviceAddress
                }
                m.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "P2P connect failed: $reason")
                        connectionInProgress = false
                    }
                })
            }
        } catch (_: Exception) { connectionInProgress = false }
    }

    /** Lowest deviceAddress so two phones don't race connecting to each other. */
    private fun pickPeer(devices: List<WifiP2pDevice>): WifiP2pDevice? =
        devices
            .filter { it.status == WifiP2pDevice.AVAILABLE }
            .minByOrNull { it.deviceAddress ?: "" }

    private fun handleConnectionChanged() {
        val m = mgr ?: return
        val ch = channel ?: return
        try {
            m.requestConnectionInfo(ch) { info ->
                if (info == null || !info.groupFormed) {
                    connectionInProgress = false
                    return@requestConnectionInfo
                }
                if (info.isGroupOwner) {
                    startServerIfNeeded()
                } else {
                    val groupOwner = info.groupOwnerAddress ?: return@requestConnectionInfo
                    dialClient(info, groupOwner.hostAddress ?: return@requestConnectionInfo)
                }
            }
        } catch (_: Exception) {}
    }

    // --- Group Owner: ServerSocket on a known port ---

    private fun startServerIfNeeded() {
        if (serverSocket != null) return
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(GROUP_OWNER_TCP_PORT))
            }
        } catch (_: Exception) { return }
        serverSocket = s
        serverAcceptJob = scope.launch {
            while (isActive && running) {
                try {
                    val sock = s.accept()
                    handleNewSocket(sock)
                } catch (_: Exception) { break }
            }
        }
    }

    // --- Client: dial GO ---

    private fun dialClient(@Suppress("UNUSED_PARAMETER") info: WifiP2pInfo, host: String) {
        scope.launch {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, GROUP_OWNER_TCP_PORT), 5_000)
                handleNewSocket(sock)
            } catch (_: Exception) {
                connectionInProgress = false
            }
        }
    }

    // --- Application-layer PeerID handshake on socket ---

    private fun handleNewSocket(sock: Socket) {
        scope.launch {
            try {
                val out = sock.getOutputStream()
                val input = sock.getInputStream()
                // Send: version(1) || localPeerID(8)
                out.write(byteArrayOf(HANDSHAKE_VERSION))
                out.write(localPeerID.toBytes())
                out.flush()
                // Receive: version(1) || remotePeerID(8)
                val hdr = ByteArray(9)
                if (!readFully(input, hdr)) { sock.close(); return@launch }
                if (hdr[0] != HANDSHAKE_VERSION) { sock.close(); return@launch }
                val remote = PeerID.fromBytes(hdr.copyOfRange(1, 9)) ?: run {
                    sock.close(); return@launch
                }
                if (remote == localPeerID) { sock.close(); return@launch }
                if (peers.containsKey(remote)) { sock.close(); return@launch }
                val link = PeerLink(remote, sock)
                peers[remote] = link
                connectionInProgress = false
                listener?.onTransportPeerConnected(this@WifiDirectTransport, remote)
                startReadLoop(link)
            } catch (_: Exception) {
                try { sock.close() } catch (_: Exception) {}
                connectionInProgress = false
            }
        }
    }

    private fun startReadLoop(link: PeerLink) {
        link.readJob = scope.launch {
            val input = link.socket.getInputStream()
            val lenBuf = ByteArray(4)
            try {
                while (isActive && running) {
                    if (!readFully(input, lenBuf)) break
                    val len = ByteBuffer.wrap(lenBuf).int
                    if (len <= 0 || len > MAX_PACKET) break
                    val data = ByteArray(len)
                    if (!readFully(input, data)) break
                    val packet = BinaryProtocol.decode(data)
                    if (packet != null) {
                        listener?.onTransportPacketReceived(this@WifiDirectTransport, packet, link.peerID)
                    }
                }
            } catch (_: Exception) {
                // fall through
            } finally {
                tearDownLink(link)
            }
        }
    }

    private fun writeFramed(link: PeerLink, data: ByteArray) {
        scope.launch {
            try {
                val out: OutputStream = link.socket.getOutputStream()
                synchronized(link.socket) {
                    out.write(ByteBuffer.allocate(4).putInt(data.size).array())
                    out.write(data)
                    out.flush()
                }
            } catch (_: Exception) {
                tearDownLink(link)
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun tearDownLink(link: PeerLink) {
        if (peers.remove(link.peerID) == null) return
        link.readJob?.cancel()
        try { link.socket.close() } catch (_: Exception) {}
        listener?.onTransportPeerDisconnected(this, link.peerID)
    }

    companion object {
        private const val TAG = "WifiDirectTransport"
        const val GROUP_OWNER_TCP_PORT = 8088
        const val MAX_PACKET = 64 * 1024
        const val DISCOVERY_INTERVAL_MS = 30_000L
        const val HANDSHAKE_VERSION: Byte = 1

        fun isAvailable(context: Context): Boolean {
            val pm = context.packageManager
            if (!pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) return false
            val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            return mgr != null
        }
    }
}
