package com.blemesh.router.router

import android.content.Context
import com.blemesh.router.model.PeerID
import com.blemesh.router.transport.RouterTransport
import com.blemesh.router.transport.WifiBridgeTransport
import com.blemesh.router.transport.wifiaware.WifiAwareTransport
import com.blemesh.router.transport.wifidirect.WifiDirectTransport
import kotlinx.coroutines.CoroutineScope

/**
 * Builds the list of router-to-router transports to enable.
 *
 * Default policy: enable every transport whose hardware capability is
 * present. The bridge dedup at [MeshRouterService.bridgeDeduplicator]
 * prevents double-delivery if a packet arrives over more than one
 * transport. Hardware that misbehaves with concurrent Aware+Direct can
 * be handled by a future build-time toggle here.
 *
 * The TCP-over-LAN transport is always included — it has no hardware
 * dependency beyond plain Wi-Fi networking.
 */
object TransportSelector {

    fun build(
        context: Context,
        localPeerID: PeerID,
        scope: CoroutineScope
    ): List<RouterTransport> {
        val transports = mutableListOf<RouterTransport>()
        // TCP-over-LAN: always available, useful when routers share infrastructure Wi-Fi.
        transports.add(WifiBridgeTransport(scope, localPeerID))
        // Wi-Fi Aware: API 26+, requires hardware. Preferred for ad-hoc N:N mesh.
        if (WifiAwareTransport.isAvailable(context)) {
            transports.add(WifiAwareTransport(context, localPeerID))
        }
        // Wi-Fi Direct: broader hardware support but single-group topology.
        if (WifiDirectTransport.isAvailable(context)) {
            transports.add(WifiDirectTransport(context, localPeerID))
        }
        return transports
    }
}
