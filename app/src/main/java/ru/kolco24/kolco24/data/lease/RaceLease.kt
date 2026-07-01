package ru.kolco24.kolco24.data.lease

import ru.kolco24.kolco24.data.api.dto.SyncManifestDto

/** A pin on one race's data source: rows for [raceId] are served from LAN until [expiresAtMs]. */
data class RaceLease(val raceId: Int, val expiresAtMs: Long)

/** Client-side default lease length while the backend's `lease_ttl_seconds`/`lease_expires_at` are stubbed `null`. */
const val DEFAULT_LEASE_MS = 12 * 60 * 60 * 1000L

/**
 * Computes the renewed lease for [raceId] at [nowMs]. Expiry precedence:
 * relative [serverTtlSec] (clock-skew-immune) → absolute [serverLeaseExpiresAtSec] (relies on a
 * sane server clock) → [nowMs] + [DEFAULT_LEASE_MS] (both `null`, today's stub).
 */
fun renewedLease(raceId: Int, serverTtlSec: Long?, serverLeaseExpiresAtSec: Long?, nowMs: Long): RaceLease {
    val expiresAtMs = when {
        serverTtlSec != null -> nowMs + serverTtlSec * 1000L
        serverLeaseExpiresAtSec != null -> serverLeaseExpiresAtSec * 1000L
        else -> nowMs + DEFAULT_LEASE_MS
    }
    return RaceLease(raceId, expiresAtMs)
}

/** `true` when [lease] pins [raceId] and has not yet expired at [nowMs]. */
fun isPinned(lease: RaceLease?, raceId: Int, nowMs: Long): Boolean =
    lease != null && lease.raceId == raceId && nowMs < lease.expiresAtMs

/** What a sync-manifest probe should do to the stored lease. */
sealed interface LeaseAction {
    /** Manifest says `local` for the probed race — renew the pin. */
    data class Renew(val lease: RaceLease) : LeaseAction

    /** Manifest says `cloud` for the probed race (handback) — release the pin. */
    object Clear : LeaseAction

    /** Error, unreachable, wrong race, or an unknown `data_source` — leave the lease untouched. */
    object Keep : LeaseAction
}

/**
 * Maps a [SyncManifestDto] probe result to a [LeaseAction]. A `null` manifest (unreachable/error),
 * a manifest for a different race, or an unrecognized `data_source` never renews the lease — only
 * an explicit `"local"`/`"cloud"` for the probed [raceId] changes anything.
 */
fun applySyncResponse(manifest: SyncManifestDto?, raceId: Int, nowMs: Long): LeaseAction {
    if (manifest == null || manifest.race != raceId) return LeaseAction.Keep
    return when (manifest.dataSource) {
        "local" -> LeaseAction.Renew(
            renewedLease(raceId, manifest.leaseTtlSeconds, manifest.leaseExpiresAt, nowMs),
        )
        "cloud" -> LeaseAction.Clear
        else -> LeaseAction.Keep
    }
}
