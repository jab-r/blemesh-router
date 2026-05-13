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
import com.blemesh.router.sync.GossipSyncManager
import com.blemesh.router.sync.RequestSyncManager
import com.blemesh.router.sync.RequestSyncPacket
import com.blemesh.router.util.MessageDeduplicator
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    private val myPeerID: PeerID,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BleMeshService"

        val SERVICE_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5D")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val DEFAULT_MTU = 512
        private const val SCAN_RESTART_INTERVAL_MS = 30_000L
        private const val RSSI_POLL_INTERVAL_MS = 15_000L

        // Store-and-forward bounds.
        // Per-peer ring depth: enough for a noise handshake (3-4 frames) plus a
        // few queued messages while the peer is suspended.
        private const val SNF_MAX_PER_PEER = 32
        // Drop after 60s — beyond that the originator's app-level timeout has
        // almost certainly fired and replay would be misleading.
        private const val SNF_MAX_AGE_MS = 60_000L
        // Sweep idle buffers (peer never reconnects) on this cadence.
        private const val SNF_SWEEP_INTERVAL_MS = 15_000L

        /**
         * Packet types worth holding for replay. ANNOUNCE / FRAGMENT / sync
         * traffic is excluded — those are either heartbeats (regenerated on
         * the next interval) or already carry their own re-assembly state.
         */
        private val SNF_ELIGIBLE_TYPES: Set<Int> = setOf(
            MessageType.MESSAGE.value.toInt() and 0xFF,
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
        )

        // Approximate BlemeshPacket header overhead (version+type+ttl+flags+ts+payloadLen+sender+recipient)
        private const val BLEMESH_PACKET_OVERHEAD = 30
        // FRAGMENT packet payload header: [id:8][index:2][total:2][type:1]
        private const val FRAGMENT_HEADER_BYTES = 13
        // Inter-fragment pacing to avoid overflowing peer RX buffers on WRITE_NO_RESPONSE.
        private const val FRAGMENT_PACE_DELAY_MS = 6L
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

    private var scanJob: Job? = null
    private var isRunning = false

    // Gossip sync
    private val requestSyncManager = RequestSyncManager()
    val gossipSyncManager = GossipSyncManager(
        myPeerID = myPeerID,
        scope = scope,
        requestSyncManager = requestSyncManager
    )

    data class PeerConnection(
        val address: String,
        val gatt: BluetoothGatt? = null,
        val mtu: Int = DEFAULT_MTU,
        var characteristic: BluetoothGattCharacteristic? = null
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
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        Log.i(TAG, "Stopping BLE mesh service")

        gossipSyncManager.stop()
        stopScanning()
        stopRssiPolling()
        stopSnfSweep()
        stopAdvertising()
        stopGattServer()
        disconnectAll()
        pendingByPeer.clear()
        knownLocalPeers.clear()
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
        val typeInt = packet.type.toInt() and 0xFF
        if (typeInt !in SNF_ELIGIBLE_TYPES) return
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
        var sent = 0
        var dropped = 0
        for (entry in snapshot) {
            if (now - entry.enqueuedAtMs > SNF_MAX_AGE_MS) {
                dropped++
                continue
            }
            sendPacketToPeer(peerID, entry.packet)
            sent++
        }
        if (sent > 0 || dropped > 0) {
            Log.i(TAG, "S&F replay ${peerID.rawValue.take(8)}: sent=$sent dropped=$dropped")
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
        for ((_, connection) in connectedPeers) {
            sendPacketToConnection(connection, packet)
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
            service.addCharacteristic(characteristic)
            gattServer?.addService(service)
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
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT server: peer connected $address")
                connectedPeers.putIfAbsent(address, PeerConnection(address = address))
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT server: peer disconnected $address")
                handleDisconnection(address)
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
                try {
                    gatt.requestMtu(DEFAULT_MTU)
                } catch (_: SecurityException) { }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT client: disconnected from $address")
                handleDisconnection(address)
                try { gatt.close() } catch (_: Exception) { }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val address = gatt.device.address
            connectedPeers[address]?.let {
                connectedPeers[address] = it.copy(mtu = mtu)
            }
            try {
                gatt.discoverServices()
            } catch (_: SecurityException) { }
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

    private fun handleIncomingData(data: ByteArray, fromAddress: String) {
        val packet = BinaryProtocol.decode(data) ?: return

        // Dedup
        val packetId = buildDeduplicationKey(packet)
        if (deduplicator.isDuplicate(packetId)) return

        val messageType = MessageType.from(packet.type)

        // Handle fragments
        if (messageType == MessageType.FRAGMENT && packet.payload.size >= 10) {
            handleFragment(packet, fromAddress)
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

        // Notify listener
        packetListener?.onPacketReceived(packet, fromAddress)

        // Relay if appropriate
        if (BlemeshProtocol.shouldRelay(packet)) {
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

    private fun handleFragment(packet: BlemeshPacket, fromAddress: String) {
        // Wire format: [id:8][index:2 BE][total:2 BE][originalType:1][data:var] = 13-byte header
        if (packet.payload.size < 13) return
        val fragmentId = packet.payload.copyOfRange(0, 8)
        val index = ((packet.payload[8].toInt() and 0xFF) shl 8) or (packet.payload[9].toInt() and 0xFF)
        val total = ((packet.payload[10].toInt() and 0xFF) shl 8) or (packet.payload[11].toInt() and 0xFF)
        val originalType = packet.payload[12]
        val fragmentData = packet.payload.copyOfRange(13, packet.payload.size)

        // Track fragment in gossip for sync
        gossipSyncManager.onPublicPacketSeen(packet)

        val reassembled = reassemblyBuffer.addFragment(fragmentId, index, total, originalType, fragmentData)
        if (reassembled != null) {
            val innerPacket = BinaryProtocol.decode(reassembled)
            if (innerPacket != null) {
                packetListener?.onPacketReceived(innerPacket, fromAddress)
            }
        }

        // Relay fragment
        if (BlemeshProtocol.shouldRelay(packet)) {
            scope.launch {
                delay(BlemeshProtocol.getRelayJitterMs().toLong())
                broadcastPacketExcluding(packet.withDecrementedTTL(), fromAddress)
            }
        }
    }

    private fun handleAnnounce(packet: BlemeshPacket, fromAddress: String) {
        val announcement = AnnouncementData.decode(packet.payload) ?: return
        val peerID = derivePeerID(announcement.noisePublicKey)

        // Only map direct announces (max TTL = direct connection)
        if ((packet.ttl.toInt() and 0xFF) != BlemeshPacket.MAX_TTL) return

        // Evict any prior address for this peer. Loxation/iOS clients rotate
        // their BLE resolvable private address every ~15 min, and dual-leg
        // server+client connections to the same peer can surface under
        // different addresses on Android's stack. Without eviction, the same
        // PeerID accumulates under multiple address keys and getConnectedPeerIDs()
        // reports the peer N times.
        //
        // Critically: also tear down the stale GATT/server connection. Without
        // this, broadcastPacket() iterates connectedPeers and writes to the dead
        // physical link as well as the live one. Those writes succeed at the
        // Android API layer (WRITE_NO_RESPONSE returns immediately) but are
        // silently dropped on-air — the recipient never sees the packet. Most
        // visibly impacts NOISE_HANDSHAKE retries and short encrypted messages
        // sent during the brief window after an address rotation.
        val priorAddress = peerIdToAddress[peerID]
        if (priorAddress != null && priorAddress != fromAddress) {
            addressToPeerID.remove(priorAddress)
            evictStaleConnection(priorAddress, peerID)
        }

        val isNewMapping = addressToPeerID.put(fromAddress, peerID) != peerID
        peerIdToAddress[peerID] = fromAddress
        knownLocalPeers.add(peerID)

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
        for ((address, connection) in connectedPeers) {
            if (address != excludeAddress) {
                sendPacketToConnection(connection, packet)
            }
        }
    }

    /**
     * Encode `packet` for `connection`. If the serialized size exceeds the
     * connection's MTU budget, split into FRAGMENT packets (13-byte fragment
     * payload header: [id:8][index:2 BE][total:2 BE][originalType:1]) matching
     * the loxation-android / loxation-sw wire format.
     *
     * Each FRAGMENT wrapper uses this router's PeerID as sender and MAX_TTL so
     * fragments can be relayed independently; reassembly recovers the inner
     * packet's original sender/TTL.
     */
    private fun sendPacketToConnection(connection: PeerConnection, packet: BlemeshPacket) {
        val encoded = BinaryProtocol.encode(packet) ?: return
        val maxSingleWrite = (connection.mtu - 3).coerceIn(20, 185)

        if (encoded.size <= maxSingleWrite) {
            writeToConnection(connection, encoded)
            return
        }

        val safeChunk = (maxSingleWrite - BLEMESH_PACKET_OVERHEAD - FRAGMENT_HEADER_BYTES).coerceAtLeast(20)
        val fragments = fragmentationManager.split(encoded, packet.type, safeChunk)
        val recipientFlag = if (!packet.isBroadcast) BlemeshPacket.FLAG_HAS_RECIPIENT else 0
        val senderLong = myPeerID.toLongBE()

        scope.launch {
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
                    ttl = BlemeshPacket.MAX_TTL.toByte(),
                    timestamp = System.currentTimeMillis(),
                    flags = recipientFlag,
                    senderId = senderLong,
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

    private fun writeToConnection(connection: PeerConnection, data: ByteArray) {
        val gatt = connection.gatt
        val char = connection.characteristic

        if (gatt != null && char != null) {
            // Client connection - write to remote characteristic
            try {
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                char.value = data
                gatt.writeCharacteristic(char)
            } catch (e: SecurityException) {
                Log.w(TAG, "Write failed: ${e.message}")
            }
        } else {
            // Server connection - notify
            val device = bluetoothAdapter?.getRemoteDevice(connection.address) ?: return
            try {
                val serverChar = gattServer?.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
                if (serverChar != null) {
                    serverChar.value = data
                    gattServer?.notifyCharacteristicChanged(device, serverChar, false)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Notify failed: ${e.message}")
            }
        }
    }

    private fun handleDisconnection(address: String) {
        connectedPeers.remove(address)
        addressToRssi.remove(address)
        val peerID = addressToPeerID.remove(address)
        if (peerID != null) {
            peerIdToAddress.remove(peerID)
            packetListener?.onPeerDisconnected(peerID, address)
            gossipSyncManager.removeAnnouncementForPeer(peerID)
        }
    }

    /**
     * Forcibly close the GATT/server connection for a stale BLE address.
     * Called when a peer reannounces from a new address: the old physical
     * link must stop receiving writes immediately so we don't fan out into
     * a dead radio path.
     */
    private fun evictStaleConnection(address: String, peerID: PeerID) {
        val conn = connectedPeers.remove(address) ?: return
        addressToRssi.remove(address)
        Log.i(TAG, "Evicting stale BLE connection $address for peer ${peerID.rawValue.take(8)}")
        val gatt = conn.gatt
        if (gatt != null) {
            try { gatt.disconnect() } catch (_: SecurityException) {} catch (_: Exception) {}
            try { gatt.close() } catch (_: Exception) {}
        } else {
            // Server-side connection: cancel from the server's side so Android
            // tears down the link instead of waiting for the supervision timeout.
            try {
                val device = bluetoothAdapter?.getRemoteDevice(address)
                if (device != null) gattServer?.cancelConnection(device)
            } catch (_: SecurityException) {} catch (_: Exception) {}
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
    }

    private fun derivePeerID(noisePublicKey: ByteArray): PeerID {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(noisePublicKey)
        val hex = digest.copyOfRange(0, 8).joinToString("") { "%02x".format(it) }
        return PeerID(hex)
    }

    // --- Gossip Delegate ---

    private val gossipDelegate = object : GossipSyncManager.Delegate {
        override fun sendPacket(packet: BlemeshPacket) {
            broadcastPacket(packet)
        }

        override fun sendPacketToPeer(peerID: PeerID, packet: BlemeshPacket) {
            this@BleMeshService.sendPacketToPeer(peerID, packet)
        }

        override fun getConnectedPeers(): List<PeerID> {
            return getConnectedPeerIDs()
        }
    }
}
