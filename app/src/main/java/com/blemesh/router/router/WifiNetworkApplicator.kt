package com.blemesh.router.router

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log

/**
 * Asks Android to associate with a specific SSID via WifiNetworkSuggestion
 * (API 29+). Removes any prior suggestion for the same SSID before adding.
 *
 * On API < 29 this is a no-op; the user must associate manually via Android
 * Settings. mDNS-based router discovery still works after manual association.
 *
 * The first time a suggestion is added Android shows the user a system
 * notification asking them to approve. Once approved the device auto-connects
 * whenever the network is in range.
 */
class WifiNetworkApplicator(private val context: Context) {

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun apply(credentials: WifiCredentials) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "WifiNetworkSuggestion needs API 29; user must associate manually")
            return
        }
        val mgr = wifiManager ?: return
        if (!credentials.isConfigured) return

        // Remove any existing suggestion for this app first; the API rejects
        // duplicates (status NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE).
        mgr.removeNetworkSuggestions(emptyList())

        val builder = WifiNetworkSuggestion.Builder().setSsid(credentials.ssid)
        if (credentials.psk.isNotEmpty()) {
            builder.setWpa2Passphrase(credentials.psk)
        }
        val suggestion = builder.build()

        val status = mgr.addNetworkSuggestions(listOf(suggestion))
        if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Log.w(TAG, "addNetworkSuggestions failed: status=$status")
        } else {
            Log.i(TAG, "Wi-Fi suggestion installed for SSID '${credentials.ssid}'")
        }
    }

    fun clear() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        wifiManager?.removeNetworkSuggestions(emptyList())
    }

    companion object {
        private const val TAG = "WifiNetworkApplicator"
    }
}
