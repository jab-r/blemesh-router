package com.blemesh.router.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.blemesh.router.model.PeerID
import java.util.concurrent.ConcurrentHashMap

/**
 * Auto-discovers other routers on the same LAN via mDNS / Network Service
 * Discovery. Publishes our TCP listen port under [SERVICE_TYPE] with the
 * local PeerID hex in a TXT attribute, and resolves the same service from
 * peers as they appear.
 *
 * The caller (typically [com.blemesh.router.router.MeshRouterService])
 * supplies an [onPeerDiscovered] callback that hands the resolved
 * `host:port` to [WifiBridgeTransport.connectToHost]. The TCP transport's
 * post-connect handshake re-validates the remote PeerID; this layer's
 * PeerID-from-TXT is purely a hint used to skip our own publish loopback.
 */
class LanPeerDiscovery(private val context: Context) {

    fun interface PeerCallback {
        fun onDiscovered(host: String, port: Int, remotePeerID: PeerID)
    }

    private val nsdManager: NsdManager? =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Currently-resolving services keyed by service name to drop duplicate resolves. */
    private val resolving = ConcurrentHashMap<String, Boolean>()

    @Volatile private var localPeerID: PeerID? = null
    @Volatile private var callback: PeerCallback? = null
    @Volatile private var running = false

    fun start(localPeerID: PeerID, listenPort: Int, callback: PeerCallback) {
        if (running) return
        val mgr = nsdManager ?: run {
            Log.w(TAG, "NsdManager unavailable; LAN discovery disabled")
            return
        }
        running = true
        this.localPeerID = localPeerID
        this.callback = callback

        acquireMulticastLock()
        register(mgr, localPeerID, listenPort)
        discover(mgr)
    }

    fun stop() {
        if (!running) return
        running = false
        val mgr = nsdManager
        registrationListener?.let {
            try { mgr?.unregisterService(it) } catch (_: Exception) {}
        }
        discoveryListener?.let {
            try { mgr?.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        registrationListener = null
        discoveryListener = null
        resolving.clear()
        releaseMulticastLock()
    }

    // --- Registration ---

    private fun register(mgr: NsdManager, localPeerID: PeerID, listenPort: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "blemesh-${localPeerID.rawValue.take(8)}"
            serviceType = SERVICE_TYPE
            port = listenPort
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAttribute(TXT_PEER_ID, localPeerID.rawValue)
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Registered NSD service '${serviceInfo.serviceName}' on port ${serviceInfo.port}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        try {
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "registerService threw: ${e.message}")
        }
    }

    // --- Discovery ---

    private fun discover(mgr: NsdManager) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD discovery started for $serviceType")
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                val name = serviceInfo.serviceName
                if (resolving.putIfAbsent(name, true) != null) return
                resolveService(mgr, serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                resolving.remove(serviceInfo.serviceName)
            }
        }
        discoveryListener = listener
        try {
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices threw: ${e.message}")
        }
    }

    private fun resolveService(mgr: NsdManager, serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolving.remove(serviceInfo.serviceName)
                if (errorCode != NsdManager.FAILURE_ALREADY_ACTIVE) {
                    Log.d(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                }
            }
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                resolving.remove(resolved.serviceName)
                handleResolved(resolved)
            }
        }
        try {
            mgr.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            resolving.remove(serviceInfo.serviceName)
            Log.w(TAG, "resolveService threw: ${e.message}")
        }
    }

    private fun handleResolved(resolved: NsdServiceInfo) {
        val attrs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            resolved.attributes
        } else null
        val peerHex = attrs?.get(TXT_PEER_ID)?.let { String(it) }
        val remotePeerID = peerHex?.let { PeerID.fromHexString(it) }
        if (remotePeerID == null) {
            Log.d(TAG, "Resolved service ${resolved.serviceName} has no peer_id; skipping")
            return
        }
        if (remotePeerID == localPeerID) return // self-loop
        val host = resolved.host?.hostAddress ?: return
        val port = resolved.port
        if (port <= 0) return

        Log.i(TAG, "Discovered router peer ${remotePeerID.rawValue.take(8)} at $host:$port")
        callback?.onDiscovered(host, port, remotePeerID)
    }

    // --- Multicast lock ---

    private fun acquireMulticastLock() {
        val mgr = wifiManager ?: return
        try {
            val lock = mgr.createMulticastLock("blemesh-router-mdns")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (e: Exception) {
            Log.d(TAG, "MulticastLock unavailable: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    companion object {
        private const val TAG = "LanPeerDiscovery"
        private const val SERVICE_TYPE = "_blemesh-router._tcp."
        private const val TXT_PEER_ID = "peer_id"
    }
}
