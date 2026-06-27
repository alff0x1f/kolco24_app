package ru.kolco24.kolco24.data.track

import ru.kolco24.kolco24.data.pluralRu

/** Correctly-declined «точка» for a GPS-fix count. */
fun pointsWord(count: Int): String = pluralRu(count, "точка", "точки", "точек")

/** «N точка/точки/точек». */
fun pointsLabel(count: Int): String = "$count ${pointsWord(count)}"

/** Correctly-declined «сегмент» for a recording-session count. */
fun segmentsWord(count: Int): String = pluralRu(count, "сегмент", "сегмента", "сегментов")

/**
 * Pure relative-time label for the upload-status row: «только что» under a minute, «N мин назад» under
 * an hour, else «N ч назад». No `java.time` (minSdk-24/no-desugaring convention) — plain arithmetic on
 * the wall-clock delta. A negative delta (clock skew / future stamp) is clamped to 0 → «только что».
 */
fun relativeTimeRu(atWallMs: Long, nowMs: Long): String {
    val seconds = ((nowMs - atWallMs).coerceAtLeast(0L)) / 1000L
    return when {
        seconds < 60L -> "только что"
        seconds < 3600L -> "${seconds / 60L} мин назад"
        else -> "${seconds / 3600L} ч назад"
    }
}
