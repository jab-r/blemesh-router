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
import com.blemesh.router.protocol.BlemeshProtocol
import com.blemesh.router.transport.WifiBridgeTransport
import com.blemesh.router.util.MessageDeduplicator
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Main BLE Mesh Router service.
 *
 * Orchestrates the BLE mesh and WiFi bridge transport layers to route packets:
 *
 * 1. Packets received from BLE mesh destined for non-local peers → forwarded over WiFi
 * 2. Packets received from WiFi bridge → injected into local BLE mesh
 * 3. Broadcast packets → forwarded in both directions
 * 4. Gossip sync → operates on both BLE and WiFi layers
 *
 * Architecture:
 * ```
 * [Remote BLE Mesh] <--BLE--> [This Router] <--WiFi--> [Other Router] <--BLE--> [Remote BLE Mesh]
 * ```
 */
class MeshRouterService : Service() {

    companion object {
        private const val TAG = "MeshRouterService"
        private const val NOTIFICATION_CHANNEL_ID = "blemesh_router"
        private const val NOTIFICATION_ID = 1

        const val EXTRA_WIFI_PORT = "wifi_port"
        const val EXTRA_CONNECT_TO = "connect_to" // comma-separated "host:port" list

        /**
         * Packet types that should be routed over WiFi (not just local relay).
         * UWB_RANGING is intentionally excluded — ranging is meaningful only
         * within BLE radio range.
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
    private val myPeerID = PeerID.generate()

    private lateinit var bleMeshService: BleMeshService
    private lateinit var wifiBridge: WifiBridgeTransport

    // Dedup for packets crossing the BLE↔WiFi boundary
    private val bridgeDeduplicator = MessageDeduplicator(
        maxAgeMillis = 60_000L,
        maxEntries = 2000
    )

    // Track which PeerIDs are known to be on which WiFi router
    private val remotePeerToRouter = ConcurrentHashMap<PeerID, String>()

    // Stats
    private var bleToWifiCount = 0L
    private var wifiToBleCount = 0L

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MeshRouterService created, PeerID: ${myPeerID.rawValue}")

        bleMeshService = BleMeshService(this, myPeerID, serviceScope)
        wifiBridge = WifiBridgeTransport(serviceScope)

        bleMeshService.packetListener = blePacketListener
        wifiBridge.packetListener = wifiPacketListener
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        val wifiPort = intent?.getIntExtra(EXTRA_WIFI_PORT, WifiBridgeTransport.DEFAULT_PORT)
            ?: WifiBridgeTransport.DEFAULT_PORT

        // Start BLE mesh and WiFi bridge
        bleMeshService.start()
        wifiBridge.start()

        // Connect to specified routers
        intent?.getStringExtra(EXTRA_CONNECT_TO)?.let { connectList ->
            for (entry in connectList.split(",")) {
                val parts = entry.trim().split(":")
                if (parts.size == 2) {
                    val host = parts[0]
                    val port = parts[1].toIntOrNull() ?: WifiBridgeTransport.DEFAULT_PORT
                    wifiBridge.connectToRouter(host, port)
                } else if (parts.size == 1 && parts[0].isNotBlank()) {
                    wifiBridge.connectToRouter(parts[0])
                }
            }
        }

        Log.i(TAG, "Router started: BLE mesh active, WiFi bridge on port $wifiPort")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        bleMeshService.stop()
        wifiBridge.stop()
        serviceScope.cancel()
        Log.i(TAG, "Router stopped. Stats: BLE→WiFi=$bleToWifiCount, WiFi→BLE=$wifiToBleCount")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- BLE Mesh → WiFi Bridge ---

    private val blePacketListener = object : BleMeshService.PacketListener {
        override fun onPacketReceived(packet: BlemeshPacket, fromAddress: String) {
            routeBlePacketToWifi(packet)
        }

        override fun onPeerDiscovered(peerID: PeerID, address: String) {
            Log.d(TAG, "BLE peer discovered: ${peerID.rawValue.take(8)} at $address")
        }

        override fun onPeerDisconnected(peerID: PeerID, address: String) {
            Log.d(TAG, "BLE peer disconnected: ${peerID.rawValue.take(8)} at $address")
        }
    }

    /**
     * Route a BLE mesh packet to the WiFi bridge.
     *
     * Decision logic:
     * - Broadcast packets: always forward to all WiFi routers
     * - Directed packets for local peers: don't forward (already delivered locally)
     * - Directed packets for non-local peers: forward to WiFi routers
     * - REQUEST_SYNC with TTL=0: don't forward (local-only by design)
     */
    private fun routeBlePacketToWifi(packet: BlemeshPacket) {
        val typeInt = packet.type.toInt() and 0xFF
        if (typeInt !in ROUTABLE_TYPES) return

        // Don't bridge local-only REQUEST_SYNC (TTL=0)
        if (packet.type == MessageType.REQUEST_SYNC.value && (packet.ttl.toInt() and 0xFF) == 0) return

        // Skip if already bridged
        val dedupKey = "ble2wifi-${packet.senderId}-${packet.timestamp}-${packet.type}"
        if (bridgeDeduplicator.isDuplicate(dedupKey)) return

        if (packet.isBroadcast) {
            // Broadcast: forward to all WiFi routers
            wifiBridge.broadcastToRouters(packet)
            bleToWifiCount++
        } else {
            // Directed: check if recipient is local
            val recipientPeerID = packet.recipientPeerID()
            if (recipientPeerID != null && bleMeshService.isLocalPeer(recipientPeerID)) {
                // Recipient is local, no need to bridge
                return
            }

            // Recipient is not local — route over WiFi
            val targetRouter = recipientPeerID?.let { remotePeerToRouter[it] }
            if (targetRouter != null) {
                // We know which router has this peer
                wifiBridge.sendToRouter(targetRouter, packet)
            } else {
                // Don't know where the peer is — broadcast to all routers
                wifiBridge.broadcastToRouters(packet)
            }
            bleToWifiCount++
        }
    }

    // --- WiFi Bridge → BLE Mesh ---

    private val wifiPacketListener = object : WifiBridgeTransport.PacketListener {
        override fun onWifiPacketReceived(packet: BlemeshPacket, fromRouter: String) {
            routeWifiPacketToBle(packet, fromRouter)
        }

        override fun onRouterConnected(address: String) {
            Log.i(TAG, "WiFi router connected: $address")
        }

        override fun onRouterDisconnected(address: String) {
            Log.i(TAG, "WiFi router disconnected: $address")
            // Clean up peer mappings for this router
            val peersToRemove = remotePeerToRouter.entries
                .filter { it.value == address }
                .map { it.key }
            peersToRemove.forEach { remotePeerToRouter.remove(it) }
        }
    }

    /**
     * Route a WiFi-received packet into the local BLE mesh.
     *
     * Decision logic:
     * - Learn sender's router affiliation (for directed routing later)
     * - Skip if already seen (dedup)
     * - Inject into local BLE mesh for delivery to local peers
     * - Also forward to other WiFi routers (multi-hop bridging)
     */
    private fun routeWifiPacketToBle(packet: BlemeshPacket, fromRouter: String) {
        // Skip if already bridged
        val dedupKey = "wifi2ble-${packet.senderId}-${packet.timestamp}-${packet.type}"
        if (bridgeDeduplicator.isDuplicate(dedupKey)) return

        // Learn: remember that this sender's PeerID is reachable via fromRouter
        val senderPeerID = packet.senderPeerID()
        if (senderPeerID != null) {
            remotePeerToRouter[senderPeerID] = fromRouter
        }

        // Handle ANNOUNCE: track remote peer's router
        if (packet.type == MessageType.ANNOUNCE.value) {
            val announcement = com.blemesh.router.model.AnnouncementData.decode(packet.payload)
            if (announcement != null && senderPeerID != null) {
                Log.d(TAG, "Remote peer ${senderPeerID.rawValue.take(8)} (${announcement.nickname}) via router $fromRouter")
            }
        }

        // Inject into local BLE mesh
        bleMeshService.injectPacketFromWifi(packet)
        wifiToBleCount++

        // Multi-hop: forward to other WiFi routers (excluding the source)
        for (routerAddress in wifiBridge.getConnectedRouters()) {
            if (routerAddress != fromRouter) {
                wifiBridge.sendToRouter(routerAddress, packet)
            }
        }
    }

    // --- Notification ---

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "BLE Mesh Router",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "BLE mesh routing service"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("BLE Mesh Router")
            .setContentText("Routing BLE mesh traffic over WiFi")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }
}
