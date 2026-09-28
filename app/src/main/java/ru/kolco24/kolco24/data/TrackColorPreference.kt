package ru.kolco24.kolco24.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive, persisted «Цвет трека по скорости» toggle (mirrors [TrackFilterPreference], default
 * **on**). Display-only: colors the map track by speed and marks stops. Ignored while «Все точки»
 * is on — raw spikes would give fake speeds. A separate class, not a second flag in
 * [TrackFilterPreference], so that class and its tests stay untouched (duplicate, don't couple).
 */
class TrackColorPreference(load: () -> Boolean, private val save: (Boolean) -> Unit) {

    private val _colorBySpeed = MutableStateFlow(load())
    val colorBySpeed: StateFlow<Boolean> = _colorBySpeed.asStateFlow()

    fun setColorBySpeed(value: Boolean) {
        _colorBySpeed.value = value
        save(value)
    }

    companion object {
        private const val PREFS_NAME = "kolco24.settings"
        private const val KEY_COLOR_BY_SPEED = "track_color_by_speed"

        /** Production adapter: backs the store with `SharedPreferences`; an absent key reads `true`. */
        fun fromSharedPreferences(context: Context): TrackColorPreference {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return TrackColorPreference(
                load = { prefs.getBoolean(KEY_COLOR_BY_SPEED, true) },
                save = { prefs.edit().putBoolean(KEY_COLOR_BY_SPEED, it).apply() },
            )
        }
    }
}
