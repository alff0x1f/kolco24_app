package ru.kolco24.kolco24.data

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.kolco24.kolco24.data.track.TrackProfile

class TrackProfilePreferenceTest {

    /** In-memory fake of the injected key-value store. */
    private class FakeStore(var value: String? = null) {
        val load: () -> String? = { value }
        val save: (String) -> Unit = { value = it }
    }

    @Test
    fun defaultsToPrecise_whenStoreEmpty() {
        val store = FakeStore(value = null)
        val pref = TrackProfilePreference(store.load, store.save)
        assertEquals(TrackProfile.Precise, pref.profile.value)
    }

    @Test
    fun parsesPreSeededValue_onInit() {
        val store = FakeStore(value = "Economy")
        val pref = TrackProfilePreference(store.load, store.save)
        assertEquals(TrackProfile.Economy, pref.profile.value)
    }

    @Test
    fun preSeededUnknownValue_fallsBackToPrecise() {
        val store = FakeStore(value = "Turbo")
        val pref = TrackProfilePreference(store.load, store.save)
        assertEquals(TrackProfile.Precise, pref.profile.value)
    }

    @Test
    fun setProfile_persistsEnumName_andEmitsNewValue() {
        val store = FakeStore(value = null)
        val pref = TrackProfilePreference(store.load, store.save)

        pref.setProfile(TrackProfile.Economy)
        assertEquals("Economy", store.value)
        assertEquals(TrackProfile.Economy, pref.profile.value)

        pref.setProfile(TrackProfile.Precise)
        assertEquals("Precise", store.value)
        assertEquals(TrackProfile.Precise, pref.profile.value)
    }

    @Test
    fun setProfile_persistedValue_isReloadedByNewInstance() {
        val store = FakeStore(value = null)
        TrackProfilePreference(store.load, store.save).setProfile(TrackProfile.Economy)

        // A fresh instance (e.g. after process death) reads the saved value synchronously.
        val reopened = TrackProfilePreference(store.load, store.save)
        assertEquals(TrackProfile.Economy, reopened.profile.value)
    }
}
