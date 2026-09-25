package ru.kolco24.kolco24.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive, persisted «Все точки» toggle (mirrors [TrackProfilePreference]): when `true` the map and
 * GPX show the raw track (`trackLines(..., filter = false)`), otherwise the spike filter applies.
 * Display-only — the server upload is always raw. The Settings row and the map chip both write
 * through [setShowAllPoints], so they stay in sync via [showAllPoints].
 *
 * The value is read **synchronously** at construction (via [load]); the store is injected as pure
 * [load]/[save] lambdas so the reactive behaviour is JVM-unit-testable; [fromSharedPreferences] is
 * the thin production adapter.
 */
class TrackFilterPreference(load: () -> Boolean, private val save: (Boolean) -> Unit) {

    private val _showAllPoints = MutableStateFlow(load())
    val showAllPoints: StateFlow<Boolean> = _showAllPoints.asStateFlow()

    fun setShowAllPoints(value: Boolean) {
        _showAllPoints.value = value
        save(value)
    }

    companion object {
        private const val PREFS_NAME = "kolco24.settings"
        private const val KEY_SHOW_ALL_POINTS = "track_show_all_points"

        /** Production adapter: backs the store with `SharedPreferences`. */
        fun fromSharedPreferences(context: Context): TrackFilterPreference {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return TrackFilterPreference(
                load = { prefs.getBoolean(KEY_SHOW_ALL_POINTS, false) },
                save = { prefs.edit().putBoolean(KEY_SHOW_ALL_POINTS, it).apply() },
            )
        }
    }
}
