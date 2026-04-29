package com.blemesh.router.router

import android.content.Context
import com.blemesh.router.model.PeerID

/**
 * Persistent 8-byte PeerID for this router.
 *
 * Generated once on first launch and stored in SharedPreferences so the router
 * keeps the same identity across restarts. Without this, every restart looks
 * like a brand-new router to the network and gossip continuity breaks.
 */
object LocalIdentity {
    private const val PREFS_FILE = "router"
    private const val KEY_PEER_ID = "peer_id"

    fun load(context: Context): PeerID {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        prefs.getString(KEY_PEER_ID, null)?.let { stored ->
            PeerID.fromHexString(stored)?.let { return it }
        }
        val fresh = PeerID.generate()
        prefs.edit().putString(KEY_PEER_ID, fresh.rawValue).apply()
        return fresh
    }
}
