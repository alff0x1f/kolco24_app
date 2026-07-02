package ru.kolco24.kolco24.data.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.kolco24.kolco24.data.RefreshResult
import ru.kolco24.kolco24.data.SyncSource
import ru.kolco24.kolco24.data.api.dto.SyncManifestDto
import ru.kolco24.kolco24.data.db.RaceEntity
import ru.kolco24.kolco24.data.lease.LeaseAction
import ru.kolco24.kolco24.data.lease.RaceLease
import ru.kolco24.kolco24.data.lease.applySyncResponse
import ru.kolco24.kolco24.data.lease.isPinned
import ru.kolco24.kolco24.data.nearestRaceId
import ru.kolco24.kolco24.data.todayIso

/**
 * Toast-facing outcome of [SyncCoordinator.enterLocalMode]/[SyncCoordinator.exitLocalMode].
 */
sealed interface LocalModeOutcome {
    /**
     * Switch-on pinned [expiresAtMs]; the four resources were refreshed from LAN. [dataStale] is
     * true when the pin itself succeeded but the LAN fan-out did not (e.g. a transient Wi-Fi blip
     * right after a successful sync-manifest probe) — the toast must not claim fresh data landed.
     */
    data class PinnedUntil(val expiresAtMs: Long, val dataStale: Boolean = false) : LocalModeOutcome

    /** LAN reachable but disclaimed authority (`data_source == "cloud"`) — refreshed from cloud instead. */
    data object LocalNoPin : LocalModeOutcome

    /** LAN unreachable on switch-on — nothing written, pin left untouched. */
    data object LocalUnreachable : LocalModeOutcome

    /** Switch-off (or a no-pin fallback) cloud refresh completed (with or without new data). */
    data object CloudUpdated : LocalModeOutcome

    /** Switch-off cloud refresh found no connectivity at all. */
    data object Offline : LocalModeOutcome

    /** No race could be resolved (no selection, empty cache, and LAN couldn't supply one either). */
    data object NoRace : LocalModeOutcome
}

/**
 * Explicit severity order for folding a fan-out's per-resource [RefreshResult]s into one outcome
 * for the snackbar: a hard error outranks a soft one, and any real attempt outranks a guard-skip.
 */
private fun severity(result: RefreshResult): Int = when (result) {
    is RefreshResult.HttpError -> 5
    RefreshResult.Forbidden -> 4
    RefreshResult.Offline -> 3
    RefreshResult.Updated -> 2
    RefreshResult.NotModified -> 1
    RefreshResult.Skipped -> 0
}

/** Folds a fan-out's results into the single most severe one; empty input is vacuously [RefreshResult.Skipped]. */
fun combineRefreshResults(results: List<RefreshResult>): RefreshResult =
    results.maxByOrNull(::severity) ?: RefreshResult.Skipped

/** A fan-out counts as having actually landed data (or a legitimate no-op) at these severities. */
private val FAN_OUT_SUCCESS_RESULTS =
    setOf(RefreshResult.Updated, RefreshResult.NotModified, RefreshResult.Skipped)

/**
 * Thin orchestration for the local-mode switch and its pin-aware auto-syncs; the lease-decision
 * logic itself lives in the pure `data/lease/RaceLease.kt` ([applySyncResponse], [isPinned]) — this
 * class only sequences calls and executes their outcomes.
 *
 * Every dependency is a **lambda seam** (the project's `tokenProvider` idiom) so
 * `SyncCoordinatorTest` can inject fakes with no MockWebServer/DAO setup.
 *
 * @param readLease synchronous read of the current lease (e.g. a `MutableStateFlow.value` getter).
 * @param writeLease persists a renewed lease or `null` to clear it (state + prefs write-through).
 * @param nowMs the lease time source (trusted time when warm, wall clock otherwise).
 * @param fetchSync one LAN sync-manifest probe; collapses any non-2xx/unreachable result to `null`
 *   (the pure [applySyncResponse] already treats a `null` manifest as unreachable/error → [LeaseAction.Keep]).
 * @param selectedRaceId the currently-selected team's race, if any.
 * @param cachedRaces the offline-readable race list (for [nearestRaceId] when nothing is selected).
 * @param refreshRaces/[refreshTeams]/[refreshLegend]/[refreshMemberTags] the four per-source refresh calls.
 */
