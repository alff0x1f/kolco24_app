package ru.kolco24.kolco24.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private lateinit var memberTagDao: FakeMemberTagDao
    private lateinit var syncMetaDao: FakeMemberTagsSyncMetaDao
    private lateinit var repository: MemberTagsRepository
    private lateinit var origin: String

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
        val interceptor = AppSignatureInterceptor(
            keyId = "android-v1",
            secret = "test-secret-123",
            installIdProvider = { "install-abc" },
            appVersion = "2.0.1",
            nowSeconds = { 1718200000L },
        )
        val apiClient = ApiClient(origin, OkHttpClient.Builder().addInterceptor(interceptor).build(), json)
        memberTagDao = FakeMemberTagDao(callLog)
        syncMetaDao = FakeMemberTagsSyncMetaDao(callLog)
        repository = MemberTagsRepository(apiClient, memberTagDao, syncMetaDao, origin)
    }

    @After
    fun tearDown() {
        server.shutdown()
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

        assertEquals(listOf("replaceAllForRace", "upsertEtag"), callLog)
    }

    @Test
    fun success_withoutEtag_storesTagsAndWritesSyncMarker() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(memberTagsJson()))

        assertEquals(RefreshResult.Updated, repository.refreshMemberTags(8))

        assertEquals(1, repository.observeForRace(8).first().size)
        // ETag resource stays null (server sent no ETag), but sync-marker is written.
        assertNull(syncMetaDao.getEtag(origin, "race/8/member_tags"))
        assertNotNull(syncMetaDao.getEtag(origin, "race/8/member_tags/synced"))
        // replaceAllForRace is written first, sync-marker second.
        assertEquals(listOf("replaceAllForRace", "upsertEtag"), callLog)
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

    override suspend fun getEtag(origin: String, resource: String): String? = store[origin to resource]

    override suspend fun upsert(meta: SyncMetaEntity) {
        store[meta.origin to meta.resource] = meta.etag
        callLog.add("upsertEtag")
    }
}
