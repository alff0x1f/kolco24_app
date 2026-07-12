package ru.kolco24.kolco24.data.time

import ru.kolco24.kolco24.data.api.dto.LanTimeDto
import ru.kolco24.kolco24.data.api.sign
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Uncertainty floor (ms) contributed by the LAN time endpoint. Its body carries a **millisecond**-precise
 * `server_ms` (no second-granularity like the HTTP `Date` header), so the granularity floor is much
 * smaller than [DATE_HEADER_GRANULARITY_MS] — a good LAN candidate (`rtt / 2 + 50`) can supersede a good
 * network `Date` candidate.
 */
const val LAN_TIME_GRANULARITY_MS = 50L

/**
 * Length (bytes) of the random nonce; hex-encoded it is a 32-char token, matching the endpoint contract.
 */
const val LAN_TIME_NONCE_BYTES = 16

/**
 * Pure verifier for the signed LAN time endpoint (`GET /app/time/?nonce=<32-hex>`, see
 * `docs/design/UPLOAD.md`). Turns the LAN server into a legitimate trusted-time anchor **in local mode**
 * — the cleartext `Date` header cannot be trusted (a MITM on the event Wi-Fi forges it), but an
 * HMAC-signed response with a fresh client nonce is tamper- and replay-evident (the attacker cannot
 * produce `HMAC(APP_SECRET, "<nonce>|<server_ms>")` without the secret, and a replayed old response
 * carries a stale nonce that fails the echo check).
 *
 * Android-free so it is JVM-unit-tested ([ru.kolco24.kolco24.data.time.LanTimeVerifier] mirrors
 * `LegendCrypto`/`GpsTimeCandidate`): the impure adapter (`AppContainer.syncLanTime`) reads the monotonic
 * clock around the network call and offers any accepted candidate to `TrustedClock.onTimeCandidate`, whose
 * replacement rule then keeps it only when it improves the anchor.
 *
 * @param secret the shared `APP_SECRET` (same key the signing interceptor uses); the LAN server signs with it.
 * @param randomBytes injectable RNG (default [SecureRandom]) producing [LAN_TIME_NONCE_BYTES] fresh bytes per nonce.
 * @param maxRttMs upper bound on an acceptable round-trip (default 10 s), mirroring `ServerTimeInterceptor`.
 */
class LanTimeVerifier(
    private val secret: String,
    private val randomBytes: () -> ByteArray = { ByteArray(LAN_TIME_NONCE_BYTES).also { secureRandom.nextBytes(it) } },
    private val maxRttMs: Long = 10_000,
) {

    /** A fresh random 32-char lower-hex nonce for one `GET /app/time/` request. */
    fun newNonce(): String = randomBytes().joinToString("") { "%02x".format(it) }

    /**
     * Verify a raw LAN time response against the [sentNonce] and map it to a [TimeCandidate], or `null`
     * when it cannot be trusted. Never throws.
     *
     * Rejects (→ `null`):
     * - [dto] or [signature] missing (unreachable / `404` / no signature header);
     * - RTT (`elapsedAfter − elapsedBefore`) negative (timing anomaly) or over [maxRttMs] (too coarse);
     * - echoed nonce != [sentNonce] (a replayed or mismatched response);
     * - the HMAC over `"<nonce>|<server_ms>"` does not match [signature] (constant-time compare — a
     *   forged `server_ms` or a wrong key fails here).
     *
     * On accept the candidate pins [LanTimeDto.serverMs] to the RTT-corrected midpoint
     * (`elapsedBefore + rtt / 2`) with uncertainty `rtt / 2 + `[LAN_TIME_GRANULARITY_MS] — the same
     * midpoint/uncertainty shape as `ServerTimeInterceptor`, only with a much smaller granularity floor.
     */
    fun verify(
        sentNonce: String,
        dto: LanTimeDto?,
        signature: String?,
        elapsedBefore: Long,
        elapsedAfter: Long,
    ): TimeCandidate? {
        if (dto == null || signature == null) return null
        val rtt = elapsedAfter - elapsedBefore
        if (rtt < 0 || rtt > maxRttMs) return null
        if (dto.nonce != sentNonce) return null
        return try {
            val expected = sign(secret, "${dto.nonce}|${dto.serverMs}")
            // Constant-time compare (MessageDigest.isEqual) so a timing side-channel can't reveal the
            // expected HMAC. Lower-casing the attacker-supplied hex leaks nothing about the secret.
            if (!MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), signature.lowercase().toByteArray(Charsets.UTF_8))) {
                return null
            }
            TimeCandidate(
                serverMs = dto.serverMs,
                anchorElapsedMs = elapsedBefore + rtt / 2, // overflow-safe midpoint
                uncertaintyMs = rtt / 2 + LAN_TIME_GRANULARITY_MS,
            )
        } catch (_: Exception) {
            null // never throws on the sync path
        }
    }

    private companion object {
        private val secureRandom = SecureRandom()
    }
}
