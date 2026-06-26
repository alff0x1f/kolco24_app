package ru.kolco24.kolco24.data.track

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure engine-selection decision shared by both [LocationEngineFactory.create] and
 * [LocationEngineFactory.createCurrentLocationProvider]. The real engines ([FusedLocationEngine]/
 * [LegacyLocationEngine]), the one-shot providers ([FusedCurrentLocationProvider]/
 * [LegacyCurrentLocationProvider]), and the Android `create*` adapters are not unit-tested by repo
 * convention — they only wrap platform/GMS APIs.
 */
class LocationEngineFactoryTest {

    @Test
    fun gmsAvailable_choosesFused() {
        assertEquals(EngineType.Fused, LocationEngineFactory.chooseEngineType(true))
    }

    @Test
    fun gmsUnavailable_choosesLegacy() {
        assertEquals(EngineType.Legacy, LocationEngineFactory.chooseEngineType(false))
    }
}
