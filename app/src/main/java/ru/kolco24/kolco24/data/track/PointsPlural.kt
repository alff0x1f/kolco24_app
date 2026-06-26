package ru.kolco24.kolco24.data.track

/**
 * Pure, Android-free Russian declension of «точка» for a GPS-fix count (JVM-unit-testable).
 *
 * Standard Russian plural rules:
 * - teens (11..14) → «точек» (1 точка vs 11 точек);
 * - last digit 1 → «точка» (1, 21, 41…);
 * - last digit 2..4 → «точки» (2, 23, 44…);
 * - otherwise (0, 5..20, 25…) → «точек».
 */
fun pointsWord(count: Int): String {
    val n = if (count < 0) -count else count
    val mod100 = n % 100
    if (mod100 in 11..14) return "точек"
    return when (n % 10) {
        1 -> "точка"
        2, 3, 4 -> "точки"
        else -> "точек"
    }
}

/** «N точка/точки/точек» with the count and the correctly-declined word. */
fun pointsLabel(count: Int): String = "$count ${pointsWord(count)}"
