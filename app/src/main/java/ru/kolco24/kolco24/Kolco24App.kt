package ru.kolco24.kolco24

import android.app.Application
import android.util.Log
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Process entry point. Owns the [AppContainer] and kicks off fire-and-forget syncs on startup;
 * the results are only logged (no UI here). Two independent launches: a one-shot race refresh and
 * a reactive legend refresh that follows the selected team.
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
        }
        container.applicationScope.launch {
            // `selectedTeam` emits its persisted value immediately on subscribe, so a team chosen in
            // a previous session refreshes the legend exactly once on cold start. `collectLatest`
            // cancels an in-flight fetch when the team switches to another race.
            container.teamRepository.selectedTeam.collectLatest { selected ->
                val raceId = selected?.raceId ?: return@collectLatest
                val result = container.legendRepository.refreshLegend(raceId)
                Log.i(TAG, "Legend refresh for race $raceId: $result")
            }
        }
    }

    private companion object {
        const val TAG = "Kolco24App"
    }
}
