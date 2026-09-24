package ru.kolco24.kolco24.data.time

import ru.kolco24.kolco24.data.track.RawFix

/**
 * Conservative one-sigma uncertainty (ms) of a GPS-derived trusted-time candidate. The OS captures
 * the `Location.time` / `Location.elapsedRealtimeNanos` pair atomically at the fix moment, so the pair
 * is tight; 500 ms leaves margin for provider quantization and is deliberately on par with the
 * second-granular HTTP `Date` candidate so a good GPS fix and a good network fix are treated as peers
 * by `TrustedClock.onTimeCandidate`.
 */
const val GPS_UNCERTAINTY_MS = 500L

/**
 * Maximum horizontal accuracy (meters) a fix may report and still be trusted as a time source. A very
 * coarse fix (network/cell, far worse than a GPS lock) is likely not a real satellite fix, so its
 * `time` is not a reliable trusted-time source — reject it. This is a **coarse** first cut: it thins
 * out obviously-bad fixes but is **not** a reliable proxy for satellite-sourced time on its own —
 * WiFi positioning routinely reports 20–60 m accuracy, well inside this gate — so for the ambiguous
 * `"fused"` provider it is backed up by the altitude **heuristic** in [isSatelliteBacked] (which
 * raises the spoofing bar but, per that function's KDoc, does not close it).
 */
const val GPS_TIME_MAX_ACCURACY_METERS = 100f

/**
 * Whether a fix's `time` may be trusted as satellite-derived, i.e. real GNSS time rather than the
 * (spoofable) device wall-clock a WiFi/cell fix carries.
 *
 * - `"gps"` — the legacy `GPS_PROVIDER` (`LocationManager`, `LegacyLocationEngine` on non-GMS
 *   devices). The provider name is an OS guarantee that the fix came from the satellite chip, so its
 *   `time` is GNSS time. Trusted by name (no altitude requirement — a 2-D satellite fix still carries
 *   real GPS time).
 * - `"fused"` — what `FusedLocationProviderClient` stamps on **every** fix on a GMS device (the
 *   **majority** configuration, and what both anchor power points — track recording + the one-shot
 *   КП-scan / judge «Время по GPS» fix — actually use). The name does **not** reveal the source: a
 *   fused fix may be a genuine GNSS lock **or** a WiFi/cell-blended fix whose `time` is just the
 *   device wall-clock (which the user can change in Android Settings with **no** mock-location app, so
 *   [RawFix.isMock] stays `false`). A pure GNSS lock is inherently 3-D and reports [RawFix.altitude];
 *   a pure WiFi/cell radio fix is 2-D and does not. Requiring a non-null altitude is therefore a
 *   cheap, minSdk-24-portable **heuristic** that *raises the bar* for a spoofed-clock fused fix: it
 *   rejects the common WiFi/cell-only fused fix, which — passing the coarse
 *   [GPS_TIME_MAX_ACCURACY_METERS] gate — could otherwise anchor `TrustedClock` to a forged time and,
 *   via the effective-uncertainty rule, overwrite a legitimate cloud anchor.
 *
 *   This is a heuristic, **not** a guarantee that a fused fix is satellite-sourced, in **both**
 *   directions:
 *   - **False positive (residual spoof surface).** `FusedLocationProviderClient` is a black-box
 *     fuser: on a barometer-equipped phone, or by blending a cached GNSS altitude onto a current
 *     WiFi-horizontal fix, it can produce `altitude != null` on a fix whose `time` is still the
 *     spoofable device wall-clock. So the guard *narrows* the spoof vector, it does not eliminate it.
 *     Kept intentionally conservative: the anchor's real last line of defense against forged offline
 *     time is the **server-side fraud checks**, not this gate.
 *   - **False negative (availability gap).** A 2-D-only GNSS fix — a 3-satellite lock, common under
 *     dense forest canopy, the exact offline scenario this anchor targets — carries valid
 *     satellite-derived `time` but `hasAltitude() == false`, so on a GMS device (`provider = "fused"`)
 *     it is rejected. That is an accepted tradeoff: this is a security gate, and a rejected legit fix
 *     merely leaves the clock in `NoSync` (safe), whereas accepting an ambiguous fix could anchor
 *     forged time (unsafe).
 *
 *   (`verticalAccuracyMeters` is a stronger signal but API 26+ only, so it is not required here — it
 *   would make the anchor inert on API 24/25.)
 * - anything else (`"network"`, `"passive"`, `null`, …) — not a satellite source.
 */
private fun RawFix.isSatelliteBacked(): Boolean = when (provider) {
    "gps" -> true
    "fused" -> altitude != null
    else -> false
}

/**
 * Pure mapper: turn a [RawFix] into a trusted-time [TimeCandidate], or `null` when the fix is not a
 * trustworthy time source. Android-free so it is JVM-unit-tested (`GpsTimeCandidateTest`); the impure
 * callers ([ru.kolco24.kolco24.TrackRecordingService]'s fix path and the one-shot
 * `CurrentLocationProvider` consumer) offer every accepted candidate to `TrustedClock.onTimeCandidate`,
 * whose replacement rule then keeps only the ones that improve the anchor.
 *
 * Rejects (→ `null`):
 * - [RawFix.isMock] — a mock-location app must not be able to set the trusted clock;
 * - a fix that is not [isSatelliteBacked] — a network/cell fix's `time` is just the device wall-clock
 *   and would defeat the whole point (immunity to wall-clock changes). `"gps"` is trusted by provider
 *   name; `"fused"` additionally requires a non-null [RawFix.altitude] as a GNSS **heuristic** that
 *   raises the spoofing bar (not a guarantee — see [isSatelliteBacked] for the residual barometer /
 *   cached-altitude spoof surface and the 2-D-GNSS availability gap it conservatively rejects);
 * - [RawFix.gpsTimeMs] `<= 0` — no usable time;
 * - [RawFix.accuracy] worse than [GPS_TIME_MAX_ACCURACY_METERS].
 *
 * The candidate pins [RawFix.gpsTimeMs] to the fix's monotonic moment
 * (`elapsedRealtimeNanos / 1_000_000`, the same ms scale as `TimeSample.elapsedMs`), so it anchors
 * correctly even for a fix captured before the current call.
 */
fun gpsTimeCandidate(fix: RawFix): TimeCandidate? {
    if (fix.isMock) return null
    if (!fix.isSatelliteBacked()) return null
    if (fix.gpsTimeMs <= 0L) return null
    if (fix.accuracy > GPS_TIME_MAX_ACCURACY_METERS) return null
    return TimeCandidate(
        serverMs = fix.gpsTimeMs,
        anchorElapsedMs = fix.elapsedRealtimeNanos / 1_000_000,
        uncertaintyMs = GPS_UNCERTAINTY_MS,
    )
}
