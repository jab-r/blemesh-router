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
import com.blemesh.router.protocol.BinaryProtocol
import com.blemesh.router.protocol.BlemeshProtocol
import com.blemesh.router.sync.RequestSyncPacket
import com.blemesh.router.sync.SyncTypeFlags
import com.blemesh.router.transport.BackboneFrame
import com.blemesh.router.transport.LanPeerDiscovery
import com.blemesh.router.transport.LinkWriter
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
        // Liveness: a ping unanswered for this long counts as missed; after
        // MAX_MISSED_PINGS consecutive misses the peer's connection is torn
        // down. Without this a router that drops off WiFi without a TCP FIN
        // stays in the routing table and black-holes directed DMs and
        // re-broadcasts until a write finally fails. Backstopped by the
        // transports' own read-idle socket timeout.
        private const val RTT_PING_TIMEOUT_MS = 10_000L
        private const val MAX_MISSED_PINGS = 3
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

        // --- Backbone path routing (BACKBONE_PATH_ROUTING_SPEC.md, Tier 3) ---
        //
        // Master flag for the visited-router path tag on the WiFi backbone.
        // When true this router: advertises path-tag support to peer routers
        // (ROUTER_CAPS) and sends tagged frames to those that also support it;
        // stamps an origin visited-path on packets it bridges; drops any bridged
        // packet whose path already contains itself (drop-on-self — the primary
        // backbone loop guard, replacing dedup-only); learns a sender's home
        // router from the path origin; re-forwards crossing broadcasts across
        // the backbone (loop-free by construction) so they reach multi-hop /
        // non-full-mesh topologies; and stops decrementing TTL on the WiFi leg
        // (§4) so a packet can traverse many routers to a far region.
        //
        // When false the router behaves exactly as before: plain frames only,
        // dedup as the sole backbone loop guard (safe at <=3 routers), and one
        // TTL unit spent per crossing. Mixed fleets interoperate — a tag-aware
        // router falls back to plain frames for any peer that hasn't advertised
        // support, and dedup still bounds loops on those hops.
        private const val BACKBONE_PATH_TAG = true

        // ROUTER_CAPS payload: [capsVersion:1][capabilityBits:1].
        private const val CAPS_VERSION: Byte = 0x01
        private const val CAPS_BIT_PATH_TAG = 0x01

        // --- Backbone GCS anti-entropy (router-to-router gossip) ---
        //
        // The crossable-content push over the WiFi backbone is a one-shot: a
        // dropped push (loss, router restart, a router that connects after the
        // push) is never recovered until the origin's next periodic emission,
        // because BLE gossip (REQUEST_SYNC) is region-local and a ttl=0 RSR is
        // dropped at the bridge. This adds bidirectional GCS anti-entropy over
        // the backbone so crossable content reconciles: each router periodically
        // (and on connect) advertises a GCS filter of what it holds; the peer
        // replies with the packets it's missing, which re-seed the store only
        // (the local BLE crowd then pulls them via existing local gossip). It
        // composes with the push (push = latency, gossip = reliable backfill);
        // the mesh dedup prevents double-processing. Mirrors BACKBONE_PATH_TAG.
        private const val BACKBONE_GOSSIP = true
        private const val BACKBONE_GOSSIP_INTERVAL_MS = 20_000L
        // Cap on ROUTER_SYNC_DATA frames per reply, half the transports' bulk
        // send-queue capacity: a full-window reply (~355 frames at the
        // 400-byte GCS filter) enqueued in one loop would overflow the queue
        // and silently evict earlier frames (queued DMs included). The
        // remainder reconciles on subsequent rounds — the peer's next filter
        // excludes whatever it already received.
        private const val MAX_SYNC_DATA_FRAMES_PER_ROUND = LinkWriter.BULK_QUEUE_CAPACITY / 2
        // Max region members advertised in one ROUTER_HOME frame. A BLE region is
        // small (tens of peers); this is a defensive bound on frame size. On
        // overflow we log and drop the tail (routes for those peers fall back to
        // the announce push path).
        private const val ROUTER_HOME_MAX_ENTRIES = 512

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

    // Bridge-crossing counters. Mutated from several transport read threads
    // concurrently, so they are AtomicLong (a plain @Volatile Long loses
    // interleaved read-modify-write increments). Diagnostic only.
    private val bleToBridgeCounter = AtomicLong(0)
    private val bridgeToBleCounter = AtomicLong(0)
    val bleToBridgeCount: Long get() = bleToBridgeCounter.get()
    val bridgeToBleCount: Long get() = bridgeToBleCounter.get()

    // RTT tracking: nonce -> outstanding ping. One global map; nonces are unique.
    private data class PingRecord(val sentNs: Long, val transportName: String, val peer: PeerID)
    private val outstandingPings = ConcurrentHashMap<Long, PingRecord>()
    private val pingNonceSeq = AtomicLong(System.nanoTime())
    // (transport name, peerID) -> last measured RTT in ms.
    private val rttByTransportPeer = ConcurrentHashMap<Pair<String, PeerID>, Long>()
    // (transport name, peerID) -> consecutive unanswered pings (liveness probe).
    private val missedPings = ConcurrentHashMap<Pair<String, PeerID>, Int>()
    private var rttJob: Job? = null

    // Backbone gossip: the crossable broadcast content reconciled over the WiFi
    // backbone (presence + location). Mirrors CROSSES_BACKBONE minus LEAVE (no
    // gossip store) and FRAGMENT (cross-backbone reassembly out of scope).
    private val CROSSABLE_SYNC_TYPES = SyncTypeFlags.fromTypes(
        MessageType.ANNOUNCE, MessageType.LOXATION_ANNOUNCE, MessageType.LOCATION_UPDATE
    )
    private var backboneGossipJob: Job? = null

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
        if (BACKBONE_GOSSIP) startBackboneGossip()

        Log.i(TAG, "Router started with ${transports.size} transport(s)")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        rttJob?.cancel()
        rttJob = null
        dmSweepJob?.cancel()
        dmSweepJob = null
        backboneGossipJob?.cancel()
        backboneGossipJob = null
        outstandingPings.clear()
        rttByTransportPeer.clear()
        missedPings.clear()
        packetRetryCounts.clear()
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

    /**
     * Re-emits the BLE advertisement with the currently persisted beacon/stage
     * config. Called by ConfigActivity (via [INSTANCE]) after a stage-id save
     * so the new layout goes on air without a service restart. Returns whether
     * the advertisement was actually re-emitted (false if the mesh wasn't
     * running) so the UI doesn't falsely confirm a live change.
     */
    fun restartBeaconAdvertising(): Boolean = bleMeshService.restartAdvertising()

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
            //
            // TTL: with the visited path tag as the backbone loop guard, WiFi
            // hops no longer decrement TTL (spec §4) so an announce can reach a
            // far region without starving; the inbound clamp on injection still
            // prevents direct-connection masquerade. Without the tag we keep the
            // legacy "spend one TTL unit per crossing" (floor 0) behavior.
            val bridged = if (BACKBONE_PATH_TAG) packet else spendCrossingTtl(packet)
            if (!crossesBackbone(bridged)) {
                Log.d(TAG, "BLE→bridge BCAST-LOCAL $tag (region-local, not bridged)")
                return
            }
            Log.d(TAG, "BLE→bridge BCAST $tag via ${transports.joinToString(",") { it.name }}")
            broadcastToBackbone(bridged, originVisited())
            bleToBridgeCounter.incrementAndGet()
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
        // may be weak, and 0x12 relay isn't guaranteed on every hop — some
        // phones/builds still drop it, so a direct write is the surest path).
        // Write to every live leg (iOS holds dual connections; the primary
        // address bounces under churn). Decrement TTL first: a packet arriving
        // at MAX_TTL is treated as proof of a direct connection, so the hop
        // through us must not masquerade as the original sender.
        if (bleMeshService.isLocalPeer(recipient)) {
            // Local delivery always spends a TTL unit regardless of the backbone
            // decoupling: a packet handed to a phone at MAX_TTL is treated as
            // proof of a direct connection, so the hop through us must not
            // masquerade as the original sender.
            val delivered = spendCrossingTtl(packet)
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
        // the last hops. Nothing to bridge, nothing to hold. 0x12 is now
        // relayable too (iOS parity), so a private DM to a region member is
        // carried by the mesh relay like any other type.
        if (BlemeshProtocol.isRelayablePacketType(packet.type) && bleMeshService.isRegionMember(recipient)) {
            Log.d(TAG, "directed REGION-RELAY $tag (delivered by mesh relay)")
            return
        }

        // 3. Recipient sits behind another router (learned from its crossing
        // announce): targeted-bridge to that one router. Also hold a copy — the
        // route may be one announce stale if the recipient just roamed. TTL:
        // decoupled on the backbone when the tag is on (spec §4); the inbound
        // clamp guards masquerade on injection at the far router.
        val bridged = if (BACKBONE_PATH_TAG) packet else spendCrossingTtl(packet)
        val route = freshRouteFor(recipient)
        if (route != null) {
            route.transport.sendToPeer(route.routerPeerID, bridged, originVisited())
            bleToBridgeCounter.incrementAndGet()
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

    // --- Backbone path routing helpers (BACKBONE_PATH_ROUTING_SPEC.md) ---

    /** Spend one TTL unit (floor 0). Used for the anti-masquerade hop cost. */
    private fun spendCrossingTtl(packet: BlemeshPacket): BlemeshPacket =
        if ((packet.ttl.toInt() and 0xFF) > 0) packet.withDecrementedTTL() else packet

    /**
     * The visited path this router stamps on a packet it originates onto the
     * backbone: `[myPeerID]` when the tag is enabled, empty otherwise (plain
     * frames, legacy behavior). The origin entry lets receivers learn the
     * sender's home router (spec §3).
     */
    private fun originVisited(): List<PeerID> =
        if (BACKBONE_PATH_TAG) listOf(myPeerID) else emptyList()

    /** Fan a packet out to every backbone transport with the given visited path. */
    private fun broadcastToBackbone(
        packet: BlemeshPacket,
        visited: List<PeerID>,
        taggedPeersOnly: Boolean = false
    ) {
        for (t in transports) t.broadcast(packet, visited, taggedPeersOnly)
    }

    /**
     * Re-forward a crossing broadcast across the backbone (spec §2, §5) so it
     * propagates over multi-hop / non-full-mesh topologies (e.g. a ring).
     * Loop-free by construction: we append ourselves to the visited path and
     * each transport floods only to tag-aware peers not already on the path
     * (split-horizon); a packet that returns to a router on its own path is
     * dropped by the drop-on-self guard. A path at the length bound is not
     * extended — the next hop would drop it anyway.
     *
     * Forwarded frames go to tag-aware peers ONLY ([broadcastToBackbone] with
     * taggedPeersOnly): a non-tag-aware peer would receive the forwarded frame
     * as a plain (empty-path) frame and mis-learn US, the forwarder, as the
     * sender's home router instead of the true origin (visited[0]). Such a peer
     * still learns the correct home router from the origin's direct broadcast,
     * and can't forward further anyway.
     */
    private fun forwardBroadcastOnBackbone(packet: BlemeshPacket, visited: List<PeerID>) {
        val forwardPath = visited + myPeerID
        if (forwardPath.size > BackboneFrame.MAX_VISITED) return
        broadcastToBackbone(packet, forwardPath, taggedPeersOnly = true)
    }

    /** The recipient's home router if we have a fresh, currently-connected route. */
    private fun freshRouteFor(recipient: PeerID): RouterRoute? {
        val route = peerToHomeRouter[recipient] ?: return null
        if (System.currentTimeMillis() - route.lastSeenMs > HOME_ROUTER_TTL_MS) return null
        if (route.routerPeerID !in route.transport.connectedPeerIDs()) return null
        return route
    }

    /**
     * Hold a directed DM for [recipient] until it becomes routable, or it ages
     * out. All dmRetryQueue deque access (here, flush, sweep) happens inside
     * compute/computeIfPresent — the map's per-key lock — so a concurrent
     * remove can never detach a deque between lookup and mutation and strand
     * the packet.
     */
    private fun enqueueDmForRetry(recipient: PeerID, packet: BlemeshPacket) {
        val now = System.currentTimeMillis()
        var size = 0
        dmRetryQueue.compute(recipient) { _, existing ->
            val queue = existing ?: ArrayDeque()
            while (queue.isNotEmpty() && now - queue.first().enqueuedMs > DM_QUEUE_MAX_AGE_MS) {
                queue.removeFirst()
            }
            if (queue.size >= DM_QUEUE_MAX_PER_PEER) queue.removeFirst()
            queue.addLast(QueuedDm(packet, now))
            size = queue.size
            queue
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
        if (!dmRetryQueue.containsKey(recipient)) return
        val localNow = bleMeshService.isLocalPeer(recipient)
        val route = if (localNow) null else freshRouteFor(recipient)
        // Snapshot under the map's per-key lock. When directly reachable the
        // queue is drained and detached in the same atomic step, so a DM
        // enqueued concurrently lands in a fresh (still-mapped) deque instead
        // of a detached one and is never lost.
        val snapshot = ArrayList<QueuedDm>()
        dmRetryQueue.computeIfPresent(recipient) { _, queue ->
            snapshot.addAll(queue)
            if (localNow) null else queue
        }
        if (snapshot.isEmpty()) return
        val now = System.currentTimeMillis()
        var delivered = 0
        for (entry in snapshot) {
            if (now - entry.enqueuedMs > DM_QUEUE_MAX_AGE_MS) continue
            val p = entry.packet
            when {
                // Local delivery spends a TTL unit (anti-masquerade), always.
                localNow -> { bleMeshService.sendPacketToAllLegs(recipient, spendCrossingTtl(p)); delivered++ }
                // Backbone leg: TTL decoupled when the tag is on (spec §4).
                route != null -> {
                    val hop = if (BACKBONE_PATH_TAG) p else spendCrossingTtl(p)
                    route.transport.sendToPeer(route.routerPeerID, hop, originVisited())
                    bleToBridgeCounter.incrementAndGet()
                }
            }
        }
        if (localNow) {
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
                // Expire and (if emptied) unmap each deque in one per-key
                // atomic step, so a concurrent enqueue can't add to a deque
                // that this sweep is about to detach.
                for (recipient in dmRetryQueue.keys) {
                    dmRetryQueue.computeIfPresent(recipient) { _, queue ->
                        while (queue.isNotEmpty() && now - queue.first().enqueuedMs > DM_QUEUE_MAX_AGE_MS) {
                            queue.removeFirst()
                        }
                        if (queue.isEmpty()) null else queue
                    }
                }
                peerToHomeRouter.entries.removeAll { now - it.value.lastSeenMs > HOME_ROUTER_TTL_MS }
                packetRetryCounts.entries.removeAll { now - it.value.lastSeenMs > RETRY_WINDOW_MS }
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
            // Fresh liveness slate: a miss counter or in-flight pings surviving
            // from a previous incarnation of this link must not count against
            // the new one, or a reconnected peer starts life at missed=1-2 and
            // a single slow ping tears it down again (flap loop).
            missedPings.remove(transport.name to peer)
            purgeOutstandingPings(transport.name, peer)
            // Advertise our backbone path-tag support so the peer knows it may
            // send us tagged frames (and we learn the same from its reply).
            // Until the caps round-trip completes both sides use plain frames.
            if (BACKBONE_PATH_TAG) sendRouterCaps(transport, peer)
            // Kick an immediate anti-entropy round so a router that connects
            // after the origin's push still reconciles crossable content and
            // learns home-router routes fast, without waiting for the interval.
            if (BACKBONE_GOSSIP) {
                sendRouterSync(transport, peer)
                sendRouterHome(transport, peer)
            }
        }

        override fun onTransportPeerDisconnected(transport: RouterTransport, peer: PeerID) {
            Log.i(TAG, "Router peer disconnected from ${transport.name}: ${peer.rawValue.take(8)}")
            val key = transport.name to peer
            rttByTransportPeer.remove(key)
            missedPings.remove(key)
            // Purge this link's in-flight pings too: left behind, they expire
            // in the reaper and resurrect the just-cleared miss counter (and
            // leak forever if the peer never returns).
            purgeOutstandingPings(transport.name, peer)
        }

        override fun onTransportPacketReceived(
            transport: RouterTransport,
            packet: BlemeshPacket,
            fromPeer: PeerID,
            visited: List<PeerID>
        ) {
            routeBridgePacketToBle(transport, packet, fromPeer, visited)
        }
    }

    private fun routeBridgePacketToBle(
        fromTransport: RouterTransport,
        packet: BlemeshPacket,
        fromRouter: PeerID,
        visited: List<PeerID>
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
            MessageType.ROUTER_CAPS.value -> {
                handleRouterCaps(fromTransport, packet, fromRouter)
                return
            }
            MessageType.ROUTER_SYNC.value -> {
                handleRouterSync(fromTransport, packet, fromRouter)
                return
            }
            MessageType.ROUTER_SYNC_DATA.value -> {
                handleRouterSyncData(fromTransport, packet, fromRouter)
                return
            }
            MessageType.ROUTER_HOME.value -> {
                handleRouterHome(fromTransport, packet, fromRouter)
                return
            }
        }

        // Mirror the BLE→bridge direction: link-local types arriving from an
        // older or non-compliant peer router must not leak into this segment
        // or be multi-hopped onward.
        if (!MessageType.isBridgeable(packet.type)) return

        // Backbone loop guard (spec §2): a packet whose visited path already
        // contains us has looped back — drop it. This is the primary loop guard
        // for the tagged backbone, loop-free by construction at any router
        // count; the dedup below stays as a backstop (BLE-origin dupes, and
        // untagged hops from legacy peers).
        if (BACKBONE_PATH_TAG && myPeerID in visited) {
            Log.d(TAG, "bridge→BLE DROP-LOOP ${packetTag(packet)} (self on path, len=${visited.size})")
            return
        }
        // Path-length bound (spec §2): drop only a path that would *exceed* the
        // bound — a path AT the bound (== MAX_VISITED) is still delivered, and
        // forwardBroadcastOnBackbone declines to grow it to MAX_VISITED+1. This
        // matches the forward guard so the full 16-router budget is usable, not
        // capped at 15. Defensive: the decoder already rejects count >
        // MAX_VISITED, so this fires only if a future format relaxes that.
        if (visited.size > BackboneFrame.MAX_VISITED) {
            Log.w(TAG, "bridge→BLE DROP-MAXPATH ${packetTag(packet)} (path=${visited.size})")
            return
        }

        val dedupKey = "br2ble-${packet.senderId}-${packet.wireTimestamp}-${packet.type}"
        if (bridgeDeduplicator.isDuplicate(dedupKey)) return

        // Packets addressed to this router terminate here (mirror of the
        // BLE-side isForMe check): never injected into BLE, never forwarded.
        if (!packet.isBroadcast && packet.recipientPeerID() == myPeerID) {
            Log.d(TAG, "bridge→BLE consume ${packetTag(packet)} (addressed to this router)")
            return
        }

        // Learn which router a peer sits behind from its ANNOUNCE crossing the
        // backbone — the routing table for targeted directed bridging. The
        // authoritative home router is the path *origin* (visited[0]), not the
        // previous hop: under multi-hop forwarding fromRouter is only the router
        // that relayed the announce to us. Fall back to fromRouter for an
        // untagged/legacy frame. On a change (first sighting or a roam) re-route
        // any DMs we're holding for that peer.
        //
        // Evidence time is the announce's own (normalized) emission time, NOT
        // receipt time: a late or reordered stale announce that crosses the
        // backbone after a fresher one must not clobber the newer route. This
        // shares learnHomeRoute's monotonic CAS with the ROUTER_HOME writer so
        // the two writers of peerToHomeRouter agree on "more-recent-sighting-wins".
        //
        // Clamped to the local clock: the ROUTER_HOME writer supplies evidence
        // in the LOCAL clock domain (now − advertised age), so a sender phone
        // whose clock runs fast would otherwise leave future-dated evidence
        // that pins its route to a stale router after a roam — fresh ROUTER_HOME
        // claims from the new router could never win the CAS. The clamp keeps
        // announce-vs-announce ordering intact for sane clocks and bounds the
        // damage from a skewed one.
        if (packet.type == MessageType.ANNOUNCE.value || packet.type == MessageType.LOXATION_ANNOUNCE.value) {
            val originRouter = visited.firstOrNull() ?: fromRouter
            PeerID.fromLongBE(packet.senderId)?.let { sender ->
                val evidenceMs = minOf(packet.timestamp, System.currentTimeMillis())
                learnHomeRoute(sender, originRouter, fromTransport, evidenceMs)
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

        // A compliant sender arrives at <= MAX_TTL-1 (TTL is spent on the
        // crossing when the tag is off; when it is on TTL is decoupled and the
        // packet may arrive at MAX_TTL). Either way, clamp any MAX_TTL arrival
        // before injection or local reference phones bind the original sender's
        // PeerID to our BLE address — the direct-connection masquerade bug.
        val inbound = if ((packet.ttl.toInt() and 0xFF) >= BlemeshPacket.MAX_TTL) {
            packet.withTTL((BlemeshPacket.MAX_TTL - 1).toByte())
        } else packet

        val tag = packetTag(inbound)
        trackRetry("br→ble", inbound)
        Log.d(TAG, "bridge→BLE ${fromTransport.name}<-${fromRouter.rawValue.take(8)} $tag path=${visited.size}")
        bleMeshService.injectPacketFromWifi(inbound)
        bridgeToBleCounter.incrementAndGet()

        // Multi-hop backbone forwarding (spec §2, §5). Re-forward crossing
        // broadcasts (announce / presence) so they propagate over multi-hop /
        // non-full-mesh topologies; drop-on-self above + the dedup backstop
        // keep it loop-free and bounded (a router forwards a given packet at
        // most once — the second copy is deduped before reaching here). Forward
        // the ORIGINAL packet, not the TTL-clamped inbound copy: the clamp is a
        // BLE-injection concern, while the backbone leg stays TTL-decoupled and
        // each downstream router re-clamps on its own injection.
        //
        // Directed traffic is NOT flooded: the origin targets the recipient's
        // home router directly (targeted bridging, spec §3). A directed packet
        // that reaches the wrong router is injected locally above (the mesh
        // relay delivers it if the recipient is a region member) but is not
        // re-forwarded onto the backbone.
        //
        // Only re-forward a broadcast that arrived *tagged* (non-empty path):
        // extending an existing path preserves its origin (visited[0]). An
        // untagged broadcast came from a legacy peer that already fanned it to
        // its own peers; re-forwarding it would falsely stamp US as the origin
        // and corrupt downstream home-router learning, so we leave it region-
        // terminal (dedup-safe, matching pre-tag behavior for legacy origins).
        if (BACKBONE_PATH_TAG && packet.isBroadcast && visited.isNotEmpty()) {
            forwardBroadcastOnBackbone(packet, visited)
        }
    }

    /** Build and send a ROUTER_CAPS advertisement (directed) to [peer]. */
    private fun sendRouterCaps(transport: RouterTransport, peer: PeerID) {
        val bits = if (BACKBONE_PATH_TAG) CAPS_BIT_PATH_TAG else 0
        val payload = byteArrayOf(CAPS_VERSION, bits.toByte())
        val packet = BlemeshPacket(
            version = BlemeshPacket.PROTOCOL_VERSION,
            type = MessageType.ROUTER_CAPS.value,
            ttl = 1.toByte(),
            timestamp = System.currentTimeMillis(),
            flags = BlemeshPacket.FLAG_HAS_RECIPIENT,
            senderId = myPeerID.toLongBE(),
            recipientId = peer.toLongBE(),
            payload = payload,
            signature = null
        )
        // Always a plain frame — this IS the negotiation, so the peer can't be
        // assumed tag-aware yet. A legacy router consumes it via its
        // addressed-to-this-router check and never injects it into BLE.
        transport.sendToPeer(peer, packet, emptyList())
    }

    /**
     * Record a peer router's advertised backbone capabilities so we only send
     * it tagged frames once it has confirmed support (spec §6 rollout).
     */
    private fun handleRouterCaps(fromTransport: RouterTransport, packet: BlemeshPacket, fromRouter: PeerID) {
        val supportsTag = packet.payload.size >= 2 &&
            (packet.payload[1].toInt() and CAPS_BIT_PATH_TAG) != 0
        fromTransport.setPeerBackboneTag(fromRouter, supportsTag)
        Log.d(TAG, "ROUTER_CAPS from ${fromRouter.rawValue.take(8)} via ${fromTransport.name}: pathTag=$supportsTag")
    }

    // --- Backbone GCS anti-entropy (ROUTER_SYNC / ROUTER_SYNC_DATA) ---

    /**
     * Periodically advertise our crossable-content GCS filter to every connected
     * router peer over every transport. All peers each round (the router count is
     * small, so reconciliation reliability beats BLE's maxPeersPerSync frugality).
     */
    private fun startBackboneGossip() {
        backboneGossipJob?.cancel()
        backboneGossipJob = serviceScope.launch {
            while (isActive) {
                delay(BACKBONE_GOSSIP_INTERVAL_MS)
                // One filter per round: buildBackboneFilter copies and re-sorts
                // the full DTN window, so building it per peer repeated that
                // work for every connected router.
                val filter = bleMeshService.gossipSyncManager.buildBackboneFilter(CROSSABLE_SYNC_TYPES)
                for (t in transports) {
                    for (peer in t.connectedPeerIDs()) {
                        sendRouterSync(t, peer, filter)
                        sendRouterHome(t, peer)
                    }
                }
            }
        }
    }

    /**
     * Advertise "what crossable content I hold" to [peer] as a directed
     * ROUTER_SYNC control frame (payload = RequestSync TLV / GCS filter). The
     * peer replies with ROUTER_SYNC_DATA for anything we're missing.
     */
    private fun sendRouterSync(
        transport: RouterTransport,
        peer: PeerID,
        payload: ByteArray = bleMeshService.gossipSyncManager.buildBackboneFilter(CROSSABLE_SYNC_TYPES)
    ) {
        val packet = BlemeshPacket(
            version = BlemeshPacket.PROTOCOL_VERSION,
            type = MessageType.ROUTER_SYNC.value,
            ttl = 1.toByte(),
            timestamp = System.currentTimeMillis(),
            flags = BlemeshPacket.FLAG_HAS_RECIPIENT,
            senderId = myPeerID.toLongBE(),
            recipientId = peer.toLongBE(),
            payload = payload,
            signature = null
        )
        // Plain frame — this is router-internal control traffic, never tagged.
        transport.sendToPeer(peer, packet, emptyList())
    }

    /**
     * A peer advertised its filter: reply with each crossable packet it lacks as
     * a ROUTER_SYNC_DATA control frame (content reconciliation only). collectMissing
     * is pure over the gossip stores (thread-safe to call synchronously here).
     * Home-router routing is carried separately by ROUTER_HOME so a content
     * re-seed cannot suppress route learning (see handleRouterHome).
     */
    private fun handleRouterSync(fromTransport: RouterTransport, packet: BlemeshPacket, fromRouter: PeerID) {
        val req = RequestSyncPacket.decode(packet.payload) ?: return
        val missing = bleMeshService.gossipSyncManager.collectMissing(req)
        val batch = missing.take(MAX_SYNC_DATA_FRAMES_PER_ROUND)
        for (pkt in batch) sendRouterSyncData(fromTransport, fromRouter, pkt)
        if (batch.size < missing.size) {
            Log.i(TAG, "ROUTER_SYNC from ${fromRouter.rawValue.take(8)}: replying ${batch.size}/${missing.size} packet(s), remainder next round")
        } else if (missing.isNotEmpty()) {
            Log.d(TAG, "ROUTER_SYNC from ${fromRouter.rawValue.take(8)}: replying ${missing.size} packet(s)")
        }
    }

    /** Ship one crossable packet the peer was missing as a directed control frame. */
    private fun sendRouterSyncData(transport: RouterTransport, peer: PeerID, inner: BlemeshPacket) {
        val encoded = BinaryProtocol.encode(inner) ?: return
        val packet = BlemeshPacket(
            version = BlemeshPacket.PROTOCOL_VERSION,
            type = MessageType.ROUTER_SYNC_DATA.value,
            ttl = 1.toByte(),
            timestamp = System.currentTimeMillis(),
            flags = BlemeshPacket.FLAG_HAS_RECIPIENT,
            senderId = myPeerID.toLongBE(),
            recipientId = peer.toLongBE(),
            payload = encoded,
            signature = null
        )
        transport.sendToPeer(peer, packet, emptyList())
    }

    /**
     * A peer sent us content we were missing: re-seed our gossip store ONLY (no
     * BLE inject, no re-forward, no home-router learning). Our local BLE crowd
     * pulls it via the existing local gossip — backfill, not a re-flood.
     */
    private fun handleRouterSyncData(fromTransport: RouterTransport, packet: BlemeshPacket, fromRouter: PeerID) {
        val inner = BinaryProtocol.decode(packet.payload) ?: return
        bleMeshService.gossipSyncManager.onPublicPacketSeen(inner)
        Log.d(TAG, "ROUTER_SYNC_DATA from ${fromRouter.rawValue.take(8)}: re-seeded ${packetTag(inner)}")
    }

    /**
     * Advertise the peers currently in our BLE region to [peer] as a ROUTER_HOME
     * control frame, so it learns peer→home-router routes directly. Decoupled
     * from content gossip: unlike a claim piggybacked on a GCS-reconciled
     * announce, this is never suppressed by another router having re-seeded that
     * announce first — which is what makes DM routing reliable across 3+ routers.
     */
    private fun sendRouterHome(transport: RouterTransport, peer: PeerID) {
        val members = bleMeshService.getRegionMembers()
        if (members.isEmpty()) return
        val entries = members.asSequence()
            .take(ROUTER_HOME_MAX_ENTRIES)
            .map { (p, ageMs) -> RouterHomeFrame.Entry(p, (ageMs / 1000L).toInt()) }
            .toList()
        val pkt = BlemeshPacket(
            version = BlemeshPacket.PROTOCOL_VERSION,
            type = MessageType.ROUTER_HOME.value,
            ttl = 1.toByte(),
            timestamp = System.currentTimeMillis(),
            flags = BlemeshPacket.FLAG_HAS_RECIPIENT,
            senderId = myPeerID.toLongBE(),
            recipientId = peer.toLongBE(),
            payload = RouterHomeFrame.encode(entries),
            signature = null
        )
        if (members.size > ROUTER_HOME_MAX_ENTRIES) {
            Log.w(TAG, "ROUTER_HOME to ${peer.rawValue.take(8)}: ${members.size} region members, capped at $ROUTER_HOME_MAX_ENTRIES")
        }
        transport.sendToPeer(peer, pkt, emptyList())
    }

    /**
     * A peer router advertised the peers in its BLE region. Learn each as a
     * peer→home-router route to that router. Trust/reachability: the advertiser
     * sent us this frame directly, so it is a connected backbone peer we can
     * reach, and it only advertises peers genuinely in its own region
     * (getRegionMembers / regionMembers is BLE-origin only) — the same authority
     * the push path uses.
     *
     * Freshness & anti-flap: we backdate the route's lastSeenMs by the advertised
     * age, so (a) the route ages on the peer's real BLE sighting time, not receipt
     * time (a stale advertiser can't hold a dead route alive), and (b) during a
     * roam the router that saw the peer MORE recently wins — a staler claim never
     * overwrites a fresher route, so the route doesn't flap. The compare+swap is
     * atomic via ConcurrentHashMap.compute (this runs on a transport read thread,
     * concurrently with the push path's learning).
     */
    private fun handleRouterHome(fromTransport: RouterTransport, packet: BlemeshPacket, fromRouter: PeerID) {
        val entries = RouterHomeFrame.decode(packet.payload) ?: return
        val now = System.currentTimeMillis()
        var learned = 0
        for (entry in entries) {
            val sightedMs = now - entry.ageSeconds * 1000L
            if (learnHomeRoute(entry.peerID, fromRouter, fromTransport, sightedMs)) learned++
        }
        if (learned > 0) {
            Log.d(TAG, "ROUTER_HOME from ${fromRouter.rawValue.take(8)}: (re)bound $learned peer(s)")
        }
    }

    /**
     * Bind [peer] to home router [router] (reachable via [transport]) under a
     * single monotonic compare-and-swap keyed on [evidenceMs] — the local-clock
     * time the peer was last known active behind that router (an announce's
     * normalized emission time on the push path; now − advertised age on the
     * ROUTER_HOME path). This is the SOLE writer discipline for peerToHomeRouter:
     * both the ANNOUNCE push path and ROUTER_HOME route through it so neither can
     * clobber the other with staler evidence.
     *
     * A route is (re)bound to a DIFFERENT router only when this evidence is
     * fresher than the current route's, and refreshed for the SAME router only
     * forward in time — so a late/reordered stale announce or a lagging advert
     * can never overwrite a fresher route (the anti-flap invariant). The CAS is
     * atomic (ConcurrentHashMap.compute); this runs on transport read threads.
     * Returns true (and flushes held DMs) when the peer's home router changed.
     */
    private fun learnHomeRoute(
        peer: PeerID,
        router: PeerID,
        transport: RouterTransport,
        evidenceMs: Long
    ): Boolean {
        if (peer == myPeerID || peer == router) return false
        var rebound = false
        peerToHomeRouter.compute(peer) { _, prev ->
            when {
                // No route, or a different router with fresher evidence → (re)bind.
                prev == null || prev.routerPeerID != router ->
                    if (prev == null || evidenceMs > prev.lastSeenMs) {
                        rebound = true
                        RouterRoute(transport, router, evidenceMs)
                    } else prev
                // Same router: refresh only forward in time.
                else ->
                    if (evidenceMs > prev.lastSeenMs) RouterRoute(transport, router, evidenceMs)
                    else prev
            }
        }
        if (rebound) flushDmQueue(peer)
        return rebound
    }

    // --- Router-to-router RTT (ROUTER_PING / ROUTER_PONG) ---

    private fun startRttProbing() {
        rttJob?.cancel()
        rttJob = serviceScope.launch {
            while (isActive) {
                delay(RTT_PING_INTERVAL_MS)
                reapMissedPings()
                for (t in transports) {
                    for (peerID in t.connectedPeerIDs()) {
                        sendRouterPing(t, peerID)
                    }
                }
            }
        }
    }

    /**
     * Expire unanswered pings and tear down peers that have missed
     * [MAX_MISSED_PINGS] in a row — the liveness signal for a half-open
     * connection whose socket never errors (radio loss without a FIN).
     */
    private fun reapMissedPings() {
        val timeoutNs = System.nanoTime() - RTT_PING_TIMEOUT_MS * 1_000_000L
        val iter = outstandingPings.entries.iterator()
        while (iter.hasNext()) {
            val rec = iter.next().value
            if (rec.sentNs >= timeoutNs) continue
            iter.remove()
            val key = rec.transportName to rec.peer
            val missed = missedPings.merge(key, 1, Int::plus) ?: 1
            if (missed >= MAX_MISSED_PINGS) {
                missedPings.remove(key)
                val transport = transports.firstOrNull { it.name == rec.transportName } ?: continue
                if (rec.peer in transport.connectedPeerIDs()) {
                    Log.w(TAG, "Router peer ${rec.peer.rawValue.take(8)} missed $missed pings on ${rec.transportName}; disconnecting")
                    transport.disconnectPeer(rec.peer)
                }
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
        outstandingPings[nonce] = PingRecord(System.nanoTime(), transport.name, peer)
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
        // Any pong from this peer proves the link is alive: reset the miss
        // counter even when the reaper already expired the matching ping
        // record (RTT just above RTT_PING_TIMEOUT_MS) — otherwise a
        // slow-but-answering link accrues misses it can never clear and is
        // torn down / reconnected in a perpetual flap.
        missedPings.remove(fromTransport.name to fromRouter)
        val record = outstandingPings.remove(nonce) ?: return
        val rttMs = (System.nanoTime() - record.sentNs) / 1_000_000L
        rttByTransportPeer[fromTransport.name to fromRouter] = rttMs
    }

    /** Drop in-flight ping records for one (transport, peer) link. */
    private fun purgeOutstandingPings(transportName: String, peer: PeerID) {
        outstandingPings.entries.removeIf { it.value.transportName == transportName && it.value.peer == peer }
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
