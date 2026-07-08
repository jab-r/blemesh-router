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
import com.blemesh.router.model.MessageType
import com.blemesh.router.model.PeerID
import com.blemesh.router.protocol.BinaryProtocol
import com.blemesh.router.transport.BackboneFrame
import com.blemesh.router.transport.LinkWriter
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
 * Reconnect: a closed link never re-establishes on its own — the peer keeps
 * advertising continuously, so the still-active subscribe session will not
 * re-fire onServiceDiscovered (that needs an onServiceLost first), and the
 * server role only reacts to the client's wake-up message. The client side
 * therefore explicitly re-initiates (bounded attempts) after any teardown of
 * one of its links; a genuine re-discovery resets the attempt budget.
 *
 * Limitations of this initial implementation (untested in this repo):
 *  - No retry on attach/publish/subscribe failure; restart the service to recover.
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

    // Written on the Aware HandlerThread, read from IO-thread teardown paths
    // (read-loop finally, writer failure) — hence @Volatile.
    @Volatile private var session: WifiAwareSession? = null
    @Volatile private var publishSession: PublishDiscoverySession? = null
    @Volatile private var subscribeSession: SubscribeDiscoverySession? = null

    private class PeerLink(
        val peerHandle: PeerHandle,
        val peerID: PeerID,
        val isServer: Boolean
    ) {
        @Volatile var network: Network? = null
        @Volatile var serverSocket: ServerSocket? = null
        @Volatile var socket: Socket? = null
        @Volatile var readJob: Job? = null
        @Volatile var writeJob: Job? = null
        // Two-lane bounded send queue + single writer (shared helper): control
        // frames (ping/pong/caps/sync adverts) can't be starved or evicted by
        // bulk gossip bursts, and bulk overflow drops-oldest with logging.
        val writer = LinkWriter("aware:${peerID.rawValue.take(8)}")
        @Volatile var networkCallback: ConnectivityManager.NetworkCallback? = null
        @Volatile var establishStartMs: Long = 0L
        // Monotonic: false -> true exactly once when the socket is published.
        // Guarded by synchronized(this) when paired with watchdog reads.
        @Volatile var established: Boolean = false
        @Volatile var watchdog: Job? = null
    }

    private val peers = ConcurrentHashMap<PeerID, PeerLink>()
    // Peers that advertised backbone path-tag support (ROUTER_CAPS).
    private val tagAwarePeers = ConcurrentHashMap.newKeySet<PeerID>()
    // Consecutive client-side re-initiation attempts per peer since the last
    // established link / genuine discovery. Paces the reconnect loop: fast
    // retries first, then a slow perpetual fallback (see scheduleClientReconnect).
    private val reconnectAttempts = ConcurrentHashMap<PeerID, Int>()
    // Freshest peerHandle seen for each peer on the CURRENT subscribe session.
    // Recorded on every onServiceDiscovered — including ones swallowed by the
    // containsKey guard while a reconnect attempt occupies the peers slot — so
    // an in-flight retry chain adopts the fresh handle instead of burning its
    // attempts against a stale one after the peer churned handles.
    private val latestPeerHandle = ConcurrentHashMap<PeerID, PeerHandle>()
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
        reconnectAttempts.clear()
        latestPeerHandle.clear()
        try { publishSession?.close() } catch (_: Exception) {}
        try { subscribeSession?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        publishSession = null
        subscribeSession = null
        session = null
        scope.cancel()
        handlerThread.quitSafely()
    }

    override fun broadcast(packet: BlemeshPacket, visited: List<PeerID>, taggedPeersOnly: Boolean) {
        val plain = BinaryProtocol.encode(packet) ?: return
        val tagged = if (visited.isNotEmpty()) BackboneFrame.encode(visited, plain) else plain
        for ((peerID, link) in peers) {
            if (peerID in visited) continue // split-horizon
            val aware = peerID in tagAwarePeers
            if (taggedPeersOnly && !aware) continue // forwarded frames: tag-aware peers only
            val body = if (visited.isNotEmpty() && aware) tagged else plain
            link.writer.enqueue(body, controlLane = false)
        }
    }

    override fun sendToPeer(peerID: PeerID, packet: BlemeshPacket, visited: List<PeerID>) {
        val link = peers[peerID] ?: return
        val plain = BinaryProtocol.encode(packet) ?: return
        val body = if (visited.isNotEmpty() && peerID in tagAwarePeers) {
            BackboneFrame.encode(visited, plain)
        } else plain
        link.writer.enqueue(body, MessageType.isControlLane(packet.type))
    }

    override fun connectedPeerIDs(): List<PeerID> =
        // isConnected alone stays true forever after a successful connect;
        // isClosed flips once the read loop / liveness teardown closes it.
        peers.values
            .filter { link -> link.socket?.let { it.isConnected && !it.isClosed } == true }
            .map { it.peerID }

    override fun disconnectPeer(peerID: PeerID) {
        val link = peers[peerID] ?: return
        tearDownLink(link)
    }

    override fun setPeerBackboneTag(peerID: PeerID, supported: Boolean) {
        if (supported) tagAwarePeers.add(peerID) else tagAwarePeers.remove(peerID)
    }

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
                // The peer is GONE: don't chain reconnect attempts against a
                // handle that will be stale if/when it returns — its return
                // fires a fresh onServiceDiscovered, which re-initiates.
                latestPeerHandle.entries.removeIf { it.value == peerHandle }
                val match = peers.values.firstOrNull { it.peerHandle == peerHandle }
                if (match != null) tearDownLink(match, scheduleReconnect = false)
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
        // Record the fresh handle and re-arm the reconnect budget BEFORE the
        // containsKey early return: a rediscovery arriving while a doomed
        // reconnect attempt occupies the peers slot must still refresh both,
        // or the retry chain burns its attempts against a stale handle and
        // this (swallowed) event was the only re-arm that would ever fire.
        latestPeerHandle[peerID] = peerHandle
        reconnectAttempts.remove(peerID)
        if (peers.containsKey(peerID)) return

        // Only the lower-PeerID side initiates from subscribe. The higher side
        // will be notified via its publish-session onMessageReceived.
        if (localPeerID.rawValue >= peerID.rawValue) return

        Log.d(TAG, "Aware discovered ${shortId(peerID)} (role=client) peerHandle=$peerHandle")
        initiateClientLink(peerHandle, peerID)
    }

    /**
     * Client-role link initiation: register the PeerLink, send the wake-up
     * message so the peer's publish session learns our identity, and start
     * NDP setup. Called from a fresh onServiceDiscovered and from
     * [scheduleClientReconnect] (the subscribe session — and therefore the
     * stored [peerHandle] — is still valid in both cases).
     */
    private fun initiateClientLink(peerHandle: PeerHandle, peerID: PeerID) {
        val sub = subscribeSession ?: return
        val link = PeerLink(peerHandle, peerID, isServer = false)
        if (peers.putIfAbsent(peerID, link) != null) return

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
                        try { sock.soTimeout = READ_IDLE_TIMEOUT_MS } catch (_: Exception) {}
                        synchronized(link) {
                            link.established = true
                            link.socket = sock
                        }
                        // A teardown may have raced the blocking accept and
                        // already evicted this link: publishing it now would
                        // create a ghost (spurious connected event, socket no
                        // send path can reach). tearDownLink closes the
                        // resources even for a de-registered link.
                        if (peers[link.peerID] !== link) {
                            Log.w(TAG, "server accept completed for de-registered link peer=${shortId(link.peerID)}; discarding")
                            handler.post { tearDownLink(link) }
                            return@launch
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
                        try { sock.soTimeout = READ_IDLE_TIMEOUT_MS } catch (_: Exception) {}
                        synchronized(link) {
                            link.established = true
                            link.socket = sock
                        }
                        // See the server-side twin: a teardown that raced the
                        // blocking dial already evicted this link — don't
                        // publish a ghost.
                        if (peers[link.peerID] !== link) {
                            Log.w(TAG, "client connect completed for de-registered link peer=${shortId(link.peerID)}; discarding")
                            handler.post { tearDownLink(link) }
                            return@launch
                        }
                        link.watchdog?.cancel()
                        link.watchdog = null
                        reconnectAttempts.remove(link.peerID)
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
        startWriteLoop(link)
        link.readJob = scope.launch {
            val sock = link.socket ?: return@launch
            val input: InputStream = sock.getInputStream()
            val lenBuf = ByteArray(4)
            try {
                while (isActive && running) {
                    if (!readFully(input, lenBuf)) break
                    val len = ByteBuffer.wrap(lenBuf).int
                    if (len <= 0 || len > LinkWriter.MAX_RECEIVE_FRAME) break
                    val data = ByteArray(len)
                    if (!readFully(input, data)) break
                    val tag = BackboneFrame.decode(data)
                    val visited = tag?.visited ?: emptyList()
                    val packet = BinaryProtocol.decode(tag?.packetBytes ?: data)
                    if (packet != null) {
                        listener?.onTransportPacketReceived(this@WifiAwareTransport, packet, link.peerID, visited)
                    }
                }
            } catch (_: Exception) {
                // fall through (includes SocketTimeoutException from the
                // read-idle liveness bound)
            } finally {
                tearDownLink(link)
            }
        }
    }

    /** Single writer per link draining [PeerLink.writer] (FIFO per lane, bounded). */
    private fun startWriteLoop(link: PeerLink) {
        val sock = link.socket ?: return
        val out = try { sock.getOutputStream() } catch (_: Exception) {
            handler.post { tearDownLink(link) }; return
        }
        link.writeJob = link.writer.startWriter(scope, out) { e ->
            Log.w(TAG, "write failed peer=${shortId(link.peerID)} err=${e.message}")
            tearDownLink(link)
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

    private fun tearDownLink(link: PeerLink, scheduleReconnect: Boolean = true) {
        // Identity-checked EVICTION, unconditional resource cleanup. Only the
        // currently-registered link gets the registration-scoped effects
        // (map/tag eviction, disconnect event, reconnect) — a stale teardown
        // must not evict a replacement registered under the same peerID
        // (matches WifiBridgeTransport.disconnectConnection). But THIS link's
        // own resources are closed either way: a superseded or never/no-longer
        // registered link would otherwise leak its socket, server socket,
        // writer coroutine, and network callback forever.
        val wasRegistered = peers.remove(link.peerID, link)
        link.watchdog?.cancel()
        link.watchdog = null
        link.readJob?.cancel()
        link.writer.close()
        link.writeJob?.cancel()
        try { link.socket?.close() } catch (_: Exception) {}
        try { link.serverSocket?.close() } catch (_: Exception) {}
        link.networkCallback?.let {
            try { connectivityMgr.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        if (!wasRegistered) return
        tagAwarePeers.remove(link.peerID)
        val delta = if (link.establishStartMs > 0) System.currentTimeMillis() - link.establishStartMs else 0L
        Log.i(TAG, "tearDownLink peer=${shortId(link.peerID)} role=${if (link.isServer) "SERVER" else "CLIENT"} established=${link.established} +${delta}ms")
        // Drop any wake-up message ids still waiting on success/fail callbacks
        // for this peer -- their callbacks would otherwise log a stale peer.
        val iter = pendingMessageIds.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value == link.peerID) iter.remove()
        }
        listener?.onTransportPeerDisconnected(this, link.peerID)
        // A torn-down link never comes back on its own (see class doc): the
        // client role must explicitly re-initiate — the peer's still-visible
        // advertisement won't re-fire onServiceDiscovered, and the server
        // role only reacts to the client's wake-up message.
        if (scheduleReconnect && running && !link.isServer) scheduleClientReconnect(link.peerHandle, link.peerID)
    }

    /**
     * Client-side re-initiation after a teardown (liveness probe, watchdog,
     * read error): fast retries first, then a slow perpetual fallback rather
     * than giving up — with the peer still advertising, no fresh
     * onServiceDiscovered will ever fire (class doc), so a hard stop would
     * recreate the permanent-severance bug this machinery exists to fix.
     * Each attempt uses the freshest known peerHandle ([latestPeerHandle])
     * in case the peer churned handles since the teardown. Skipped when the
     * subscribe session was replaced in the meantime — a restarted session
     * invalidates stored handles, and its own onServiceDiscovered
     * re-initiates instead. A genuine re-discovery or a successful connect
     * resets the pace to fast.
     */
    private fun scheduleClientReconnect(peerHandle: PeerHandle, peerID: PeerID) {
        val sessionAtTeardown = subscribeSession ?: return
        val attempt = (reconnectAttempts[peerID] ?: 0) + 1
        reconnectAttempts[peerID] = attempt
        val delayMs = if (attempt <= MAX_FAST_RECONNECT_ATTEMPTS) {
            CLIENT_RECONNECT_DELAY_MS
        } else {
            if (attempt == MAX_FAST_RECONNECT_ATTEMPTS + 1) {
                Log.w(TAG, "Aware reconnect to ${shortId(peerID)} still failing after $MAX_FAST_RECONNECT_ATTEMPTS attempts; slowing to every ${SLOW_RECONNECT_DELAY_MS / 1000}s")
            }
            SLOW_RECONNECT_DELAY_MS
        }
        scope.launch {
            delay(delayMs)
            if (!running || peers.containsKey(peerID)) return@launch
            if (subscribeSession !== sessionAtTeardown) return@launch
            handler.post {
                if (!running || peers.containsKey(peerID) || subscribeSession !== sessionAtTeardown) return@post
                Log.i(TAG, "Aware re-initiating client link to ${shortId(peerID)} (attempt $attempt)")
                initiateClientLink(latestPeerHandle[peerID] ?: peerHandle, peerID)
            }
        }
    }

    companion object {
        private const val TAG = "WifiAwareTransport"
        const val SERVICE_NAME = "blemesh-router-v1"
        // Frame size bounds and send-queue sizing live in LinkWriter, shared
        // by all three backbone transports so they can't drift apart.
        // Read-idle liveness bound; router RTT pings flow every 5s, so an
        // idle read this long means a dead link (isConnected never flips on
        // silent peer loss).
        private const val READ_IDLE_TIMEOUT_MS = 45_000
        // Client-side re-initiation after a teardown (see scheduleClientReconnect):
        // fast attempts first, then a slow perpetual fallback.
        private const val CLIENT_RECONNECT_DELAY_MS = 5_000L
        private const val MAX_FAST_RECONNECT_ATTEMPTS = 5
        private const val SLOW_RECONNECT_DELAY_MS = 60_000L
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
