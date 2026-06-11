package com.blemesh.router.router

import android.content.Context
import android.util.Base64
import android.util.Log
import com.blemesh.router.model.BlemeshPacket
import com.blemesh.router.model.PeerID
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Persistent cryptographic identity for this router.
 *
 * Reference peers (loxation-android / loxation-sw) derive a peer's identity as
 * PeerID = first 8 bytes of SHA-256(noise static public key), and learn the
 * key material from a signed TLV ANNOUNCE. A random PeerID with no backing
 * keys can't produce a valid announce, so the router holds a real Noise static
 * (X25519) keypair plus an Ed25519 signing keypair, generated once on first
 * launch and stored in SharedPreferences.
 *
 * The router never performs Noise handshakes — it only relays — so the X25519
 * private key exists solely to anchor the PeerID derivation; the Ed25519 key
 * signs ANNOUNCE packets (signature input: senderID + timestamp + payload,
 * both big-endian, matching NoiseEncryptionService on both references).
 */
object LocalIdentity {
    private const val TAG = "LocalIdentity"
    private const val PREFS_FILE = "router"
    private const val KEY_PEER_ID = "peer_id" // legacy random ID, replaced by derived ID
    private const val KEY_NOISE_PRIVATE = "noise_private_key"
    private const val KEY_SIGNING_PRIVATE = "signing_private_key"

    class RouterIdentity(
        val peerID: PeerID,
        val noisePublicKey: ByteArray,
        val signingPublicKey: ByteArray,
        private val signingPrivateKey: Ed25519PrivateKeyParameters
    ) {
        val nickname: String = "router-${peerID.rawValue.take(8)}"

        /**
         * 64-byte Ed25519 signature over senderID(8 BE) + wireTimestamp(8 BE) +
         * payload — the signed-packet format both references verify. The
         * signature must cover the bytes that go on the wire, never a
         * receiver-normalized timestamp (identity for our own packets, but
         * this site must stay normalization-free per the June 2026 lockstep
         * fix).
         */
        fun signPacket(packet: BlemeshPacket): ByteArray {
            val input = ByteBuffer.allocate(8 + 8 + packet.payload.size)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(packet.senderId)
                .putLong(packet.wireTimestamp)
                .put(packet.payload)
                .array()
            val signer = Ed25519Signer()
            signer.init(true, signingPrivateKey)
            signer.update(input, 0, input.size)
            return signer.generateSignature()
        }
    }

    fun load(context: Context): RouterIdentity {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

        var noisePrivateBytes = prefs.getString(KEY_NOISE_PRIVATE, null)?.let { decode(it) }
        var signingPrivateBytes = prefs.getString(KEY_SIGNING_PRIVATE, null)?.let { decode(it) }

        if (noisePrivateBytes?.size != X25519PrivateKeyParameters.KEY_SIZE ||
            signingPrivateBytes?.size != Ed25519PrivateKeyParameters.KEY_SIZE
        ) {
            val random = SecureRandom()
            noisePrivateBytes = ByteArray(X25519PrivateKeyParameters.KEY_SIZE).also(random::nextBytes)
            signingPrivateBytes = ByteArray(Ed25519PrivateKeyParameters.KEY_SIZE).also(random::nextBytes)
            prefs.edit()
                .putString(KEY_NOISE_PRIVATE, encode(noisePrivateBytes))
                .putString(KEY_SIGNING_PRIVATE, encode(signingPrivateBytes))
                .remove(KEY_PEER_ID)
                .apply()
            Log.i(TAG, "Generated new router identity keys")
        }

        val noisePrivate = X25519PrivateKeyParameters(noisePrivateBytes, 0)
        val signingPrivate = Ed25519PrivateKeyParameters(signingPrivateBytes, 0)
        val noisePublic = ByteArray(32).also { noisePrivate.generatePublicKey().encode(it, 0) }
        val signingPublic = ByteArray(32).also { signingPrivate.generatePublicKey().encode(it, 0) }

        val peerID = PeerID.fromNoisePublicKey(noisePublic)

        return RouterIdentity(
            peerID = peerID,
            noisePublicKey = noisePublic,
            signingPublicKey = signingPublic,
            signingPrivateKey = signingPrivate
        )
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray? = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        null
    }
}
