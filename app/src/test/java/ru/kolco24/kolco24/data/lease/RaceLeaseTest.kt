package ru.kolco24.kolco24.data.lease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.api.dto.SyncManifestDto

class RaceLeaseTest {

    // region renewedLease precedence

    @Test
    fun renewedLease_prefersTtl_overAbsoluteAndDefault() {
        val lease = renewedLease(raceId = 1, serverTtlSec = 3600L, serverLeaseExpiresAtSec = 999L, nowMs = 10_000L)
        assertEquals(RaceLease(1, 10_000L + 3600L * 1000L), lease)
    }

    @Test
    fun renewedLease_fallsBackToAbsolute_whenTtlNull() {
        val lease = renewedLease(raceId = 1, serverTtlSec = null, serverLeaseExpiresAtSec = 5_000L, nowMs = 10_000L)
        assertEquals(RaceLease(1, 5_000L * 1000L), lease)
    }

    @Test
    fun renewedLease_fallsBackToClientDefault_whenBothNull() {
        val lease = renewedLease(raceId = 1, serverTtlSec = null, serverLeaseExpiresAtSec = null, nowMs = 10_000L)
        assertEquals(RaceLease(1, 10_000L + DEFAULT_LEASE_MS), lease)
    }

    // endregion

    // region isPinned

    @Test
    fun isPinned_true_whenRaceMatchesAndNotExpired() {
        val lease = RaceLease(raceId = 1, expiresAtMs = 10_000L)
        assertTrue(isPinned(lease, raceId = 1, nowMs = 9_999L))
    }

    @Test
    fun isPinned_false_whenNullLease() {
        assertFalse(isPinned(null, raceId = 1, nowMs = 0L))
    }

    @Test
    fun isPinned_false_whenRaceMismatch() {
        val lease = RaceLease(raceId = 1, expiresAtMs = 10_000L)
        assertFalse(isPinned(lease, raceId = 2, nowMs = 0L))
    }

    @Test
    fun isPinned_false_atExpiryBoundary() {
        val lease = RaceLease(raceId = 1, expiresAtMs = 10_000L)
        assertFalse(isPinned(lease, raceId = 1, nowMs = 10_000L))
    }

    @Test
    fun isPinned_false_pastExpiry() {
        val lease = RaceLease(raceId = 1, expiresAtMs = 10_000L)
        assertFalse(isPinned(lease, raceId = 1, nowMs = 10_001L))
    }

    @Test
    fun isPinned_false_forPastServerLease() {
        // A server lease already in the past on arrival must never read as an active pin.
        val lease = renewedLease(raceId = 1, serverTtlSec = null, serverLeaseExpiresAtSec = 100L, nowMs = 200_000L)
        assertFalse(isPinned(lease, raceId = 1, nowMs = 200_000L))
    }

    // endregion

    // region applySyncResponse

    @Test
    fun applySyncResponse_renews_onLocal() {
        val manifest = SyncManifestDto(race = 1, dataSource = "local", leaseTtlSeconds = 3600L)
        val action = applySyncResponse(manifest, raceId = 1, nowMs = 10_000L)
        assertEquals(LeaseAction.Renew(RaceLease(1, 10_000L + 3600L * 1000L)), action)
    }

    @Test
    fun applySyncResponse_clears_onCloud() {
        val manifest = SyncManifestDto(race = 1, dataSource = "cloud")
        val action = applySyncResponse(manifest, raceId = 1, nowMs = 10_000L)
        assertEquals(LeaseAction.Clear, action)
    }

    @Test
    fun applySyncResponse_keeps_onNullManifest() {
        val action = applySyncResponse(null, raceId = 1, nowMs = 10_000L)
        assertEquals(LeaseAction.Keep, action)
    }

    @Test
    fun applySyncResponse_keeps_onManifestForAnotherRace() {
        val manifest = SyncManifestDto(race = 2, dataSource = "local", leaseTtlSeconds = 3600L)
        val action = applySyncResponse(manifest, raceId = 1, nowMs = 10_000L)
        assertEquals(LeaseAction.Keep, action)
    }

    @Test
    fun applySyncResponse_keeps_onUnknownDataSource() {
        val manifest = SyncManifestDto(race = 1, dataSource = "mirror")
        val action = applySyncResponse(manifest, raceId = 1, nowMs = 10_000L)
        assertEquals(LeaseAction.Keep, action)
    }

    // endregion
}
