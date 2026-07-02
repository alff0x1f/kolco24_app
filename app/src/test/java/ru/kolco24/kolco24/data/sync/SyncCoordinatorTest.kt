package ru.kolco24.kolco24.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.kolco24.kolco24.data.RefreshResult
import ru.kolco24.kolco24.data.SyncSource
import ru.kolco24.kolco24.data.api.dto.SyncManifestDto
import ru.kolco24.kolco24.data.db.RaceEntity
import ru.kolco24.kolco24.data.lease.LeaseAction
import ru.kolco24.kolco24.data.lease.RaceLease
import ru.kolco24.kolco24.data.lease.isPinned

class SyncCoordinatorTest {

    private var lease: RaceLease? = null
    private var now: Long = 0L
    private var manifest: SyncManifestDto? = null
    private var races: List<RaceEntity> = emptyList()
    private var selectedRaceId: Int? = null
    private val calls = mutableListOf<String>()

    private var racesResult: RefreshResult = RefreshResult.Updated
    private var teamsResult: RefreshResult = RefreshResult.Updated
    private var legendResult: RefreshResult = RefreshResult.Updated
    private var memberTagsResult: RefreshResult = RefreshResult.Updated

    /** Fires on `refreshRaces(Local)` so the empty-cache-fallback test can seed the cache. */
    private var onRefreshRacesLocal: (() -> Unit)? = null

    // Far-future date so `nearestRaceId`'s `effectiveEnd >= today` check (real wall-clock `todayIso()`
    // inside SyncCoordinator, not injectable) never goes stale regardless of when this test runs.
    private fun race(id: Int) = RaceEntity(
        id = id, name = "Race $id", slug = "race-$id", date = "2099-01-01",
        dateEnd = null, place = "Here", regStatus = "open",
    )

    private fun buildCoordinator(): SyncCoordinator = SyncCoordinator(
        readLease = { lease },
        writeLease = { lease = it },
        nowMs = { now },
        fetchSync = { raceId -> calls.add("fetchSync($raceId)"); manifest },
        selectedRaceId = { selectedRaceId },
        cachedRaces = { races },
        refreshRaces = { source ->
            calls.add("refreshRaces($source)")
            if (source == SyncSource.Local) onRefreshRacesLocal?.invoke()
            racesResult
        },
        refreshTeams = { raceId, source -> calls.add("refreshTeams($raceId,$source)"); teamsResult },
        refreshLegend = { raceId, source -> calls.add("refreshLegend($raceId,$source)"); legendResult },
        refreshMemberTags = { raceId, source -> calls.add("refreshMemberTags($raceId,$source)"); memberTagsResult },
    )

    // region sourceFor

    @Test
    fun sourceFor_local_whenPinned() = runTest {
        lease = RaceLease(1, 10_000L)
        now = 5_000L
        assertEquals(SyncSource.Local, buildCoordinator().sourceFor(1))
    }

    @Test
    fun sourceFor_cloud_whenNotPinned() = runTest {
        assertEquals(SyncSource.Cloud, buildCoordinator().sourceFor(1))
    }

    // endregion

    // region probeLocalAndRenew

    @Test
    fun probe_renewsLease_onLocal() = runTest {
        manifest = SyncManifestDto(race = 1, dataSource = "local", leaseTtlSeconds = 3600L)
        now = 1_000L
        val action = buildCoordinator().probeLocalAndRenew(1)
        assertTrue(action is LeaseAction.Renew)
        assertEquals(RaceLease(1, 1_000L + 3600L * 1000L), lease)
    }

    @Test
    fun probe_clearsLease_onCloudHandback() = runTest {
        lease = RaceLease(1, 10_000L)
        manifest = SyncManifestDto(race = 1, dataSource = "cloud")
        val action = buildCoordinator().probeLocalAndRenew(1)
        assertEquals(LeaseAction.Clear, action)
        assertNull(lease)
    }

    @Test
    fun probe_keepsLease_onUnreachable() = runTest {
        lease = RaceLease(1, 10_000L)
        manifest = null
        val action = buildCoordinator().probeLocalAndRenew(1)
        assertEquals(LeaseAction.Keep, action)
        assertEquals(RaceLease(1, 10_000L), lease)
    }

    // endregion

    // region enterLocalMode

    @Test
    fun enterLocalMode_pinsAndFansOutLocal_onLocalDataSource() = runTest {
        selectedRaceId = 7
        manifest = SyncManifestDto(race = 7, dataSource = "local", leaseTtlSeconds = 3600L)
        now = 1_000L
        val outcome = buildCoordinator().enterLocalMode()
        assertEquals(LocalModeOutcome.PinnedUntil(1_000L + 3600L * 1000L), outcome)
        assertEquals(RaceLease(7, 1_000L + 3600L * 1000L), lease)
        assertTrue(calls.contains("refreshRaces(Local)"))
        assertTrue(calls.contains("refreshTeams(7,Local)"))
        assertTrue(calls.contains("refreshLegend(7,Local)"))
        assertTrue(calls.contains("refreshMemberTags(7,Local)"))
    }

