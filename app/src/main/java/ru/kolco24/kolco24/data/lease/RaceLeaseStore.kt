package ru.kolco24.kolco24.data.lease

import android.content.Context

/**
 * Plain-SharedPreferences store for the current [RaceLease]. Mirrors
 * [ru.kolco24.kolco24.data.time.ClockAnchorStore] (pure injected [load]/[save] seam +
 * [fromSharedPreferences] adapter, synchronous read at construction).
 *
 * **Atomic write:** the lease is stored as **one delimited string under a single key** —
 * `"$raceId|$expiresAtMs"` — so [write] is one `save` → one `apply()`; the persisted lease is
 * always whole or absent. Only one pinned race at a time.
 */
class RaceLeaseStore(
    private val load: (String) -> String?,
    private val save: (String, String?) -> Unit,
) {

    /** Reads the persisted lease, or `null` if the key is absent or the stored string is malformed. */
    fun read(): RaceLease? {
        val raw = load(KEY_LEASE) ?: return null
        val parts = raw.split('|')
        if (parts.size != 2) return null
        val raceId = parts[0].toIntOrNull() ?: return null
        val expiresAtMs = parts[1].toLongOrNull() ?: return null
        return RaceLease(raceId, expiresAtMs)
    }

    /** Persists [lease] as one serialized string under one key (one `apply()`). */
    fun write(lease: RaceLease) {
        save(KEY_LEASE, "${lease.raceId}|${lease.expiresAtMs}")
    }

    /** Removes the persisted lease. */
    fun clear() {
        save(KEY_LEASE, null)
    }

    companion object {
        private const val PREFS_NAME = "kolco24.lease"
        private const val KEY_LEASE = "lease"

        /** Production adapter: backs the store with `SharedPreferences`. A `null` value removes the key. */
        fun fromSharedPreferences(context: Context): RaceLeaseStore {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return RaceLeaseStore(
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
