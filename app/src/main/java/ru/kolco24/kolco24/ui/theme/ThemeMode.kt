package ru.kolco24.kolco24.ui.theme

/**
 * Pure, Android-free user theme preference (mirrors `CheckpointColor.kt` / `NfcUid.kt` — JVM-unit-tested).
 *
 * [SYSTEM] follows the OS dark-mode setting (today's default behavior); [LIGHT]/[DARK] override it.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Resolve whether the dark color scheme applies, given the current OS dark-mode state. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean =
    when (this) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

/** Parse a persisted enum name to [ThemeMode]; `null`/unknown default to [ThemeMode.SYSTEM] (forward-compatible). */
fun parseThemeMode(raw: String?): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