    @Test
    fun enterLocalMode_pinsButFlagsStale_whenLanFanOutFails() = runTest {
        selectedRaceId = 7
        manifest = SyncManifestDto(race = 7, dataSource = "local", leaseTtlSeconds = 3600L)
        now = 1_000L
        teamsResult = RefreshResult.Offline
        val outcome = buildCoordinator().enterLocalMode()
        assertEquals(LocalModeOutcome.PinnedUntil(1_000L + 3600L * 1000L, dataStale = true), outcome)
        // The pin itself must still land even though the fan-out failed.
        assertEquals(RaceLease(7, 1_000L + 3600L * 1000L), lease)
    }

    @Test
    fun enterLocalMode_noPinAndFansOutCloud_onCloudDataSource() = runTest {
        selectedRaceId = 7
        manifest = SyncManifestDto(race = 7, dataSource = "cloud")
        val outcome = buildCoordinator().enterLocalMode()
        assertEquals(LocalModeOutcome.LocalNoPin, outcome)
        assertNull(lease)
        assertTrue(calls.contains("refreshTeams(7,Cloud)"))
        assertTrue(
            "no LAN race-scoped rows must be fetched on a cloud handback",
            calls.none { it == "refreshTeams(7,Local)" || it == "refreshLegend(7,Local)" || it == "refreshMemberTags(7,Local)" },
        )
    }

    @Test
    fun enterLocalMode_writesNothing_whenUnreachable() = runTest {
        selectedRaceId = 7
        manifest = null
        val outcome = buildCoordinator().enterLocalMode()
        assertEquals(LocalModeOutcome.LocalUnreachable, outcome)
        assertNull(lease)
        assertTrue(
            "nothing must be fetched on an unreachable LAN",
            calls.none {
                it.startsWith("refreshTeams") || it.startsWith("refreshLegend") ||
                    it.startsWith("refreshMemberTags") || it.startsWith("refreshRaces")
            },
        )
    }

    @Test
    fun enterLocalMode_emptyCache_pullsRacesFromLanFirstThenPins() = runTest {
        selectedRaceId = null
        races = emptyList()
        manifest = SyncManifestDto(race = 9, dataSource = "local", leaseTtlSeconds = 3600L)
        onRefreshRacesLocal = { races = listOf(race(9)) }

        val outcome = buildCoordinator().enterLocalMode()

        assertTrue(outcome is LocalModeOutcome.PinnedUntil)
        assertEquals(9, lease?.raceId)
        assertTrue(calls.contains("refreshTeams(9,Local)"))
    }

    @Test
    fun enterLocalMode_noRace_whenNothingResolvable() = runTest {
        selectedRaceId = null
        races = emptyList()
        // The LAN races pull succeeds (Updated) but genuinely returns nothing.
        val outcome = buildCoordinator().enterLocalMode()
        assertEquals(LocalModeOutcome.NoRace, outcome)
    }

    @Test
    fun enterLocalMode_localUnreachable_whenEmptyCacheAndLanRacesPullFails() = runTest {
        selectedRaceId = null
        races = emptyList()
        racesResult = RefreshResult.Offline
        // Cache stays empty because the LAN races pull itself failed, not because there's
        // genuinely nothing — must surface as LocalUnreachable, not the generic NoRace.
        val outcome = buildCoordinator().enterLocalMode()
        assertEquals(LocalModeOutcome.LocalUnreachable, outcome)
    }

    @Test
    fun enterLocalMode_pastServerLease_isNotSurfacedAsPinned() = runTest {
        selectedRaceId = 7
        now = 200_000L
        // `lease_expires_at` in the past (seconds) — already expired on arrival.
        manifest = SyncManifestDto(race = 7, dataSource = "local", leaseExpiresAt = 100L)

        val outcome = buildCoordinator().enterLocalMode()

        assertEquals(LocalModeOutcome.LocalNoPin, outcome)
        assertNull("must actively clear the lease, not merely read as unpinned", lease)
        assertFalse(isPinned(lease, 7, now))
    }

    // endregion

    // region exitLocalMode

    @Test
    fun exitLocalMode_alwaysUnpinsAndRefreshesCloud() = runTest {
        lease = RaceLease(7, 10_000L)
        selectedRaceId = 7
        val outcome = buildCoordinator().exitLocalMode()
        assertNull(lease)
        assertEquals(LocalModeOutcome.CloudUpdated, outcome)
        assertTrue(calls.contains("refreshTeams(7,Cloud)"))
        assertTrue(calls.contains("refreshLegend(7,Cloud)"))
        assertTrue(calls.contains("refreshMemberTags(7,Cloud)"))
    }

