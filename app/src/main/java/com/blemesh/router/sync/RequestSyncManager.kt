package com.blemesh.router.sync

import android.util.Log
import com.blemesh.router.model.PeerID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages outgoing sync requests and validates incoming RSR packets.
 * Prevents unsolicited flood attacks by only accepting responses from peers
 * we actively requested sync from within a 30-second window.
 */
class RequestSyncManager {
    companion object {
        private const val TAG = "RequestSyncManager"
    }

    private val pendingRequests = ConcurrentHashMap<PeerID, Long>()
    private val responseWindowMs = 30_000L

    fun registerRequest(peerID: PeerID) {
        Log.d(TAG, "Registering sync request to ${peerID.rawValue.take(8)}...")
        pendingRequests[peerID] = System.currentTimeMillis()
    }

    fun isValidResponse(from: PeerID): Boolean {
        val requestTime = pendingRequests[from] ?: return false
        val now = System.currentTimeMillis()
        return now - requestTime <= responseWindowMs
    }

    fun cleanup() {
        val now = System.currentTimeMillis()
        val expired = pendingRequests.entries.filter { now - it.value > responseWindowMs }
        expired.forEach { pendingRequests.remove(it.key) }
    }
}
