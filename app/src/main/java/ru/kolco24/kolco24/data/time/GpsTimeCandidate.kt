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
 * `time` is not a reliable trusted-time source — reject it.
 */
const val GPS_TIME_MAX_ACCURACY_METERS = 100f

/**
 * Pure mapper: turn a [RawFix] into a trusted-time [TimeCandidate], or `null` when the fix is not a
 * trustworthy time source. Android-free so it is JVM-unit-tested (`GpsTimeCandidateTest`); the impure
 * callers ([ru.kolco24.kolco24.TrackRecordingService]'s fix path and the one-shot
 * `CurrentLocationProvider` consumer) offer every accepted candidate to `TrustedClock.onTimeCandidate`,
 * whose replacement rule then keeps only the ones that improve the anchor.
 *
 * Rejects (→ `null`):
 * - [RawFix.isMock] — a mock-location app must not be able to set the trusted clock;
 * - [RawFix.provider] != `"gps"` — only a real satellite fix carries GPS-satellite time; a
 *   network/cell/fused-but-non-gps fix's `time` is just the device wall-clock and would defeat the
 *   whole point (immunity to wall-clock changes);
 * - [RawFix.gpsTimeMs] `<= 0` — no usable time;
 * - [RawFix.accuracy] worse than [GPS_TIME_MAX_ACCURACY_METERS].
 *
 * The candidate pins [RawFix.gpsTimeMs] to the fix's monotonic moment
 * (`elapsedRealtimeNanos / 1_000_000`, the same ms scale as `TimeSample.elapsedMs`), so it anchors
 * correctly even for a fix captured before the current call.
 */
fun gpsTimeCandidate(fix: RawFix): TimeCandidate? {
    if (fix.isMock) return null
    if (fix.provider != "gps") return null
    if (fix.gpsTimeMs <= 0L) return null
    if (fix.accuracy > GPS_TIME_MAX_ACCURACY_METERS) return null
    return TimeCandidate(
        serverMs = fix.gpsTimeMs,
        anchorElapsedMs = fix.elapsedRealtimeNanos / 1_000_000,
        uncertaintyMs = GPS_UNCERTAINTY_MS,
    )
}
