package ru.kolco24.kolco24.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.kolco24.kolco24.data.api.ApiClient
import ru.kolco24.kolco24.data.api.AppSignatureInterceptor
import ru.kolco24.kolco24.data.db.MemberTagDao
import ru.kolco24.kolco24.data.db.MemberTagEntity
import ru.kolco24.kolco24.data.db.SyncMetaDao
import ru.kolco24.kolco24.data.db.SyncMetaEntity

class MemberTagsRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var localServer: MockWebServer
    private lateinit var memberTagDao: FakeMemberTagDao
    private lateinit var syncMetaDao: FakeMemberTagsSyncMetaDao
    private lateinit var repository: MemberTagsRepository
    private lateinit var pinnedRepository: MemberTagsRepository
    private lateinit var origin: String
    private lateinit var localOrigin: String
    private lateinit var apiClient: ApiClient
    private lateinit var localApiClient: ApiClient

    private val json = Json { ignoreUnknownKeys = true }
    private val callLog = mutableListOf<String>()

    private fun memberTagsJson(uids: List<Pair<Int, String>> = listOf(101 to "04A2B3C4D5E680")) =
        buildString {
            append("""{"member_tags":[""")
            uids.forEachIndexed { index, (number, uid) ->
                if (index > 0) append(",")
                append("""{"number":$number,"nfc_uid":"$uid"}""")
            }
            append("]}")
        }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        origin = server.url("/").toString()
        localServer = MockWebServer()
        localServer.start()
        localOrigin = localServer.url("/").toString()
        val interceptor = AppSignatureInterceptor(
            keyId = "android-v1",
            secret = "test-secret-123",
            installIdProvider = { "install-abc" },
            appVersion = "2.0.1",
            nowSeconds = { 1718200000L },
        )
        apiClient = ApiClient(origin, OkHttpClient.Builder().addInterceptor(interceptor).build(), json)
        localApiClient =
            ApiClient(localOrigin, OkHttpClient.Builder().addInterceptor(interceptor).build(), json)
        memberTagDao = FakeMemberTagDao(callLog)
        syncMetaDao = FakeMemberTagsSyncMetaDao(callLog)
        repository = MemberTagsRepository(
            apiClient, memberTagDao, syncMetaDao, origin,
            localApiClient, localOrigin, isRacePinned = { false },
        )
        // A SyncSource.Local call is only ever legitimately made while pinned (see the
        // Local-not-pinned guard in refreshMemberTags) — Local-source tests use this instance.
        pinnedRepository = MemberTagsRepository(
            apiClient, memberTagDao, syncMetaDao, origin,
            localApiClient, localOrigin, isRacePinned = { true },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        localServer.shutdown()
    }

    @Test
    fun success_mapsEntitiesAndStoresEtag() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"")
                .setBody(memberTagsJson(listOf(101 to "04A2B3C4D5E680", 102 to "0411223344"))),
        )

        assertEquals(RefreshResult.Updated, repository.refreshMemberTags(8))

        val tags = repository.observeForRace(8).first()
        assertEquals(2, tags.size)
        val tag = tags.single { it.nfcUid == "04A2B3C4D5E680" }
        assertEquals(101, tag.number)
        assertEquals(8, tag.raceId)

        assertEquals("\"v1\"", syncMetaDao.getEtag(origin, "race/8/member_tags"))
    }

    @Test
    fun success_writesDataBeforeEtag() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(memberTagsJson()),
        )

        repository.refreshMemberTags(8)

        assertEquals(listOf("deleteEtag", "deleteEtag", "replaceAllForRace", "upsertEtag"), callLog)
    }

    @Test
    fun success_withoutEtag_storesTagsAndWritesSyncMarker() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(memberTagsJson()))

        assertEquals(RefreshResult.Updated, repository.refreshMemberTags(8))

        assertEquals(1, repository.observeForRace(8).first().size)
        // ETag resource stays null (server sent no ETag), but sync-marker is written.
        assertNull(syncMetaDao.getEtag(origin, "race/8/member_tags"))
        assertNotNull(syncMetaDao.getEtag(origin, "race/8/member_tags/synced"))
        // Both other-origin markers are invalidated first, then data is replaced, then the
        // own-origin marker/etag is written last.
        assertEquals(listOf("deleteEtag", "deleteEtag", "replaceAllForRace", "upsertEtag"), callLog)
    }

    @Test
    fun notModified_leavesDataUntouched() = runTest {
        memberTagDao.setTags(listOf(MemberTagEntity(raceId = 8, nfcUid = "AABB", number = 99)))
        syncMetaDao.upsert(SyncMetaEntity(origin, "race/8/member_tags", "\"v1\""))
        callLog.clear()
        server.enqueue(MockResponse().setResponseCode(304))

        assertEquals(RefreshResult.NotModified, repository.refreshMemberTags(8))

        assertEquals(1, repository.observeForRace(8).first().size)
        assertTrue("DAO must not be written on 304", callLog.none { it == "replaceAllForRace" })
    }

    @Test
    fun offline_returnsOfflineAndLeavesDataUntouched() = runTest {
        memberTagDao.setTags(listOf(MemberTagEntity(raceId = 8, nfcUid = "AABB", number = 99)))
        syncMetaDao.upsert(SyncMetaEntity(origin, "race/8/member_tags", "\"existing\""))
        callLog.clear()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(RefreshResult.Offline, repository.refreshMemberTags(8))

        assertEquals(1, repository.observeForRace(8).first().size)
        assertEquals("\"existing\"", syncMetaDao.getEtag(origin, "race/8/member_tags"))
        assertTrue(callLog.none { it == "replaceAllForRace" })
    }

    @Test
    fun forbidden_returnsForbidden() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(RefreshResult.Forbidden, repository.refreshMemberTags(8))
        assertFalse(repository.hasBeenSynced(8))
    }

    @Test
    fun serverError_returnsHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(RefreshResult.HttpError(500), repository.refreshMemberTags(8))
        assertFalse(repository.hasBeenSynced(8))
    }

    @Test
    fun emptyList_replacesExistingRows() = runTest {
        memberTagDao.setTags(listOf(MemberTagEntity(raceId = 8, nfcUid = "AABB", number = 55)))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"member_tags":[]}"""))

        assertEquals(RefreshResult.Updated, repository.refreshMemberTags(8))

        assertTrue(repository.observeForRace(8).first().isEmpty())
    }

    @Test
    fun hasBeenSynced_falseBeforeFirstSync() = runTest {
        assertFalse(repository.hasBeenSynced(8))
    }

    @Test
    fun hasBeenSynced_trueAfterSuccessfulSyncWithEtag() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(memberTagsJson()),
        )
        repository.refreshMemberTags(8)
        assertTrue(repository.hasBeenSynced(8))
    }

    @Test
    fun hasBeenSynced_trueAfterSuccessfulSyncWithoutEtag() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(memberTagsJson()))
        repository.refreshMemberTags(8)
        // A valid 200 with no ETag still marks the pool as synced via the sync-marker resource.
        assertTrue(repository.hasBeenSynced(8))
    }

    @Test
    fun hasBeenSynced_trueAfterNotModified() = runTest {
        syncMetaDao.upsert(SyncMetaEntity(origin, "race/8/member_tags", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(304))
        repository.refreshMemberTags(8)
        assertTrue(repository.hasBeenSynced(8))
    }

    @Test
    fun hasBeenSynced_scoped_toRace() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"a\"").setBody(memberTagsJson()),
        )
        repository.refreshMemberTags(8)
        assertTrue(repository.hasBeenSynced(8))
        assertFalse(repository.hasBeenSynced(9))
    }

    @Test
    fun hasBeenSynced_local_readsLocalOriginNotCloud() = runTest {
        localServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(memberTagsJson()),
        )
        pinnedRepository.refreshMemberTags(8, SyncSource.Local)

        assertTrue(repository.hasBeenSynced(8, SyncSource.Local))
        assertFalse("a Local sync must not be visible under the Cloud origin", repository.hasBeenSynced(8))
    }

    @Test
    fun observeHasBeenSynced_falseBeforeFirstSync() = runTest {
        assertFalse(repository.observeHasBeenSynced(8).first())
    }

    @Test
    fun observeHasBeenSynced_trueAfterSuccessfulSyncWithEtag() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(memberTagsJson()),
        )
        repository.refreshMemberTags(8)
        assertTrue(repository.observeHasBeenSynced(8).first())
    }

    @Test
    fun observeHasBeenSynced_trueAfterSuccessfulSyncWithoutEtag() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(memberTagsJson()))
        repository.refreshMemberTags(8)
        assertTrue(repository.observeHasBeenSynced(8).first())
    }

    @Test
    fun observeHasBeenSynced_scoped_toRace() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"a\"").setBody(memberTagsJson()),
        )
        repository.refreshMemberTags(8)
        assertTrue(repository.observeHasBeenSynced(8).first())
        assertFalse(repository.observeHasBeenSynced(9).first())
    }

    @Test
    fun observeHasBeenSynced_local_readsLocalOriginNotCloud() = runTest {
        localServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(memberTagsJson()),
        )
        pinnedRepository.refreshMemberTags(8, SyncSource.Local)

        assertTrue(repository.observeHasBeenSynced(8, SyncSource.Local).first())
        assertFalse(
            "a Local sync must not be visible under the Cloud origin",
            repository.observeHasBeenSynced(8).first(),
        )
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun observeHasBeenSynced_emitsOnFetchCompletingAfterSubscriptionStarts() = runTest {
        // Regression test for the bug this Flow was introduced to fix: a collector that starts
        // observing before a fetch completes (e.g. JudgeScanScreen mounting while a warm-up sync
        // is still in flight) must see the flip to `true` on that same subscription, without
        // re-subscribing.
        val emissions = mutableListOf<Boolean>()
        backgroundScope.launch { repository.observeHasBeenSynced(8).toList(emissions) }
        runCurrent()
        assertEquals(listOf(false), emissions)

        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(memberTagsJson()),
        )
        repository.refreshMemberTags(8)
        runCurrent()

        assertEquals(listOf(false, true), emissions)
    }

    @Test
    fun findByUid_resolvesAgainstThatRacePool() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(memberTagsJson(listOf(101 to "04A2B3C4D5E680"))),
        )
        repository.refreshMemberTags(8)

        assertEquals(101, repository.findByUid(8, "04A2B3C4D5E680")?.number)
        assertNull(repository.findByUid(8, "DEADBEEF"))
        assertNull("uid belongs to race 8, not 9", repository.findByUid(9, "04A2B3C4D5E680"))
    }

    @Test
    fun localSource_hitsLocalClientAndStoresEtagUnderLocalOrigin() = runTest {
        localServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"local-v1\"").setBody(memberTagsJson()),
        )

        assertEquals(RefreshResult.Updated, pinnedRepository.refreshMemberTags(8, SyncSource.Local))

        assertEquals(1, pinnedRepository.observeForRace(8).first().size)
        assertEquals("\"local-v1\"", syncMetaDao.getEtag(localOrigin, "race/8/member_tags"))
        assertNull("cloud origin must stay untouched", syncMetaDao.getEtag(origin, "race/8/member_tags"))
        assertEquals(0, server.requestCount)
        assertEquals(1, localServer.requestCount)
    }

    @Test
    fun localSource_invalidatesStaleCloudEtag() = runTest {
        // A prior cloud fetch left an ETag; switching to Local must drop it so a later
        // switch-back to Cloud can't earn a 304 against rows this Local fetch just overwrote.
        syncMetaDao.upsert(SyncMetaEntity(origin, "race/8/member_tags", "\"cloud-v1\""))
        localServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"local-v1\"").setBody(memberTagsJson()),
        )

        assertEquals(RefreshResult.Updated, pinnedRepository.refreshMemberTags(8, SyncSource.Local))

        assertNull(syncMetaDao.getEtag(origin, "race/8/member_tags"))
        assertEquals("\"local-v1\"", syncMetaDao.getEtag(localOrigin, "race/8/member_tags"))
    }

    @Test
    fun cloudSource_invalidatesStaleLocalEtag() = runTest {
        syncMetaDao.upsert(SyncMetaEntity(localOrigin, "race/8/member_tags", "\"local-v1\""))
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(memberTagsJson()),
        )

        assertEquals(RefreshResult.Updated, repository.refreshMemberTags(8))

        assertNull(syncMetaDao.getEtag(localOrigin, "race/8/member_tags"))
        assertEquals("\"v1\"", syncMetaDao.getEtag(origin, "race/8/member_tags"))
    }

    @Test
    fun localSource_invalidatesStaleCloudSyncMarker() = runTest {
        // A prior cloud fetch synced an empty pool without an ETag (marker-only). Switching to
        // Local and overwriting the shared table must also invalidate that marker so a later
        // switch-back to Cloud can't trust hasBeenSynced() against rows Local just replaced.
        syncMetaDao.upsert(SyncMetaEntity(origin, "race/8/member_tags/synced", "1"))
        localServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"local-v1\"").setBody(memberTagsJson()),
        )

        assertEquals(RefreshResult.Updated, pinnedRepository.refreshMemberTags(8, SyncSource.Local))

        assertFalse(
            "a stale cloud sync-marker must not survive a Local write to the shared table",
            repository.hasBeenSynced(8, SyncSource.Cloud),
        )
    }

    @Test
    fun cloudSource_invalidatesStaleLocalSyncMarker() = runTest {
        syncMetaDao.upsert(SyncMetaEntity(localOrigin, "race/8/member_tags/synced", "1"))
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(memberTagsJson()),
        )

        assertEquals(RefreshResult.Updated, repository.refreshMemberTags(8))

        assertFalse(
            "a stale local sync-marker must not survive a Cloud write to the shared table",
            repository.hasBeenSynced(8, SyncSource.Local),
        )
    }

    @Test
    fun localSource_unpinnedRace_skipsWithoutTouchingNetworkOrData() = runTest {
        memberTagDao.setTags(listOf(MemberTagEntity(raceId = 8, nfcUid = "AABB", number = 99)))
        val unpinnedRepo = MemberTagsRepository(
            apiClient, memberTagDao, syncMetaDao, origin,
            localApiClient, localOrigin, isRacePinned = { false },
        )

        assertEquals(RefreshResult.Skipped, unpinnedRepo.refreshMemberTags(8, SyncSource.Local))

        assertEquals(0, localServer.requestCount)
        assertEquals(1, unpinnedRepo.observeForRace(8).first().size)
    }

    @Test
    fun localSource_unpinDisappearingMidFlight_doesNotPersist() = runTest {
        localServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"local-v1\"").setBody(memberTagsJson()),
        )
        var checks = 0
        val unpinningRepo = MemberTagsRepository(
            apiClient, memberTagDao, syncMetaDao, origin,
            localApiClient, localOrigin, isRacePinned = { checks++ == 0 },
        )

        assertEquals(RefreshResult.Skipped, unpinningRepo.refreshMemberTags(8, SyncSource.Local))

        assertTrue(
            "in-flight LAN response must not persist once unpinned",
            unpinningRepo.observeForRace(8).first().isEmpty(),
        )
        assertNull(syncMetaDao.getEtag(localOrigin, "race/8/member_tags"))
    }

    @Test
    fun cloudSource_pinnedRace_skipsWithoutTouchingNetworkOrData() = runTest {
        memberTagDao.setTags(listOf(MemberTagEntity(raceId = 8, nfcUid = "AABB", number = 99)))
        val pinnedRepo = MemberTagsRepository(
            apiClient, memberTagDao, syncMetaDao, origin,
            localApiClient, localOrigin, isRacePinned = { true },
        )

        assertEquals(RefreshResult.Skipped, pinnedRepo.refreshMemberTags(8, SyncSource.Cloud))

        assertEquals(0, server.requestCount)
        assertEquals(1, pinnedRepo.observeForRace(8).first().size)
    }

    @Test
    fun cloudSource_pinAppearingMidFlight_doesNotPersist() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(memberTagsJson()))
        var checks = 0
        val pinnedRepo = MemberTagsRepository(
            apiClient, memberTagDao, syncMetaDao, origin,
            localApiClient, localOrigin, isRacePinned = { checks++ > 0 },
        )

        assertEquals(RefreshResult.Skipped, pinnedRepo.refreshMemberTags(8, SyncSource.Cloud))

        assertTrue(
            "in-flight response must not persist once pinned",
            pinnedRepo.observeForRace(8).first().isEmpty(),
        )
        assertNull(syncMetaDao.getEtag(origin, "race/8/member_tags"))
    }

    @Test
    fun unpinnedCloud_behaviorUnchanged() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(memberTagsJson()))

        assertEquals(RefreshResult.Updated, repository.refreshMemberTags(8, SyncSource.Cloud))

        assertEquals(1, repository.observeForRace(8).first().size)
        assertEquals("\"v1\"", syncMetaDao.getEtag(origin, "race/8/member_tags"))
    }

    @Test
    fun differentRaceIds_useDifferentSyncResources() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"a\"")
                .setBody(memberTagsJson(listOf(101 to "AAA"))),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"b\"")
                .setBody(memberTagsJson(listOf(201 to "BBB"))),
        )

        repository.refreshMemberTags(8)
        repository.refreshMemberTags(9)

        assertEquals("\"a\"", syncMetaDao.getEtag(origin, "race/8/member_tags"))
        assertEquals("\"b\"", syncMetaDao.getEtag(origin, "race/9/member_tags"))

        // Disjoint rows: each race only holds its own pool.
        assertEquals(listOf("AAA"), repository.observeForRace(8).first().map { it.nfcUid })
        assertEquals(listOf("BBB"), repository.observeForRace(9).first().map { it.nfcUid })

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertTrue(first.path!!.contains("/app/race/8/member_tags/"))
        assertTrue(second.path!!.contains("/app/race/9/member_tags/"))
    }
}

private class FakeMemberTagDao(private val callLog: MutableList<String>) : MemberTagDao {
    private val tags = MutableStateFlow<List<MemberTagEntity>>(emptyList())

    fun setTags(value: List<MemberTagEntity>) {
        tags.value = value
    }

    override fun observeForRace(raceId: Int): Flow<List<MemberTagEntity>> =
        tags.map { list -> list.filter { it.raceId == raceId } }

    override suspend fun findByUid(raceId: Int, nfcUid: String): MemberTagEntity? =
        tags.value.firstOrNull { it.raceId == raceId && it.nfcUid == nfcUid }

    override suspend fun insertAll(tags: List<MemberTagEntity>) {
        this.tags.value = this.tags.value + tags
    }

    override suspend fun deleteForRace(raceId: Int) {
        tags.value = tags.value.filterNot { it.raceId == raceId }
    }

    override suspend fun replaceAllForRace(raceId: Int, tags: List<MemberTagEntity>) {
        deleteForRace(raceId)
        insertAll(tags)
        callLog.add("replaceAllForRace")
    }
}

private class FakeMemberTagsSyncMetaDao(private val callLog: MutableList<String>) : SyncMetaDao {
    private val store = mutableMapOf<Pair<String, String>, String>()
    // Bumped on every write so observeEtagsExist re-evaluates for already-subscribed collectors,
    // mirroring Room's invalidation-tracking behavior on the real Flow.
    private val version = MutableStateFlow(0)

    override suspend fun getEtag(origin: String, resource: String): String? = store[origin to resource]

    override fun observeEtagsExist(origin: String, resource1: String, resource2: String): Flow<Boolean> =
        version.map { store.containsKey(origin to resource1) || store.containsKey(origin to resource2) }

    override suspend fun upsert(meta: SyncMetaEntity) {
        store[meta.origin to meta.resource] = meta.etag
        callLog.add("upsertEtag")
        version.value++
    }

    override suspend fun deleteEtag(origin: String, resource: String) {
        store.remove(origin to resource)
        callLog.add("deleteEtag")
        version.value++
    }
}