class SyncCoordinator(
    private val readLease: () -> RaceLease?,
    private val writeLease: (RaceLease?) -> Unit,
    private val nowMs: () -> Long,
    private val fetchSync: suspend (raceId: Int) -> SyncManifestDto?,
    private val selectedRaceId: suspend () -> Int?,
    private val cachedRaces: suspend () -> List<RaceEntity>,
    private val refreshRaces: suspend (SyncSource) -> RefreshResult,
    private val refreshTeams: suspend (Int, SyncSource) -> RefreshResult,
    private val refreshLegend: suspend (Int, SyncSource) -> RefreshResult,
    private val refreshMemberTags: suspend (Int, SyncSource) -> RefreshResult,
) {

    // Serializes every lease read-decide-write sequence below: `probeLocalAndRenew` (fired from
    // Launch B on every team switch and from a pinned pull-to-refresh) and the user-driven
    // `enterLocalMode`/`exitLocalMode` all run on `applicationScope` with no other ordering
    // guarantee — without this, a probe's stale in-flight `Renew` could land after an explicit
    // `exitLocalMode()` and silently re-pin a race the user just unpinned (or the symmetric case).
    private val leaseMutex = Mutex()

    /** `Local` when [raceId] is currently pinned, else `Cloud`. */
    fun sourceFor(raceId: Int): SyncSource =
        if (isPinned(readLease(), raceId, nowMs())) SyncSource.Local else SyncSource.Cloud

    /**
     * One LAN heartbeat: probes the sync manifest and applies the resulting [LeaseAction] to the
     * stored lease (renew / clear on handback / keep on error). Used at the three probe points —
     * switch-on, Launch B while pinned, and a pinned pull-to-refresh.
     */
    suspend fun probeLocalAndRenew(raceId: Int): LeaseAction = leaseMutex.withLock {
        val action = applySyncResponse(fetchSync(raceId), raceId, nowMs())
        when (action) {
            is LeaseAction.Renew -> writeLease(action.lease)
            LeaseAction.Clear -> writeLease(null)
            LeaseAction.Keep -> {}
        }
        action
    }

    /**
     * Switch-on flow: resolves a race (selected team's race, else the nearest cached one — pulling
     * races from LAN first if the cache is empty), probes the LAN manifest, and either pins +
     * refreshes from LAN, or (manifest reachable but not `local`, incl. an unrecognized
     * `data_source`) refreshes from cloud without pinning, or — LAN unreachable — writes nothing.
     */
    suspend fun enterLocalMode(): LocalModeOutcome = leaseMutex.withLock {
        val selected = selectedRaceId()
        var races = cachedRaces()
        if (selected == null && races.isEmpty()) {
            // Fresh install / empty cache: the LAN pull itself failing (not just "no races in it")
            // must surface as LocalUnreachable, not the generic NoRace — this is the exact
            // no-internet-fresh-install scenario the feature targets.
            val racesResult = refreshRaces(SyncSource.Local)
            races = cachedRaces()
            if (races.isEmpty() && racesResult != RefreshResult.Updated && racesResult != RefreshResult.NotModified) {
                return@withLock LocalModeOutcome.LocalUnreachable
            }
        }
        val raceId = selected ?: nearestRaceId(races, todayIso()) ?: return@withLock LocalModeOutcome.NoRace
        val manifest = fetchSync(raceId)
        when (val action = applySyncResponse(manifest, raceId, nowMs())) {
            is LeaseAction.Renew -> {
                if (isPinned(action.lease, raceId, nowMs())) {
                    writeLease(action.lease)
                    val results = fanOut(raceId, SyncSource.Local)
                    // The pin can succeed while the LAN fan-out itself fails (transient blip right
                    // after a successful manifest probe) — the toast must not claim fresh data.
                    val dataStale = combineRefreshResults(results) !in FAN_OUT_SUCCESS_RESULTS
                    LocalModeOutcome.PinnedUntil(action.lease.expiresAtMs, dataStale)
                } else {
                    // The server's own lease was already expired on arrival — never surface a
                    // "pinned" outcome that isn't actually active; fall back like the no-pin branch.
                    writeLease(null)
                    fanOut(raceId, SyncSource.Cloud)
                    LocalModeOutcome.LocalNoPin
                }
            }
            LeaseAction.Clear -> {
                writeLease(null)
                fanOut(raceId, SyncSource.Cloud)
                LocalModeOutcome.LocalNoPin
            }
            LeaseAction.Keep ->
                if (manifest == null) {
                    LocalModeOutcome.LocalUnreachable
                } else {
                    // Reachable but neither `local` nor `cloud` for this race (unrecognized
                    // data_source or a race mismatch) — never pin on garbage; fall back to cloud.
                    fanOut(raceId, SyncSource.Cloud)
                    LocalModeOutcome.LocalNoPin
                }
        }
    }

    /** Switch-off flow: clears the lease unconditionally, then refreshes from cloud in the background. */
    suspend fun exitLocalMode(): LocalModeOutcome = leaseMutex.withLock {
        writeLease(null)
        val raceId = selectedRaceId() ?: nearestRaceId(cachedRaces(), todayIso())
        val results = if (raceId != null) fanOut(raceId, SyncSource.Cloud) else listOf(refreshRaces(SyncSource.Cloud))
        // A 403/5xx must not be reported as success alongside a genuine offline drop — only a
        // real update/no-change/guard-skip counts as `CloudUpdated`.
        if (combineRefreshResults(results) in FAN_OUT_SUCCESS_RESULTS) {
            LocalModeOutcome.CloudUpdated
        } else {
            LocalModeOutcome.Offline
        }
    }

    /**
     * Pull-to-refresh body: while pinned, probes the LAN first (heartbeat + immediate handback
     * detection), then fans out via [sourceFor] **re-read** — the probe may have just unpinned.
     */
    suspend fun refreshAll(raceId: Int): RefreshResult {
        if (sourceFor(raceId) == SyncSource.Local) {
            probeLocalAndRenew(raceId)
        }
        return combineRefreshResults(fanOut(raceId, sourceFor(raceId)))
    }

    private suspend fun fanOut(raceId: Int, source: SyncSource): List<RefreshResult> = supervisorScope {
        val races = async { refreshRaces(source) }
        val teams = async { refreshTeams(raceId, source) }
        val legend = async { refreshLegend(raceId, source) }
        val memberTags = async { refreshMemberTags(raceId, source) }
        awaitAll(races, teams, legend, memberTags)
    }
}
