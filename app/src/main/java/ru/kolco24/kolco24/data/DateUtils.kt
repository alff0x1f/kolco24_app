package ru.kolco24.kolco24.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.kolco24.kolco24.data.db.RaceEntity

/** Today as a `YYYY-MM-DD` string (no `java.time` — minSdk 24 without core library desugaring). */
fun todayIso(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/** Last day a race is relevant: [RaceEntity.dateEnd] when present, otherwise the start [RaceEntity.date]. */
internal fun RaceEntity.effectiveEnd(): String = dateEnd ?: date

/**
 * Id of the soonest-starting current race; `null` when none is current.
 * "Current" means `effectiveEnd >= today` (still relevant, just like `splitRaces`); among those the
 * one with the earliest start [RaceEntity.date] is chosen (lexicographic ISO comparison).
 * Used to warm the team/legend cache at startup; offline/empty → `null` → no-op.
 */
fun nearestRaceId(races: List<RaceEntity>, today: String): Int? =
    races.filter { it.effectiveEnd() >= today }.minByOrNull { it.date }?.id
