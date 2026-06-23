package ru.kolco24.kolco24.data.time

import android.content.Context

/**
 * Plain-SharedPreferences store for the [ClockAnchor] (the trusted-time warm-start seed). Mirrors
 * the [ru.kolco24.kolco24.data.ThemePreference] / [ru.kolco24.kolco24.data.InstallId] pattern (pure
 * injected [load]/[save] seam + [fromSharedPreferences] adapter, synchronous read at construction).
 *
 * **Atomic write (P1):** the anchor is stored as **one delimited string under a single key**, not
 * four keys — a process kill between [android.content.SharedPreferences.Editor.apply] calls could
 * otherwise leave a mix of old/new fields that parses but is internally inconsistent. The single key
 * means [write] is one `save` → one `apply()`, so the persisted anchor is always whole or absent.
 *
 * Format: `"$serverEpochMs|$anchorElapsedMs|$capturedWallMs|${bootCount ?: ""}"` — an empty 4th
 * segment encodes `bootCount == null`.
 */
class ClockAnchorStore(
    private val load: (String) -> String?,
    private val save: (String, String?) -> Unit,
) {

    /**
     * Reads the persisted anchor, or `null` if the key is absent or the stored string is malformed
     * (wrong field count, or a non-numeric segment). A blank 4th segment maps to `bootCount = null`.
     */
    fun read(): ClockAnchor? {
        val raw = load(KEY_ANCHOR) ?: return null
        // Kotlin's split keeps a trailing empty segment by default (bootCount == null → 4 parts).
        val parts = raw.split('|')
        if (parts.size != 4) return null
        val serverEpochMs = parts[0].toLongOrNull() ?: return null
        val anchorElapsedMs = parts[1].toLongOrNull() ?: return null
        val capturedWallMs = parts[2].toLongOrNull() ?: return null
        val bootCount = if (parts[3].isEmpty()) null else (parts[3].toIntOrNull() ?: return null)
        return ClockAnchor(serverEpochMs, anchorElapsedMs, capturedWallMs, bootCount)
    }

    /** Persists [anchor] as one serialized string under one key (one `apply()`). */
    fun write(anchor: ClockAnchor) {
        val serialized = "${anchor.serverEpochMs}|${anchor.anchorElapsedMs}|" +
            "${anchor.capturedWallMs}|${anchor.bootCount ?: ""}"
        save(KEY_ANCHOR, serialized)
    }

    /** Removes the persisted anchor. */
    fun clear() {
        save(KEY_ANCHOR, null)
    }

    companion object {
        private const val PREFS_NAME = "kolco24.clock"
        private const val KEY_ANCHOR = "anchor"

        /** Production adapter: backs the store with `SharedPreferences`. A `null` value removes the key. */
        fun fromSharedPreferences(context: Context): ClockAnchorStore {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return ClockAnchorStore(
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
