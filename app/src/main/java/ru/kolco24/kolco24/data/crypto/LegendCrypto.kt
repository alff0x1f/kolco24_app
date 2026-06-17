package ru.kolco24.kolco24.data.crypto

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure, offline crypto engine for the encrypted legend (see `docs/design/API.md` →
 * «Шифрование легенды»). Mirrors the server reference (`src/apps/mobile/crypto.py` +
 * `legend_crypto.py`) byte-for-byte: SHA-256 `bid`, HKDF-SHA256 wrap-key derivation, AES-256-GCM
 * unseal, and the `bundle_blob → content_key → enc` indirection.
 *
 * The engine is deliberately persistence- and Android-free so it can be JVM-unit-tested: it
 * consumes minimal value types ([UnlockTag], [EncBlob]) rather than DTOs/entities — the repository
 * (Task 6) builds those maps. Base64 is decoded via okio's `ByteString` (`java.util.Base64` needs
 * API 26 > minSdk 24; `android.util.Base64` is stubbed in JVM unit tests).
 */
object LegendCrypto {

    /** `info` for the HKDF expand step; ASCII bytes, matching the server. */
    private const val WRAP_INFO = "kp-wrap-v1"

    /** GCM auth-tag length in bits (the 16-byte tag is appended to the ciphertext). */
    private const val GCM_TAG_BITS = 128

    /** SHA-256 output size in bytes — also the HKDF `salt=None` length (RFC 5869). */
    private const val SHA256_LEN = 32

    /** `bid = sha256(code).hexdigest()[:16]` — the public tag identifier. */
    fun bid(code: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(code)
            .joinToString("") { "%02x".format(it) }
            .take(16)

    /**
     * `wrap_key = HKDF-SHA256(code, salt=None, info="kp-wrap-v1", length=32)`.
     *
     * Hand-rolled HKDF (RFC 5869) over `HmacSHA256`: `salt=None` → 32 zero bytes; the 32-byte output
     * needs exactly one expand block (`T(1) = HMAC(prk, info || 0x01)`).
     */
    fun deriveWrapKey(code: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // Extract: PRK = HMAC(salt, code), salt = 32 zero bytes.
        mac.init(SecretKeySpec(ByteArray(SHA256_LEN), "HmacSHA256"))
        val prk = mac.doFinal(code)
        // Expand: T(1) = HMAC(PRK, info || 0x01); one block covers the 32-byte length.
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(WRAP_INFO.toByteArray(Charsets.US_ASCII))
        mac.update(byteArrayOf(0x01))
        return mac.doFinal().copyOf(SHA256_LEN)
    }

    /**
     * AES-256-GCM unseal of a `{iv, ct}` envelope. `iv` = 12 bytes (Base64), `ct` =
     * `ciphertext || tag(16)` (Base64). GCM verifies the tag and `aad`; a wrong key/`aad`/tamper
     * throws `AEADBadTagException` (a [GeneralSecurityException]).
     */
    fun open(key: ByteArray, ivB64: String, ctB64: String, aad: ByteArray): ByteArray {
        val iv = ivB64.decodeBase64Bytes()
        val ct = ctB64.decodeBase64Bytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }

    /**
     * Full offline unlock for a scanned tag. Pure — no I/O, never throws:
     *
     * - `tag.iv == null && tag.ct == null` (open-CP tag, nothing to decrypt) → [UnlockResult.IdentityOnly].
     * - exactly one of `iv`/`ct` is null (malformed envelope) → [UnlockResult.Failed].
     * - else: open the `bundle_blob` (`aad = bid`) → `{ "<cpId>": "<b64 content_key>" }`, then for
     *   each `cpId` present in [encById], open its `enc` (`aad = str(cpId)`) → `{cost, description}`,
     *   yielding [UnlockResult.Revealed].
     * - any crypto/parse failure → [UnlockResult.Failed].
     */
    fun unlock(
        code: ByteArray,
        tag: UnlockTag,
        encById: Map<Int, EncBlob>,
        json: Json,
    ): UnlockResult {
        if (tag.iv == null && tag.ct == null) return UnlockResult.IdentityOnly(tag.point)
        if (tag.iv == null || tag.ct == null) return UnlockResult.Failed("malformed tag envelope: exactly one of iv/ct is null")
        return try {
            val bidStr = bid(code)
            val wrapKey = deriveWrapKey(code)
            val bundleJson = open(wrapKey, tag.iv, tag.ct, bidStr.toByteArray(Charsets.US_ASCII))
            val bundle: Map<String, String> = json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                String(bundleJson, Charsets.UTF_8),
            )
            val revealed = bundle.mapNotNull { (cpIdStr, keyB64) ->
                val cpId = cpIdStr.toInt()
                val enc = encById[cpId] ?: return@mapNotNull null
                val contentKey = keyB64.decodeBase64Bytes()
                val plainJson = open(contentKey, enc.iv, enc.ct, cpId.toString().toByteArray(Charsets.US_ASCII))
                val plain = json.decodeFromString(
                    RevealedPlain.serializer(),
                    String(plainJson, Charsets.UTF_8),
                )
                RevealedCheckpoint(cpId, plain.cost, plain.description)
            }
            UnlockResult.Revealed(tag.point, revealed)
        } catch (e: GeneralSecurityException) {
            UnlockResult.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: SerializationException) {
            UnlockResult.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            // bad Base64, non-integer cpId, etc.
            UnlockResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun String.decodeBase64Bytes(): ByteArray =
        (decodeBase64() ?: throw IllegalArgumentException("invalid Base64")).toByteArray()
}

/** Encrypted `{iv, ct}` envelope of a locked checkpoint (Base64), keyed by CP id in the unlock map. */
data class EncBlob(val iv: String, val ct: String)

/**
 * The minimal slice of a `tags[]` entry the engine needs: the CP it identifies (`point`) and the
 * `bundle_blob` envelope (`iv`/`ct`, both `null` for open-CP tags).
 */
data class UnlockTag(val point: Int, val iv: String?, val ct: String?)

/** A checkpoint whose `{cost, description}` was just decrypted. */
data class RevealedCheckpoint(val id: Int, val cost: Int, val description: String)

/** Outcome of [LegendCrypto.unlock]. */
sealed interface UnlockResult {
    /** The tag opened one or more locked CPs (possibly empty if it only unlocks unknown ids). */
    data class Revealed(val point: Int, val checkpoints: List<RevealedCheckpoint>) : UnlockResult

    /** Open-CP tag (`iv == null`): only identifies its `point`, nothing to decrypt. */
    data class IdentityOnly(val point: Int) : UnlockResult

    /** A crypto or parse failure (wrong key, tamper, malformed bundle). */
    data class Failed(val reason: String) : UnlockResult
}

/** Decrypted plaintext of a locked checkpoint's `enc` envelope. */
@Serializable
private data class RevealedPlain(val cost: Int, val description: String)
