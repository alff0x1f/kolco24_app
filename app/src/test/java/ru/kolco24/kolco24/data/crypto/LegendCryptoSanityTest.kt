package ru.kolco24.kolco24.data.crypto

import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Internal sanity tests for [LegendCrypto]. These seal data locally and prove the AES-GCM wiring +
 * the bundle/content-key indirection round-trip — they do NOT verify HKDF/`bid`/AAD interop with
 * the server (that needs the server-generated vector, gated to Task 5's [LegendCryptoTest]).
 */
class LegendCryptoSanityTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val rng = SecureRandom()

    /** Mirror of [LegendCrypto.open] for the encrypt direction — test-only `seal`. */
    private fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): EncBlob {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        return EncBlob(iv = iv.toByteString().base64(), ct = ct.toByteString().base64())
    }

    @Test
    fun bidIs16HexCharsAndDeterministic() {
        val code = ByteArray(16) { it.toByte() }
        val bid = LegendCrypto.bid(code)
        assertEquals(16, bid.length)
        assertTrue(bid.all { it in "0123456789abcdef" })
        assertEquals(bid, LegendCrypto.bid(code))
    }

    @Test
    fun deriveWrapKeyIs32Bytes() {
        val key = LegendCrypto.deriveWrapKey(ByteArray(16) { 7 })
        assertEquals(32, key.size)
    }

    @Test
    fun openRoundTripsSelfSealedData() {
        val key = ByteArray(32) { it.toByte() }
        val aad = "42".toByteArray(Charsets.US_ASCII)
        val plaintext = """{"cost":3,"description":"Родник"}""".toByteArray()
        val blob = seal(key, plaintext, aad)

        val out = LegendCrypto.open(key, blob.iv, blob.ct, aad)
        assertArrayEquals(plaintext, out)
    }

    @Test
    fun unlockRevealsCheckpointsThroughBundleIndirection() {
        val code = ByteArray(16) { (it * 3).toByte() }
        val wrapKey = LegendCrypto.deriveWrapKey(code)
        val bidBytes = LegendCrypto.bid(code).toByteArray(Charsets.US_ASCII)

        // Per-CP content key + its sealed plaintext (aad = decimal cp id).
        val contentKey = ByteArray(32) { (it + 1).toByte() }
        val cpId = 103
        val enc = seal(contentKey, """{"cost":5,"description":"Вершина"}""".toByteArray(), cpId.toString().toByteArray(Charsets.US_ASCII))

        // bundle_blob: { "<cpId>": "<b64 content_key>" }, sealed with wrap_key (aad = bid).
        val bundleJson = """{"$cpId":"${contentKey.toByteString().base64()}"}"""
        val bundle = seal(wrapKey, bundleJson.toByteArray(), bidBytes)

        val tag = UnlockTag(point = cpId, iv = bundle.iv, ct = bundle.ct)
        val result = LegendCrypto.unlock(code, tag, mapOf(cpId to enc), json)

        assertTrue(result is UnlockResult.Revealed)
        val revealed = (result as UnlockResult.Revealed)
        assertEquals(cpId, revealed.point)
        assertEquals(1, revealed.checkpoints.size)
        assertEquals(RevealedCheckpoint(cpId, 5, "Вершина"), revealed.checkpoints.single())
    }

    @Test
    fun unlockIdentityOnlyForOpenCpTag() {
        val result = LegendCrypto.unlock(
            code = ByteArray(16),
            tag = UnlockTag(point = 101, iv = null, ct = null),
            encById = emptyMap(),
            json = json,
        )
        assertEquals(UnlockResult.IdentityOnly(101), result)
    }

    @Test
    fun unlockFailsOnPartialEnvelope() {
        val tag = UnlockTag(point = 101, iv = "someIv", ct = null)
        val result = LegendCrypto.unlock(ByteArray(16), tag, emptyMap(), json)
        assertTrue(result is UnlockResult.Failed)
    }

    @Test
    fun unlockFailsOnTamperedCiphertext() {
        val code = ByteArray(16) { 9 }
        val wrapKey = LegendCrypto.deriveWrapKey(code)
        val bidBytes = LegendCrypto.bid(code).toByteArray(Charsets.US_ASCII)
        val bundle = seal(wrapKey, """{"103":"AAAA"}""".toByteArray(), bidBytes)

        // Flip the first Base64 char of the ciphertext (stays valid Base64) → GCM tag check fails.
        val tamperedCt = (if (bundle.ct[0] == 'A') 'B' else 'A') + bundle.ct.substring(1)
        val tag = UnlockTag(point = 103, iv = bundle.iv, ct = tamperedCt)

        val result = LegendCrypto.unlock(code, tag, emptyMap(), json)
        assertTrue(result is UnlockResult.Failed)
    }
}
