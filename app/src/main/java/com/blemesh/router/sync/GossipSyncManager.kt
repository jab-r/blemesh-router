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
    private val requestSyncManager: RequestSyncManager,
    private val config: Config = Config()
) {
    companion object {
        private const val TAG = "GossipSyncManager"
    }

    interface Delegate {
        fun sendPacket(packet: BlemeshPacket)
        fun sendPacketToPeer(peerID: PeerID, packet: BlemeshPacket)
        fun getConnectedPeers(): List<PeerID>
    }

    data class Config(
        val seenCapacity: Int = 1000,
        val gcsMaxBytes: Int = 400,
        val gcsTargetFpr: Double = 0.01,
        val maxMessageAgeSeconds: Long = 900,
        val maintenanceIntervalSeconds: Long = 30,
        val stalePeerCleanupIntervalSeconds: Long = 60,
        val stalePeerTimeoutSeconds: Long = 60,
        val fragmentCapacity: Int = 600,
        val loxationCapacity: Int = 200,
        val fragmentSyncIntervalSeconds: Long = 30,
        val loxationSyncIntervalSeconds: Long = 60,
        val messageSyncIntervalSeconds: Long = 15,
        val maxPeersPerSync: Int = 2,
        val syncJitterRatio: Double = 0.3
    )

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

        @Synchronized
        fun allPackets(isFresh: (BlemeshPacket) -> Boolean): List<BlemeshPacket> {
            return order.mapNotNull { key ->
                val pkt = packets[key] ?: return@mapNotNull null
                if (isFresh(pkt)) pkt else null
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

    private fun isPacketFresh(packet: BlemeshPacket): Boolean {
        val nowMs = System.currentTimeMillis()
        val thresholdMs = config.maxMessageAgeSeconds * 1000
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
                if (!isPacketFresh(packet)) return
                if (!isAnnouncementFresh(packet)) {
                    val sender = PeerID.fromLongBE(packet.senderId) ?: return
                    removeState(sender)
                    return
                }
                val idHex = PacketIdUtil.computeIdHex(packet)
                val sender = PeerID.fromLongBE(packet.senderId) ?: return
                latestAnnouncementByPeer[sender] = Pair(idHex, packet)
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
            for (peerID in peersToSync) {
                sendRequestSync(peerID, types = types)
            }
        } else {
            sendRequestSyncBroadcast(types)
        }
    }

    private fun sendRequestSyncBroadcast(types: SyncTypeFlags) {
        val payload = buildGcsPayload(types)
        val pkt = BlemeshPacket(
            version = BlemeshPacket.PROTOCOL_VERSION,
            type = MessageType.REQUEST_SYNC.value,
            ttl = 0,
            timestamp = System.currentTimeMillis(),
            flags = 0,
            senderId = myPeerID.toLongBE(),
            recipientId = BlemeshPacket.BROADCAST_ADDRESS,
            payload = payload,
            signature = null
        )
        delegate?.sendPacket(pkt)
    }

    private fun sendRequestSync(peerID: PeerID, types: SyncTypeFlags) {
        requestSyncManager.registerRequest(peerID)
        val payload = buildGcsPayload(types)
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
        val requestedTypes = request.types ?: SyncTypeFlags.PUBLIC_MESSAGES
        val sorted = GCSFilter.decodeToSortedSet(request.p, request.m, request.data)

        fun mightContain(id: ByteArray): Boolean {
            val bucket = GCSFilter.bucket(id, request.m)
            return GCSFilter.contains(sorted, bucket)
        }

        var sentCount = 0

        if (requestedTypes.contains(MessageType.ANNOUNCE)) {
            for ((_, pair) in latestAnnouncementByPeer) {
                val (idHex, pkt) = pair
                if (!isPacketFresh(pkt)) continue
                val idBytes = hexToBytes(idHex)
                if (!mightContain(idBytes)) {
                    delegate?.sendPacketToPeer(fromPeerID, pkt.withTTL(0))
                    sentCount++
                }
            }
        }

        if (requestedTypes.contains(MessageType.MESSAGE)) {
            for (pkt in messages.allPackets(::isPacketFresh)) {
                val idBytes = PacketIdUtil.computeIdBytes(pkt)
                if (!mightContain(idBytes)) {
                    delegate?.sendPacketToPeer(fromPeerID, pkt.withTTL(0))
                    sentCount++
                }
            }
        }

        if (requestedTypes.contains(MessageType.FRAGMENT)) {
            for (pkt in fragments.allPackets(::isPacketFresh)) {
                val idBytes = PacketIdUtil.computeIdBytes(pkt)
                if (!mightContain(idBytes)) {
                    delegate?.sendPacketToPeer(fromPeerID, pkt.withTTL(0))
                    sentCount++
                }
            }
        }

        if (requestedTypes.contains(MessageType.LOXATION_ANNOUNCE)) {
            for (pkt in loxationPackets.allPackets(::isPacketFresh)) {
                val idBytes = PacketIdUtil.computeIdBytes(pkt)
                if (!mightContain(idBytes)) {
                    delegate?.sendPacketToPeer(fromPeerID, pkt.withTTL(0))
                    sentCount++
                }
            }
        }

        if (sentCount > 0) {
            Log.d(TAG, "Sent $sentCount packets to ${fromPeerID.rawValue.take(8)} in response to REQUEST_SYNC")
        }
    }

    private fun buildGcsPayload(types: SyncTypeFlags): ByteArray {
        val candidates = mutableListOf<BlemeshPacket>()
        if (types.contains(MessageType.ANNOUNCE)) {
            for ((_, pair) in latestAnnouncementByPeer) {
                if (isPacketFresh(pair.second)) candidates.add(pair.second)
            }
        }
        if (types.contains(MessageType.MESSAGE)) {
            candidates.addAll(messages.allPackets(::isPacketFresh))
        }
        if (types.contains(MessageType.FRAGMENT)) {
            candidates.addAll(fragments.allPackets(::isPacketFresh))
        }
        if (types.contains(MessageType.LOXATION_ANNOUNCE)) {
            candidates.addAll(loxationPackets.allPackets(::isPacketFresh))
        }

        if (candidates.isEmpty()) {
            val p = GCSFilter.deriveP(config.gcsTargetFpr)
            return RequestSyncPacket(p = p, m = 1, data = ByteArray(0), types = types).encode()
        }

        candidates.sortByDescending { it.timestamp }

        val p = GCSFilter.deriveP(config.gcsTargetFpr)
        val nMax = GCSFilter.estimateMaxElementsForSize(config.gcsMaxBytes, p)
        val cap = when {
            types == SyncTypeFlags.FRAGMENT -> config.fragmentCapacity.coerceAtLeast(1)
            types == SyncTypeFlags.LOXATION_ANNOUNCE -> config.loxationCapacity.coerceAtLeast(1)
            else -> config.seenCapacity.coerceAtLeast(1)
        }
        val takeN = minOf(candidates.size, nMax, cap)
        if (takeN <= 0) {
            return RequestSyncPacket(p = p, m = 1, data = ByteArray(0), types = types).encode()
        }

        val ids = candidates.take(takeN).map { PacketIdUtil.computeIdBytes(it) }
        val params = GCSFilter.buildFilter(ids, config.gcsMaxBytes, config.gcsTargetFpr)
        val mVal = if (params.m <= 0L) 1 else params.m
        return RequestSyncPacket(p = params.p, m = mVal, data = params.data, types = types).encode()
    }

    private fun performPeriodicMaintenance() {
        cleanupExpiredMessages()
        cleanupStaleAnnouncementsIfNeeded()
        requestSyncManager.cleanup()

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
        latestAnnouncementByPeer.remove(peerID)
        val senderLong = peerID.toLongBE()
        messages.remove { it.senderId == senderLong }
        fragments.remove { it.senderId == senderLong }
        loxationPackets.remove { it.senderId == senderLong }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }
}
