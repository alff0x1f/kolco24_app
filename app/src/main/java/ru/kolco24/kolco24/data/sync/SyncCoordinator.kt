package ru.kolco24.kolco24.data.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
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
    /** Switch-on pinned [expiresAtMs]; the four resources were refreshed from LAN. */
    data class PinnedUntil(val expiresAtMs: Long) : LocalModeOutcome

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

    /** `Local` when [raceId] is currently pinned, else `Cloud`. */
    fun sourceFor(raceId: Int): SyncSource =
        if (isPinned(readLease(), raceId, nowMs())) SyncSource.Local else SyncSource.Cloud

    /**
     * One LAN heartbeat: probes the sync manifest and applies the resulting [LeaseAction] to the
     * stored lease (renew / clear on handback / keep on error). Used at the three probe points —
     * switch-on, Launch B while pinned, and a pinned pull-to-refresh.
     */
    suspend fun probeLocalAndRenew(raceId: Int): LeaseAction {
        val action = applySyncResponse(fetchSync(raceId), raceId, nowMs())
        when (action) {
            is LeaseAction.Renew -> writeLease(action.lease)
            LeaseAction.Clear -> writeLease(null)
            LeaseAction.Keep -> {}
        }
        return action
    }

    /**
     * Switch-on flow: resolves a race (selected team's race, else the nearest cached one — pulling
     * races from LAN first if the cache is empty), probes the LAN manifest, and either pins +
     * refreshes from LAN, or (manifest reachable but not `local`, incl. an unrecognized
     * `data_source`) refreshes from cloud without pinning, or — LAN unreachable — writes nothing.
     */
    suspend fun enterLocalMode(): LocalModeOutcome {
        val raceId = resolveRaceId(refreshFromLocalIfEmpty = true) ?: return LocalModeOutcome.NoRace
        val manifest = fetchSync(raceId)
        return when (val action = applySyncResponse(manifest, raceId, nowMs())) {
            is LeaseAction.Renew -> {
                if (isPinned(action.lease, raceId, nowMs())) {
                    writeLease(action.lease)
                    fanOut(raceId, SyncSource.Local)
                    LocalModeOutcome.PinnedUntil(action.lease.expiresAtMs)
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
    suspend fun exitLocalMode(): LocalModeOutcome {
        writeLease(null)
        val raceId = resolveRaceId(refreshFromLocalIfEmpty = false)
        val results = if (raceId != null) fanOut(raceId, SyncSource.Cloud) else listOf(refreshRaces(SyncSource.Cloud))
        return if (combineRefreshResults(results) == RefreshResult.Offline) {
            LocalModeOutcome.Offline
        } else {
            LocalModeOutcome.CloudUpdated
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

    /**
     * Resolves the race to act on: the selected team's race, else the nearest current race in the
     * cache. When [refreshFromLocalIfEmpty] and the cache is empty (a fresh install with nothing
     * synced yet), pulls the race list from LAN once and recomputes before giving up.
     */
    private suspend fun resolveRaceId(refreshFromLocalIfEmpty: Boolean): Int? {
        selectedRaceId()?.let { return it }
        var races = cachedRaces()
        if (races.isEmpty() && refreshFromLocalIfEmpty) {
            refreshRaces(SyncSource.Local)
            races = cachedRaces()
        }
        return nearestRaceId(races, todayIso())
    }

    private suspend fun fanOut(raceId: Int, source: SyncSource): List<RefreshResult> = supervisorScope {
        val races = async { refreshRaces(source) }
        val teams = async { refreshTeams(raceId, source) }
        val legend = async { refreshLegend(raceId, source) }
        val memberTags = async { refreshMemberTags(raceId, source) }
        awaitAll(races, teams, legend, memberTags)
    }
}
