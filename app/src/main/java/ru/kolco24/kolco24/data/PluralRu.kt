package ru.kolco24.kolco24.data

/**
 * Pure, Android-free Russian plural picker (JVM-unit-testable), shared by every count-with-noun in
 * the app (баллы, точки, сегменты, человек).
 *
 * Standard rules over the magnitude of [count]:
 * - teens (11..14) → [many] (1 балл vs 11 баллов);
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
