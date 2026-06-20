package ru.kolco24.kolco24.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.kolco24.kolco24.ui.theme.ThemeMode

class ThemePreferenceTest {

    /** In-memory fake of the injected key-value store. */
    private class FakeStore(var value: String? = null) {
        val load: () -> String? = { value }
        val save: (String) -> Unit = { value = it }
    }

    @Test
    fun defaultsToSystem_whenStoreEmpty() {
        val store = FakeStore(value = null)
        val pref = ThemePreference(store.load, store.save)
        assertEquals(ThemeMode.SYSTEM, pref.mode.value)
    }

    @Test
    fun parsesPreSeededValue_onInit() {
        val store = FakeStore(value = "DARK")
        val pref = ThemePreference(store.load, store.save)
        assertEquals(ThemeMode.DARK, pref.mode.value)
    }

    @Test
    fun preSeededUnknownValue_fallsBackToSystem() {
        val store = FakeStore(value = "AUTO")
        val pref = ThemePreference(store.load, store.save)
        assertEquals(ThemeMode.SYSTEM, pref.mode.value)
    }

    @Test
    fun setMode_persistsEnumName_andEmitsNewValue() {
        val store = FakeStore(value = null)
        val pref = ThemePreference(store.load, store.save)

        pref.setMode(ThemeMode.LIGHT)
        assertEquals("LIGHT", store.value)
        assertEquals(ThemeMode.LIGHT, pref.mode.value)

        pref.setMode(ThemeMode.DARK)
        assertEquals("DARK", store.value)
        assertEquals(ThemeMode.DARK, pref.mode.value)
    }

    @Test
    fun setMode_persistedValue_isReloadedByNewInstance() {
        val store = FakeStore(value = null)
        ThemePreference(store.load, store.save).setMode(ThemeMode.DARK)

        // A fresh instance (e.g. after process death) reads the saved value synchronously.
        val reopened = ThemePreference(store.load, store.save)
        assertEquals(ThemeMode.DARK, reopened.mode.value)
    }

    @Test
    fun emptyStore_loadReturnsNull() {
        val store = FakeStore(value = null)
        assertNull(store.load())
    }
}
