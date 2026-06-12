package ru.kolco24.kolco24

import android.app.Application
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Process entry point. Owns the [AppContainer] and kicks off a fire-and-forget race refresh on
 * startup; the result is only logged (no UI in this task).
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
    }

    private companion object {
        const val TAG = "Kolco24App"
    }
}