    @Test
    fun exitLocalMode_offline_whenCloudUnreachable() = runTest {
        lease = RaceLease(7, 10_000L)
        selectedRaceId = 7
        racesResult = RefreshResult.Offline
        teamsResult = RefreshResult.Offline
        legendResult = RefreshResult.Offline
        memberTagsResult = RefreshResult.Offline
        val outcome = buildCoordinator().exitLocalMode()
        assertEquals(LocalModeOutcome.Offline, outcome)
        assertNull("unpins even when the cloud refresh fails", lease)
    }

    @Test
    fun exitLocalMode_unpinsEvenWithNoResolvableRace() = runTest {
        lease = RaceLease(7, 10_000L)
        selectedRaceId = null
        races = emptyList()
        val outcome = buildCoordinator().exitLocalMode()
        assertNull(lease)
        assertEquals(LocalModeOutcome.CloudUpdated, outcome)
        assertTrue(calls.contains("refreshRaces(Cloud)"))
    }

    @Test
    fun exitLocalMode_httpError_isNotReportedAsCloudUpdated() = runTest {
        lease = RaceLease(7, 10_000L)
        selectedRaceId = 7
        teamsResult = RefreshResult.HttpError(500)
        val outcome = buildCoordinator().exitLocalMode()
        assertNull(lease)
        assertEquals(LocalModeOutcome.Offline, outcome)
    }

    @Test
    fun exitLocalMode_forbidden_isNotReportedAsCloudUpdated() = runTest {
        lease = RaceLease(7, 10_000L)
        selectedRaceId = 7
        legendResult = RefreshResult.Forbidden
        val outcome = buildCoordinator().exitLocalMode()
        assertNull(lease)
        assertEquals(LocalModeOutcome.Offline, outcome)
    }

    // endregion

    // region refreshAll

    @Test
    fun refreshAll_unpinned_neverTouchesLan() = runTest {
        val result = buildCoordinator().refreshAll(7)
        assertEquals(RefreshResult.Updated, result)
        assertTrue(calls.none { it.endsWith(",Local)") })
    }

    @Test
    fun refreshAll_pinned_probesThenFansOutLocal_whenStillPinned() = runTest {
        lease = RaceLease(7, 10_000L)
        now = 1_000L
        manifest = SyncManifestDto(race = 7, dataSource = "local", leaseTtlSeconds = 3600L)
        val result = buildCoordinator().refreshAll(7)
        assertEquals(RefreshResult.Updated, result)
        assertTrue(calls.contains("refreshTeams(7,Local)"))
        assertEquals(RaceLease(7, 1_000L + 3600L * 1000L), lease)
    }

    @Test
    fun refreshAll_pinned_fallsBackToCloud_onHandbackDuringProbe() = runTest {
        lease = RaceLease(7, 10_000L)
        now = 1_000L
        manifest = SyncManifestDto(race = 7, dataSource = "cloud")
        val result = buildCoordinator().refreshAll(7)
        assertEquals(RefreshResult.Updated, result)
        assertNull(lease)
        assertTrue(calls.contains("refreshTeams(7,Cloud)"))
        assertTrue(calls.none { it == "refreshTeams(7,Local)" })
    }

    @Test
    fun refreshAll_pinned_staysLocal_whenProbeUnreachable() = runTest {
        // Connectivity loss during the mid-pull probe must never release the pin — the fan-out
        // still routes Local using the (unchanged) stored lease.
        lease = RaceLease(7, 10_000L)
        now = 1_000L
        manifest = null
        val result = buildCoordinator().refreshAll(7)
        assertEquals(RefreshResult.Updated, result)
        assertEquals(RaceLease(7, 10_000L), lease)
        assertTrue(calls.contains("refreshTeams(7,Local)"))
        assertTrue(calls.none { it == "refreshTeams(7,Cloud)" })
    }

    // endregion

    // region combineRefreshResults

    @Test
    fun combineRefreshResults_severityOrder() {
        assertEquals(
            RefreshResult.HttpError(500),
            combineRefreshResults(
                listOf(RefreshResult.Forbidden, RefreshResult.HttpError(500), RefreshResult.Updated),
            ),
        )
        assertEquals(
            RefreshResult.Forbidden,
            combineRefreshResults(listOf(RefreshResult.Offline, RefreshResult.Forbidden, RefreshResult.Skipped)),
        )
        assertEquals(
            RefreshResult.Offline,
            combineRefreshResults(listOf(RefreshResult.Offline, RefreshResult.Updated, RefreshResult.NotModified)),
        )
        assertEquals(
            RefreshResult.Updated,
            combineRefreshResults(listOf(RefreshResult.Updated, RefreshResult.NotModified, RefreshResult.Skipped)),
        )
        assertEquals(
            RefreshResult.NotModified,
            combineRefreshResults(listOf(RefreshResult.NotModified, RefreshResult.Skipped)),
        )
        assertEquals(RefreshResult.Skipped, combineRefreshResults(listOf(RefreshResult.Skipped)))
    }

    @Test
    fun combineRefreshResults_emptyList_isVacuouslySkipped() {
        assertEquals(RefreshResult.Skipped, combineRefreshResults(emptyList()))
    }

    // endregion
}
