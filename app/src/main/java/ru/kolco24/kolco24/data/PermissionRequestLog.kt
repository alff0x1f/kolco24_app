package ru.kolco24.kolco24.data

import android.content.Context

/** How a permission request result updates a persisted "denied before" flag (Android-free). */
enum class DenialLogUpdate { Set, Clear, Keep }

/**
 * Persisted "this runtime permission was really denied before" flags, the `deniedBefore` input of the
 * pure `ui/common/PermissionPrompts` decisions (which also decide every [apply] update). Must outlive
 * the process: a session-only flag resets on relaunch, so a permanent denial after a restart would be
 * a request the system answers instantly with no UI and no settings route. Records a real denial, not
 * a request — a dismissed dialog must keep the system dialog available — and is cleared on a grant
 * (the system's unused-app auto-reset only revokes granted permissions). `kolco24.permissions.xml` is
 * excluded from backup — on a restored device the permission was never asked.
 *
 * The store is injected as pure [load]/[save] lambdas; [fromSharedPreferences] is the thin production
 * adapter (mirrors [ThemePreference]).
 */
class PermissionRequestLog(
    private val load: (String) -> Boolean,
    private val save: (String, Boolean) -> Unit,
) {
    fun wasDenied(key: String): Boolean = load(key)

    fun apply(key: String, update: DenialLogUpdate) {
        when (update) {
            DenialLogUpdate.Set -> save(key, true)
            DenialLogUpdate.Clear -> if (load(key)) save(key, false)
            DenialLogUpdate.Keep -> Unit
        }
    }

    companion object {
        private const val PREFS_NAME = "kolco24.permissions"
        /** One flag for FINE+COARSE — they are always requested together. */
        const val LOCATION = "location"
        const val NOTIFICATIONS = "notifications"

        /** Production adapter: backs the store with `SharedPreferences`. */
        fun fromSharedPreferences(context: Context): PermissionRequestLog {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return PermissionRequestLog(
                load = { prefs.getBoolean(it, false) },
                save = { key, value -> prefs.edit().putBoolean(key, value).apply() },
            )
        }
    }
}
