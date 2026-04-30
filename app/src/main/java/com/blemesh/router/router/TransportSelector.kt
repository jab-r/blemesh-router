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
 * Policy:
 *  - TCP-over-LAN is always included (no hardware dependency beyond plain
 *    Wi-Fi networking; useful whenever routers share infrastructure Wi-Fi).
 *  - Wi-Fi Aware is included when hardware supports it. Strictly better than
 *    Direct on hardware that has both — clean N:N mesh, no Group Owner
 *    bottleneck, deterministic role assignment.
 *  - Wi-Fi Direct is included only as a fallback, when Aware is unavailable.
 *    On Pixel chipsets, running Aware and Direct concurrently causes the
 *    P2P stack's NAN-iface allocation to fail every discovery cycle (logged
 *    as `HalDevMgr E createIfaceIfPossible: Failed to create iface for
 *    ifaceType=3` every 30 s) because the NAN iface is already held by Aware.
 *    Skipping Direct in that case is the cleanest fix.
 *
 * Cross-transport packet duplicates are dropped by [MeshRouterService]'s
 * bridge dedup, so a router reachable via multiple transports won't deliver
 * twice.
 */
object TransportSelector {

    fun build(
        context: Context,
        localPeerID: PeerID,
        scope: CoroutineScope
    ): List<RouterTransport> {
        val transports = mutableListOf<RouterTransport>()
        // TCP-over-LAN: always available.
        transports.add(WifiBridgeTransport(scope, localPeerID))

        val awareAvailable = WifiAwareTransport.isAvailable(context)
        if (awareAvailable) {
            transports.add(WifiAwareTransport(context, localPeerID))
        } else if (WifiDirectTransport.isAvailable(context)) {
            // Direct only as a fallback when Aware is missing.
            transports.add(WifiDirectTransport(context, localPeerID))
        }
        return transports
    }
}
