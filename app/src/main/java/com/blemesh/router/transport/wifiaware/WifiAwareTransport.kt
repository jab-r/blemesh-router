package com.blemesh.router.transport.wifiaware

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.HandlerThread
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi Aware (NAN) router-to-router transport.
 *
 * Lifecycle:
 *  start()
 *    -> WifiAwareManager.attach -> WifiAwareSession
 *    -> publish service "blemesh-router-v1" with our PeerID in serviceSpecificInfo
 *    -> subscribe to the same service
 *  on subscribe.onServiceDiscovered(peerHandle, serviceSpecificInfo):
 *    -> peer's PeerID = decode(serviceSpecificInfo)
 *    -> deterministic role: lexicographically-greater PeerID is server
 *    -> server: build NetworkSpecifier with port, ConnectivityManager.requestNetwork
 *               on onAvailable -> ServerSocket.accept
 *    -> client: build NetworkSpecifier without port
 *               on onCapabilitiesChanged -> dial WifiAwareNetworkInfo.peerIpv6Addr:port
 *  on socket connected:
 *    -> length-prefixed read loop -> listener.onTransportPacketReceived
 *
 * Identity binding is eager: serviceSpecificInfo carries the 8-byte raw PeerID,
 * so we know the peer's identity before any data flows.
 *
 * Limitations of this initial implementation (untested in this repo):
 *  - No retry on attach/publish/subscribe failure; restart the service to recover.
 *  - No reconnect on transient network loss.
 *  - Open data path (no PSK) — depends on framework default. Routers within
 *    a deployment are assumed to trust each other.
 */
