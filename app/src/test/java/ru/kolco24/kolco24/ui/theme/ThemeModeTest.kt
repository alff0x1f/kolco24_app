package ru.kolco24.kolco24.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    @Test
    fun isDark_system_followsSystemFlag() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
    }

    @Test
    fun isDark_light_alwaysFalse() {
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = false))
    }

    @Test
    fun isDark_dark_alwaysTrue() {
        assertTrue(ThemeMode.DARK.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
    }

    @Test
    fun parseThemeMode_null_isSystem() {
        assertEquals(ThemeMode.SYSTEM, parseThemeMode(null))
    }

    @Test
    fun parseThemeMode_unknown_isSystem() {
        assertEquals(ThemeMode.SYSTEM, parseThemeMode("AUTO"))
        assertEquals(ThemeMode.SYSTEM, parseThemeMode(""))
        assertEquals(ThemeMode.SYSTEM, parseThemeMode("dark"))
    }

    @Test
    fun parseThemeMode_roundTripsEnumNames() {
        for (mode in ThemeMode.entries) {
            assertEquals(mode, parseThemeMode(mode.name))
        }
    }
}
