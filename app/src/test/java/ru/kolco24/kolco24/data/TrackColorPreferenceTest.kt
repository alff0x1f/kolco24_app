package ru.kolco24.kolco24.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackColorPreferenceTest {

    /** In-memory fake of the injected key-value store; counts saves. */
    private class FakeStore(var value: Boolean = true) {
        var saves = 0
        val load: () -> Boolean = { value }
        val save: (Boolean) -> Unit = { value = it; saves++ }
    }

    @Test
    fun initialValue_comesFromLoad() {
        val on = FakeStore(value = true)
        assertTrue(TrackColorPreference(on.load, on.save).colorBySpeed.value)
        val off = FakeStore(value = false)
        assertFalse(TrackColorPreference(off.load, off.save).colorBySpeed.value)
        assertEquals(0, on.saves + off.saves)
    }

    @Test
    fun setColorBySpeed_updatesFlow_andCallsSave() {
        val store = FakeStore(value = true)
        val pref = TrackColorPreference(store.load, store.save)

        pref.setColorBySpeed(false)
        assertFalse(pref.colorBySpeed.value)
        assertFalse(store.value)
        assertEquals(1, store.saves)

        pref.setColorBySpeed(true)
        assertTrue(pref.colorBySpeed.value)
        assertTrue(store.value)
        assertEquals(2, store.saves)
    }

    @Test
    fun persistedValue_isReloadedByNewInstance() {
        val store = FakeStore(value = true)
        TrackColorPreference(store.load, store.save).setColorBySpeed(false)
        assertFalse(TrackColorPreference(store.load, store.save).colorBySpeed.value)
    }
}
