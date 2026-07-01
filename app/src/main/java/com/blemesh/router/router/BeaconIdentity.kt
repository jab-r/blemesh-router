package com.blemesh.router.router

import android.content.Context
import android.util.Log
import java.security.SecureRandom

/**
 * Persistent beacon identity for this router.
 *
 * The router advertises a fixed, server-registerable id in its BLE scan
 * response (generic manufacturer data, company id 0xFFFF) so the existing
 * loxation client beacon scanners (loxation-android / loxation-sw) can sight it
 * with no client-side change. See docs/beacon-mode.md.
 *
 * The id is 16 random bytes rendered as 32 lowercase hex chars — the same
 * `DeviceID.rawValue` shape loxation uses for its "mobile beacon" identity, and
 * exactly what both scanners' generic path normalizes our on-air element to
 * (last 16 bytes -> dashed UUID -> lowercased, dashes stripped). So the string
 * shown in the config screen == the id an operator registers == the id a
 * scanner reports.
 *
 * Independent of the mesh PeerID ([LocalIdentity]): it lives under its own prefs
 * key, so re-keying the Noise/signing identity never changes the registered
 * beacon, which represents the router's fixed install location.
 */
object BeaconIdentity {
    private const val TAG = "BeaconIdentity"
    private const val PREFS_FILE = "router"
    private const val KEY_BEACON_UUID = "beacon_uuid"

    /** Company id for the scan-response manufacturer-data element (0xFFFF = no-company / testing). */
    const val COMPANY_ID = 0xFFFF

    private const val ID_BYTES = 16
    private const val ID_HEX_LENGTH = ID_BYTES * 2

    /**
     * Loads the persisted beacon id (32 lowercase hex chars), generating and
     * storing one from [SecureRandom] on first launch.
     */
    fun load(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_BEACON_UUID, null)
        if (stored != null && stored.length == ID_HEX_LENGTH && stored.all { it.isHex() }) {
            return stored.lowercase()
        }
        val bytes = ByteArray(ID_BYTES).also(SecureRandom()::nextBytes)
        val id = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_BEACON_UUID, id).apply()
        Log.i(TAG, "Generated new beacon id")
        return id
    }

    /** The 16-byte on-air payload: the id hex-decoded in natural order. */
    fun manufacturerBytes(id: String): ByteArray =
        id.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Display-only: the id as an uppercase dashed UUID (8-4-4-4-12). Not the
     * registration value — the server matches the plain 32-hex [load] form.
     */
    fun asDashedUuid(id: String): String {
        val u = id.uppercase()
        return "${u.substring(0, 8)}-${u.substring(8, 12)}-${u.substring(12, 16)}-" +
            "${u.substring(16, 20)}-${u.substring(20, 32)}"
    }

    private fun Char.isHex(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
