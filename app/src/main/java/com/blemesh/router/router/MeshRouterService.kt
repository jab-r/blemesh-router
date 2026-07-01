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

        // Directed-DM retry queue (region-local routing). A DM whose recipient
        // isn't immediately deliverable is held and re-routed as the recipient's
        // announces reveal its current router (mobility), then expired.
        private const val DM_QUEUE_MAX_AGE_MS = 120_000L
        private const val DM_QUEUE_MAX_PER_PEER = 32
        private const val DM_SWEEP_INTERVAL_MS = 15_000L
        // How long a learned peer->home-router route stays valid without a fresh
        // crossing announce (~3 missed 30s announce intervals).
        private const val HOME_ROUTER_TTL_MS = 90_000L

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

    // Region-local routing (REGION_LOCAL_ROUTING_SPEC.md).
    //
    // peerToHomeRouter: which router a peer sits behind, learned from that
    // peer's ANNOUNCE crossing the backbone from that router. Drives targeted
    // bridging (send a directed DM to the one router that owns the recipient
    // instead of fanning out) and self-heals on roam (last crossing announce
    // wins). Aged out on HOME_ROUTER_TTL_MS.
    private data class RouterRoute(val transport: RouterTransport, val routerPeerID: PeerID, val lastSeenMs: Long)
    private val peerToHomeRouter = ConcurrentHashMap<PeerID, RouterRoute>()

    // dmRetryQueue: directed DMs held for a recipient that isn't immediately
    // deliverable (moved / briefly offline). Re-routed when the recipient's
    // announce reveals its current router or it reappears locally; expired after
    // DM_QUEUE_MAX_AGE_MS. Never fanned out, never dropped mid-window.
    private data class QueuedDm(val packet: BlemeshPacket, val enqueuedMs: Long)
    private val dmRetryQueue = ConcurrentHashMap<PeerID, ArrayDeque<QueuedDm>>()
    private var dmSweepJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val identity = LocalIdentity.load(this)
        myPeerID = identity.peerID
        Log.i(TAG, "MeshRouterService created, PeerID: ${myPeerID.rawValue}")

        val beaconId = BeaconIdentity.load(this)
        Log.i(TAG, "Beacon id: $beaconId")
        bleMeshService = BleMeshService(this, identity, beaconId, serviceScope)
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
        startDmSweep()

        Log.i(TAG, "Router started with ${transports.size} transport(s)")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        rttJob?.cancel()
        rttJob = null
        dmSweepJob?.cancel()
        dmSweepJob = null
        outstandingPings.clear()
        rttByTransportPeer.clear()
        peerToHomeRouter.clear()
        dmRetryQueue.clear()
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
            // If a peer we're holding DMs for is now directly reachable over
            // BLE, deliver them (it came back / arrived in our region).
            PeerID.fromLongBE(packet.senderId)?.let { sender ->
                if (dmRetryQueue.containsKey(sender) && bleMeshService.isLocalPeer(sender)) {
                    flushDmQueue(sender)
                }
            }
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

        val dedupKey = "ble2br-${packet.senderId}-${packet.wireTimestamp}-${packet.type}"
        if (bridgeDeduplicator.isDuplicate(dedupKey)) return

        val tag = packetTag(packet)
        trackRetry("ble→br", packet)

        val recipient = packet.recipientPeerID()
        if (recipient == null) {
            // Broadcast. Region-local rule: only announce/presence broadcasts
            // cross the backbone (they carry cross-router discovery); the
            // unencrypted public MESSAGE broadcast and other content stay
            // region-local, carried by BLE mesh only. The same gate applies to
            // ttl=0 gossip-RSR replays (broadcasts of these same types).
            // Crossing spends one TTL unit (floor 0) so a bridged packet never
            // reaches a remote phone at MAX_TTL and looks directly linked.
            val ttlInt = packet.ttl.toInt() and 0xFF
            val bridged = if (ttlInt > 0) packet.withDecrementedTTL() else packet
            if (!crossesBackbone(bridged)) {
                Log.d(TAG, "BLE→bridge BCAST-LOCAL $tag (region-local, not bridged)")
                return
            }
            Log.d(TAG, "BLE→bridge BCAST $tag via ${transports.joinToString(",") { it.name }}")
            for (t in transports) t.broadcast(bridged)
            bleToBridgeCount++
            return
        }

        // Directed 1:1 DM — route it to the recipient (never fanout, never drop).
        routeDirected(recipient, packet, tag)
    }

    /**
     * Route a directed packet to [recipient]. The router's primary job: deliver
     * a 1:1 DM to wherever the recipient is, or hold it and retry until we learn
     * where it went. Order: local direct → local relayable region member →
     * targeted bridge to the recipient's home router → hold for retry.
     */
    private fun routeDirected(recipient: PeerID, packet: BlemeshPacket, tag: String) {
        // 1. Directly connected: the router is the most reliable path (P2P BLE
        // may be weak, and NOISE_ENCRYPTED 0x12 is never relayed by phones).
        // Write to every live leg (iOS holds dual connections; the primary
        // address bounces under churn). Decrement TTL first: a packet arriving
        // at MAX_TTL is treated as proof of a direct connection, so the hop
        // through us must not masquerade as the original sender.
        if (bleMeshService.isLocalPeer(recipient)) {
            val delivered = if ((packet.ttl.toInt() and 0xFF) > 0) packet.withDecrementedTTL() else packet
            val sent = bleMeshService.sendPacketToAllLegs(recipient, delivered)
            Log.d(TAG, "directed LOCAL-DELIVER $tag legs=$sent")
            return
        }

        // A ttl=0 REQUEST_SYNC describes the local segment and is answered by
        // the local router — delivered above if its recipient is directly
        // connected, otherwise it must never cross the bridge or be held.
        if (packet.type == MessageType.REQUEST_SYNC.value && (packet.ttl.toInt() and 0xFF) == 0) return

        // 2. Relayable type to a multi-hop member of our own BLE region: the
        // mesh relay (BleMeshService.processIncomingPacket) already carries it
        // the last hops. Nothing to bridge, nothing to hold. (0x12 is not
        // relayable, so it never stops here.)
        if (BlemeshProtocol.isRelayablePacketType(packet.type) && bleMeshService.isRegionMember(recipient)) {
            Log.d(TAG, "directed REGION-RELAY $tag (delivered by mesh relay)")
            return
        }

        // 3. Recipient sits behind another router (learned from its crossing
        // announce): targeted-bridge to that one router. Also hold a copy — the
        // route may be one announce stale if the recipient just roamed.
        val bridged = if ((packet.ttl.toInt() and 0xFF) > 0) packet.withDecrementedTTL() else packet
        val route = freshRouteFor(recipient)
        if (route != null) {
            route.transport.sendToPeer(route.routerPeerID, bridged)
            bleToBridgeCount++
            enqueueDmForRetry(recipient, packet)
            Log.d(TAG, "directed TARGETED $tag → ${route.transport.name}:${route.routerPeerID.rawValue.take(8)}")
            return
        }

        // 4. No known route yet — hold and retry when the recipient's next
        // announce reveals where it is (local, or behind some router).
        enqueueDmForRetry(recipient, packet)
        Log.d(TAG, "directed QUEUED $tag (awaiting route)")
    }

    // --- Region-local routing helpers ---

    /**
     * Whether a broadcast packet is allowed to cross the backbone. FRAGMENTs are
     * judged by their inner reassembled type so a fragmented announce crosses
     * but a fragmented public message does not.
     */
    private fun crossesBackbone(packet: BlemeshPacket): Boolean {
        if (packet.type == MessageType.FRAGMENT.value) {
            val inner = fragmentInnerType(packet) ?: return false
            return MessageType.crossesBackboneAsBroadcast(inner)
        }
        return MessageType.crossesBackboneAsBroadcast(packet.type)
    }

    /**
     * The originalType byte from a FRAGMENT payload header
     * ([id:8][index:2][total:2][originalType:1][data]). Null if malformed.
     */
    private fun fragmentInnerType(packet: BlemeshPacket): Byte? =
        if (packet.payload.size > 12) packet.payload[12] else null

    /** The recipient's home router if we have a fresh, currently-connected route. */
    private fun freshRouteFor(recipient: PeerID): RouterRoute? {
        val route = peerToHomeRouter[recipient] ?: return null
        if (System.currentTimeMillis() - route.lastSeenMs > HOME_ROUTER_TTL_MS) return null
        if (route.routerPeerID !in route.transport.connectedPeerIDs()) return null
        return route
    }

    /** Hold a directed DM for [recipient] until it becomes routable, or it ages out. */
    private fun enqueueDmForRetry(recipient: PeerID, packet: BlemeshPacket) {
        val now = System.currentTimeMillis()
        val queue = dmRetryQueue.computeIfAbsent(recipient) { ArrayDeque() }
        val size: Int
        synchronized(queue) {
            while (queue.isNotEmpty() && now - queue.first().enqueuedMs > DM_QUEUE_MAX_AGE_MS) {
                queue.removeFirst()
            }
            if (queue.size >= DM_QUEUE_MAX_PER_PEER) queue.removeFirst()
            queue.addLast(QueuedDm(packet, now))
            size = queue.size
        }
        Log.d(TAG, "DM queued for ${recipient.rawValue.take(8)} (q=$size)")
    }

    /**
     * Re-route the DMs held for [recipient] against the current routing state.
     * Called when the recipient reappears locally or its home router changes
     * (roam). If it's now directly reachable the queue is drained and cleared;
     * otherwise each DM is re-sent (best-effort) to its current home router and
     * held for the next update. Aged-out entries are dropped.
     */
    private fun flushDmQueue(recipient: PeerID) {
        val queue = dmRetryQueue[recipient] ?: return
        val snapshot: List<QueuedDm>
        synchronized(queue) { snapshot = queue.toList() }
        if (snapshot.isEmpty()) return
        val now = System.currentTimeMillis()
        val localNow = bleMeshService.isLocalPeer(recipient)
        val route = if (localNow) null else freshRouteFor(recipient)
        var delivered = 0
        for (entry in snapshot) {
            if (now - entry.enqueuedMs > DM_QUEUE_MAX_AGE_MS) continue
            val p = entry.packet
            val hop = if ((p.ttl.toInt() and 0xFF) > 0) p.withDecrementedTTL() else p
            when {
                localNow -> { bleMeshService.sendPacketToAllLegs(recipient, hop); delivered++ }
                route != null -> { route.transport.sendToPeer(route.routerPeerID, hop); bleToBridgeCount++ }
            }
        }
        if (localNow) {
            // Directly reachable now — consider the queue drained.
            dmRetryQueue.remove(recipient)
            Log.i(TAG, "DM flush ${recipient.rawValue.take(8)}: delivered=$delivered locally")
        } else if (route != null) {
            Log.i(TAG, "DM flush ${recipient.rawValue.take(8)}: re-routed ${snapshot.size} → ${route.transport.name}:${route.routerPeerID.rawValue.take(8)}")
        }
    }

    /**
     * Periodically expire aged-out DM-queue entries and stale home routes.
     * Re-routing itself is event-driven (a recipient reappearing locally or a
     * roam changing its home router), so this sweep only reaps.
     */
    private fun startDmSweep() {
        dmSweepJob?.cancel()
        dmSweepJob = serviceScope.launch {
            while (isActive) {
                delay(DM_SWEEP_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val emptyKeys = mutableListOf<PeerID>()
                for ((recipient, queue) in dmRetryQueue) {
                    synchronized(queue) {
                        while (queue.isNotEmpty() && now - queue.first().enqueuedMs > DM_QUEUE_MAX_AGE_MS) {
                            queue.removeFirst()
                        }
                        if (queue.isEmpty()) emptyKeys += recipient
                    }
                }
                for (k in emptyKeys) dmRetryQueue.remove(k)
                peerToHomeRouter.entries.removeAll { now - it.value.lastSeenMs > HOME_ROUTER_TTL_MS }
            }
        }
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

        val dedupKey = "br2ble-${packet.senderId}-${packet.wireTimestamp}-${packet.type}"
        if (bridgeDeduplicator.isDuplicate(dedupKey)) return

        // Packets addressed to this router terminate here (mirror of the
        // BLE-side isForMe check): never injected into BLE, never forwarded.
        if (!packet.isBroadcast && packet.recipientPeerID() == myPeerID) {
            Log.d(TAG, "bridge→BLE consume ${packetTag(packet)} (addressed to this router)")
            return
        }

        // Learn which router a peer sits behind from its ANNOUNCE crossing the
        // backbone — the routing table for targeted directed bridging. On a
        // change (first sighting or a roam to a different router) re-route any
        // DMs we're currently holding for that peer.
        if (packet.type == MessageType.ANNOUNCE.value || packet.type == MessageType.LOXATION_ANNOUNCE.value) {
            PeerID.fromLongBE(packet.senderId)?.let { sender ->
                if (sender != myPeerID) {
                    val prev = peerToHomeRouter.put(
                        sender, RouterRoute(fromTransport, fromRouter, System.currentTimeMillis())
                    )
                    if (prev == null || prev.routerPeerID != fromRouter) flushDmQueue(sender)
                }
            }
        }

        // Region-local rule (defensive inbound mirror): a broadcast that is not
        // an allowed cross-backbone type came from an older peer router still
        // bridging public messages. Drop it — don't inject it into this region
        // or re-forward it onward.
        if (packet.isBroadcast && !crossesBackbone(packet)) {
            Log.d(TAG, "bridge→BLE drop BCAST-LOCAL ${packetTag(packet)} (region-local type)")
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

        // No router-to-router re-forwarding. The LAN transport is a full mesh
        // (mDNS makes every router connect to every other), so each router
        // bridges its own region's announces directly to all peer routers and
        // targets a directed DM straight at the recipient's home router — one
        // hop reaches everyone. Re-forwarding would (a) re-fan-out targeted DMs,
        // undoing the backbone saving, and (b) let a *forwarding* router look
        // like a peer's home router (the peer→home-router map is inferred from
        // whoever bridged the announce), mis-targeting DMs. Keeping delivery to
        // one hop makes the map authoritative: home == the router that heard the
        // peer over BLE. Non-full-mesh / 4+ router topologies need the
        // routers-visited TLV (Tier-3, AUDIT.md §5) — out of scope here.
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
