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

        const val EXTRA_WIFI_PORT = "wifi_port"
        const val EXTRA_CONNECT_TO = "connect_to" // comma-separated "host:port" list

        @Volatile
        var INSTANCE: MeshRouterService? = null
            private set

        /**
         * Packet types that should be routed over a Wi-Fi bridge (not just
         * local BLE relay). UWB_RANGING is excluded — ranging is meaningful
         * only within BLE radio range.
         */
        private val ROUTABLE_TYPES: Set<Int> = setOf(
            MessageType.ANNOUNCE.value.toInt() and 0xFF,
            MessageType.LEAVE.value.toInt() and 0xFF,
            MessageType.MESSAGE.value.toInt() and 0xFF,
            MessageType.FRAGMENT.value.toInt() and 0xFF,
            MessageType.DELIVERY_ACK.value.toInt() and 0xFF,
            MessageType.DELIVERY_STATUS_REQUEST.value.toInt() and 0xFF,
            MessageType.READ_RECEIPT.value.toInt() and 0xFF,
            MessageType.NOISE_HANDSHAKE.value.toInt() and 0xFF,
            MessageType.NOISE_ENCRYPTED.value.toInt() and 0xFF,
            MessageType.NOISE_IDENTITY_ANNOUNCE.value.toInt() and 0xFF,
            MessageType.LOXATION_ANNOUNCE.value.toInt() and 0xFF,
            MessageType.LOXATION_QUERY.value.toInt() and 0xFF,
            MessageType.LOXATION_CHUNK.value.toInt() and 0xFF,
            MessageType.LOXATION_COMPLETE.value.toInt() and 0xFF,
            MessageType.LOCATION_UPDATE.value.toInt() and 0xFF,
            MessageType.MLS_MESSAGE.value.toInt() and 0xFF,
            MessageType.REQUEST_SYNC.value.toInt() and 0xFF,
        )
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var myPeerID: PeerID

    private lateinit var bleMeshService: BleMeshService
    private lateinit var transports: List<RouterTransport>
    private lateinit var lanDiscovery: LanPeerDiscovery

    // Dedup for packets crossing the BLE↔bridge boundary
    private val bridgeDeduplicator = MessageDeduplicator(
        maxAgeMillis = 60_000L,
        maxEntries = 2000
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

    override fun onCreate() {
        super.onCreate()
        myPeerID = LocalIdentity.load(this)
        Log.i(TAG, "MeshRouterService created, PeerID: ${myPeerID.rawValue}")

        bleMeshService = BleMeshService(this, myPeerID, serviceScope)
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
        val typeInt = packet.type.toInt() and 0xFF
        if (typeInt !in ROUTABLE_TYPES) return

        // Don't bridge local-only REQUEST_SYNC (TTL=0)
        if (packet.type == MessageType.REQUEST_SYNC.value && (packet.ttl.toInt() and 0xFF) == 0) return

        val dedupKey = "ble2br-${packet.senderId}-${packet.timestamp}-${packet.type}"
        if (bridgeDeduplicator.isDuplicate(dedupKey)) return

        if (packet.isBroadcast) {
            for (t in transports) t.broadcast(packet)
            bleToBridgeCount++
            return
        }

        val recipient = packet.recipientPeerID()
        if (recipient != null && bleMeshService.isLocalPeer(recipient)) {
            return  // already deliverable locally
        }

        // Directed: send via the transport that has the recipient if any,
        // otherwise broadcast to all transports.
        val targeted = recipient?.let { r ->
            transports.firstOrNull { r in it.connectedPeerIDs() }?.also { it.sendToPeer(r, packet) }
        }
        if (targeted == null) {
            for (t in transports) t.broadcast(packet)
        }
        bleToBridgeCount++
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

        val dedupKey = "br2ble-${packet.senderId}-${packet.timestamp}-${packet.type}"
        if (bridgeDeduplicator.isDuplicate(dedupKey)) return

        bleMeshService.injectPacketFromWifi(packet)
        bridgeToBleCount++

        // Multi-hop: forward to other router peers (across all transports)
        // excluding the one we just heard it from.
        for (t in transports) {
            for (peerID in t.connectedPeerIDs()) {
                if (t === fromTransport && peerID == fromRouter) continue
                t.sendToPeer(peerID, packet)
            }
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
