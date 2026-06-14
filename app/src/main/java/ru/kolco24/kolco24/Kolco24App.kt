package ru.kolco24.kolco24

import android.app.Application
import android.util.Log
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
            val result = container.raceRepository.refreshRaces()
            Log.i(TAG, "Startup race refresh: $result")
            // Warm the nearest current race's teams + legend so the picker opens instantly. ETag/304
            // keeps this cheap; `nearestRaceId` returns null offline/empty → no-op (return@launch).
            val nearest = nearestRaceId(container.raceRepository.races.first(), todayIso())
                ?: return@launch
            launch {
                val teams = container.teamRepository.refreshTeams(nearest)
                Log.i(TAG, "Prefetch teams for nearest race $nearest: $teams")
            }
            launch {
                val legend = container.legendRepository.refreshLegend(nearest)
                Log.i(TAG, "Prefetch legend for nearest race $nearest: $legend")
            }
        }
        container.applicationScope.launch {
            // `selectedTeam` emits its persisted value immediately on subscribe, so a team chosen in
            // a previous session refreshes both legend and teams exactly once on cold start.
            // `supervisorScope` ties both child launches to the `collectLatest` block so a team
            // switch cancels in-flight fetches, while isolating child failures from each other and
            // from the enclosing `collectLatest` block (so one failing refresh can't kill the loop).
            container.teamRepository.selectedTeam.collectLatest { selected ->
                val raceId = selected?.raceId ?: return@collectLatest
                supervisorScope {
                    launch {
                        val result = container.legendRepository.refreshLegend(raceId)
                        Log.i(TAG, "Legend refresh for race $raceId: $result")
                    }
                    launch {
                        val teams = container.teamRepository.refreshTeams(raceId)
                        Log.i(TAG, "Teams refresh for selected race $raceId: $teams")
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Kolco24App"
    }
}
