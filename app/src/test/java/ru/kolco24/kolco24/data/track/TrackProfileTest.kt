package ru.kolco24.kolco24.data.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackProfileTest {

    @Test
    fun parseTrackProfile_null_isPrecise() {
        assertEquals(TrackProfile.Precise, parseTrackProfile(null))
    }

    @Test
    fun parseTrackProfile_economy_roundTrips() {
        assertEquals(TrackProfile.Economy, parseTrackProfile("Economy"))
    }

    @Test
    fun parseTrackProfile_precise_roundTrips() {
        assertEquals(TrackProfile.Precise, parseTrackProfile("Precise"))
    }

    @Test
    fun parseTrackProfile_unknown_isPrecise() {
        assertEquals(TrackProfile.Precise, parseTrackProfile("garbage"))
        assertEquals(TrackProfile.Precise, parseTrackProfile(""))
        assertEquals(TrackProfile.Precise, parseTrackProfile("economy"))
    }

    @Test
    fun parseTrackProfile_roundTripsAllEnumNames() {
        for (profile in TrackProfile.entries) {
            assertEquals(profile, parseTrackProfile(profile.name))
        }
    }

    @Test
    fun economy_hasThreeMinuteIntervalAndDelay() {
        assertEquals(180_000L, TrackProfile.Economy.intervalMs)
        assertEquals(180_000L, TrackProfile.Economy.maxDelayMs)
        assertTrue(TrackProfile.Economy.highAccuracy)
    }

    @Test
    fun precise_matchesCurrentBehavior() {
        assertEquals(15_000L, TrackProfile.Precise.intervalMs)
        assertEquals(60_000L, TrackProfile.Precise.maxDelayMs)
        assertTrue(TrackProfile.Precise.highAccuracy)
    }
}
