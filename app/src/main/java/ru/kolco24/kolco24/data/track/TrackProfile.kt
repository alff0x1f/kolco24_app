package ru.kolco24.kolco24.data.track

/**
 * Pure, Android-free GPS-track recording profile (mirrors `ui/theme/ThemeMode.kt` /
 * `ui/legend/CheckpointColor.kt` — JVM-unit-tested, no Compose/Android imports).
 *
 * Each profile carries the three numbers an engine needs to build its location request:
 * [highAccuracy] (priority — both profiles keep HIGH; a forest race has no cell/WiFi so
 * `BALANCED` would be useless), [intervalMs] (the requested fix interval), and [maxDelayMs]
 * (batch delay — Fused may hold fixes up to this long before delivery).
 *
 * [Precise] (15 s / 60 s) is the default and equals today's hard-coded behavior; [Economy]
 * (180 s / 180 s) is the battery mode — at a 3-minute interval the GPS radio drops into
 * duty-cycle (sleeps between fixes) for a several-fold saving over a multi-hour race, while a
 * coordinate every ~3 min is still sufficient for the anti-fraud proof-of-path use case.
 */
enum class TrackProfile(val highAccuracy: Boolean, val intervalMs: Long, val maxDelayMs: Long) {
    Precise(highAccuracy = true, intervalMs = 15_000L, maxDelayMs = 60_000L),
    Economy(highAccuracy = true, intervalMs = 180_000L, maxDelayMs = 180_000L),
}

/** Parse a persisted enum name to [TrackProfile]; `null`/unknown default to [TrackProfile.Precise] (forward-compatible). */
fun parseTrackProfile(raw: String?): TrackProfile =
    TrackProfile.entries.firstOrNull { it.name == raw } ?: TrackProfile.Precise
