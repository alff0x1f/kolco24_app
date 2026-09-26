package ru.kolco24.kolco24.data.db

import java.util.Locale

/**
 * [CheckpointEntity.type] vocabulary. Compare only after [normalizeCpType], so a server variant like
 * `"Finish"` / `" test"` is read the same everywhere (КВ cell, track auto start/stop).
 */
const val CP_TYPE_START = "start"
const val CP_TYPE_FINISH = "finish"
const val CP_TYPE_TEST = "test"
const val CP_TYPE_KP = "kp"

/** Trim + lowercase a raw [CheckpointEntity.type]; `null` stays `null`. */
fun normalizeCpType(cpType: String?): String? = cpType?.trim()?.lowercase(Locale.ROOT)
