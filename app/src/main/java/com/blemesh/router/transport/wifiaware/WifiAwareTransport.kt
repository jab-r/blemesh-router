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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wi-Fi Aware (NAN) router-to-router transport.
 *
 * Lifecycle:
 *  start()
 *    -> WifiAwareManager.attach -> WifiAwareSession
 *    -> publish service "blemesh-router-v1" with our PeerID in serviceSpecificInfo
 *    -> subscribe to the same service
 *
 * Role assignment uses lexicographic PeerID order; the larger PeerID is the
 * NDP data-path server (and must be the Aware *publisher* on this connection,
 * because setPort/setTransportProtocol on WifiAwareNetworkSpecifier are
 * publisher-only). The flow:
 *
 *  on subscribe.onServiceDiscovered(peerHandle, serviceSpecificInfo):
 *    decode peer PeerID
 *    if localPeerID < peerID  -> we are the client:
 *        sendMessage(peerHandle, localPeerID) on subscribeSession to wake
 *        the peer's publish-session onMessageReceived
 *        build NetworkSpecifier from subscribeSession (no port, no protocol)
 *        requestNetwork; on onCapabilitiesChanged dial
 *        WifiAwareNetworkInfo.peerIpv6Addr:port
 *    else                     -> ignore; peer will contact us via publish
 *  on publish.onMessageReceived(peerHandle, message):
 *    decode peer PeerID from message; we are the server (localPeerID > peerID)
 *    open ServerSocket(0)
 *    build NetworkSpecifier from publishSession with setPort + IPPROTO_TCP
 *    requestNetwork; on onAvailable accept()
 *  on socket connected:
 *    length-prefixed read loop -> listener.onTransportPacketReceived
 *
 * Identity binding is eager: serviceSpecificInfo carries the 8-byte raw PeerID,
 * so we know the peer's identity before any data flows.
 *
 * Limitations of this initial implementation (untested in this repo):
 *  - No retry on attach/publish/subscribe failure; restart the service to recover.
 *  - No reconnect on transient network loss.
 *  - Shared static PSK on the NDP link. Android requires a secure link when
 *    setPort/setTransportProtocol are used, so we cannot use an open link.
 *    The PSK is a constant baked into the app — routers within a deployment
 *    are assumed to trust each other.
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
        @Volatile var establishStartMs: Long = 0L
        // Monotonic: false -> true exactly once when the socket is published.
        // Guarded by synchronized(this) when paired with watchdog reads.
        @Volatile var established: Boolean = false
        @Volatile var watchdog: Job? = null
    }

    private val peers = ConcurrentHashMap<PeerID, PeerLink>()
    private val nextMessageId = AtomicInteger(1)
    // msgId -> peer the wake-up message was addressed to. Used by the subscribe
    // session's onMessageSendSucceeded/Failed callbacks to log which peer.
    private val pendingMessageIds = ConcurrentHashMap<Int, PeerID>()
    @Volatile private var running = false

    private fun shortId(p: PeerID): String = p.rawValue.take(8)

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
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handlePublishMessage(peerHandle, message)
            }
            override fun onSessionTerminated() {
                Log.w(TAG, "publishSession terminated; tearing down server-role links and restarting publish")
                publishSession = null
                for (link in peers.values.toList()) {
                    if (link.isServer) tearDownLink(link)
                }
                if (running) handler.post { startPublish() }
            }
            override fun onSessionConfigFailed() {
                Log.w(TAG, "publish onSessionConfigFailed; will retry")
                publishSession = null
                if (running) handler.post { startPublish() }
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
            override fun onMessageSendSucceeded(messageId: Int) {
                val peerID = pendingMessageIds.remove(messageId) ?: return
                Log.d(TAG, "Aware sendMessage delivered peer=${shortId(peerID)} msgId=$messageId")
            }
            override fun onMessageSendFailed(messageId: Int) {
                val peerID = pendingMessageIds.remove(messageId)
                Log.w(TAG, "Aware sendMessage onMessageSendFailed peer=${peerID?.let { shortId(it) } ?: "?"} msgId=$messageId (watchdog will tear down if NDP doesn't establish)")
                // Do not tear down here -- the watchdog is the authoritative timeout,
                // and Android sometimes reports onMessageSendFailed for messages the
                // peer actually received.
            }
            override fun onSessionTerminated() {
                Log.w(TAG, "subscribeSession terminated; tearing down client-role links and restarting subscribe")
                subscribeSession = null
                for (link in peers.values.toList()) {
                    if (!link.isServer) tearDownLink(link)
                }
                if (running) handler.post { startSubscribe() }
            }
            override fun onSessionConfigFailed() {
                Log.w(TAG, "subscribe onSessionConfigFailed; will retry")
                subscribeSession = null
                if (running) handler.post { startSubscribe() }
            }
        }, handler)
    }

    private fun handleDiscovery(peerHandle: PeerHandle, info: ByteArray?) {
        if (info == null || info.size != 8) return
        val peerID = PeerID.fromBytes(info) ?: return
        if (peerID == localPeerID) return
        if (peers.containsKey(peerID)) return

        // Only the lower-PeerID side initiates from subscribe. The higher side
        // will be notified via its publish-session onMessageReceived.
        if (localPeerID.rawValue >= peerID.rawValue) return

        val sub = subscribeSession ?: return
        val link = PeerLink(peerHandle, peerID, isServer = false)
        peers[peerID] = link
        Log.d(TAG, "Aware discovered ${shortId(peerID)} (role=client) peerHandle=$peerHandle")

        val ourId = localPeerID.toBytes()
        if (ourId == null) {
            tearDownLink(link); return
        }

        // Retry the wake-up send a few times off the Aware HandlerThread.
        // Eager call to startClientSide(): the NAN follow-up is small and
        // usually delivers within tens of ms; blocking NDP setup behind ack
        // adds latency on the happy path. If the message is lost the watchdog
        // tears down the link and the next onServiceDiscovered retries.
        val msgId = nextMessageId.getAndIncrement()
        pendingMessageIds[msgId] = peerID
        scope.launch {
            var sendOk = false
            for (attempt in 0..MSG_SEND_RETRIES) {
                try {
                    sub.sendMessage(peerHandle, msgId, ourId)
                    sendOk = true
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Aware sendMessage attempt=$attempt peer=${shortId(peerID)} err=${e.message}")
                    if (attempt < MSG_SEND_RETRIES) delay(MSG_SEND_RETRY_DELAY_MS)
                }
            }
            if (!sendOk) {
                pendingMessageIds.remove(msgId)
                handler.post { tearDownLink(link) }
            }
        }
        startClientSide(link)
    }

    private fun handlePublishMessage(peerHandle: PeerHandle, message: ByteArray) {
        if (message.size != 8) return
        val peerID = PeerID.fromBytes(message) ?: return
        if (peerID == localPeerID) return
        if (peers.containsKey(peerID)) return
        // We must be the higher-PeerID side to act as server.
        if (localPeerID.rawValue <= peerID.rawValue) return

        val link = PeerLink(peerHandle, peerID, isServer = true)
        peers[peerID] = link
        Log.d(TAG, "Aware connect-request from ${shortId(peerID)} (role=server) peerHandle=$peerHandle")
        startServerSide(link)
    }

    // --- NDP server side ---

    private fun startServerSide(link: PeerLink) {
        // Server (publisher) side: setPort/setTransportProtocol require a
        // PublishDiscoverySession-built specifier per Android NDP contract.
        val pub = publishSession ?: run { tearDownLink(link); return }
        val server = try {
            ServerSocket(0).also { it.reuseAddress = true }
        } catch (_: Exception) {
            peers.remove(link.peerID); return
        }
        link.serverSocket = server
        val port = server.localPort

        val specifier = WifiAwareNetworkSpecifier.Builder(pub, link.peerHandle)
            .setPskPassphrase(DATA_PATH_PSK)
            .setPort(port)
            .setTransportProtocol(IPPROTO_TCP)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val delta = System.currentTimeMillis() - link.establishStartMs
                Log.i(TAG, "NDP onAvailable SERVER peer=${shortId(link.peerID)} +${delta}ms network=$network")
                link.network = network
                scope.launch {
                    val acceptStart = System.currentTimeMillis()
                    try {
                        val sock = server.accept()
                        Log.i(TAG, "server.accept returned peer=${shortId(link.peerID)} +${System.currentTimeMillis() - acceptStart}ms remote=${sock.inetAddress}")
                        synchronized(link) {
                            link.established = true
                            link.socket = sock
                        }
                        link.watchdog?.cancel()
                        link.watchdog = null
                        listener?.onTransportPeerConnected(this@WifiAwareTransport, link.peerID)
                        startReadLoop(link)
                    } catch (e: Exception) {
                        Log.w(TAG, "server.accept failed peer=${shortId(link.peerID)} err=${e.message}")
                        handler.post { tearDownLink(link) }
                    }
                }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiAwareNetworkInfo
                Log.d(TAG, "NDP onCapabilitiesChanged SERVER peer=${shortId(link.peerID)} ip=${info?.peerIpv6Addr} port=${info?.port}")
            }
            override fun onLost(network: Network) {
                val delta = System.currentTimeMillis() - link.establishStartMs
                Log.w(TAG, "NDP onLost SERVER peer=${shortId(link.peerID)} +${delta}ms")
                handler.post { tearDownLink(link) }
            }
            override fun onUnavailable() {
                val delta = System.currentTimeMillis() - link.establishStartMs
                Log.w(TAG, "NDP onUnavailable SERVER peer=${shortId(link.peerID)} +${delta}ms (framework declared request unfulfillable)")
                handler.post { tearDownLink(link) }
            }
        }
        link.networkCallback = cb
        link.establishStartMs = System.currentTimeMillis()
        Log.i(TAG, "requestNetwork SERVER peer=${shortId(link.peerID)} port=$port proto=TCP")
        try {
            connectivityMgr.requestNetwork(request, cb)
            armWatchdog(link)
        } catch (e: Exception) {
            Log.w(TAG, "requestNetwork (server) failed peer=${shortId(link.peerID)} err=${e.message}")
            tearDownLink(link)
        }
    }

    // --- NDP client side ---

    private fun startClientSide(link: PeerLink) {
        // Client (subscriber) side: must NOT call setPort or setTransportProtocol.
        // The port arrives via WifiAwareNetworkInfo on onCapabilitiesChanged.
        val sub = subscribeSession ?: return
        val specifier = WifiAwareNetworkSpecifier.Builder(sub, link.peerHandle)
            .setPskPassphrase(DATA_PATH_PSK)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var dialed = false
            override fun onAvailable(network: Network) {
                val delta = System.currentTimeMillis() - link.establishStartMs
                Log.i(TAG, "NDP onAvailable CLIENT peer=${shortId(link.peerID)} +${delta}ms network=$network")
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (dialed) return
                val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                val peerIp: InetAddress = info.peerIpv6Addr ?: return
                val peerPort = info.port
                if (peerPort <= 0) return
                val delta = System.currentTimeMillis() - link.establishStartMs
                Log.i(TAG, "NDP onCapabilitiesChanged CLIENT peer=${shortId(link.peerID)} ip=$peerIp port=$peerPort +${delta}ms")
                dialed = true
                link.network = network
                scope.launch {
                    val dialStart = System.currentTimeMillis()
                    try {
                        val sock = network.socketFactory.createSocket(peerIp, peerPort)
                        Log.i(TAG, "client connect returned peer=${shortId(link.peerID)} +${System.currentTimeMillis() - dialStart}ms")
                        synchronized(link) {
                            link.established = true
                            link.socket = sock
                        }
                        link.watchdog?.cancel()
                        link.watchdog = null
                        listener?.onTransportPeerConnected(this@WifiAwareTransport, link.peerID)
                        startReadLoop(link)
                    } catch (e: Exception) {
                        Log.w(TAG, "client connect failed peer=${shortId(link.peerID)} err=${e.message}")
                        handler.post { tearDownLink(link) }
                    }
                }
            }
            override fun onLost(network: Network) {
                val delta = System.currentTimeMillis() - link.establishStartMs
                Log.w(TAG, "NDP onLost CLIENT peer=${shortId(link.peerID)} +${delta}ms")
                handler.post { tearDownLink(link) }
            }
            override fun onUnavailable() {
                val delta = System.currentTimeMillis() - link.establishStartMs
                Log.w(TAG, "NDP onUnavailable CLIENT peer=${shortId(link.peerID)} +${delta}ms (framework declared request unfulfillable)")
                handler.post { tearDownLink(link) }
            }
        }
        link.networkCallback = cb
        link.establishStartMs = System.currentTimeMillis()
        Log.i(TAG, "requestNetwork CLIENT peer=${shortId(link.peerID)} (port arrives via peer info)")
        try {
            connectivityMgr.requestNetwork(request, cb)
            armWatchdog(link)
        } catch (e: Exception) {
            Log.w(TAG, "requestNetwork (client) failed peer=${shortId(link.peerID)} err=${e.message}")
            tearDownLink(link)
        }
    }

    /**
     * Per-link watchdog. Tears down a PeerLink if NDP hasn't established within
     * [NDP_ESTABLISH_TIMEOUT_MS]. Without this, a stuck requestNetwork leaves us
     * waiting ~51s for the framework's own eviction, with no signal to retry.
     */
    private fun armWatchdog(link: PeerLink) {
        link.watchdog = scope.launch {
            delay(NDP_ESTABLISH_TIMEOUT_MS)
            val shouldTearDown: Boolean
            synchronized(link) {
                // `established` is monotonic; a snapshot under the lock paired
                // with the same lock in the establish path closes the race.
                shouldTearDown = !link.established
            }
            if (shouldTearDown) {
                Log.w(TAG, "NDP watchdog firing peer=${shortId(link.peerID)} role=${if (link.isServer) "SERVER" else "CLIENT"} after ${NDP_ESTABLISH_TIMEOUT_MS}ms")
                // Serialize teardown with framework callbacks on the Aware HandlerThread.
                handler.post { tearDownLink(link) }
            }
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
        val delta = if (link.establishStartMs > 0) System.currentTimeMillis() - link.establishStartMs else 0L
        Log.i(TAG, "tearDownLink peer=${shortId(link.peerID)} role=${if (link.isServer) "SERVER" else "CLIENT"} established=${link.established} +${delta}ms")
        link.watchdog?.cancel()
        link.watchdog = null
        link.readJob?.cancel()
        try { link.socket?.close() } catch (_: Exception) {}
        try { link.serverSocket?.close() } catch (_: Exception) {}
        link.networkCallback?.let {
            try { connectivityMgr.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        // Drop any wake-up message ids still waiting on success/fail callbacks
        // for this peer -- their callbacks would otherwise log a stale peer.
        val iter = pendingMessageIds.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value == link.peerID) iter.remove()
        }
        listener?.onTransportPeerDisconnected(this, link.peerID)
    }

    companion object {
        private const val TAG = "WifiAwareTransport"
        const val SERVICE_NAME = "blemesh-router-v1"
        const val MAX_PACKET = 64 * 1024
        // IPPROTO_TCP from posix; WifiAwareNetworkSpecifier expects this raw int.
        private const val IPPROTO_TCP = 6
        // Static PSK satisfies Android's "secure link" requirement when
        // setPort/setTransportProtocol are used. Same string on every router
        // in the deployment.
        private const val DATA_PATH_PSK = "blemesh-router-aware-v1"
        // Per-link NDP establish timeout. The framework typically gives up at
        // ~51s; we cut over at 18s so we can retry on the next discovery cycle
        // instead of stalling. Comfortably above typical NDP setup (~1-8s).
        private const val NDP_ESTABLISH_TIMEOUT_MS = 18_000L
        private const val MSG_SEND_RETRIES = 2
        private const val MSG_SEND_RETRY_DELAY_MS = 500L

        fun isAvailable(context: Context): Boolean {
            val pm = context.packageManager
            if (!pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) return false
            val mgr = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
                ?: return false
            return mgr.isAvailable
        }
    }
}
