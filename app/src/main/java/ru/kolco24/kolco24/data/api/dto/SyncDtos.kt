package ru.kolco24.kolco24.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Payload of `GET /app/race/<id>/sync/` — the local-mode lease manifest. `versions` is
 * deliberately unmapped: the client never compares manifest versions (they are opaque hashes;
 * per-origin ETag/304 already answers "did it change"), so `ignoreUnknownKeys` just drops it.
 *
 * `leaseTtlSeconds` (relative) is preferred over `leaseExpiresAt` (absolute epoch seconds): a
 * race-day fresh install may have a cold `TrustedClock` (no cloud contact yet) and the LAN
 * client deliberately carries no `ServerTimeInterceptor`, so lease math against an absolute
 * server timestamp is exposed to phone wall-clock skew (a skewed clock could instantly expire a
 * valid pin or over-pin past handback). A relative TTL is computed against receipt time and is
 * immune. The absolute fallback relies on the local server's clock being correct. Both fields are
 * stubbed `null` today — the backend lease is not implemented yet.
 */
@Serializable
data class SyncManifestDto(
    val race: Int,
    @SerialName("data_source") val dataSource: String,
    @SerialName("lease_ttl_seconds") val leaseTtlSeconds: Long? = null,
    @SerialName("lease_expires_at") val leaseExpiresAt: Long? = null,
)
