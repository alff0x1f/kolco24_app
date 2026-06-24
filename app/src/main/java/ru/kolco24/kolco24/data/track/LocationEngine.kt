package ru.kolco24.kolco24.data.track

/**
 * Seam over the platform location source so [TrackRecordingService] is decoupled from the concrete
 * engine (Fused on GMS devices, Legacy elsewhere) and the choice stays testable (mirrors the
 * `NfcTransport` seam in `MifareUltralightWriter.kt`).
 *
 * [start] is **fire-and-forget asynchronous**: it requests updates and returns; fixes arrive later on
 * [onPoints] (one or more [RawFix] per delivery — Fused batches, Legacy delivers singletons). Because
 * the start can fail *after* it returns (provider lost, `SecurityException` on a permission revoke
 * race), errors are reported through the [onError] callback rather than a return value — a thrown
 * `requestLocationUpdates` is wrapped and routed to [onError] so the service can tear down cleanly.
 */
interface LocationEngine {
    /**
     * Begin requesting location updates. [onPoints] receives each delivered batch of fixes (never
     * empty); [onError] receives any start-time or later failure (missing/disabled provider,
     * `SecurityException`, GMS task failure) so the caller can stop and surface it. Both callbacks may
     * fire on an arbitrary thread.
     */
    fun start(onPoints: (List<RawFix>) -> Unit, onError: (Throwable) -> Unit)

    /**
     * Force delivery of any buffered batch of fixes, then invoke [onComplete]. The default body
     * completes immediately — correct for engines that never batch (Legacy delivers singletons). The
     * Fused engine overrides it: GMS holds up to `maxUpdateDelay` of fixes in its buffer, and a bare
     * [stop] (`removeLocationUpdates`) discards them, so the buffer must be flushed and **delivered to
     * `onPoints`** (enqueued for insert) before stopping. [onComplete] runs after delivery; it may fire
     * on an arbitrary thread (Fused fires on the main thread).
     */
    fun flush(onComplete: () -> Unit = {}) { onComplete() }

    /** Stop requesting updates and release listeners. Idempotent. */
    fun stop()
}

/** Which [LocationEngine] implementation to use — decided purely by GMS availability. */
enum class EngineType { Fused, Legacy }
