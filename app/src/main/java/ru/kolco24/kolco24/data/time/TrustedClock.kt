package ru.kolco24.kolco24.data.time

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Skew above this magnitude (ms) flips [ClockStatus] from [ClockStatus.Ok] to [ClockStatus.Skewed]. */
const val SKEW_THRESHOLD_MS = 60_000L

/**
 * A trusted-time anchor: server epoch pinned to a reading of the **monotonic** `elapsedRealtime`
 * timer plus the boot-session identity in which that reading was taken.
 *
 * @param serverEpochMs server time (epoch ms) parsed from a network `Date` header.
 * @param anchorElapsedMs the monotonic `elapsedRealtime()` reading the server time is pinned to
 *   (RTT-corrected midpoint).
 * @param capturedWallMs the device wall-clock at capture (forensics only).
 * @param bootCount `Settings.Global.BOOT_COUNT` at capture — boot-session identity; `null` if it
 *   could not be read (warm start is then disabled and the monotonic-regression heuristic is used).
 */
data class ClockAnchor(
    val serverEpochMs: Long,
    val anchorElapsedMs: Long,
    val capturedWallMs: Long,
    val bootCount: Int?,
)

/**
 * A single consistent snapshot of all time sources, taken atomically by [TrustedClock.sample].
 *
 * Invariant: [elapsedMs] is the **raw** `elapsedRealtime()` reading (`== elapsedProvider()` at
 * snapshot time). Any direct `SystemClock.elapsedRealtime()` call in scan-window math is
 * interchangeable with [elapsedMs] — one monotonic source, or the 20 s ring would jump.
 */
data class TimeSample(
    val wallMs: Long,
    val elapsedMs: Long,
    val trustedMs: Long?,
    val bootCount: Int?,
)

/** Result of comparing the device wall-clock against trusted time. */
sealed interface ClockStatus {
    /** No trusted anchor yet (cold start before first sync, or invalidated by reboot). */
    data object NoSync : ClockStatus

    /** Wall-clock agrees with trusted time within [SKEW_THRESHOLD_MS]. */
    data object Ok : ClockStatus

    /** Wall-clock disagrees with trusted time by [skewMs] (`wall − trusted`; sign = direction). */
    data class Skewed(val skewMs: Long) : ClockStatus
}

/** Single immutable in-memory snapshot of the clock's state. */
private data class ClockState(val anchor: ClockAnchor?, val verified: Boolean)

/**
 * Pure trusted-time core. Holds a [ClockAnchor] and derives trusted time from the monotonic
 * `elapsedRealtime()` timer — immune to wall-clock changes (the monotonic timer is not adjustable
 * from settings). Only a **reboot** breaks the anchor (`elapsedRealtime` resets to 0).
 *
 * `trusted = serverEpochMs + (elapsedNow − anchorElapsedMs)`.
 *
 * Threading (P1): reads ([sample]/[trusted]/[signingSeconds]) are lock-free — each takes the
 * [AtomicReference] exactly once. All writes ([onServerTime]/[recomputeStatus]) run under a single
 * `synchronized(lock)` so a late network thread or a UI tick can never overwrite the store/flow with
 * an older value.
 *
 * Dependencies are injected so the model is Android-free and JVM-unit-testable (mirrors
 * `ScanSession.kt`): [elapsedProvider] is the **raw** `SystemClock.elapsedRealtime()`,
 * [wallProvider] is `System.currentTimeMillis()`, [bootCountProvider] reads `BOOT_COUNT` (cached
 * once per process), [persist] is best-effort warm-start persistence (`ClockAnchorStore::write`),
 * [persisted] is the anchor read back at construction.
 */
