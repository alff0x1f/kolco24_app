package ru.kolco24.kolco24.data.track

/**
 * Pure, Android-free Russian plural picker (JVM-unit-testable).
 *
 * Standard rules over the magnitude of [count]:
 * - teens (11..14) → [many] (1 точка vs 11 точек);
 * - last digit 1 → [one] (1, 21, 41…);
 * - last digit 2..4 → [few] (2, 23, 82…);
 * - otherwise (0, 5..20, 25…) → [many].
 */
fun pluralRu(count: Int, one: String, few: String, many: String): String {
    val n = if (count < 0) -count else count
    if (n % 100 in 11..14) return many
    return when (n % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}

/** Correctly-declined «точка» for a GPS-fix count. */
fun pointsWord(count: Int): String = pluralRu(count, "точка", "точки", "точек")

/** «N точка/точки/точек». */
fun pointsLabel(count: Int): String = "$count ${pointsWord(count)}"

/** Correctly-declined «сегмент» for a recording-session count. */
fun segmentsWord(count: Int): String = pluralRu(count, "сегмент", "сегмента", "сегментов")

/** «N сегмент/сегмента/сегментов». */
fun segmentsLabel(count: Int): String = "$count ${segmentsWord(count)}"
