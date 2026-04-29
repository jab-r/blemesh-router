package com.blemesh.router.router

import android.content.Context

/**
 * Persistent SSID/PSK for the router-backbone Wi-Fi the device should
 * associate with. Stored alongside the local PeerID in the "router"
 * SharedPreferences file.
 *
 * The router does not enforce association at the kernel level — it asks
 * via [WifiNetworkApplicator] (API 29+) and the user confirms once. On
 * older devices the user has to associate manually via Android settings;
 * mDNS-based peer discovery still works once associated.
 */
data class WifiCredentials(val ssid: String, val psk: String) {

    val isConfigured: Boolean get() = ssid.isNotBlank()

    companion object {
        private const val PREFS_FILE = "router"
        private const val KEY_SSID = "wifi_ssid"
        private const val KEY_PSK = "wifi_psk"

        fun load(context: Context): WifiCredentials {
            val p = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            return WifiCredentials(
                ssid = p.getString(KEY_SSID, "") ?: "",
                psk = p.getString(KEY_PSK, "") ?: ""
            )
        }

        fun save(context: Context, credentials: WifiCredentials) {
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SSID, credentials.ssid)
                .putString(KEY_PSK, credentials.psk)
                .apply()
        }

        fun clear(context: Context) {
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_SSID)
                .remove(KEY_PSK)
                .apply()
        }
    }
}