class TrustedClock(
    private val elapsedProvider: () -> Long,
    private val wallProvider: () -> Long,
    private val bootCountProvider: () -> Int?,
    private val persist: (ClockAnchor) -> Unit = {},
    persisted: ClockAnchor? = null,
) {
    private val lock = Any()
    private val ref: AtomicReference<ClockState>
    private val statusFlow: MutableStateFlow<ClockStatus>

    init {
        // Warm start through boot identity (P0, null-safe): both bootCounts must be non-null AND
        // equal. `null == null` does NOT grant a warm start (that would falsely verify a reboot).
        val currentBoot = bootCountProvider()
        val verified =
            persisted != null && currentBoot != null && persisted.bootCount == currentBoot
        val initialState = ClockState(persisted, verified)
        ref = AtomicReference(initialState)
        // Seed the flow from the warm state so the UI has the correct status on the first frame
        // (verified → Ok/Skewed, else NoSync) instead of NoSync-until-first-tick.
        statusFlow = MutableStateFlow(
            computeStatus(initialState, elapsedProvider(), wallProvider(), currentBoot),
        )
    }

    /** Observable clock status (deduped by [MutableStateFlow]); recomputed on sync and on tick. */
    val status: StateFlow<ClockStatus> = statusFlow.asStateFlow()

    /**
     * Pure: trusted epoch ms from an already-captured [state] + readings, or `null` when there is no
     * verified anchor or the anchor is invalidated by reboot. No side effects, no `.get()`, no
     * provider calls — operates on the snapshot passed in.
     *
     * Reboot invalidation: monotonic regression (`anchorElapsedMs > elapsedNow`) **or** both boot
     * ids non-null and different. A lone `null` boot id does not prove a reboot (regression catches
     * it).
     */
    private fun computeTrusted(state: ClockState, elapsedNow: Long, bootNow: Int?): Long? {
        if (!state.verified) return null
        val anchor = state.anchor ?: return null
        // Monotonic regression = authoritative reboot detect: the anchor cannot be in the future of
        // the monotonic clock within the same session.
        if (anchor.anchorElapsedMs > elapsedNow) return null
        if (anchor.bootCount != null && bootNow != null && anchor.bootCount != bootNow) return null
        return anchor.serverEpochMs + (elapsedNow - anchor.anchorElapsedMs)
    }

    /** Pure: derive [ClockStatus] from an already-captured snapshot. */
    private fun computeStatus(
        state: ClockState,
        elapsedNow: Long,
        wallNow: Long,
        bootNow: Int?,
    ): ClockStatus {
        val trusted = computeTrusted(state, elapsedNow, bootNow) ?: return ClockStatus.NoSync
        val skew = wallNow - trusted
        return if (abs(skew) > SKEW_THRESHOLD_MS) ClockStatus.Skewed(skew) else ClockStatus.Ok
    }

    /** Trusted epoch ms, or `null` when not verified / invalidated by reboot. Lock-free read. */
    fun trusted(): Long? {
        val state = ref.get()
        return computeTrusted(state, elapsedProvider(), bootCountProvider())
    }

    /**
     * A single consistent snapshot (P1): one `ref.get()`, one [elapsedProvider], one [wallProvider],
     * one [bootCountProvider]; trusted time computed from those same captured values.
     */
    fun sample(): TimeSample {
        val state = ref.get()
        val elapsedNow = elapsedProvider()
        val wallNow = wallProvider()
        val bootNow = bootCountProvider()
        return TimeSample(
            wallMs = wallNow,
            elapsedMs = elapsedNow,
            trustedMs = computeTrusted(state, elapsedNow, bootNow),
            bootCount = bootNow,
        )
    }

    /**
     * Seconds source for request signing (`X-App-Ts`): trusted seconds when verified, else an honest
     * fallback to wall — so signing survives a skewed wall-clock once the anchor is established.
     * Lock-free; called from OkHttp threads.
     */
    fun signingSeconds(): Long = sample().let { it.trustedMs ?: it.wallMs } / 1000

    /**
     * Re-anchor from a network `Date` header. [anchorElapsed] is the RTT-corrected midpoint reading;
     * [wallNow]/[bootNow] are captured by the caller. Serialized under [lock] (P1).
     *
     * Accept rule (P0 + out-of-order + null-safe): accept if (a) no current anchor; (b) current is
     * monotonically invalid (`anchorElapsedMs > elapsedNow`, reboot) — stale dropped, incoming
     * accepted unconditionally; (c) both boot ids non-null and differ; or (d) (same session)
     * `anchorElapsed >= current.anchorElapsedMs` (newer by monotonic time). A late out-of-order
     * sample with a smaller `anchorElapsed` in the same session is rejected by (d).
     */
    fun onServerTime(serverMs: Long, anchorElapsed: Long, wallNow: Long, bootNow: Int?) {
        synchronized(lock) {
            val elapsedNow = elapsedProvider()
            val current = ref.get()
            val cur = current.anchor
            val accept = when {
                cur == null -> true
                cur.anchorElapsedMs > elapsedNow -> true // reboot: drop stale, accept unconditionally
                cur.bootCount != null && bootNow != null && cur.bootCount != bootNow -> true
                else -> anchorElapsed >= cur.anchorElapsedMs
            }
            if (!accept) return
            val newAnchor = ClockAnchor(
                serverEpochMs = serverMs,
                anchorElapsedMs = anchorElapsed,
                capturedWallMs = wallNow,
                bootCount = bootNow,
            )
            val newState = ClockState(newAnchor, verified = true)
            // Ordered three-step (P1): ref.set → persist (best-effort) → statusFlow.
            ref.set(newState)
            runCatching { persist(newAnchor) } // P2: must not throw on the OkHttp thread
            statusFlow.value = computeStatus(newState, elapsedNow, wallNow, bootNow)
        }
    }

    /**
     * Recompute and publish [status] (driven by a local ~5 s tick). Under the same [lock] as
     * [onServerTime] so a tick can never overwrite a freshly-synced status with a stale read. Equal
     * values are deduped by [MutableStateFlow] — no spurious recompositions.
     */
    fun recomputeStatus() {
        synchronized(lock) {
            val state = ref.get()
            statusFlow.value =
                computeStatus(state, elapsedProvider(), wallProvider(), bootCountProvider())
        }
    }
}
