package com.blemesh.router.router

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.blemesh.router.mesh.BleMeshService
import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.MessageType
import com.blemesh.router.model.PeerID
import com.blemesh.router.transport.LanPeerDiscovery
import com.blemesh.router.transport.RouterTransport
import com.blemesh.router.transport.WifiBridgeTransport
import com.blemesh.router.util.MessageDeduplicator
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Main BLE Mesh Router service.
 *
 * Orchestrates the BLE mesh and one-or-more router-to-router transports
 * (TCP-over-LAN, Wi-Fi Aware, Wi-Fi Direct):
 *
 * 1. Packets received from BLE mesh destined for non-local peers → forwarded
 *    over the router-to-router transport(s).
 * 2. Packets received over a router transport → injected into local BLE mesh.
 * 3. Broadcast packets → forwarded in both directions.
 * 4. Gossip sync → operates on both BLE and bridge layers.
 *
 * Architecture:
 * ```
 * [BLE peers] <--BLE--> [This Router] <--Wi-Fi--> [Other Router] <--BLE--> [BLE peers]
 * ```
 */
class MeshRouterService : Service() {

    companion object {
        private const val TAG = "MeshRouterService"
        private const val NOTIFICATION_CHANNEL_ID = "blemesh_router"
        private const val NOTIFICATION_ID = 1
        private const val RTT_PING_INTERVAL_MS = 5_000L
        private const val RTT_PING_PAYLOAD_BYTES = 8
        // Sliding window for the retry-storm counter. A (sender,recipient,type)
        // bucket resets if no new origination is seen for this long.
        private const val RETRY_WINDOW_MS = 30_000L

        const val EXTRA_WIFI_PORT = "wifi_port"
        const val EXTRA_CONNECT_TO = "connect_to" // comma-separated "host:port" list

        @Volatile
        var INSTANCE: MeshRouterService? = null
            private set

    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var myPeerID: PeerID

    private lateinit var bleMeshService: BleMeshService
    private lateinit var transports: List<RouterTransport>
    private lateinit var lanDiscovery: LanPeerDiscovery

    // Dedup for packets crossing the BLE↔bridge boundary. Window matches the
    // references' 5-minute dedup so a late replay can't loop between routers.
    private val bridgeDeduplicator = MessageDeduplicator(
        maxAgeMillis = 5 * 60_000L,
        maxEntries = 5000
    )

    @Volatile var bleToBridgeCount = 0L
        private set
    @Volatile var bridgeToBleCount = 0L
        private set

    // RTT tracking: nonce -> send time (ns). One global map; nonces are unique.
    private val outstandingPings = ConcurrentHashMap<Long, Long>()
    private val pingNonceSeq = AtomicLong(System.nanoTime())
    // (transport name, peerID) -> last measured RTT in ms.
    private val rttByTransportPeer = ConcurrentHashMap<Pair<String, PeerID>, Long>()
    private var rttJob: Job? = null

    // Counts distinct originations of (sender, recipient, type) within a sliding
    // window. Each NOISE_HANDSHAKE retransmission carries a fresh timestamp so it
    // bypasses dedup, but it lands on the same (sender, recipient, type) bucket.
    // A high count here means the handshake isn't completing — the originator's
    // Noise stack keeps re-driving the same state because it never sees the next
    // message back from the peer.
    private data class RetryCounter(var count: Int, var firstSeenMs: Long, var lastSeenMs: Long)
    private val packetRetryCounts = ConcurrentHashMap<String, RetryCounter>()

    override fun onCreate() {
        super.onCreate()
        val identity = LocalIdentity.load(this)
        myPeerID = identity.peerID
        Log.i(TAG, "MeshRouterService created, PeerID: ${myPeerID.rawValue}")

        bleMeshService = BleMeshService(this, identity, serviceScope)
        bleMeshService.packetListener = blePacketListener

        transports = TransportSelector.build(this, myPeerID, serviceScope)
        for (t in transports) t.listener = transportListener

        lanDiscovery = LanPeerDiscovery(this)

        INSTANCE = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        bleMeshService.start()
        for (t in transports) {
            if (t.isAvailable()) t.start()
            else Log.i(TAG, "Skipping unavailable transport: ${t.name}")
        }

        // Apply saved Wi-Fi credentials so the device auto-associates to the
        // backbone SSID. No-op if not configured or on API < 29.
        val credentials = WifiCredentials.load(this)
        if (credentials.isConfigured) {
            WifiNetworkApplicator(this).apply(credentials)
        }

        // Auto-discover other routers on the same LAN via mDNS, then hand
        // host:port pairs to the TCP transport for connect-and-handshake.
        val tcp = transports.filterIsInstance<WifiBridgeTransport>().firstOrNull()
        if (tcp != null) {
            lanDiscovery.start(myPeerID, WifiBridgeTransport.DEFAULT_PORT) { host, port, _ ->
                tcp.connectToHost(host, port)
            }
        }

        // Manual host overrides (still supported for dev / fallback).
        intent?.getStringExtra(EXTRA_CONNECT_TO)?.let { connectList ->
            if (tcp != null) {
                for (entry in connectList.split(",")) {
                    val parts = entry.trim().split(":")
                    when (parts.size) {
                        2 -> {
                            val port = parts[1].toIntOrNull() ?: WifiBridgeTransport.DEFAULT_PORT
                            tcp.connectToHost(parts[0], port)
                        }
                        1 -> if (parts[0].isNotBlank()) tcp.connectToHost(parts[0])
                    }
                }
            }
        }

        startRttProbing()

        Log.i(TAG, "Router started with ${transports.size} transport(s)")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        rttJob?.cancel()
        rttJob = null
        outstandingPings.clear()
        rttByTransportPeer.clear()
        lanDiscovery.stop()
        bleMeshService.stop()
        for (t in transports) t.stop()
        serviceScope.cancel()
        INSTANCE = null
        Log.i(TAG, "Router stopped. Stats: BLE→bridge=$bleToBridgeCount, bridge→BLE=$bridgeToBleCount")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Snapshot for UI ---

    data class Snapshot(
        val peerID: PeerID,
        val configuredSsid: String,
        val blePeers: List<BlePeerInfo>,
        val transports: List<TransportSnapshot>,
        val bleToBridge: Long,
        val bridgeToBle: Long
    )

    data class TransportSnapshot(
        val name: String,
        val available: Boolean,
        val peers: List<RouterPeerInfo>
    )

    data class BlePeerInfo(val peerID: PeerID, val rssi: Int?)
    data class RouterPeerInfo(val peerID: PeerID, val rttMs: Long?)

    fun snapshot(): Snapshot = Snapshot(
        peerID = myPeerID,
        configuredSsid = WifiCredentials.load(this).ssid,
        blePeers = bleMeshService.getConnectedPeerIDs().map {
            BlePeerInfo(it, bleMeshService.getPeerRssi(it))
        },
        transports = transports.map { t ->
            TransportSnapshot(
                name = t.name,
                available = t.isAvailable(),
                peers = t.connectedPeerIDs().map { p ->
                    RouterPeerInfo(p, rttByTransportPeer[t.name to p])
                }
            )
        },
        bleToBridge = bleToBridgeCount,
        bridgeToBle = bridgeToBleCount
    )

    // --- BLE Mesh → bridge ---

    private val blePacketListener = object : BleMeshService.PacketListener {
        override fun onPacketReceived(packet: BlemeshPacket, fromAddress: String) {
            routeBlePacketToBridge(packet)
        }

        override fun onPeerDiscovered(peerID: PeerID, address: String) {
            Log.d(TAG, "BLE peer discovered: ${peerID.rawValue.take(8)} at $address")
        }

        override fun onPeerDisconnected(peerID: PeerID, address: String) {
            Log.d(TAG, "BLE peer disconnected: ${peerID.rawValue.take(8)} at $address")
        }
    }

    private fun routeBlePacketToBridge(packet: BlemeshPacket) {
        if (!MessageType.isBridgeable(packet.type)) return

        val dedupKey = "ble2br-${packet.senderId}-${packet.timestamp}-${packet.type}"
        if (bridgeDeduplicator.isDuplicate(dedupKey)) return

        val tag = packetTag(packet)
        trackRetry("ble→br", packet)

        val recipient = packet.recipientPeerID()
        if (recipient != null && bleMeshService.isLocalPeer(recipient)) {
            // Both endpoints are local. The router is the most reliable path
            // between them: peer-to-peer BLE may be weak (the very reason we
            // exist), and NOISE_ENCRYPTED bypasses BLE-mesh relay entirely
            // (BlemeshProtocol.shouldRelay returns false for type 0x12), so
            // without an explicit targeted write the recipient never sees it.
            //
            // Write to every live BLE leg of the recipient: iOS routinely
            // holds two simultaneous connections (server-side accept +
            // client-side dial) and the "primary" address bounces sub-second
            // under reconnect churn. Hitting all legs makes us robust to that
            // race; the recipient dedups duplicates.
            //
            // Decrement TTL before delivering: reference peers treat a packet
            // arriving at MAX_TTL as proof of a direct connection and bind the
            // sending BLE address (ours) to the packet's sender PeerID. The
            // hop through us must not masquerade as the original sender.
            val delivered = if ((packet.ttl.toInt() and 0xFF) > 0) packet.withDecrementedTTL() else packet
            val sent = bleMeshService.sendPacketToAllLegs(recipient, delivered)
            Log.d(TAG, "BLE→bridge LOCAL-DELIVER $tag legs=$sent")
            return
        }

        // Crossing the bridge is a hop: spend one TTL unit (floor 0) so the
        // packet never arrives at a remote phone at MAX_TTL and masquerades
        // as a direct connection (see above). Low-TTL packets still cross:
        // gossip replays are emitted at ttl=0 ("local-only" in the
        // references) but the bridge is the only cross-segment backfill
        // path — routers don't gossip-sync each other over Wi-Fi. They
        // arrive at the far segment at ttl=0: delivered to that router's
        // directly connected phones, never re-relayed. REQUEST_SYNC keeps
        // the old rule — a ttl=0 sync request describes the local segment
        // and is answered by the local router.
        val ttlInt = packet.ttl.toInt() and 0xFF
        if (packet.type == MessageType.REQUEST_SYNC.value && ttlInt == 0) return
        val bridged = if (ttlInt > 0) packet.withDecrementedTTL() else packet

        if (bridged.isBroadcast) {
            Log.d(TAG, "BLE→bridge BCAST $tag via ${transports.joinToString(",") { it.name }}")
            for (t in transports) t.broadcast(bridged)
            bleToBridgeCount++
            return
        }

        // Directed: send via the transport that has the recipient if any,
        // otherwise broadcast to all transports.
        val targeted = recipient?.let { r ->
            transports.firstOrNull { r in it.connectedPeerIDs() }?.also { it.sendToPeer(r, bridged) }
        }
        if (targeted != null) {
            Log.d(TAG, "BLE→bridge DIRECT $tag via ${targeted.name}")
        } else {
            Log.d(TAG, "BLE→bridge FANOUT $tag via ${transports.joinToString(",") { it.name }}")
            for (t in transports) t.broadcast(bridged)
        }
        bleToBridgeCount++
    }

    private fun packetTag(packet: BlemeshPacket): String {
        val type = "0x%02x".format(packet.type.toInt() and 0xFF)
        val sender = PeerID.fromLongBE(packet.senderId)?.rawValue?.take(8) ?: "?"
        val recipient = if (packet.isBroadcast) "bcast"
            else packet.recipientPeerID()?.rawValue?.take(8) ?: "?"
        val ttl = packet.ttl.toInt() and 0xFF
        return "type=$type ttl=$ttl ${sender}→${recipient}"
    }

    /**
     * Count distinct originations per (sender, recipient, type) bucket. Only
     * counts MAX_TTL packets — i.e. freshly originated, not relayed copies —
     * since those are the ones that reflect "the originator's stack hasn't
     * given up yet". Logs a warning at count thresholds so handshake-retry
     * storms surface in router.log.
     */
    private fun trackRetry(direction: String, packet: BlemeshPacket) {
        if ((packet.ttl.toInt() and 0xFF) != BlemeshPacket.MAX_TTL) return
        if (!MessageType.isRetryTracked(packet.type)) return
        val sender = PeerID.fromLongBE(packet.senderId)?.rawValue?.take(8) ?: "?"
        val recipient = if (packet.isBroadcast) "bcast"
            else packet.recipientPeerID()?.rawValue?.take(8) ?: "?"
        val type = "0x%02x".format(packet.type.toInt() and 0xFF)
        val key = "$sender→$recipient:$type"
        val now = System.currentTimeMillis()
        val rc = packetRetryCounts.compute(key) { _, existing ->
            if (existing == null || now - existing.lastSeenMs > RETRY_WINDOW_MS) {
                RetryCounter(1, now, now)
            } else {
                existing.count++
                existing.lastSeenMs = now
                existing
            }
        }!!
        if (rc.count == 3 || rc.count == 6 || (rc.count > 6 && rc.count % 5 == 0)) {
            Log.w(TAG, "RETRY-STORM $direction $key x${rc.count} over ${now - rc.firstSeenMs}ms")
        }
    }

    // --- Bridge → BLE Mesh ---

    private val transportListener = object : RouterTransport.Listener {
        override fun onTransportPeerConnected(transport: RouterTransport, peer: PeerID) {
            Log.i(TAG, "Router peer connected via ${transport.name}: ${peer.rawValue.take(8)}")
        }

        override fun onTransportPeerDisconnected(transport: RouterTransport, peer: PeerID) {
            Log.i(TAG, "Router peer disconnected from ${transport.name}: ${peer.rawValue.take(8)}")
        }

        override fun onTransportPacketReceived(
            transport: RouterTransport,
            packet: BlemeshPacket,
            fromPeer: PeerID
        ) {
            routeBridgePacketToBle(transport, packet, fromPeer)
        }
    }

    private fun routeBridgePacketToBle(
        fromTransport: RouterTransport,
        packet: BlemeshPacket,
        fromRouter: PeerID
    ) {
        // Router-internal control traffic: never inject into BLE, never multi-hop.
        when (packet.type) {
            MessageType.ROUTER_PING.value -> {
                handleRouterPing(fromTransport, packet, fromRouter)
                return
            }
            MessageType.ROUTER_PONG.value -> {
                handleRouterPong(fromTransport, packet, fromRouter)
                return
            }
        }

        // Mirror the BLE→bridge direction: link-local types arriving from an
        // older or non-compliant peer router must not leak into this segment
        // or be multi-hopped onward.
        if (!MessageType.isBridgeable(packet.type)) return

        val dedupKey = "br2ble-${packet.senderId}-${packet.timestamp}-${packet.type}"
        if (bridgeDeduplicator.isDuplicate(dedupKey)) return

        // Packets addressed to this router terminate here (mirror of the
        // BLE-side isForMe check): never injected into BLE, never forwarded.
        if (!packet.isBroadcast && packet.recipientPeerID() == myPeerID) {
            Log.d(TAG, "bridge→BLE consume ${packetTag(packet)} (addressed to this router)")
            return
        }

        // A compliant sender spends a TTL unit for the crossing, so arrival
        // at MAX_TTL means a pre-fix or non-compliant build. Clamp before
        // injection, or local reference phones bind the original sender's
        // PeerID to our BLE address — the masquerade bug the BLE→bridge
        // direction already guards against.
        val inbound = if ((packet.ttl.toInt() and 0xFF) >= BlemeshPacket.MAX_TTL) {
            packet.withTTL((BlemeshPacket.MAX_TTL - 1).toByte())
        } else packet

        val tag = packetTag(inbound)
        trackRetry("br→ble", inbound)
        Log.d(TAG, "bridge→BLE ${fromTransport.name}<-${fromRouter.rawValue.take(8)} $tag")
        bleMeshService.injectPacketFromWifi(inbound)
        bridgeToBleCount++

        // Multi-hop: forward to other router peers (across all transports)
        // excluding the one we just heard it from. Each router-to-router hop
        // spends TTL like any other hop, so bridge loops are bounded by TTL
        // as well as by the dedup window.
        if ((inbound.ttl.toInt() and 0xFF) <= 1) return
        val forwarded = inbound.withDecrementedTTL()
        val fwdTargets = mutableListOf<String>()
        for (t in transports) {
            for (peerID in t.connectedPeerIDs()) {
                if (t === fromTransport && peerID == fromRouter) continue
                t.sendToPeer(peerID, forwarded)
                fwdTargets += "${t.name}:${peerID.rawValue.take(8)}"
            }
        }
        if (fwdTargets.isNotEmpty()) {
            Log.d(TAG, "bridge→bridge multi-hop $tag → ${fwdTargets.joinToString(",")}")
        }
    }

    // --- Router-to-router RTT (ROUTER_PING / ROUTER_PONG) ---

    private fun startRttProbing() {
        rttJob?.cancel()
        rttJob = serviceScope.launch {
            while (isActive) {
                delay(RTT_PING_INTERVAL_MS)
                for (t in transports) {
                    for (peerID in t.connectedPeerIDs()) {
                        sendRouterPing(t, peerID)
                    }
                }
                // Garbage-collect pings older than 10x the interval so the map
                // doesn't grow when peers vanish without replying.
                val cutoffNs = System.nanoTime() - RTT_PING_INTERVAL_MS * 1_000_000L * 10
                outstandingPings.entries.removeAll { it.value < cutoffNs }
            }
        }
    }

    private fun sendRouterPing(transport: RouterTransport, peer: PeerID) {
        val nonce = pingNonceSeq.incrementAndGet()
        val payload = ByteBuffer.allocate(RTT_PING_PAYLOAD_BYTES).putLong(nonce).array()
        val packet = BlemeshPacket(
            version = BlemeshPacket.PROTOCOL_VERSION,
            type = MessageType.ROUTER_PING.value,
            ttl = 1.toByte(),
            timestamp = System.currentTimeMillis(),
            flags = BlemeshPacket.FLAG_HAS_RECIPIENT,
            senderId = myPeerID.toLongBE(),
            recipientId = peer.toLongBE(),
            payload = payload,
            signature = null
        )
        outstandingPings[nonce] = System.nanoTime()
        transport.sendToPeer(peer, packet)
    }

    private fun handleRouterPing(
        fromTransport: RouterTransport,
        packet: BlemeshPacket,
        fromRouter: PeerID
    ) {
        // Echo the payload back as a PONG over the same transport.
        val reply = BlemeshPacket(
            version = BlemeshPacket.PROTOCOL_VERSION,
            type = MessageType.ROUTER_PONG.value,
            ttl = 1.toByte(),
            timestamp = System.currentTimeMillis(),
            flags = BlemeshPacket.FLAG_HAS_RECIPIENT,
            senderId = myPeerID.toLongBE(),
            recipientId = fromRouter.toLongBE(),
            payload = packet.payload,
            signature = null
        )
        fromTransport.sendToPeer(fromRouter, reply)
    }

    private fun handleRouterPong(
        fromTransport: RouterTransport,
        packet: BlemeshPacket,
        fromRouter: PeerID
    ) {
        if (packet.payload.size < RTT_PING_PAYLOAD_BYTES) return
        val nonce = ByteBuffer.wrap(packet.payload, 0, RTT_PING_PAYLOAD_BYTES).long
        val sentNs = outstandingPings.remove(nonce) ?: return
        val rttMs = (System.nanoTime() - sentNs) / 1_000_000L
        rttByTransportPeer[fromTransport.name to fromRouter] = rttMs
    }

    // --- Notification ---

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "BLE Mesh Router",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "BLE mesh routing service" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("BLE Mesh Router")
            .setContentText("Routing BLE mesh traffic over Wi-Fi")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }
}
