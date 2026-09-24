package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `GET /app/time/?nonce=<32-hex>` — the signed LAN time endpoint (see `docs/design/UPLOAD.md`).
 * The LAN server echoes the client's [nonce] verbatim and returns its own millisecond-precise epoch in
 * [serverMs]; the response also carries `X-App-Signature = hex(HMAC_SHA256(APP_SECRET, "<nonce>|<server_ms>"))`,
 * verified against [nonce]/[serverMs] by [ru.kolco24.kolco24.data.time.LanTimeVerifier]. Unlike the HTTP
 * `Date` header the value is ms-granular, so a good LAN candidate is more precise than a good network one.
 */
@Serializable
data class LanTimeDto(
    @SerialName("server_ms") val serverMs: Long,
    val nonce: String,
)