@SuppressLint("MissingPermission")
class WifiAwareTransport(
    private val context: Context,
    private val localPeerID: PeerID
) : RouterTransport {

    override val name: String = "aware"
    override var listener: RouterTransport.Listener? = null

    private val mgr: WifiAwareManager? =
        context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    private val connectivityMgr: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handlerThread = HandlerThread("WifiAwareTransport").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null

    private class PeerLink(
        val peerHandle: PeerHandle,
        val peerID: PeerID,
        val isServer: Boolean
    ) {
        @Volatile var network: Network? = null
        @Volatile var serverSocket: ServerSocket? = null
        @Volatile var socket: Socket? = null
        @Volatile var readJob: Job? = null
        @Volatile var networkCallback: ConnectivityManager.NetworkCallback? = null
    }

    private val peers = ConcurrentHashMap<PeerID, PeerLink>()
    @Volatile private var running = false

    // --- RouterTransport ---

    override fun isAvailable(): Boolean = isAvailable(context)

    override fun start() {
        if (running) return
        if (!isAvailable()) {
            Log.i(TAG, "Wi-Fi Aware not available on this device")
            return
        }
        running = true
        attach()
    }

    override fun stop() {
        if (!running) return
        running = false
        for (link in peers.values.toList()) tearDownLink(link)
        peers.clear()
        try { publishSession?.close() } catch (_: Exception) {}
        try { subscribeSession?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        publishSession = null
        subscribeSession = null
        session = null
        scope.cancel()
        handlerThread.quitSafely()
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

    override fun connectedPeerIDs(): List<PeerID> =
        peers.values.filter { it.socket?.isConnected == true }.map { it.peerID }

    // --- Aware attach / discovery ---

    private fun attach() {
        val m = mgr ?: return
        m.attach(object : AttachCallback() {
            override fun onAttached(s: WifiAwareSession) {
                session = s
                if (running) {
                    startPublish()
                    startSubscribe()
                }
            }
            override fun onAttachFailed() {
                Log.w(TAG, "Wi-Fi Aware attach failed")
            }
        }, handler)
    }

    private fun startPublish() {
        val s = session ?: return
        val cfg = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(localPeerID.toBytes())
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .build()
        s.publish(cfg, object : DiscoverySessionCallback() {
            override fun onPublishStarted(s: PublishDiscoverySession) {
                publishSession = s
                Log.d(TAG, "Aware publish started")
            }
        }, handler)
    }

    private fun startSubscribe() {
        val s = session ?: return
        val cfg = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .build()
        s.subscribe(cfg, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(s: SubscribeDiscoverySession) {
                subscribeSession = s
                Log.d(TAG, "Aware subscribe started")
            }
            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: List<ByteArray>?
            ) {
                handleDiscovery(peerHandle, serviceSpecificInfo)
            }
            override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
                val match = peers.values.firstOrNull { it.peerHandle == peerHandle }
                if (match != null) tearDownLink(match)
            }
        }, handler)
    }

    private fun handleDiscovery(peerHandle: PeerHandle, info: ByteArray?) {
        if (info == null || info.size != 8) return
        val peerID = PeerID.fromBytes(info) ?: return
        if (peerID == localPeerID) return
        if (peers.containsKey(peerID)) return

        val isServer = localPeerID.rawValue > peerID.rawValue
        val link = PeerLink(peerHandle, peerID, isServer)
        peers[peerID] = link

        Log.d(TAG, "Aware discovered ${peerID.rawValue.take(8)} (role=${if (isServer) "server" else "client"})")
        if (isServer) startServerSide(link) else startClientSide(link)
    }

    // --- NDP server side ---

    private fun startServerSide(link: PeerLink) {
        val sub = subscribeSession ?: return
        val server = try {
            ServerSocket(0).also { it.reuseAddress = true }
        } catch (_: Exception) {
            peers.remove(link.peerID); return
        }
        link.serverSocket = server
        val port = server.localPort

        val specifier = WifiAwareNetworkSpecifier.Builder(sub, link.peerHandle)
            .setPort(port)
            .setTransportProtocol(IPPROTO_TCP)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                link.network = network
                scope.launch {
                    try {
                        val sock = server.accept()
                        link.socket = sock
                        listener?.onTransportPeerConnected(this@WifiAwareTransport, link.peerID)
                        startReadLoop(link)
                    } catch (_: Exception) {
                        tearDownLink(link)
                    }
                }
            }
            override fun onLost(network: Network) {
                tearDownLink(link)
            }
        }
        link.networkCallback = cb
        try { connectivityMgr.requestNetwork(request, cb) } catch (e: Exception) {
            Log.w(TAG, "requestNetwork (server) failed: ${e.message}")
            tearDownLink(link)
        }
    }

    // --- NDP client side ---

    private fun startClientSide(link: PeerLink) {
        val sub = subscribeSession ?: return
        val specifier = WifiAwareNetworkSpecifier.Builder(sub, link.peerHandle)
            .setTransportProtocol(IPPROTO_TCP)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var dialed = false
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (dialed) return
                val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                val peerIp: InetAddress = info.peerIpv6Addr ?: return
                val peerPort = info.port
                if (peerPort <= 0) return
                dialed = true
                link.network = network
                scope.launch {
                    try {
                        val sock = network.socketFactory.createSocket(peerIp, peerPort)
                        link.socket = sock
                        listener?.onTransportPeerConnected(this@WifiAwareTransport, link.peerID)
                        startReadLoop(link)
                    } catch (_: Exception) {
                        tearDownLink(link)
                    }
                }
            }
            override fun onLost(network: Network) {
                tearDownLink(link)
            }
        }
        link.networkCallback = cb
        try { connectivityMgr.requestNetwork(request, cb) } catch (e: Exception) {
            Log.w(TAG, "requestNetwork (client) failed: ${e.message}")
            tearDownLink(link)
        }
    }

    // --- Length-prefixed packet I/O ---

    private fun startReadLoop(link: PeerLink) {
        link.readJob = scope.launch {
            val sock = link.socket ?: return@launch
            val input: InputStream = sock.getInputStream()
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
                        listener?.onTransportPacketReceived(this@WifiAwareTransport, packet, link.peerID)
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
        val sock = link.socket ?: return
        scope.launch {
            try {
                val out: OutputStream = sock.getOutputStream()
                synchronized(sock) {
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
        try { link.socket?.close() } catch (_: Exception) {}
        try { link.serverSocket?.close() } catch (_: Exception) {}
        link.networkCallback?.let {
            try { connectivityMgr.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        listener?.onTransportPeerDisconnected(this, link.peerID)
    }

    companion object {
        private const val TAG = "WifiAwareTransport"
        const val SERVICE_NAME = "blemesh-router-v1"
        const val MAX_PACKET = 64 * 1024
        // IPPROTO_TCP from posix; WifiAwareNetworkSpecifier expects this raw int.
        private const val IPPROTO_TCP = 6

        fun isAvailable(context: Context): Boolean {
            val pm = context.packageManager
            if (!pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) return false
            val mgr = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
                ?: return false
            return mgr.isAvailable
        }
    }
}
