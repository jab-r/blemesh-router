package com.blemesh.router.model

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The router-to-router gossip control frames must never cross the WiFi bridge or
 * leak from BLE onto the backbone — same policy as PING/PONG/CAPS. They are
 * dispatched (and terminated) before the isBridgeable gate, but the gate is the
 * defensive backstop for the BLE→bridge direction.
 */
class MessageTypeBackboneGossipTest {

    @Test
    fun routerSync_isNotBridgeable() {
        assertFalse(MessageType.isBridgeable(MessageType.ROUTER_SYNC.value))
    }

    @Test
    fun routerSyncData_isNotBridgeable() {
        assertFalse(MessageType.isBridgeable(MessageType.ROUTER_SYNC_DATA.value))
    }
}
