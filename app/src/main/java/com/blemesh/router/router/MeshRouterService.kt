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
import com.blemesh.router.transport.RouterTransport
import com.blemesh.router.transport.WifiBridgeTransport
import com.blemesh.router.util.MessageDeduplicator
import kotlinx.coroutines.*

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
    private val transports = mutableListOf<RouterTransport>()
    private lateinit var tcpBridge: WifiBridgeTransport

    // Dedup for packets crossing the BLE↔bridge boundary
    private val bridgeDeduplicator = MessageDeduplicator(
        maxAgeMillis = 60_000L,
        maxEntries = 2000
    )

    @Volatile var bleToBridgeCount = 0L
        private set
    @Volatile var bridgeToBleCount = 0L
        private set

    override fun onCreate() {
        super.onCreate()
        myPeerID = LocalIdentity.load(this)
        Log.i(TAG, "MeshRouterService created, PeerID: ${myPeerID.rawValue}")

        bleMeshService = BleMeshService(this, myPeerID, serviceScope)
        bleMeshService.packetListener = blePacketListener

        tcpBridge = WifiBridgeTransport(serviceScope, myPeerID)
        tcpBridge.listener = transportListener
        transports.add(tcpBridge)

        INSTANCE = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        bleMeshService.start()
        for (t in transports) {
            if (t.isAvailable()) t.start()
            else Log.i(TAG, "Skipping unavailable transport: ${t.name}")
        }

        intent?.getStringExtra(EXTRA_CONNECT_TO)?.let { connectList ->
            for (entry in connectList.split(",")) {
                val parts = entry.trim().split(":")
                when (parts.size) {
                    2 -> {
                        val port = parts[1].toIntOrNull() ?: WifiBridgeTransport.DEFAULT_PORT
                        tcpBridge.connectToHost(parts[0], port)
                    }
                    1 -> if (parts[0].isNotBlank()) tcpBridge.connectToHost(parts[0])
                }
            }
        }

        Log.i(TAG, "Router started with ${transports.size} transport(s)")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
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
        val blePeers: List<PeerID>,
        val transports: List<TransportSnapshot>,
        val bleToBridge: Long,
        val bridgeToBle: Long
    )

    data class TransportSnapshot(
        val name: String,
        val available: Boolean,
        val peers: List<PeerID>
    )

    fun snapshot(): Snapshot = Snapshot(
        peerID = myPeerID,
        blePeers = bleMeshService.getConnectedPeerIDs(),
        transports = transports.map {
            TransportSnapshot(
                name = it.name,
                available = it.isAvailable(),
                peers = it.connectedPeerIDs()
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
