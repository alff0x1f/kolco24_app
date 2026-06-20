package ru.kolco24.kolco24.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.kolco24.kolco24.ui.theme.ThemeMode
import ru.kolco24.kolco24.ui.theme.parseThemeMode

/**
 * Reactive, persisted app theme preference. The current value is read **synchronously** at
 * construction (via [load]) so the first composed frame already reflects the stored mode — no
 * light-theme flash on a cold start with a saved DARK mode.
 *
 * The store is injected as pure [load]/[save] lambdas so the reactive behaviour can be unit-tested
 * on the JVM without Android; [fromSharedPreferences] is the thin production adapter (mirrors
 * [InstallId]).
 */
class ThemePreference(load: () -> String?, private val save: (String) -> Unit) {

    private val _mode = MutableStateFlow(parseThemeMode(load()))
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun setMode(m: ThemeMode) {
        _mode.value = m
        save(m.name)
    }

    companion object {
        private const val PREFS_NAME = "kolco24.settings"
        private const val KEY_THEME_MODE = "theme_mode"

        /** Production adapter: backs the store with `SharedPreferences`. */
        fun fromSharedPreferences(context: Context): ThemePreference {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return ThemePreference(
                load = { prefs.getString(KEY_THEME_MODE, null) },
                save = { prefs.edit().putString(KEY_THEME_MODE, it).apply() },
            )
        }
    }
}
