package ru.kolco24.kolco24.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kolco24.kolco24.data.api.dto.LanTimeDto
import ru.kolco24.kolco24.data.api.sign

class LanTimeVerifierTest {

    private val secret = "test-secret-123"

    /** Deterministic nonce so `verify`'s echo check is exercised against a known value. */
    private val fixedNonceBytes = ByteArray(LAN_TIME_NONCE_BYTES) { it.toByte() }
    private val fixedNonceHex = fixedNonceBytes.joinToString("") { "%02x".format(it) }

    private fun verifier(secret: String = this.secret) =
        LanTimeVerifier(secret = secret, randomBytes = { fixedNonceBytes })

    /** The signature the honest server would return for `(nonce, serverMs)`. */
    private fun serverSig(nonce: String, serverMs: Long, secret: String = this.secret) =
        sign(secret, "$nonce|$serverMs")

    @Test
    fun newNonce_isThirtyTwoHexChars() {
        val nonce = verifier().newNonce()
        assertEquals(fixedNonceHex, nonce)
        assertEquals(32, nonce.length)
    }

    @Test
    fun validSignature_producesCandidate_withMidpointAndUncertainty() {
        val serverMs = 1_718_900_000_123L
        val dto = LanTimeDto(serverMs = serverMs, nonce = fixedNonceHex)
        val candidate = verifier().verify(
            sentNonce = fixedNonceHex,
            dto = dto,
            signature = serverSig(fixedNonceHex, serverMs),
            elapsedBefore = 1_000L,
            elapsedAfter = 1_400L, // rtt = 400
        )
        assertNotNull(candidate)
        candidate!!
        assertEquals(serverMs, candidate.serverMs)
        assertEquals(1_200L, candidate.anchorElapsedMs) // 1000 + 400/2
        assertEquals(250L, candidate.uncertaintyMs) // 400/2 + 50
    }

    @Test
    fun uncertainty_isRttHalfPlusLanGranularity() {
        val serverMs = 10_000L
        val dto = LanTimeDto(serverMs = serverMs, nonce = fixedNonceHex)
        val candidate = verifier().verify(
            sentNonce = fixedNonceHex,
            dto = dto,
            signature = serverSig(fixedNonceHex, serverMs),
            elapsedBefore = 0L,
            elapsedAfter = 10_000L, // rtt = 10_000
        )
        assertEquals(5_050L, candidate!!.uncertaintyMs) // 10_000/2 + 50
    }

    @Test
    fun upperCaseSignature_isAccepted() {
        val serverMs = 42L
        val dto = LanTimeDto(serverMs = serverMs, nonce = fixedNonceHex)
        val candidate = verifier().verify(
            sentNonce = fixedNonceHex,
            dto = dto,
            signature = serverSig(fixedNonceHex, serverMs).uppercase(),
            elapsedBefore = 0L,
            elapsedAfter = 100L,
        )
        assertNotNull(candidate)
    }

    @Test
    fun tamperedServerMs_isRejected() {
        // The server signed 100 but the body claims 999_999 — a MITM bumped the epoch.
        val dto = LanTimeDto(serverMs = 999_999L, nonce = fixedNonceHex)
        val candidate = verifier().verify(
            sentNonce = fixedNonceHex,
            dto = dto,
            signature = serverSig(fixedNonceHex, 100L),
            elapsedBefore = 0L,
            elapsedAfter = 100L,
        )
        assertNull(candidate)
    }

    @Test
    fun mismatchedNonce_isRejected() {
        // A replayed response carrying a stale nonce that isn't the one we just sent.
        val serverMs = 100L
        val staleNonce = "ffffffffffffffffffffffffffffffff"
        val dto = LanTimeDto(serverMs = serverMs, nonce = staleNonce)
        val candidate = verifier().verify(
            sentNonce = fixedNonceHex,
            dto = dto,
            signature = serverSig(staleNonce, serverMs),
            elapsedBefore = 0L,
            elapsedAfter = 100L,
        )
        assertNull(candidate)
    }

    @Test
    fun wrongKey_isRejected() {
        val serverMs = 100L
        val dto = LanTimeDto(serverMs = serverMs, nonce = fixedNonceHex)
        val candidate = verifier().verify(
            sentNonce = fixedNonceHex,
            dto = dto,
            signature = serverSig(fixedNonceHex, serverMs, secret = "attacker-secret"),
            elapsedBefore = 0L,
            elapsedAfter = 100L,
        )
        assertNull(candidate)
    }

    @Test
    fun nullDto_isRejected() {
        assertNull(verifier().verify(fixedNonceHex, null, "sig", 0L, 100L))
    }

    @Test
    fun nullSignature_isRejected() {
        val dto = LanTimeDto(serverMs = 100L, nonce = fixedNonceHex)
        assertNull(verifier().verify(fixedNonceHex, dto, null, 0L, 100L))
    }

    @Test
    fun negativeRtt_isRejected() {
        val serverMs = 100L
        val dto = LanTimeDto(serverMs = serverMs, nonce = fixedNonceHex)
        val candidate = verifier().verify(
            sentNonce = fixedNonceHex,
            dto = dto,
            signature = serverSig(fixedNonceHex, serverMs),
            elapsedBefore = 5_000L,
            elapsedAfter = 4_900L, // rtt = -100
        )
        assertNull(candidate)
    }

    @Test
    fun rttOverMax_isRejected() {
        val serverMs = 100L
        val dto = LanTimeDto(serverMs = serverMs, nonce = fixedNonceHex)
        val candidate = verifier().verify(
            sentNonce = fixedNonceHex,
            dto = dto,
            signature = serverSig(fixedNonceHex, serverMs),
            elapsedBefore = 0L,
            elapsedAfter = 10_001L, // rtt = 10_001 > maxRttMs
        )
        assertNull(candidate)
    }

    @Test
    fun rttAtMax_isAccepted() {
        val serverMs = 100L
        val dto = LanTimeDto(serverMs = serverMs, nonce = fixedNonceHex)
        val candidate = verifier().verify(
            sentNonce = fixedNonceHex,
            dto = dto,
            signature = serverSig(fixedNonceHex, serverMs),
            elapsedBefore = 0L,
            elapsedAfter = 10_000L, // rtt == maxRttMs
        )
        assertNotNull(candidate)
        assertEquals(5_000L, candidate!!.anchorElapsedMs) // 0 + 10_000/2
    }
}
