package ru.kolco24.kolco24.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFilterPreferenceTest {

    /** In-memory fake of the injected key-value store; counts saves. */
    private class FakeStore(var value: Boolean = false) {
        var saves = 0
        val load: () -> Boolean = { value }
        val save: (Boolean) -> Unit = { value = it; saves++ }
    }

    @Test
    fun initialValue_comesFromLoad_false() {
        val store = FakeStore(value = false)
        val pref = TrackFilterPreference(store.load, store.save)
        assertFalse(pref.showAllPoints.value)
        assertEquals(0, store.saves)
    }

    @Test
    fun initialValue_comesFromLoad_true() {
        val store = FakeStore(value = true)
        val pref = TrackFilterPreference(store.load, store.save)
        assertTrue(pref.showAllPoints.value)
    }

    @Test
    fun setShowAllPoints_updatesFlow_andCallsSave() {
        val store = FakeStore(value = false)
        val pref = TrackFilterPreference(store.load, store.save)

        pref.setShowAllPoints(true)
        assertTrue(pref.showAllPoints.value)
        assertTrue(store.value)
        assertEquals(1, store.saves)

        pref.setShowAllPoints(false)
        assertFalse(pref.showAllPoints.value)
        assertFalse(store.value)
        assertEquals(2, store.saves)
    }

    @Test
    fun persistedValue_isReloadedByNewInstance() {
        val store = FakeStore(value = false)
        TrackFilterPreference(store.load, store.save).setShowAllPoints(true)

        // A fresh instance (e.g. after process death) reads the saved value synchronously.
        val reopened = TrackFilterPreference(store.load, store.save)
        assertTrue(reopened.showAllPoints.value)
    }
}
