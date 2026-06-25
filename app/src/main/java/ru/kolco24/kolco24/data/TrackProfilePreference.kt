package ru.kolco24.kolco24.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.kolco24.kolco24.data.track.TrackProfile
import ru.kolco24.kolco24.data.track.parseTrackProfile

/**
 * Reactive, persisted GPS-track recording profile (mirrors [ThemePreference]). The current value is
 * read **synchronously** at construction (via [load]) so the service reads the stored profile
 * immediately on start with no cold-read flash.
 *
 * The store is injected as pure [load]/[save] lambdas so the reactive behaviour can be unit-tested
 * on the JVM without Android; [fromSharedPreferences] is the thin production adapter (mirrors
 * [ThemePreference]).
 */
class TrackProfilePreference(load: () -> String?, private val save: (String) -> Unit) {

    private val _profile = MutableStateFlow(parseTrackProfile(load()))
    val profile: StateFlow<TrackProfile> = _profile.asStateFlow()

    fun setProfile(p: TrackProfile) {
        _profile.value = p
        save(p.name)
    }

    companion object {
        private const val PREFS_NAME = "kolco24.settings"
        private const val KEY_TRACK_PROFILE = "track_profile"

        /** Production adapter: backs the store with `SharedPreferences`. */
        fun fromSharedPreferences(context: Context): TrackProfilePreference {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return TrackProfilePreference(
                load = { prefs.getString(KEY_TRACK_PROFILE, null) },
                save = { prefs.edit().putString(KEY_TRACK_PROFILE, it).apply() },
            )
        }
    }
}
