package com.blemesh.router.router

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Router-emitter side of the cross-repo lockstep fixture
 * fixtures/router_stage_advertisement.json (docs/SUBLOCATION_ADVERTISEMENT.md).
 * Groups `stageHash` (G1) and `encode` (G2) are normative for this repo;
 * `parse`/`effectiveMapping` are the phone clients' (loxation-sw /
 * loxation-android) side of the same file.
 */
class RouterStageAdvertisementTest {

    private val fixture: JSONObject by lazy {
        val candidates = listOf(
            "fixtures/router_stage_advertisement.json",
            "../fixtures/router_stage_advertisement.json",
            "../../fixtures/router_stage_advertisement.json"
        )
        val file = candidates.map(::File).firstOrNull(File::exists)
            ?: fail("fixture not found from ${File(".").absolutePath}").let { throw AssertionError() }
        JSONObject(file.readText())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // --- G1: stageHash vectors ---

    @Test
    fun stageHashVectors() {
        val vectors = fixture.getJSONObject("stageHash").getJSONArray("vectors")
        assertTrue(vectors.length() >= 8)
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val stageId = v.getString("stageId")
            assertEquals(
                "stageHash($stageId)",
                v.getString("sha256Prefix4Hex"),
                BeaconIdentity.stageHash(stageId).toHex()
            )
        }
    }

    @Test
    fun collisionPairCollides() {
        // The fixture's pre-brute-forced pair must actually collide — the
        // phones' ambiguity rule (advert absent, never guess) depends on it.
        assertEquals(
            BeaconIdentity.stageHash("collide-7912").toHex(),
            BeaconIdentity.stageHash("collide-c21f").toHex()
        )
    }

    // --- G2: encode vectors ---

    @Test
    fun encodeVectors() {
        val encode = fixture.getJSONObject("encode").getJSONArray("vectors")
        assertTrue(encode.length() >= 3)
        for (i in 0 until encode.length()) {
            val v = encode.getJSONObject(i)
            val stageId = if (v.isNull("stageId")) null else v.getString("stageId")
            val payload = BeaconIdentity.advertisementPayload(v.getString("beaconUuidHex"), stageId)
            assertEquals(v.getString("name"), v.getString("androidPayloadHex"), payload.toHex())
            // iOS surfaces the same bytes with the little-endian company id
            // prepended; 0xFFFF is byte-order-symmetric.
            assertEquals(v.getString("name"), v.getString("iosMdHex"), "ffff" + payload.toHex())
        }
    }

    @Test
    fun fixtureConstantsMatchEmitter() {
        assertEquals(fixture.getInt("advertisementVersion"), BeaconIdentity.ADVERTISEMENT_VERSION.toInt())
        assertEquals(fixture.getString("companyIdHex"), "%04x".format(BeaconIdentity.COMPANY_ID))
    }

    @Test
    fun uuidIsTrailing16BytesInBothLayouts() {
        // THE invariant (spec §2): every deployed client extracts the beacon id
        // as the last 16 bytes of the payload, in every layout, forever.
        val uuidHex = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val legacy = BeaconIdentity.advertisementPayload(uuidHex, null)
        val v1 = BeaconIdentity.advertisementPayload(uuidHex, "mainstage")
        assertEquals(16, legacy.size)
        assertEquals(21, v1.size)
        assertEquals(uuidHex, legacy.toHex())
        assertEquals(uuidHex, v1.copyOfRange(v1.size - 16, v1.size).toHex())
        assertEquals(0x01, v1[0].toInt())
    }

    // --- §4 validation contract: trim → lowercase → [a-z0-9-]{1,64} ---

    @Test
    fun normalizeAcceptsAndNormalizes() {
        assertEquals("mainstage", BeaconIdentity.normalizeStageId("mainstage"))
        assertEquals("mainstage", BeaconIdentity.normalizeStageId("  MainStage  "))
        assertEquals("main-stage", BeaconIdentity.normalizeStageId("MAIN-STAGE"))
        assertEquals("x", BeaconIdentity.normalizeStageId("x"))
        val max = "a".repeat(64)
        assertEquals(max, BeaconIdentity.normalizeStageId(max))
    }

    @Test
    fun normalizeRejectsInvalid() {
        assertNull(BeaconIdentity.normalizeStageId(""))
        assertNull(BeaconIdentity.normalizeStageId("   "))
        assertNull(BeaconIdentity.normalizeStageId("a".repeat(65)))
        assertNull(BeaconIdentity.normalizeStageId("main_stage"))
        assertNull(BeaconIdentity.normalizeStageId("main stage"))
        assertNull(BeaconIdentity.normalizeStageId("stage!"))
        assertNull(BeaconIdentity.normalizeStageId("stagé"))
        assertNull(BeaconIdentity.normalizeStageId("main.stage"))
    }

    @Test
    fun stageHashRefusesUnnormalizedInput() {
        // Validate-before-hash is MUST-level (spec §3): hashing un-normalized
        // text would silently diverge the id space between router and phones.
        for (bad in listOf("", "MainStage", " mainstage", "a".repeat(65))) {
            try {
                BeaconIdentity.stageHash(bad)
                fail("stageHash accepted un-normalized input: \"$bad\"")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }
}
