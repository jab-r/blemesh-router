package com.blemesh.router.mesh

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.blemesh.router.model.AnnouncementData
import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.MessageType
import com.blemesh.router.model.PeerID
import com.blemesh.router.protocol.BinaryProtocol
import com.blemesh.router.protocol.BlemeshProtocol
import com.blemesh.router.router.LocalIdentity
import com.blemesh.router.sync.GossipSyncManager
import com.blemesh.router.sync.RequestSyncPacket
import com.blemesh.router.util.MessageDeduplicator
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * BLE Mesh Service for the router.
 *
 * Handles BLE scanning, advertising, GATT server/client connections, and packet
 * send/receive over the mesh. Stripped down from loxation-android BLEMeshService
 * to focus on packet relay (no Noise encryption, no UI, no Nostr).
 *
 * The router participates in the mesh as a relay node:
 * - Scans for and connects to BLE mesh peripherals
 * - Advertises as a BLE mesh peripheral for others to connect
 * - Receives packets and forwards them to the packet handler
 * - Sends packets to connected BLE peers
 */
class BleMeshService(
    private val context: Context,
    private val identity: LocalIdentity.RouterIdentity,
    private val scope: CoroutineScope
) {
    val myPeerID: PeerID = identity.peerID
    private val myPeerIdLong = identity.peerID.toLongBE()

    companion object {
        private const val TAG = "BleMeshService"

        val SERVICE_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5D")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // ATT default until the link negotiates a larger MTU (onMtuChanged on
        // either role). Assuming a big MTU before negotiation produces notifies
        // the peer's stack silently truncates.
        private const val DEFAULT_MTU = 23
        // Field-proven request value from loxation-android.
        private const val REQUEST_MTU = 247
        private const val SCAN_RESTART_INTERVAL_MS = 30_000L
        private const val RSSI_POLL_INTERVAL_MS = 15_000L
        private const val ANNOUNCE_INTERVAL_MS = 30_000L

        // GATT write serialization (mirrors loxation-android writeChunkedWWR):
        // Android fails a writeCharacteristic()/notify issued while one is
        // pending (returns false, frame silently lost), so writes per
        // connection are serialized with pacing and retry.
        private const val WRITE_LOCK_TIMEOUT_MS = 500L
        private const val GATT_WRITE_PACING_MS = 8L
        private const val GATT_WRITE_RETRY_DELAY_MS = 20L
        private const val GATT_WRITE_MAX_RETRIES = 3

        // Store-and-forward bounds.
        // Per-peer ring depth: enough for a noise handshake (3-4 frames) plus a
        // few queued messages while the peer is suspended.
        private const val SNF_MAX_PER_PEER = 32
        // Drop after 60s — beyond that the originator's app-level timeout has
        // almost certainly fired and replay would be misleading.
        private const val SNF_MAX_AGE_MS = 60_000L
        // Sweep idle buffers (peer never reconnects) on this cadence.
        private const val SNF_SWEEP_INTERVAL_MS = 15_000L

        // Approximate BlemeshPacket header overhead (version+type+ttl+flags+ts+payloadLen+sender+recipient)
        private const val BLEMESH_PACKET_OVERHEAD = 30
        // FRAGMENT packet payload header: [id:8][index:2][total:2][type:1]
        private const val FRAGMENT_HEADER_BYTES = 13
        // Inter-fragment pacing to avoid overflowing peer RX buffers on WRITE_NO_RESPONSE.
        private const val FRAGMENT_PACE_DELAY_MS = 6L
        // Packets parked per connection while the link is still at the
        // 23-byte default MTU (a fragment frame's fixed overhead alone
        // exceeds that budget). Flushed by onMtuChanged.
        private const val PENDING_MTU_QUEUE_MAX = 16
    }

    /**
     * Callback for packets received from the BLE mesh.
     */
    interface PacketListener {
        /** Called when a raw packet is received from a BLE peer. */
        fun onPacketReceived(packet: BlemeshPacket, fromAddress: String)

        /** Called when a new BLE peer is discovered. */
        fun onPeerDiscovered(peerID: PeerID, address: String)

        /** Called when a BLE peer disconnects. */
        fun onPeerDisconnected(peerID: PeerID, address: String)
    }

    var packetListener: PacketListener? = null

    // BLE state
    private val bluetoothManager by lazy { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }
    private var bleScanner: BluetoothLeScanner? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null
    // Centrals that enabled notifications via CCCD. Notifying a central that
    // never subscribed violates GATT and confuses reference stacks.
    private val subscribedCentrals = CopyOnWriteArraySet<BluetoothDevice>()
    private var announceJob: Job? = null

    // Connected peers: BLE address -> connection state
    private val connectedPeers = ConcurrentHashMap<String, PeerConnection>()
    // BLE address -> PeerID mapping (populated from ANNOUNCE packets)
    private val addressToPeerID = ConcurrentHashMap<String, PeerID>()
    private val peerIdToAddress = ConcurrentHashMap<PeerID, String>()
    // BLE address -> last observed RSSI (dBm). Updated from scan results and
    // from periodic readRemoteRssi() on client-side GATT connections. Stays
    // populated after the scanner stops surfacing the address.
    private val addressToRssi = ConcurrentHashMap<String, Int>()
    private var rssiPollJob: Job? = null

    // Peers that have, at some point during this session, sent a direct
    // ANNOUNCE to this router (i.e., have a strong-enough BLE link to reach us
    // at MAX_TTL). Membership is the gate for store-and-forward eligibility:
    // we don't buffer packets for peers we've never directly served, since
    // those flow only over the bridge.
    private val knownLocalPeers: MutableSet<PeerID> = ConcurrentHashMap.newKeySet()

    // All currently live BLE addresses observed for a given peer. iOS clients
    // routinely surface as TWO simultaneous connections to us (server-side
    // accept + client-side dial, or RPA + identity-resolved), and the address
    // bounces sub-second under reconnect churn. Writing to a single "latest"
    // address often hits the leg that just dropped; writing to every live leg
    // is what reliably reaches the phone.
    private val peerToLiveAddresses: ConcurrentHashMap<PeerID, MutableSet<String>> = ConcurrentHashMap()

    // Store-and-forward buffer: PeerID -> FIFO of (packet, enqueue-ts).
    // Holds directed packets for a known local peer while it is briefly
    // unreachable (iOS background suspend / RPA reconnect). Replayed on the
    // next direct ANNOUNCE from that peer.
    private data class PendingPacket(val packet: BlemeshPacket, val enqueuedAtMs: Long)
    private val pendingByPeer = ConcurrentHashMap<PeerID, ArrayDeque<PendingPacket>>()
    private var snfSweepJob: Job? = null

    private val deduplicator = MessageDeduplicator()
    private val fragmentationManager = BleMeshFragmentationManager()
    private val reassemblyBuffer = BleMeshFragmentationManager.ReassemblyBuffer()

    // All server-leg notifies share the single serverCharacteristic object;
    // the per-connection writeLock can't protect it. value-set + notify must
    // be atomic across connections or central A receives B's frame.
    private val serverNotifyLock = Any()

    private var scanJob: Job? = null
    private var isRunning = false

    // Gossip sync
    val gossipSyncManager = GossipSyncManager(
        myPeerID = myPeerID,
        scope = scope
    )

    data class PeerConnection(
        val address: String,
        val gatt: BluetoothGatt? = null,
        val mtu: Int = DEFAULT_MTU,
        var characteristic: BluetoothGattCharacteristic? = null,
        // Serializes GATT writes per connection. copy() carries the same
        // instance forward, so the lock survives mtu/characteristic updates.
        val writeLock: Semaphore = Semaphore(1),
        // Packets too large for the pre-negotiation ATT budget, parked until
        // onMtuChanged flushes them. Same carried-by-copy() semantics as
        // writeLock.
        val pendingUntilMtu: MutableList<BlemeshPacket> =
            java.util.Collections.synchronizedList(mutableListOf())
    )

    // --- Lifecycle ---

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Starting BLE mesh service")

        gossipSyncManager.delegate = gossipDelegate
        gossipSyncManager.start()

        startAdvertising()
        startGattServer()
        startScanning()
        startRssiPolling()
        startSnfSweep()
        startAnnouncing()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        Log.i(TAG, "Stopping BLE mesh service")

        gossipSyncManager.stop()
        stopAnnouncing()
        stopScanning()
        stopRssiPolling()
        stopSnfSweep()
        stopAdvertising()
        stopGattServer()
        disconnectAll()
        pendingByPeer.clear()
        knownLocalPeers.clear()
        peerToLiveAddresses.clear()
    }

    private fun startRssiPolling() {
        rssiPollJob?.cancel()
        rssiPollJob = scope.launch {
            while (isActive && isRunning) {
                delay(RSSI_POLL_INTERVAL_MS)
                for ((_, connection) in connectedPeers) {
                    val gatt = connection.gatt ?: continue
                    try { gatt.readRemoteRssi() } catch (_: SecurityException) { } catch (_: Exception) { }
                }
            }
        }
    }

    private fun stopRssiPolling() {
        rssiPollJob?.cancel()
        rssiPollJob = null
    }

    // --- Store-and-forward ---

    /**
     * Buffer a directed packet for a known-local peer that is currently
     * unreachable. No-op for broadcasts, ineligible types, or peers we've
     * never directly served. The oldest entries in the per-peer ring are
     * evicted when the cap is hit.
     */
    private fun maybeBufferForLater(packet: BlemeshPacket) {
        if (packet.isBroadcast) return
        if (!MessageType.isStoreAndForwardEligible(packet.type)) return
        val recipient = packet.recipientPeerID() ?: return
        if (recipient !in knownLocalPeers) return
        if (peerIdToAddress.containsKey(recipient)) return // currently reachable; broadcast will deliver

        val now = System.currentTimeMillis()
        val queue = pendingByPeer.computeIfAbsent(recipient) { ArrayDeque() }
        val size: Int
        synchronized(queue) {
            // Drop aged-out entries while we're touching it.
            while (queue.isNotEmpty() && now - queue.first().enqueuedAtMs > SNF_MAX_AGE_MS) {
                queue.removeFirst()
            }
            if (queue.size >= SNF_MAX_PER_PEER) queue.removeFirst()
            queue.addLast(PendingPacket(packet, now))
            size = queue.size
        }
        Log.d(TAG, "S&F buffer type=0x${"%02x".format(packet.type.toInt() and 0xFF)} for ${recipient.rawValue.take(8)} (q=$size)")
    }

    /**
     * Called when a peer freshly announces. Replays any buffered packets to
     * the now-live BLE address. Drops aged entries. No-op if nothing pending.
     */
    private fun flushPendingFor(peerID: PeerID) {
        val queue = pendingByPeer.remove(peerID) ?: return
        val snapshot: List<PendingPacket>
        synchronized(queue) {
            snapshot = queue.toList()
            queue.clear()
        }
        val now = System.currentTimeMillis()
        // Replay off the GATT binder thread: serialized writes pace at ~8ms
        // per frame, and a full ring would stall callback delivery otherwise.
        scope.launch {
            var sent = 0
            var dropped = 0
            for (entry in snapshot) {
                if (now - entry.enqueuedAtMs > SNF_MAX_AGE_MS) {
                    dropped++
                    continue
                }
                sendPacketToAllLegs(peerID, entry.packet)
                sent++
            }
            if (sent > 0 || dropped > 0) {
                Log.i(TAG, "S&F replay ${peerID.rawValue.take(8)}: sent=$sent dropped=$dropped")
            }
        }
    }

    private fun startSnfSweep() {
        snfSweepJob?.cancel()
        snfSweepJob = scope.launch {
            while (isActive && isRunning) {
                delay(SNF_SWEEP_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val emptyKeys = mutableListOf<PeerID>()
                for ((peerID, queue) in pendingByPeer) {
                    synchronized(queue) {
                        while (queue.isNotEmpty() && now - queue.first().enqueuedAtMs > SNF_MAX_AGE_MS) {
                            queue.removeFirst()
                        }
                        if (queue.isEmpty()) emptyKeys += peerID
                    }
                }
                for (k in emptyKeys) pendingByPeer.remove(k)
            }
        }
    }

    private fun stopSnfSweep() {
        snfSweepJob?.cancel()
        snfSweepJob = null
    }

    // --- Identity announce ---

    /**
     * Periodic signed ANNOUNCE so reference peers can bind our BLE address to
     * our PeerID (loxation-android announces every 30s; iOS announces shortly
     * after subscription events). Without it the router is invisible in peer
     * lists and directed traffic to us degrades to broadcast.
     */
    private fun startAnnouncing() {
        announceJob?.cancel()
        announceJob = scope.launch {
            delay(1_000)
            while (isActive && isRunning) {
                sendLocalAnnounce()
                delay(ANNOUNCE_INTERVAL_MS)
            }
        }
    }

    private fun stopAnnouncing() {
        announceJob?.cancel()
        announceJob = null
    }

    private fun sendLocalAnnounce() {
        if (connectedPeers.isEmpty()) return
        val payload = AnnouncementData(
            nickname = identity.nickname,
            noisePublicKey = identity.noisePublicKey,
            signingPublicKey = identity.signingPublicKey
        ).encode()
        if (payload.isEmpty()) return

        val unsigned = BlemeshPacket(
            version = BlemeshPacket.PROTOCOL_VERSION,
            type = MessageType.ANNOUNCE.value,
            ttl = BlemeshPacket.MAX_TTL.toByte(),
            timestamp = System.currentTimeMillis(),
            flags = 0,
            senderId = myPeerID.toLongBE(),
            recipientId = BlemeshPacket.BROADCAST_ADDRESS,
            payload = payload,
            signature = null
        )
        val packet = unsigned.copy(signature = identity.signPacket(unsigned))
        gossipSyncManager.onPublicPacketSeen(packet)
        broadcastPacket(packet)
    }

    // --- Public API ---

    /** Get list of currently connected BLE peer IDs. */
    fun getConnectedPeerIDs(): List<PeerID> {
        return addressToPeerID.values.toList()
    }

    /** Get all connected BLE addresses. */
    fun getConnectedAddresses(): Set<String> {
        return connectedPeers.keys.toSet()
    }

    /** Check if a PeerID is reachable via local BLE mesh. */
    fun isLocalPeer(peerID: PeerID): Boolean {
        return peerIdToAddress.containsKey(peerID)
    }

    /** Last observed RSSI for a connected BLE peer, or null if unknown. */
    fun getPeerRssi(peerID: PeerID): Int? {
        val address = peerIdToAddress[peerID] ?: return null
        return addressToRssi[address]
    }

    /** Send a packet to all connected BLE peers (broadcast). */
    fun broadcastPacket(packet: BlemeshPacket) {
        // Encode once; per-connection work is only MTU sizing.
        val encoded = BinaryProtocol.encode(packet) ?: return
        for ((_, connection) in connectedPeers) {
            sendPacketToConnection(connection, packet, encoded)
        }
    }

    /** Send a packet to a specific BLE peer by PeerID. */
    fun sendPacketToPeer(peerID: PeerID, packet: BlemeshPacket) {
        val address = peerIdToAddress[peerID]
        if (address == null) {
            Log.w(TAG, "No BLE address for peer ${peerID.rawValue.take(8)}, broadcasting instead")
            broadcastPacket(packet)
            return
        }
        val connection = connectedPeers[address] ?: return
        sendPacketToConnection(connection, packet)
    }

    /**
     * Send a packet to every live BLE address we currently have for [peerID].
     * Use this for delivery of directed packets where the recipient may hold
     * multiple simultaneous connections to us (dual-leg). Each phone dedups,
     * so duplicate writes are harmless; missing the live leg is not.
     */
    fun sendPacketToAllLegs(peerID: PeerID, packet: BlemeshPacket): Int {
        val addrs = peerToLiveAddresses[peerID]
        if (addrs.isNullOrEmpty()) {
            // Fall back to the legacy single-address path (and its broadcast
            // fallback for completely-unmapped peers).
            sendPacketToPeer(peerID, packet)
            return 0
        }
        var sent = 0
        // Snapshot to avoid ConcurrentModification mid-iteration.
        val snapshot = synchronized(addrs) { addrs.toList() }
        val encoded = BinaryProtocol.encode(packet) ?: return 0
        for (addr in snapshot) {
            val conn = connectedPeers[addr] ?: continue
            sendPacketToConnection(conn, packet, encoded)
            sent++
        }
        return sent
    }

    /** Send raw pre-encoded data to all connected peers. Caller is responsible for MTU sizing. */
    fun broadcastRaw(data: ByteArray) {
        for ((_, connection) in connectedPeers) {
            writeToConnection(connection, data)
        }
    }

    /** Inject a packet received from WiFi bridge into the local BLE mesh. */
    fun injectPacketFromWifi(packet: BlemeshPacket) {
        // Use the same fragment-aware dedup key as the BLE inbound path so
        // that a FRAGMENT arriving via WiFi and the same FRAGMENT arriving
        // via BLE collide on a single key (sender-ts-type-fragmentID-index).
        if (deduplicator.isDuplicate(buildDeduplicationKey(packet))) return

        val type = "0x%02x".format(packet.type.toInt() and 0xFF)

        // Gossip replays cross the bridge at ttl=0 (the only cross-segment
        // backfill path — see routeBlePacketToBridge). Reference phones gate
        // syncable DATA packets at ttl == 0: unsolicited ones are dropped
        // with a gossip.security warning, so pushing these over BLE is dead
        // airtime that reads as an attack in phone logs. Store them instead;
        // they are replayed on each phone's next registered REQUEST_SYNC to
        // us, which passes the gate. Both phone teams have signed off on
        // store-and-serve as the sole delivery path for these. A syncable
        // type missing from GOSSIP_STORED falls through to the broadcast:
        // a push that may land inside an open sync window beats certain loss.
        if ((packet.ttl.toInt() and 0xFF) == 0 &&
            packet.isBroadcast &&
            MessageType.isGossipStored(packet.type)
        ) {
            gossipSyncManager.onPublicPacketSeen(packet)
            Log.d(TAG, "WiFi→BLE store-only type=$type (ttl=0 replay; served on next sync)")
            return
        }

        val targets = connectedPeers.keys.joinToString(",")
        Log.d(TAG, "WiFi→BLE inject type=$type → [${if (targets.isEmpty()) "no BLE peers" else targets}]")

        // Store-and-forward: if the directed recipient is a known local peer
        // that is currently disconnected, buffer for replay on reconnect. The
        // broadcast below still happens (mesh redundancy); the buffer is a
        // belt-and-suspenders backup for the case where no live BLE address
        // exists for the recipient at this moment.
        maybeBufferForLater(packet)

        // Track in gossip
        gossipSyncManager.onPublicPacketSeen(packet)

        // Broadcast to local BLE peers
        broadcastPacket(packet)
    }

    // --- BLE Scanning ---

    private fun startScanning() {
        scanJob?.cancel()
        scanJob = scope.launch {
            while (isActive && isRunning) {
                doStartScan()
                delay(SCAN_RESTART_INTERVAL_MS)
                doStopScan()
                delay(1000) // Brief pause between scan cycles
            }
        }
    }

    private fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
        doStopScan()
    }

    private fun doStartScan() {
        try {
            bleScanner = bluetoothAdapter?.bluetoothLeScanner
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            bleScanner?.startScan(listOf(filter), settings, scanCallback)
            Log.d(TAG, "BLE scan started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE scan permission", e)
        }
    }

    private fun doStopScan() {
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (_: Exception) { }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            addressToRssi[address] = result.rssi
            if (connectedPeers.containsKey(address)) return

            Log.d(TAG, "Discovered BLE mesh device: $address (rssi=${result.rssi})")
            connectToDevice(device)
        }
    }

    // --- BLE Advertising ---

    private fun startAdvertising() {
        try {
            bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.d(TAG, "BLE advertising started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE advertise permission", e)
        }
    }

    private fun stopAdvertising() {
        try {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) { }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
        }
    }

    // --- GATT Server ---

    private fun startGattServer() {
        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or
                        BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            // CCCD (0x2902) — without it, an iOS central's setNotifyValue(true)
            // fails silently at the ATT layer and the central never receives
            // notifications from us.
            val cccd = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            characteristic.addDescriptor(cccd)
            service.addCharacteristic(characteristic)
            gattServer?.addService(service)
            serverCharacteristic = characteristic
            Log.d(TAG, "GATT server started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE connect permission", e)
        }
    }

    private fun stopGattServer() {
        try {
            gattServer?.close()
        } catch (_: Exception) { }
        gattServer = null
        serverCharacteristic = null
        subscribedCentrals.clear()
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT server: peer connected $address")
                connectedPeers.putIfAbsent(address, PeerConnection(address = address))
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT server: peer disconnected $address")
                subscribedCentrals.filter { it.address == address }
                    .forEach { subscribedCentrals.remove(it) }
                handleDisconnection(address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            connectedPeers[device.address]?.let {
                val updated = it.copy(mtu = mtu)
                connectedPeers[device.address] = updated
                flushPendingForMtu(updated)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                handleIncomingData(value, device.address)
            }
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (_: SecurityException) { }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Mesh traffic flows via writes/notifies; answer reads with an
            // empty value so the peer doesn't hang into a 30s GATT timeout.
            try {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
            } catch (_: SecurityException) { }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                        value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                if (enable) {
                    val isNew = subscribedCentrals.add(device)
                    Log.d(TAG, "GATT server: ${device.address} subscribed (new=$isNew)")
                    if (isNew) {
                        // Announce shortly after subscription so the central can
                        // bind our address to our PeerID (iOS announces 0.4s
                        // after a central subscribes).
                        scope.launch {
                            delay(400)
                            sendLocalAnnounce()
                        }
                    }
                } else {
                    subscribedCentrals.remove(device)
                    Log.d(TAG, "GATT server: ${device.address} unsubscribed")
                }
            }
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (_: SecurityException) { }
            }
        }
    }

    // --- GATT Client ---

    private fun connectToDevice(device: BluetoothDevice) {
        try {
            device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE connect permission", e)
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT client: connected to $address")
                connectedPeers[address] = PeerConnection(address = address, gatt = gatt)
                // Request a bigger MTU but don't gate discovery on the callback:
                // if requestMtu fails or onMtuChanged never fires, the link must
                // still come up (writes just stay at the 20-byte default budget).
                try {
                    gatt.requestMtu(REQUEST_MTU)
                } catch (_: SecurityException) { }
                try {
                    gatt.discoverServices()
                } catch (_: SecurityException) { }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT client: disconnected from $address")
                handleDisconnection(address)
                try { gatt.close() } catch (_: Exception) { }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val address = gatt.device.address
            connectedPeers[address]?.let {
                val updated = it.copy(mtu = mtu)
                connectedPeers[address] = updated
                flushPendingForMtu(updated)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(SERVICE_UUID) ?: return
            val char = service.getCharacteristic(CHARACTERISTIC_UUID) ?: return

            // Fire-and-forget writes mirror the mesh's notification-shaped traffic.
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            val address = gatt.device.address
            connectedPeers[address]?.let {
                connectedPeers[address] = it.copy(characteristic = char)
            }

            // Enable notifications locally AND on the peer via CCCD, otherwise
            // the peer's GATT server will never send us onCharacteristicChanged.
            try {
                gatt.setCharacteristicNotification(char, true)
                val cccd = char.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                } else {
                    Log.w(TAG, "No CCCD descriptor on characteristic for $address")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "CCCD enable failed: ${e.message}")
            }

            Log.d(TAG, "Services discovered for $address, ready for data")

            // Announce on this fresh link so the peripheral can bind our
            // address to our PeerID (mirrors reference gatt_ready announce).
            scope.launch {
                delay(500)
                sendLocalAnnounce()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                handleIncomingData(value, gatt.device.address)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addressToRssi[gatt.device.address] = rssi
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                val value = characteristic.value ?: return
                handleIncomingData(value, gatt.device.address)
            }
        }
    }

    // --- Packet Handling ---

    /** Re-send packets parked while the connection sat at the default MTU. */
    private fun flushPendingForMtu(connection: PeerConnection) {
        val parked: List<BlemeshPacket>
        synchronized(connection.pendingUntilMtu) {
            if (connection.pendingUntilMtu.isEmpty()) return
            parked = connection.pendingUntilMtu.toList()
            connection.pendingUntilMtu.clear()
        }
        Log.d(TAG, "MTU ${connection.mtu} negotiated for ${connection.address}; sending ${parked.size} deferred packet(s)")
        for (p in parked) sendPacketToConnection(connection, p)
    }

    private fun handleIncomingData(data: ByteArray, fromAddress: String) {
        val packet = BinaryProtocol.decode(data) ?: return
        processIncomingPacket(packet, fromAddress, fromFragment = false)
    }

    /**
     * Full receive pipeline: dedup, fragment reassembly, ANNOUNCE / REQUEST_SYNC
     * handling, gossip tracking, bridge listener, BLE relay.
     *
     * Reassembled inner packets re-enter here with [fromFragment] = true so a
     * fragmented ANNOUNCE or REQUEST_SYNC affects router state exactly like an
     * unfragmented one (both references do this). Inner packets skip the relay
     * step — the raw fragments were already relayed hop-by-hop.
     */
    private fun processIncomingPacket(packet: BlemeshPacket, fromAddress: String, fromFragment: Boolean) {
        // Echoes of our own packets terminate immediately. Outbound sends are
        // not recorded in the deduplicator, so without this guard a phone
        // relaying our fragmented ANNOUNCE back would reassemble into a
        // "fresh" announce that binds myPeerID to the relayer's address and
        // schedules gossip sync-to-self.
        if (packet.senderId == myPeerIdLong) return

        // Dedup. Inner packets get deduped too: this is what stops a packet we
        // injected from WiFi, re-fragmented, and heard echoed back from being
        // re-broadcast over the bridge a second time.
        val packetId = buildDeduplicationKey(packet)
        if (deduplicator.isDuplicate(packetId)) return

        val messageType = MessageType.from(packet.type)

        // Handle fragments. Malformed FRAGMENTs (payload < 13-byte header) are
        // dropped — never fall through to the generic path (matches references).
        // A reassembled inner packet may itself be a FRAGMENT (a relayed
        // FRAGMENT that got re-fragmented for a smaller-MTU leg); it must
        // re-enter reassembly or the outer transfer can never complete.
        if (messageType == MessageType.FRAGMENT) {
            if (packet.payload.size >= 13) {
                handleFragment(packet, fromAddress, fromFragment)
            }
            return
        }

        // Handle ANNOUNCE - extract PeerID mapping
        if (messageType == MessageType.ANNOUNCE) {
            handleAnnounce(packet, fromAddress)
        }

        // Handle REQUEST_SYNC
        if (messageType == MessageType.REQUEST_SYNC) {
            handleRequestSync(packet, fromAddress)
            return
        }

        // Track in gossip sync
        gossipSyncManager.onPublicPacketSeen(packet)

        // Packets addressed to this router itself (e.g. a NOISE_HANDSHAKE a
        // phone initiates toward us after seeing our announce) terminate here:
        // never bridged, never relayed back out.
        val isForMe = !packet.isBroadcast && packet.recipientPeerID() == myPeerID
        if (isForMe) {
            if (messageType != MessageType.ANNOUNCE) {
                Log.d(TAG, "Ignoring packet addressed to router: type=0x%02x".format(packet.type.toInt() and 0xFF))
            }
            return
        }

        // Notify listener
        packetListener?.onPacketReceived(packet, fromAddress)

        // Relay if appropriate
        if (!fromFragment && BlemeshProtocol.shouldRelay(packet)) {
            // Also buffer the relayed copy for the directed recipient if they
            // are a known-local peer that is currently disconnected.
            val relayed = packet.withDecrementedTTL()
            maybeBufferForLater(relayed)
            scope.launch {
                delay(BlemeshProtocol.getRelayJitterMs().toLong())
                broadcastPacketExcluding(relayed, fromAddress)
            }
        }
    }

    private fun handleFragment(packet: BlemeshPacket, fromAddress: String, fromFragment: Boolean) {
        // Wire format: [id:8][index:2 BE][total:2 BE][originalType:1][data:var] = 13-byte header
        if (packet.payload.size < 13) return
        val fragmentId = packet.payload.copyOfRange(0, 8)
        val index = ((packet.payload[8].toInt() and 0xFF) shl 8) or (packet.payload[9].toInt() and 0xFF)
        val total = ((packet.payload[10].toInt() and 0xFF) shl 8) or (packet.payload[11].toInt() and 0xFF)
        val originalType = packet.payload[12]
        val fragmentData = packet.payload.copyOfRange(13, packet.payload.size)

        // Track fragment in gossip for sync
        gossipSyncManager.onPublicPacketSeen(packet)

        val reassembled = reassemblyBuffer.addFragment(packet.senderId, fragmentId, index, total, originalType, fragmentData)
        if (reassembled != null) {
            val innerPacket = BinaryProtocol.decode(reassembled)
            if (innerPacket != null) {
                // The fragments made the hops, not the inner packet: relayers
                // decrement the outer FRAGMENT's TTL while the inner bytes
                // ride untouched. Clamp the inner TTL to the outer's so a
                // relayed-then-reassembled ANNOUNCE can't pass handleAnnounce's
                // ttl==MAX_TTL "direct connection" check and bind the original
                // sender's PeerID to the relayer's address.
                val outerTtl = packet.ttl.toInt() and 0xFF
                val inner = if ((innerPacket.ttl.toInt() and 0xFF) > outerTtl) {
                    innerPacket.withTTL(outerTtl.toByte())
                } else innerPacket
                processIncomingPacket(inner, fromAddress, fromFragment = true)
            }
        }

        // Relay raw fragments only: a nested inner FRAGMENT's carriers were
        // already relayed hop-by-hop as they arrived.
        if (!fromFragment && BlemeshProtocol.shouldRelay(packet)) {
            scope.launch {
                delay(BlemeshProtocol.getRelayJitterMs().toLong())
                broadcastPacketExcluding(packet.withDecrementedTTL(), fromAddress)
            }
        }
    }

    private fun handleAnnounce(packet: BlemeshPacket, fromAddress: String) {
        val announcement = AnnouncementData.decode(packet.payload) ?: return
        val peerID = PeerID.fromNoisePublicKey(announcement.noisePublicKey)

        // Only map direct announces (max TTL = direct connection)
        if ((packet.ttl.toInt() and 0xFF) != BlemeshPacket.MAX_TTL) return

        // Dedup the address→PeerID mapping so getConnectedPeerIDs() reports the
        // peer once. Do NOT close the prior GATT/server connection here: when
        // Android resolves an RPA against its IRK mid-session, or when the peer
        // holds both a server-side and client-side connection to us (dual-leg,
        // common with iOS Loxation), the same physical peer surfaces under two
        // addresses simultaneously. Both are live. Closing one of them kills
        // a working link and triggers a reconnect storm that compounds the
        // loss instead of fixing it.
        //
        // Stale GATT entries left in connectedPeers are harmless on their own:
        // writes to a dead handle return immediately at the Android API layer
        // without delivering anything. The real fix for local→local directed
        // delivery is targeted-write in MeshRouterService, not eviction here.
        val priorAddress = peerIdToAddress[peerID]
        if (priorAddress != null && priorAddress != fromAddress) {
            addressToPeerID.remove(priorAddress)
        }

        val isNewMapping = addressToPeerID.put(fromAddress, peerID) != peerID
        peerIdToAddress[peerID] = fromAddress
        knownLocalPeers.add(peerID)

        // Record this address as a live leg for the peer. The set may already
        // contain it (re-ANNOUNCE from the same leg) or contain another address
        // (dual-leg). Both are fine.
        val legs = peerToLiveAddresses.computeIfAbsent(peerID) {
            java.util.Collections.synchronizedSet(mutableSetOf())
        }
        legs.add(fromAddress)
        // Sweep legs whose link is already gone: a rotated-away address whose
        // disconnect callback hasn't fired yet (or got dropped from
        // addressToPeerID above) must not linger here — a dead leg keeps the
        // peer "reachable" and directed traffic never falls through to the
        // bridge.
        synchronized(legs) {
            legs.retainAll { it == fromAddress || connectedPeers.containsKey(it) }
        }

        // Replay any packets we held for this peer while it was off-air. Even
        // if isNewMapping is false (same address re-ANNOUNCEs us), a buffer
        // can still exist from a transient disconnect → reconnect cycle.
        flushPendingFor(peerID)

        if (isNewMapping) {
            packetListener?.onPeerDiscovered(peerID, fromAddress)
            gossipSyncManager.scheduleInitialSyncToPeer(peerID)
        }
    }

    private fun handleRequestSync(packet: BlemeshPacket, fromAddress: String) {
        val senderPeerID = PeerID.fromLongBE(packet.senderId) ?: return
        val request = RequestSyncPacket.decode(packet.payload) ?: return
        gossipSyncManager.handleRequestSync(senderPeerID, request)
    }

    private fun buildDeduplicationKey(packet: BlemeshPacket): String {
        return if (packet.type == MessageType.FRAGMENT.value && packet.payload.size >= 13) {
            val fragmentID = packet.payload.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            val index = ((packet.payload[8].toInt() and 0xFF) shl 8) or (packet.payload[9].toInt() and 0xFF)
            "${packet.senderId}-${packet.timestamp}-${packet.type}-$fragmentID-$index"
        } else {
            "${packet.senderId}-${packet.timestamp}-${packet.type}"
        }
    }

    private fun broadcastPacketExcluding(packet: BlemeshPacket, excludeAddress: String) {
        val encoded = BinaryProtocol.encode(packet) ?: return
        for ((address, connection) in connectedPeers) {
            if (address != excludeAddress) {
                sendPacketToConnection(connection, packet, encoded)
            }
        }
    }

    /**
     * Encode `packet` for `connection`. If the serialized size exceeds the
     * connection's MTU budget, split into FRAGMENT packets (13-byte fragment
     * payload header: [id:8][index:2 BE][total:2 BE][originalType:1]) matching
     * the loxation-android / loxation-sw wire format.
     *
     * FRAGMENT wrappers carry the original packet's sender, timestamp, and TTL
     * (loxation-sw behavior): relayed/bridged packets keep their hop budget
     * instead of being reborn at MAX_TTL, and local-only (ttl=0) packets stay
     * local even when fragmented.
     */
    private fun sendPacketToConnection(
        connection: PeerConnection,
        packet: BlemeshPacket,
        preEncoded: ByteArray? = null
    ) {
        val encoded = preEncoded ?: BinaryProtocol.encode(packet) ?: return
        val maxSingleWrite = (connection.mtu - 3).coerceIn(20, 185)

        if (encoded.size <= maxSingleWrite) {
            // Off the caller's thread: callers are often GATT binder callbacks
            // and writeToConnection blocks (lock wait + pacing/retry sleeps).
            // Dispatchers.IO because the write machinery genuinely blocks —
            // parking Default-pool threads freezes scans/announces/gossip.
            scope.launch(Dispatchers.IO) {
                writeToConnection(connection, encoded)
            }
            return
        }

        val safeChunk = maxSingleWrite - BLEMESH_PACKET_OVERHEAD - FRAGMENT_HEADER_BYTES
        if (safeChunk < 1) {
            // Pre-negotiation budget (MTU 23 → 20B writable) can't fit even a
            // 1-byte fragment frame (~43B of packet + fragment overhead).
            // Writing anyway emits frames the peer's stack truncates, so park
            // the packet until onMtuChanged flushes the queue.
            synchronized(connection.pendingUntilMtu) {
                if (connection.pendingUntilMtu.size >= PENDING_MTU_QUEUE_MAX) {
                    connection.pendingUntilMtu.removeAt(0)
                }
                connection.pendingUntilMtu.add(packet)
            }
            Log.d(TAG, "Deferred ${encoded.size}B packet for ${connection.address} until MTU negotiation (budget=${maxSingleWrite}B)")
            return
        }
        val fragments = fragmentationManager.split(encoded, packet.type, safeChunk)
        val recipientFlag = if (!packet.isBroadcast) BlemeshPacket.FLAG_HAS_RECIPIENT else 0

        scope.launch(Dispatchers.IO) {
            for (fragment in fragments) {
                val payload = java.io.ByteArrayOutputStream().apply {
                    write(fragment.id)
                    write((fragment.index shr 8) and 0xFF)
                    write(fragment.index and 0xFF)
                    write((fragment.total shr 8) and 0xFF)
                    write(fragment.total and 0xFF)
                    write(fragment.type.toInt() and 0xFF)
                    write(fragment.payload)
                }.toByteArray()

                val fragmentPacket = BlemeshPacket(
                    version = BlemeshPacket.PROTOCOL_VERSION,
                    type = MessageType.FRAGMENT.value,
                    ttl = packet.ttl,
                    timestamp = packet.timestamp,
                    flags = recipientFlag,
                    senderId = packet.senderId,
                    recipientId = packet.recipientId,
                    payload = payload,
                    signature = null
                )
                val fragmentEncoded = BinaryProtocol.encode(fragmentPacket) ?: continue
                writeToConnection(connection, fragmentEncoded)
                delay(FRAGMENT_PACE_DELAY_MS)
            }
        }
    }

    /**
     * Serialized GATT write with retry, mirroring loxation-android's
     * writeChunkedWWR: Android silently fails a write issued while another is
     * pending on the same connection (returns false), so writes are serialized
     * per connection with a pacing delay (WRITE_NO_RESPONSE has no completion
     * callback) and retried with backoff on immediate failure.
     */
    private fun writeToConnection(connection: PeerConnection, data: ByteArray): Boolean {
        // Server-side leg with no CCCD subscription: nothing to deliver to.
        // Bail before the lock/retry machinery burns ~60ms per frame on it.
        if ((connection.gatt == null || connection.characteristic == null) &&
            subscribedCentrals.none { it.address == connection.address }
        ) {
            return false
        }
        val acquired = try {
            connection.writeLock.tryAcquire(WRITE_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        if (!acquired) {
            Log.w(TAG, "Write lock timeout for ${connection.address}, dropping ${data.size}B frame")
            return false
        }
        try {
            for (attempt in 1..GATT_WRITE_MAX_RETRIES) {
                if (performWrite(connection, data)) {
                    try {
                        Thread.sleep(GATT_WRITE_PACING_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    return true
                }
                if (attempt < GATT_WRITE_MAX_RETRIES) {
                    try {
                        Thread.sleep(GATT_WRITE_RETRY_DELAY_MS * attempt)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
            }
            Log.w(TAG, "Write exhausted retries for ${connection.address} (${data.size}B)")
            return false
        } finally {
            connection.writeLock.release()
        }
    }

    private fun performWrite(connection: PeerConnection, data: ByteArray): Boolean {
        val gatt = connection.gatt
        val char = connection.characteristic

        return if (gatt != null && char != null) {
            // Client connection - write to remote characteristic
            try {
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                char.value = data
                gatt.writeCharacteristic(char)
            } catch (e: SecurityException) {
                Log.w(TAG, "Write failed: ${e.message}")
                false
            }
        } else {
            // Server connection - notify, but only centrals that subscribed
            // via CCCD (notifying an unsubscribed central violates GATT).
            val serverChar = serverCharacteristic ?: return false
            val device = subscribedCentrals.firstOrNull { it.address == connection.address }
                ?: return false
            try {
                synchronized(serverNotifyLock) {
                    serverChar.value = data
                    gattServer?.notifyCharacteristicChanged(device, serverChar, false) == true
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Notify failed: ${e.message}")
                false
            }
        }
    }

    private fun handleDisconnection(address: String) {
        connectedPeers.remove(address)
        addressToRssi.remove(address)
        addressToPeerID.remove(address)

        // Prune this address from EVERY peer's live-leg set, not just the
        // peer addressToPeerID currently maps it to: handleAnnounce unmaps a
        // rotated-away address immediately while its leg stays registered
        // until this callback fires, and a leg that is never pruned keeps the
        // peer "reachable" on a dead handle forever.
        for ((peerID, legs) in peerToLiveAddresses) {
            // Removal, emptiness check, and survivor pick must be atomic:
            // both legs of a dual-leg peer can disconnect on separate binder
            // threads, and a synchronizedSet only locks individual calls.
            val removed: Boolean
            val survivor: String?
            synchronized(legs) {
                removed = legs.remove(address)
                survivor = if (removed) legs.firstOrNull() else null
            }
            if (!removed) continue

            if (survivor == null) {
                peerToLiveAddresses.remove(peerID, legs)
                peerIdToAddress.remove(peerID)
                packetListener?.onPeerDisconnected(peerID, address)
                gossipSyncManager.removeAnnouncementForPeer(peerID)
            } else if (peerIdToAddress[peerID] == address) {
                // The "primary" pointer was on the dropped leg. Move it to a
                // surviving leg so sendPacketToPeer() and isLocalPeer() keep
                // working.
                peerIdToAddress[peerID] = survivor
            }
        }
    }

    private fun disconnectAll() {
        for ((address, connection) in connectedPeers) {
            try { connection.gatt?.disconnect() } catch (_: Exception) { }
            try { connection.gatt?.close() } catch (_: Exception) { }
        }
        connectedPeers.clear()
        addressToPeerID.clear()
        peerIdToAddress.clear()
        addressToRssi.clear()
        peerToLiveAddresses.clear()
    }

    // --- Gossip Delegate ---

    private val gossipDelegate = object : GossipSyncManager.Delegate {
        override fun sendPacketToPeer(peerID: PeerID, packet: BlemeshPacket) {
            this@BleMeshService.sendPacketToPeer(peerID, packet)
        }

        override fun getConnectedPeers(): List<PeerID> {
            return getConnectedPeerIDs()
        }
    }
}
