package ru.kolco24.kolco24.data.track

/**
 * One-shot fresh-location seam (no foreground service) — used by the checkpoint-take flow to stamp an
 * anti-fraud coordinate on the take row the moment a КП chip is scanned. Mirrors the [LocationEngine]
 * seam (one pure choice + thin Android adapters): the Fused/Legacy implementations are real adapters,
 * untested per repo convention, while the choice goes through [LocationEngineFactory.chooseEngineType].
 *
 * Contract:
 * - Returns **one fresh** fix or `null`. **Never throws** — a timeout, a missing permission, a missing
 *   provider, or any platform error all resolve to `null` (the take row simply stays without a
 *   coordinate; "no coordinate" is strictly better for anti-fraud than a stale/wrong one).
 * - **Freshness is mandatory.** A cached/old fix must not be returned: the Fused impl forces a fresh
 *   fix with `setMaxUpdateAgeMillis(0)`, the Legacy impl drops any fix older than [MAX_FIX_AGE_MS] by
 *   `elapsedRealtimeNanos`. The whole point is to prove the team is **physically at** the КП now.
 *
 * A plain `interface` (not `fun interface`) so the abstract method can carry the [timeoutMs] default —
 * Kotlin forbids a default value on a functional interface's SAM.
 */
interface CurrentLocationProvider {
    /** One fresh fix or null (timeout / no provider / no permission). Never throws. */
    suspend fun current(timeoutMs: Long = 8_000): RawFix?
}

/**
 * Maximum age (ms, monotonic by `elapsedRealtimeNanos`) a fix may have and still count as "fresh".
 * Used by [LegacyCurrentLocationProvider] to reject a recent OS cache — for anti-fraud, "no
 * coordinate" beats a stale one. (Fused doesn't need this — `setMaxUpdateAgeMillis(0)` already forces
 * a fresh fix.)
 */
const val MAX_FIX_AGE_MS = 10_000L
