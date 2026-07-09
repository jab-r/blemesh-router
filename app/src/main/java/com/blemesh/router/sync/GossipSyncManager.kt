package com.blemesh.router.sync

import android.util.Log
import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.MessageType
import com.blemesh.router.model.PeerID
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Gossip-based sync manager using GCS filters for bandwidth-efficient set reconciliation.
 * Ported from loxation-android GossipSyncManager with separate packet stores per type.
 */
class GossipSyncManager(
    private val myPeerID: PeerID,
    private val scope: CoroutineScope,
    private val config: Config = Config()
) {
    companion object {
        private const val TAG = "GossipSyncManager"

        /**
         * Max-min fair (water-filling) split of the GCS id budget across sync
         * buckets: every non-empty bucket gets an equal share of what remains;
         * capacity a bucket cannot use spills to the others. Guarantees a
         * mixed window always reserves at least budget/numBuckets ids for each
         * bucket that has candidates — presence packets can never fully crowd
         * out text cargo (or vice versa). Pure + deterministic given
         * (counts, budget). Mirrors the iOS GossipSyncManager.bucketBudgets.
         */
        internal fun bucketBudgets(counts: List<Int>, budget: Int): List<Int> {
            val result = IntArray(counts.size)
            if (budget <= 0) return result.toList()
            var remaining = budget
            var active = counts.withIndex()
                .filter { it.value > 0 }
                .map { it.index to it.value }
            while (remaining > 0 && active.isNotEmpty()) {
                val share = (remaining / active.size).coerceAtLeast(1)
                val unsatisfied = mutableListOf<Pair<Int, Int>>()
                for ((index, need) in active) {
                    val take = minOf(need, share, remaining)
                    result[index] += take
                    remaining -= take
                    if (need - take > 0) unsatisfied.add(index to (need - take))
                    if (remaining == 0) break
                }
                active = unsatisfied
            }
            return result.toList()
        }

        // --- #4233 Option A (cap-to-fit) — pure, testable egress-sizing policy ---

        /**
         * Worst-case non-filter bytes of an encoded REQUEST_SYNC: outer
         * BinaryProtocol (14 header + 8 sender + 8 recipient; 0x60 is on the
         * NO_COMPRESS list and is unsigned) = 30, plus the RequestSyncPacket
         * TLV envelope around the GCS data (P 4 + M 7 + data tag/len 3 +
         * types TLV ≤ 11) ≤ 25. Same value as the iOS constant
         * (GossipSyncManager.requestSyncNonFilterOverheadBytes) — the wire
         * encodings are interop-locked. Pinned end-to-end by
         * GossipCapToFitTest.requestSyncEnvelopeOverheadPinned — if either
         * encoding grows, that test fails before a frame can silently exceed
         * a link on hardware.
         */
        internal const val REQUEST_SYNC_NON_FILTER_OVERHEAD_BYTES = 56

        /**
         * Effective GCS filter byte budget for a REQUEST_SYNC over a link that
         * can carry at most [linkMaxFrameBytes] in one frame. null link info →
         * config budget (an oversize request then falls into
         * BleMeshService.sendPacketToConnection's fragmentation path, which
         * warns — see the REQUEST_SYNC log there).
         */
        internal fun effectiveGcsBytes(configMaxBytes: Int, linkMaxFrameBytes: Int?): Int {
            if (linkMaxFrameBytes == null) return configMaxBytes
            return minOf(configMaxBytes, linkMaxFrameBytes - REQUEST_SYNC_NON_FILTER_OVERHEAD_BYTES)
        }
    }

    interface Delegate {
        fun sendPacketToPeer(peerID: PeerID, packet: BlemeshPacket)

        /**
         * Max encoded-packet bytes deliverable to this peer in ONE BLE frame
         * (the write/notify budget of the link a [sendPacketToPeer] will ride),
         * or null when no link info is available. #4233: REQUEST_SYNC frames
         * must never be fragmented — the ttl=0 FRAGMENT wrappers are dropped
         * at reference phones' RSR flood-gates unless the phone coincidentally
         * has its own sync window open toward us — so requests are sized to
         * fit this budget instead.
         */
        fun maxSingleFrameBytes(peerID: PeerID): Int?

        fun getConnectedPeers(): List<PeerID>
    }

    data class Config(
        // DTN carry buffer (docs/adaptive-mpr-fix.md, iOS festival-scale plan):
        // sized so the 30-min text horizon below is not silently undone by FIFO
        // eviction. The router is the mesh's DTN reconciliation point, so it
        // carries at least the phone-side buffer.
        val seenCapacity: Int = 6000,
        val gcsMaxBytes: Int = 400,
        // #4233 Option A: below this GCS byte budget a link can't carry a
        // useful filter (a 23-byte default-ATT link nets a negative budget) —
        // skip the sync round to that peer entirely instead of sending a
        // degenerate filter that elicits a near-full re-flood. Matches iOS
        // Config.minGcsBytesForSync.
        val minGcsBytesForSync: Int = 64,
        // Keep <= 0.01 (Rice parameter P >= 7): GCSFilter.decodeToSortedSet
        // reads unary+P-bit codes until EOF, and with P <= 6 the up-to-7 zero
        // padding bits of the final byte can satisfy a full read and inject
        // phantom buckets (false-positive membership → wrongly suppressed
        // sync sends). At P >= 7 the padding can't complete a read. Matches
        // the reference/iOS decoder, so fix there too before relaxing this.
        val gcsTargetFpr: Double = 0.01,
        // Per-type freshness horizons (DTN store-carry-forward). Async text
        // (message/fragment) IS the DTN cargo — 30 min so carried packets
        // survive a walk across the grounds (the old blanket 900s GC'd exactly
        // what DTN was carrying). Positions/presence are real-time-only
        // signals — carrying them is anti-useful (a stale position misleads).
        val messageMaxAgeSeconds: Long = 1800,
        val fragmentMaxAgeSeconds: Long = 1800, // fragments carry oversized text
        val loxationMaxAgeSeconds: Long = 900, // profile announce — semi-static
        val locationUpdateMaxAgeSeconds: Long = 60, // real-time only, never carried
        // Announce horizon must stay STRICTLY GREATER than
        // stalePeerTimeoutSeconds: the stale reaper (removeState side effect)
        // retires a departed peer at ~60–120s, and an equal horizon would let
        // generic expiry silently drop the entry before the reaper (which owns
        // the departure cleanup) ever saw it — dead reaper, no removeState.
        val announceMaxAgeSeconds: Long = 120,
        val maintenanceIntervalSeconds: Long = 30,
        val stalePeerCleanupIntervalSeconds: Long = 60,
        val stalePeerTimeoutSeconds: Long = 60,
        val fragmentCapacity: Int = 2400, // scaled with the fragment horizon (was 600)
        val loxationCapacity: Int = 200,
        val locationUpdateCapacity: Int = 100, // indoor position update packets (short-lived)
        val fragmentSyncIntervalSeconds: Long = 30,
        val loxationSyncIntervalSeconds: Long = 60,
        val locationUpdateSyncIntervalSeconds: Long = 15, // frequent for real-time positioning
        val messageSyncIntervalSeconds: Long = 15,
        val maxPeersPerSync: Int = 2,
        val syncJitterRatio: Double = 0.3
    ) {
        /**
         * DTN per-type freshness horizon. Unknown/other types get the message
         * horizon (only the syncable buckets ever consult this).
         */
        fun maxAgeSeconds(forType: Byte): Long = when (MessageType.from(forType)) {
            MessageType.FRAGMENT -> fragmentMaxAgeSeconds
            MessageType.ANNOUNCE -> announceMaxAgeSeconds
            MessageType.LOXATION_ANNOUNCE -> loxationMaxAgeSeconds
            MessageType.LOCATION_UPDATE -> locationUpdateMaxAgeSeconds
            else -> messageMaxAgeSeconds
        }
    }

    private class PacketStore {
        private val packets = LinkedHashMap<String, BlemeshPacket>()
        private val order = ArrayList<String>()

        @Synchronized
        fun insert(idHex: String, packet: BlemeshPacket, capacity: Int) {
            if (capacity <= 0) return
            if (packets.containsKey(idHex)) {
                packets[idHex] = packet
                return
            }
            packets[idHex] = packet
            order.add(idHex)
            while (order.size > capacity) {
                val victim = order.removeAt(0)
                packets.remove(victim)
            }
        }

        /**
         * Fresh (idHex, packet) entries in insertion order. The key rides
         * along so sync rounds can decode the GCS id from it instead of
         * re-running SHA-256 over every window packet per round.
         */
        @Synchronized
        fun freshEntries(isFresh: (BlemeshPacket) -> Boolean): List<Pair<String, BlemeshPacket>> {
            return order.mapNotNull { key ->
                val pkt = packets[key] ?: return@mapNotNull null
                if (isFresh(pkt)) key to pkt else null
            }
        }

        @Synchronized
        fun remove(shouldRemove: (BlemeshPacket) -> Boolean) {
            val nextOrder = ArrayList<String>()
            for (key in order) {
                val pkt = packets[key] ?: continue
                if (shouldRemove(pkt)) {
                    packets.remove(key)
                } else {
                    nextOrder.add(key)
                }
            }
            order.clear()
            order.addAll(nextOrder)
        }

        @Synchronized
        fun removeExpired(isFresh: (BlemeshPacket) -> Boolean) {
            remove { !isFresh(it) }
        }
    }

    private data class SyncSchedule(
        val types: SyncTypeFlags,
        val intervalMs: Long,
        var nextFireMs: Long = 0
    )

    var delegate: Delegate? = null

    private val messages = PacketStore()
    private val fragments = PacketStore()
    private val loxationPackets = PacketStore()
    private val locationUpdatePackets = PacketStore()
    private val latestAnnouncementByPeer = ConcurrentHashMap<PeerID, Pair<String, BlemeshPacket>>()

    private var periodicJob: Job? = null
    private var lastStalePeerCleanupMs = 0L
    private val syncSchedules = mutableListOf<SyncSchedule>()

    init {
        if (config.seenCapacity > 0 && config.messageSyncIntervalSeconds > 0) {
            syncSchedules.add(SyncSchedule(SyncTypeFlags.PUBLIC_MESSAGES, config.messageSyncIntervalSeconds * 1000))
        }
        if (config.fragmentCapacity > 0 && config.fragmentSyncIntervalSeconds > 0) {
            syncSchedules.add(SyncSchedule(SyncTypeFlags.FRAGMENT, config.fragmentSyncIntervalSeconds * 1000))
        }
        if (config.loxationCapacity > 0 && config.loxationSyncIntervalSeconds > 0) {
            syncSchedules.add(SyncSchedule(SyncTypeFlags.LOXATION_ANNOUNCE, config.loxationSyncIntervalSeconds * 1000))
        }
        if (config.locationUpdateCapacity > 0 && config.locationUpdateSyncIntervalSeconds > 0) {
            syncSchedules.add(SyncSchedule(SyncTypeFlags.LOCATION_UPDATE, config.locationUpdateSyncIntervalSeconds * 1000))
        }
    }

    fun start() {
        stop()
        val intervalMs = (config.maintenanceIntervalSeconds * 1000).coerceAtLeast(100)
        periodicJob = scope.launch {
            delay(intervalMs)
            while (isActive) {
                performPeriodicMaintenance()
                delay(intervalMs)
            }
        }
        Log.d(TAG, "Started (interval=${config.maintenanceIntervalSeconds}s)")
    }

    fun stop() {
        periodicJob?.cancel()
        periodicJob = null
    }

    fun scheduleInitialSyncToPeer(peerID: PeerID, delayMs: Long = 5000) {
        scope.launch {
            val jitterMs = (Math.random() * 3000).toLong()
            delay(delayMs + jitterMs)
            sendRequestSync(peerID, types = SyncTypeFlags.PUBLIC_MESSAGES)
            if (config.fragmentCapacity > 0 && config.fragmentSyncIntervalSeconds > 0) {
                delay(500)
                sendRequestSync(peerID, types = SyncTypeFlags.FRAGMENT)
            }
            if (config.loxationCapacity > 0 && config.loxationSyncIntervalSeconds > 0) {
                delay(500)
                sendRequestSync(peerID, types = SyncTypeFlags.LOXATION_ANNOUNCE)
            }
            if (config.locationUpdateCapacity > 0 && config.locationUpdateSyncIntervalSeconds > 0) {
                delay(500)
                sendRequestSync(peerID, types = SyncTypeFlags.LOCATION_UPDATE)
            }
        }
    }

    fun onPublicPacketSeen(packet: BlemeshPacket) {
        scope.launch(Dispatchers.Default) {
            onPublicPacketSeenInternal(packet)
        }
    }

    fun handleRequestSync(fromPeerID: PeerID, request: RequestSyncPacket) {
        scope.launch(Dispatchers.Default) {
            handleRequestSyncInternal(fromPeerID, request)
        }
    }

    fun removeAnnouncementForPeer(peerID: PeerID) {
        scope.launch(Dispatchers.Default) {
            removeState(peerID)
        }
    }

    // --- Internal ---

    /** Per-type DTN horizon (Config.maxAgeSeconds), not one blanket age. */
    private fun isPacketFresh(packet: BlemeshPacket): Boolean {
        val nowMs = System.currentTimeMillis()
        val thresholdMs = config.maxAgeSeconds(packet.type) * 1000
        if (nowMs < thresholdMs) return true
        return packet.timestamp >= nowMs - thresholdMs
    }

    private fun isAnnouncementFresh(packet: BlemeshPacket): Boolean {
        if (config.stalePeerTimeoutSeconds <= 0) return true
        val nowMs = System.currentTimeMillis()
        val timeoutMs = config.stalePeerTimeoutSeconds * 1000
        if (nowMs < timeoutMs) return true
        return packet.timestamp >= nowMs - timeoutMs
    }

    private fun onPublicPacketSeenInternal(packet: BlemeshPacket) {
        val messageType = MessageType.from(packet.type) ?: return
        val isBroadcast = packet.recipientId == BlemeshPacket.BROADCAST_ADDRESS

        when (messageType) {
            MessageType.ANNOUNCE -> {
                val sender = PeerID.fromLongBE(packet.senderId) ?: return
                // Stale-peer check FIRST — it carries the removeState side
                // effect. The announce horizon (120s) is deliberately close to
                // stalePeerTimeout (60s); a freshness-first guard would swallow
                // the stale signal for 60–120s-old announces and leave dead
                // peer state to the periodic reaper. The signal is bounded and
                // monotonic, though: only an announce within the announce
                // horizon AND at least as new as the one we hold is evidence
                // of departure. An ancient or out-of-order replay (re-emitted
                // from a restarting node's store, or injected over the
                // backbone) must never purge fresher presence/profile state —
                // unbounded, any >60s-old replay erased a live peer on every
                // receiving segment, re-triggerable at will.
                if (!isAnnouncementFresh(packet)) {
                    if (!isPacketFresh(packet)) return
                    // Decide against the held announce ATOMICALLY (compute
                    // holds the map's per-key lock): packets are processed on
                    // concurrent Dispatchers.Default coroutines, so a plain
                    // read-check-removeState here would race the fresh path's
                    // merge and erase a fresh announce merged in between.
                    var departed = false
                    latestAnnouncementByPeer.compute(sender) { _, held ->
                        if (held == null || packet.timestamp >= held.second.timestamp) {
                            departed = true
                            null // retire the presence entry in the same atomic step
                        } else held
                    }
                    if (departed) removeProfileAndPositionState(sender)
                    return
                }
                if (!isPacketFresh(packet)) return
                val idHex = PacketIdUtil.computeIdHex(packet)
                // Monotonic: a replayed older announce never displaces a fresher one.
                latestAnnouncementByPeer.merge(sender, Pair(idHex, packet)) { held, incoming ->
                    if (incoming.second.timestamp >= held.second.timestamp) incoming else held
                }
            }
            MessageType.MESSAGE -> {
                if (!isBroadcast || !isPacketFresh(packet)) return
                val idHex = PacketIdUtil.computeIdHex(packet)
                messages.insert(idHex, packet, config.seenCapacity.coerceAtLeast(1))
            }
            MessageType.FRAGMENT -> {
                if (!isBroadcast || !isPacketFresh(packet)) return
                val idHex = PacketIdUtil.computeIdHex(packet)
                fragments.insert(idHex, packet, config.fragmentCapacity.coerceAtLeast(1))
            }
            MessageType.LOXATION_ANNOUNCE -> {
                if (!isBroadcast || !isPacketFresh(packet)) return
                val idHex = PacketIdUtil.computeIdHex(packet)
                loxationPackets.insert(idHex, packet, config.loxationCapacity.coerceAtLeast(1))
            }
            MessageType.LOCATION_UPDATE -> {
                if (!isBroadcast || !isPacketFresh(packet)) return
                val idHex = PacketIdUtil.computeIdHex(packet)
                locationUpdatePackets.insert(idHex, packet, config.locationUpdateCapacity.coerceAtLeast(1))
            }
            else -> { /* ignore */ }
        }
    }

    private fun sendPeriodicSync(types: SyncTypeFlags) {
        val connectedPeers = delegate?.getConnectedPeers()
        if (!connectedPeers.isNullOrEmpty()) {
            val peersToSync = if (connectedPeers.size <= config.maxPeersPerSync) {
                connectedPeers
            } else {
                connectedPeers.shuffled().take(config.maxPeersPerSync)
            }
            // One payload per DISTINCT link budget per round (#4233):
            // buildGcsPayload copies and re-sorts the full sync window, so
            // building it per peer repeated that work — but the filter is now
            // sized per link, so the cache keys on the effective budget
            // (post-negotiation BLE links share one 185-byte budget, so this
            // is still one build per round in the common case).
            val payloadByBudget = HashMap<Int, ByteArray>()
            for (peerID in peersToSync) {
                sendRequestSync(peerID, types = types, payloadCache = payloadByBudget)
            }
        }
        // No broadcast fallback when no peers are mapped: both reference apps
        // deleted it (their RSR flood gates are keyed per-PeerID, so responses
        // to a broadcast were dropped as unsolicited), and for the router it
        // was nearly dead code — it fired only with zero announce-mapped
        // peers, i.e. nobody we could address — while inviting phones that
        // lack our address mapping to broadcast their ttl=0 RSR burst, which
        // other phones drop with gossip.security warnings. First-contact
        // reconciliation is covered by scheduleInitialSyncToPeer (unicast ~5s
        // after a peer's announce). Do NOT re-add a broadcast REQUEST_SYNC.
    }

    /**
     * #4233 Option A: size the filter to THIS link so the encoded REQUEST_SYNC
     * always fits one BLE frame — requests are locally built, so they never
     * need fragmentation (a fragmented request rides ttl=0 FRAGMENT frames
     * that reference phones drop at their RSR flood-gate as unsolicited
     * syncable data, unless the phone coincidentally has its own sync window
     * open toward us — luck-based delivery, and reliably absent exactly at
     * first contact). Degenerate links (budget < [Config.minGcsBytesForSync],
     * e.g. a still-at-default-ATT 23-byte link) skip the round with a log;
     * the next periodic round covers them after MTU negotiation. Unlike iOS
     * there is no RSR-window registration to reorder here: the router does
     * not gate inbound ttl=0 responses (processIncomingPacket stores them
     * unconditionally via onPublicPacketSeen).
     *
     * Internal (not private) so tests can drive it synchronously.
     * [payloadCache] lets one periodic round reuse a built payload across
     * peers sharing the same effective budget.
     */
    internal fun sendRequestSync(
        peerID: PeerID,
        types: SyncTypeFlags,
        payloadCache: MutableMap<Int, ByteArray>? = null
    ) {
        val linkMax = delegate?.maxSingleFrameBytes(peerID)
        val gcsBytes = effectiveGcsBytes(config.gcsMaxBytes, linkMax)
        if (gcsBytes < config.minGcsBytesForSync) {
            Log.w(TAG, "REQUEST_SYNC to ${peerID.rawValue.take(8)} skipped: degenerate link (linkMax=$linkMax gcsBudget=$gcsBytes)")
            return
        }
        val payload = payloadCache?.getOrPut(gcsBytes) { buildGcsPayload(types, gcsBytes) }
            ?: buildGcsPayload(types, gcsBytes)
        val pkt = BlemeshPacket(
            version = BlemeshPacket.PROTOCOL_VERSION,
            type = MessageType.REQUEST_SYNC.value,
            ttl = 0,
            timestamp = System.currentTimeMillis(),
            flags = BlemeshPacket.FLAG_HAS_RECIPIENT,
            senderId = myPeerID.toLongBE(),
            recipientId = peerID.toLongBE(),
            payload = payload,
            signature = null
        )
        delegate?.sendPacketToPeer(peerID, pkt)
    }

    private fun handleRequestSyncInternal(fromPeerID: PeerID, request: RequestSyncPacket) {
        val missing = collectMissing(request)
        missing.forEach { delegate?.sendPacketToPeer(fromPeerID, it) }
        if (missing.isNotEmpty()) {
            Log.d(TAG, "Sent ${missing.size} packets to ${fromPeerID.rawValue.take(8)} in response to REQUEST_SYNC")
        }
    }

    /**
     * The stored crossable packets the requester's GCS filter does NOT contain —
     * i.e. what it's missing — each returned at ttl=0 (matching the BLE gossip
     * reply). Pure over the packet stores (no delegate, no I/O): the BLE path
     * calls it and forwards each result via [Delegate.sendPacketToPeer]; the
     * router's WiFi backbone gossip calls it directly and ships each result as a
     * ROUTER_SYNC_DATA control frame. Safe to call synchronously off-scope — the
     * stores are @Synchronized and latestAnnouncementByPeer is concurrent.
     *
     * The diff runs over the SAME windowed candidate set the requester used to
     * build its filter ([syncWindowCandidates]) — the 400-byte GCS filter only
     * summarizes ~355 ids, so diffing the ENTIRE store re-sends every stored
     * packet outside the requester's window every round, forever (a perpetual
     * multi-thousand-frame re-flood at DTN store sizes, and an empty-filter
     * initial sync would elicit the whole store over one link). Older cargo
     * stays stored for relay/DTN carry — it is simply not re-reconciled.
     *
     * withTTL(0) is copy(ttl=0); the GCS id (PacketIdUtil) excludes ttl, so ids
     * and downstream dedup keys are unperturbed by the reset.
     */
    fun collectMissing(request: RequestSyncPacket): List<BlemeshPacket> {
        val requestedTypes = request.types ?: SyncTypeFlags.PUBLIC_MESSAGES
        val sorted = GCSFilter.decodeToSortedSet(request.p, request.m, request.data)

        fun mightContain(id: ByteArray): Boolean {
            val bucket = GCSFilter.bucket(id, request.m)
            return GCSFilter.contains(sorted, bucket)
        }

        val missing = mutableListOf<BlemeshPacket>()
        for ((idHex, pkt) in syncWindowCandidates(requestedTypes)) {
            val idBytes = PacketIdUtil.idBytesFromHex(idHex)
            if (!mightContain(idBytes)) missing.add(pkt.withTTL(0))
        }
        return missing
    }

    /**
     * The newest-first candidate window a sync round operates on: fresh packets
     * of the requested types, truncated to the GCS id budget (nMax). SHARED by
     * filter construction ([buildGcsPayload]) and the response diff
     * ([collectMissing]) — the two MUST use the same computation or
     * reconciliation degenerates (see collectMissing).
     *
     * The budget is split PER BUCKET (max-min fair via [bucketBudgets]), NOT
     * over one mixed newest-first list: a publicMessages request mixes
     * announces (≤120s fresh, one per peer) with text cargo (≤1800s), so in a
     * dense cell hundreds of always-newer announces crowded every message out
     * of a single mixed window — text cargo then never synced at exactly the
     * density the DTN horizon targets. Bucket order is fixed (announce,
     * message, fragment, loxationAnnounce, locationUpdate) — cross-platform
     * mirrors must match for best convergence.
     *
     * [gcsMaxBytes] overrides the config filter budget for the REQUESTER path
     * when the link caps it below config (#4233); the RESPONDER path
     * ([collectMissing]) and the backbone filter keep the config default — a
     * slightly wider responder window only re-sends packets the requester
     * already holds (deduped on receive), while a narrower one would break
     * convergence.
     */
    private fun syncWindowCandidates(
        types: SyncTypeFlags,
        gcsMaxBytes: Int = config.gcsMaxBytes
    ): List<Pair<String, BlemeshPacket>> {
        val buckets = mutableListOf<List<Pair<String, BlemeshPacket>>>()
        if (types.contains(MessageType.ANNOUNCE)) {
            buckets.add(latestAnnouncementByPeer.values.filter { isPacketFresh(it.second) })
        }
        if (types.contains(MessageType.MESSAGE)) {
            buckets.add(messages.freshEntries(::isPacketFresh))
        }
        if (types.contains(MessageType.FRAGMENT)) {
            buckets.add(fragments.freshEntries(::isPacketFresh))
        }
        if (types.contains(MessageType.LOXATION_ANNOUNCE)) {
            buckets.add(loxationPackets.freshEntries(::isPacketFresh))
        }
        if (types.contains(MessageType.LOCATION_UPDATE)) {
            buckets.add(locationUpdatePackets.freshEntries(::isPacketFresh))
        }
        if (buckets.all { it.isEmpty() }) return emptyList()

        val sortedBuckets = buckets.map { bucket -> bucket.sortedByDescending { it.second.timestamp } }

        val p = GCSFilter.deriveP(config.gcsTargetFpr)
        val nMax = GCSFilter.estimateMaxElementsForSize(gcsMaxBytes, p)
        val budgets = bucketBudgets(sortedBuckets.map { it.size }, nMax)
        val window = mutableListOf<Pair<String, BlemeshPacket>>()
        for ((index, bucket) in sortedBuckets.withIndex()) {
            window.addAll(bucket.take(budgets[index]))
        }
        return window
    }

    /**
     * A GCS filter (RequestSync TLV) over the crossable content this node holds
     * for [types] — reusable by the router's WiFi backbone gossip to advertise
     * "what I have" to a peer router. Thin, side-effect-free wrapper over the
     * same payload builder the BLE REQUEST_SYNC path uses. Deliberately keeps
     * the full config filter budget (#4233 does not apply): the backbone is
     * length-prefixed TCP with a 64 KiB LinkWriter send bound, not a BLE frame.
     */
    fun buildBackboneFilter(types: SyncTypeFlags): ByteArray = buildGcsPayload(types)

    /**
     * Build the REQUEST_SYNC payload over the shared sync window.
     * [gcsMaxBytes] is the link-capped filter budget (#4233) — it bounds BOTH
     * the id window and the encoded filter so the whole frame fits the target
     * link (GCSFilter.buildFilter trims until data ≤ maxBytes).
     */
    private fun buildGcsPayload(types: SyncTypeFlags, gcsMaxBytes: Int = config.gcsMaxBytes): ByteArray {
        val window = syncWindowCandidates(types, gcsMaxBytes)
        if (window.isEmpty()) {
            val p = GCSFilter.deriveP(config.gcsTargetFpr)
            return RequestSyncPacket(p = p, m = 1, data = ByteArray(0), types = types).encode()
        }
        val ids = window.map { PacketIdUtil.idBytesFromHex(it.first) }
        val params = GCSFilter.buildFilter(ids, gcsMaxBytes, config.gcsTargetFpr)
        val mVal = if (params.m <= 0L) 1 else params.m
        return RequestSyncPacket(p = params.p, m = mVal, data = params.data, types = types).encode()
    }

    private fun performPeriodicMaintenance() {
        // Stale-peer reaper BEFORE generic expiry: the reaper carries the
        // removeState side effect (retiring the departed peer's presence
        // state), and generic expiry silently drops the very entries the
        // reaper inspects — run second, it would never fire (the announce
        // horizon is deliberately only slightly above the stale timeout).
        cleanupStaleAnnouncementsIfNeeded()
        cleanupExpiredMessages()

        val now = System.currentTimeMillis()
        for (i in syncSchedules.indices) {
            val schedule = syncSchedules[i]
            if (schedule.intervalMs <= 0) continue
            if (now >= schedule.nextFireMs) {
                val jitterMs = (Math.random() * schedule.intervalMs * config.syncJitterRatio).toLong()
                syncSchedules[i] = schedule.copy(nextFireMs = now + schedule.intervalMs + jitterMs)
                sendPeriodicSync(schedule.types)
            }
        }
    }

    private fun cleanupExpiredMessages() {
        val staleAnnouncements = latestAnnouncementByPeer.entries.filter { !isPacketFresh(it.value.second) }
        staleAnnouncements.forEach { latestAnnouncementByPeer.remove(it.key) }
        messages.removeExpired(::isPacketFresh)
        fragments.removeExpired(::isPacketFresh)
        loxationPackets.removeExpired(::isPacketFresh)
        locationUpdatePackets.removeExpired(::isPacketFresh)
    }

    private fun cleanupStaleAnnouncementsIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastStalePeerCleanupMs < config.stalePeerCleanupIntervalSeconds * 1000) return
        lastStalePeerCleanupMs = now
        val timeoutMs = config.stalePeerTimeoutSeconds * 1000
        if (now < timeoutMs) return
        val cutoff = now - timeoutMs
        val stalePeerIDs = latestAnnouncementByPeer.entries
            .filter { it.value.second.timestamp < cutoff }
            .map { it.key }
        for (peerID in stalePeerIDs) {
            removeState(peerID)
        }
    }

    private fun removeState(peerID: PeerID) {
        // Peer departure (stale announce / LEAVE) retires the peer's REAL-TIME
        // state: presence, profile packets, positions — serving those after
        // departure is anti-useful. DTN cargo (message/fragment) is
        // deliberately KEPT: store-carry-forward exists precisely so cargo
        // outlives the originator's proximity — purging a sender's messages
        // the moment they walk out of announce range would silently defeat the
        // 30-min carry horizon (the pre-DTN behavior). Cargo expires by its
        // own per-type horizon in cleanupExpiredMessages.
        latestAnnouncementByPeer.remove(peerID)
        removeProfileAndPositionState(peerID)
    }

    private fun removeProfileAndPositionState(peerID: PeerID) {
        val senderLong = peerID.toLongBE()
        loxationPackets.remove { it.senderId == senderLong }
        locationUpdatePackets.remove { it.senderId == senderLong }
    }
}
