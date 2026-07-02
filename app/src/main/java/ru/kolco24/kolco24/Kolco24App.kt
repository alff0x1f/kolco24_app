package ru.kolco24.kolco24

import android.app.Application
import android.util.Log
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.kolco24.kolco24.data.SyncSource
import ru.kolco24.kolco24.data.nearestRaceId
import ru.kolco24.kolco24.data.todayIso

/**
 * Process entry point. Owns the [AppContainer] and kicks off fire-and-forget syncs on startup;
 * results are only logged (no UI here). Two independent launches: Launch A does a one-shot race
 * refresh then prefetches the nearest race's teams + legend; Launch B reactively refreshes both
 * teams and legend whenever the selected team changes.
 */
class Kolco24App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.applicationScope.launch {
            // Reclaim photo-mark frames orphaned by process death mid-capture (dir exists, row never
            // written). Best-effort; failures are swallowed by the supervisor scope.
            runCatching { container.sweepOrphanPhotoDirs() }
                .onFailure { Log.w(TAG, "Orphan photo sweep failed", it) }
        }
        container.applicationScope.launch {
            val result = container.raceRepository.refreshRaces()
            Log.i(TAG, "Startup race refresh: $result")
            // Warm the nearest current race's teams + legend so the picker opens instantly. ETag/304
            // keeps this cheap; `nearestRaceId` returns null offline/empty → no-op (return@launch).
            val nearest = nearestRaceId(container.raceRepository.races.first(), todayIso())
                ?: return@launch
            // Pin-aware: a race pinned to LAN (e.g. re-entering local mode after a process
            // restart) prefetches from LAN instead of cloud.
            val source = container.syncCoordinator.sourceFor(nearest)
            launch {
                val teams = container.teamRepository.refreshTeams(nearest, source)
                Log.i(TAG, "Prefetch teams for nearest race $nearest: $teams")
            }
            launch {
                val legend = container.legendRepository.refreshLegend(nearest, source)
                Log.i(TAG, "Prefetch legend for nearest race $nearest: $legend")
            }
            launch {
                val memberTags = container.memberTagsRepository.refreshMemberTags(nearest, source)
                Log.i(TAG, "Prefetch member tags for nearest race $nearest: $memberTags")
            }
        }
        container.applicationScope.launch {
            // `selectedTeam` emits its persisted value immediately on subscribe, so a team chosen in
            // a previous session refreshes both legend and teams exactly once on cold start.
            // `supervisorScope` ties both child launches to the `collectLatest` block so a team
            // switch cancels in-flight fetches, while isolating sibling failures from each other
            // (a failing refresh does not cancel its sibling). Repos return RefreshResult and never
            // throw, so exceptions never propagate out of supervisorScope in practice.
            container.teamRepository.selectedTeam.collectLatest { selected ->
                val raceId = selected?.raceId ?: return@collectLatest
                supervisorScope {
                    // Pinned race: probe the LAN once (heartbeat + immediate handback detection)
                    // before re-reading the source — the probe may have just unpinned it.
                    if (container.syncCoordinator.sourceFor(raceId) == SyncSource.Local) {
                        container.syncCoordinator.probeLocalAndRenew(raceId)
                    }
                    val source = container.syncCoordinator.sourceFor(raceId)
                    launch {
                        val result = container.legendRepository.refreshLegend(raceId, source)
                        Log.i(TAG, "Legend refresh for race $raceId: $result")
                    }
                    launch {
                        val teams = container.teamRepository.refreshTeams(raceId, source)
                        Log.i(TAG, "Teams refresh for selected race $raceId: $teams")
                    }
                    launch {
                        val memberTags = container.memberTagsRepository.refreshMemberTags(raceId, source)
                        Log.i(TAG, "Member tags refresh for selected race $raceId: $memberTags")
                    }
                    launch {
                        // Opportunistic re-send across ALL pending scopes (not just this race/team) so
                        // points stranded under an old race/team still flush. Guarded by a tryLock —
                        // a concurrent service-stop flush just skips this one.
                        container.trackRepository.uploadAllPending()
                    }
                    launch {
                        // Same opportunistic re-send for КП takes (marks): any mark stranded under an
                        // old race/team flushes here. Idempotent by client id, tryLock-guarded.
                        container.markRepository.uploadAllPending()
                    }
                    launch {
                        // Same opportunistic re-send for judge start/finish piks: raceId-scoped (no
                        // team dimension), idempotent by client id, tryLock-guarded.
                        container.judgeScanRepository.uploadAllPending()
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Kolco24App"
    }
}
