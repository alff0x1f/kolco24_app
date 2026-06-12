package ru.kolco24.kolco24.data

import android.content.Context
import java.util.UUID

/**
 * Stable per-install identifier sent as `X-Install-Id` (see docs/API.md). The API caps it at
 * 64 chars; a UUID string is 36, so it always fits.
 *
 * The read-or-generate logic is a pure function over an injected key-value store so it can be
 * unit-tested on the JVM without Android; [fromSharedPreferences] is the thin production adapter.
 */
object InstallId {

    private const val PREFS_NAME = "kolco24.install"
    private const val KEY_INSTALL_ID = "install_id"

    /**
     * Returns the existing id from [load], or generates one, persists it via [save], and returns it.
     */
    fun getOrCreate(load: () -> String?, save: (String) -> Unit): String {
        val existing = load()
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        save(generated)
        return generated
    }

    /** Production adapter: backs [getOrCreate] with `SharedPreferences`. */
    fun fromSharedPreferences(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return getOrCreate(
            load = { prefs.getString(KEY_INSTALL_ID, null) },
            save = { prefs.edit().putString(KEY_INSTALL_ID, it).apply() },
        )
    }
}
