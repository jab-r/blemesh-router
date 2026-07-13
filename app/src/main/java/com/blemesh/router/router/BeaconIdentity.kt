package com.blemesh.router.router

import android.content.Context
import android.util.Log
import java.security.MessageDigest
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
 *
 * Also owns the optional stage (sublocation) binding advertised alongside the
 * id — docs/SUBLOCATION_ADVERTISEMENT.md, fixture
 * fixtures/router_stage_advertisement.json (groups `stageHash` + `encode` are
 * normative for this emitter). With a stage configured the scan-response
 * payload switches from the legacy `[uuid:16]` layout to v1
 * `[ver=0x01][stageHash:4][uuid:16]`; the uuid stays the TRAILING 16 bytes in
 * every version, forever, so deployed clients' suffix-16 membership extraction
 * never breaks.
 */
object BeaconIdentity {
    private const val TAG = "BeaconIdentity"
    private const val PREFS_FILE = "router"
    private const val KEY_BEACON_UUID = "beacon_uuid"
    private const val KEY_SUBLOCATION_ID = "sublocation_id"

    /** Company id for the scan-response manufacturer-data element (0xFFFF = no-company / testing). */
    const val COMPANY_ID = 0xFFFF

    /**
     * Stage-advertisement layout version emitted when a stage is configured.
     * 0x00 is reserved-invalid and never emitted; any future layout revision
     * extends BETWEEN the version byte and the trailing uuid, never after it.
     */
    const val ADVERTISEMENT_VERSION: Byte = 0x01

    /** Declared sublocation-id constraint, identical on all three platforms. */
    private val STAGE_ID_REGEX = Regex("^[a-z0-9-]{1,64}$")
    private const val STAGE_HASH_BYTES = 4

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

    // --- Stage (sublocation) binding — docs/SUBLOCATION_ADVERTISEMENT.md ---

    /**
     * Trim → lowercase → validate `[a-z0-9-]{1,64}`. Returns the normalized
     * stage id, or null if the input is empty or invalid after normalization.
     * Callers distinguish "clear" (empty input) from "refuse" (non-empty but
     * invalid) before calling; both come back null here.
     */
    fun normalizeStageId(input: String): String? {
        val normalized = input.trim().lowercase()
        return if (STAGE_ID_REGEX.matches(normalized)) normalized else null
    }

    /**
     * First 4 bytes of SHA256(UTF-8(stageId)), compared bytewise on the phone
     * side. Requires an already-normalized id — the advertisement must never
     * carry a hash of un-normalized text, or the id space diverges between
     * router and phones.
     */
    fun stageHash(stageId: String): ByteArray {
        require(STAGE_ID_REGEX.matches(stageId)) { "stage id not normalized/valid" }
        return MessageDigest.getInstance("SHA-256")
            .digest(stageId.toByteArray(Charsets.UTF_8))
            .copyOf(STAGE_HASH_BYTES)
    }

    /**
     * The persisted stage id, or null when unconfigured. A stored value that
     * fails validation (never written by [saveStageId], but defensively
     * possible) is treated as unconfigured — an invalid advertisement must be
     * ABSENT, never emitted.
     */
    fun loadStageId(context: Context): String? {
        val stored = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_SUBLOCATION_ID, null) ?: return null
        val normalized = normalizeStageId(stored)
        if (normalized == null) Log.w(TAG, "Ignoring invalid persisted stage id")
        return normalized
    }

    /** Persists a normalized stage id; null clears (back to the legacy layout). */
    fun saveStageId(context: Context, stageId: String?) {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        if (stageId == null) {
            prefs.edit().remove(KEY_SUBLOCATION_ID).apply()
            return
        }
        require(STAGE_ID_REGEX.matches(stageId)) { "stage id not normalized/valid" }
        prefs.edit().putString(KEY_SUBLOCATION_ID, stageId).apply()
    }

    /**
     * The scan-response manufacturer-data payload (the bytes handed to
     * `addManufacturerData(COMPANY_ID, …)` — Android prepends the company id
     * on air itself):
     *
     *  - no stage:  `[uuid:16]` — legacy plain-beacon layout (unconfigured / spare)
     *  - stage set: `[ver=0x01][stageHash:4][uuid:16]` — v1
     *
     * The uuid is the trailing 16 bytes in both layouts (fixture group
     * `encode`).
     */
    fun advertisementPayload(beaconId: String, stageId: String?): ByteArray {
        val uuid = manufacturerBytes(beaconId)
        if (stageId == null) return uuid
        return byteArrayOf(ADVERTISEMENT_VERSION) + stageHash(stageId) + uuid
    }

    private fun Char.isHex(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
