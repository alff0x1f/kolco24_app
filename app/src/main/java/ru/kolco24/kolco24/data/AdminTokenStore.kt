package ru.kolco24.kolco24.data

import android.content.Context

/**
 * The persisted three values of an admin session: the opaque 30-day bearer [token], the [email]
 * the admin logged in with, and the raw ISO [expiresAt] string from the server (UTC, `Z`-suffixed).
 */
data class StoredSession(val token: String, val email: String, val expiresAt: String)

/**
 * Plain-SharedPreferences store for the admin bearer session. Mirrors the [ThemePreference] /
 * [InstallId] pattern (pure injected store + [fromSharedPreferences] adapter, synchronous read at
 * call time, no `java.time`) — but with a **multi-key seam** ([load]/[save] keyed by name) because
 * three keys are persisted, deliberately differing from [ThemePreference]'s single-value seam.
 *
 * The token is a revocable 30-day bearer (not a password), so plain SharedPreferences is acceptable.
 */
class AdminTokenStore(
    private val load: (String) -> String?,
    private val save: (String, String?) -> Unit,
) {

    /** Reads the stored session, or `null` if any of the three keys is absent. */
    fun read(): StoredSession? {
        val token = load(KEY_TOKEN) ?: return null
        val email = load(KEY_EMAIL) ?: return null
        val expiresAt = load(KEY_EXPIRES_AT) ?: return null
        return StoredSession(token, email, expiresAt)
    }

    /** Persists all three values. */
    fun write(token: String, email: String, expiresAt: String) {
        save(KEY_TOKEN, token)
        save(KEY_EMAIL, email)
        save(KEY_EXPIRES_AT, expiresAt)
    }

    /** Removes all three keys. */
    fun clear() {
        save(KEY_TOKEN, null)
        save(KEY_EMAIL, null)
        save(KEY_EXPIRES_AT, null)
    }

    companion object {
        private const val PREFS_NAME = "kolco24.settings"
        private const val KEY_TOKEN = "admin_token"
        private const val KEY_EMAIL = "admin_email"
        private const val KEY_EXPIRES_AT = "admin_token_expires_at"

        /** Production adapter: backs the store with `SharedPreferences`. A `null` value removes the key. */
        fun fromSharedPreferences(context: Context): AdminTokenStore {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return AdminTokenStore(
                load = { key -> prefs.getString(key, null) },
                save = { key, value ->
                    prefs.edit().apply {
                        if (value == null) remove(key) else putString(key, value)
                    }.apply()
                },
            )
        }
    }
}
