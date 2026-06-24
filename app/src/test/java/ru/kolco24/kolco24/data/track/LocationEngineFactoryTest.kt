package ru.kolco24.kolco24.data.track

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure engine-selection decision. The real engines ([FusedLocationEngine]/
 * [LegacyLocationEngine]) and the Android [LocationEngineFactory.create] adapter are not unit-tested
 * by repo convention — they only wrap platform/GMS APIs.
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
