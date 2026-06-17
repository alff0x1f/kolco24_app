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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.kolco24.kolco24.data.api.ApiClient
import ru.kolco24.kolco24.data.api.AppSignatureInterceptor
import ru.kolco24.kolco24.data.db.CheckpointDao
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.data.db.SyncMetaDao
import ru.kolco24.kolco24.data.db.SyncMetaEntity

class LegendRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var checkpointDao: FakeCheckpointDao
    private lateinit var syncMetaDao: FakeLegendSyncMetaDao
    private lateinit var repository: LegendRepository
    private lateinit var origin: String

    private val json = Json { ignoreUnknownKeys = true }
    private val callLog = mutableListOf<String>()

    private fun legendJson(raceId: Int = 8, checkpointIds: List<Int> = listOf(101)) = buildString {
        append("""{"race":$raceId,"checkpoints":[""")
        checkpointIds.forEachIndexed { index, id ->
            if (index > 0) append(",")
            append("""{"id":$id,"number":${index + 1},"cost":10,"type":"kp","description":"КП $id"}""")
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
        checkpointDao = FakeCheckpointDao(callLog)
        syncMetaDao = FakeLegendSyncMetaDao(callLog)
        repository = LegendRepository(apiClient, checkpointDao, syncMetaDao, origin)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun success_mapsEntitiesAndStoresEtag() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"")
                .setBody(legendJson(checkpointIds = listOf(101, 102))),
        )

        assertEquals(RefreshResult.Updated, repository.refreshLegend(8))

        val checkpoints = repository.checkpointsForRace(8).first()
        assertEquals(2, checkpoints.size)
        val cp = checkpoints[0]
        assertEquals(101, cp.id)
        assertEquals(8, cp.raceId)
        assertEquals(1, cp.number)
        assertEquals(10, cp.cost)
        assertEquals("kp", cp.type)
        assertFalse(cp.taken)

        assertEquals("\"v1\"", syncMetaDao.getEtag(origin, "race/8/legend"))
    }

    @Test
    fun success_writesDataBeforeEtag() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(legendJson()),
        )

        repository.refreshLegend(8)

        assertEquals(listOf("replaceAllForRace", "upsertEtag"), callLog)
    }

    @Test
    fun success_withoutEtag_storesCheckpointsButSkipsEtagSave() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(legendJson()))

        assertEquals(RefreshResult.Updated, repository.refreshLegend(8))

        assertEquals(1, repository.checkpointsForRace(8).first().size)
        assertNull(syncMetaDao.getEtag(origin, "race/8/legend"))
        assertEquals(listOf("replaceAllForRace"), callLog)
    }

    @Test
    fun notModified_leavesDataUntouched() = runTest {
        checkpointDao.setCheckpoints(listOf(checkpointEntity(id = 99, raceId = 8)))
        syncMetaDao.upsert(SyncMetaEntity(origin, "race/8/legend", "\"v1\""))
        callLog.clear()
        server.enqueue(MockResponse().setResponseCode(304))

        assertEquals(RefreshResult.NotModified, repository.refreshLegend(8))

        val checkpoints = repository.checkpointsForRace(8).first()
        assertEquals(1, checkpoints.size)
        assertTrue("DAO must not be written on 304", callLog.none { it == "replaceAllForRace" })
    }

    @Test
    fun offline_returnsOfflineAndLeavesDataUntouched() = runTest {
        checkpointDao.setCheckpoints(listOf(checkpointEntity(id = 99, raceId = 8)))
        syncMetaDao.upsert(SyncMetaEntity(origin, "race/8/legend", "\"existing\""))
        callLog.clear()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(RefreshResult.Offline, repository.refreshLegend(8))

        assertEquals(1, repository.checkpointsForRace(8).first().size)
        assertEquals("\"existing\"", syncMetaDao.getEtag(origin, "race/8/legend"))
        assertTrue(callLog.none { it == "replaceAllForRace" })
    }

    @Test
    fun forbidden_returnsForbidden() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(RefreshResult.Forbidden, repository.refreshLegend(8))
    }

    @Test
    fun serverError_returnsHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(RefreshResult.HttpError(500), repository.refreshLegend(8))
    }

    @Test
    fun differentRaceIds_useDifferentSyncResources() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"a\"").setBody(legendJson(raceId = 8)),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"b\"")
                .setBody(legendJson(raceId = 9, checkpointIds = listOf(201))),
        )

        repository.refreshLegend(8)
        repository.refreshLegend(9)

        assertEquals("\"a\"", syncMetaDao.getEtag(origin, "race/8/legend"))
        assertEquals("\"b\"", syncMetaDao.getEtag(origin, "race/9/legend"))

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertTrue(first.path!!.contains("/app/race/8/legend/"))
        assertTrue(second.path!!.contains("/app/race/9/legend/"))
    }

    @Test
    fun emptyCheckpoints_replacesExistingRows() = runTest {
        checkpointDao.setCheckpoints(listOf(checkpointEntity(id = 55, raceId = 8)))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"race":8,"checkpoints":[]}"""),
        )

        assertEquals(RefreshResult.Updated, repository.refreshLegend(8))

        assertTrue(repository.checkpointsForRace(8).first().isEmpty())
    }

    private fun checkpointEntity(id: Int, raceId: Int) = CheckpointEntity(
        id = id,
        raceId = raceId,
        number = 1,
        cost = 10,
        type = "kp",
        description = "test",
    )
}

private class FakeCheckpointDao(private val callLog: MutableList<String>) : CheckpointDao {
    private val checkpoints = MutableStateFlow<List<CheckpointEntity>>(emptyList())

    fun setCheckpoints(value: List<CheckpointEntity>) {
        checkpoints.value = value
    }

    override fun observeCheckpointsForRace(raceId: Int): Flow<List<CheckpointEntity>> =
        checkpoints.map { list -> list.filter { it.raceId == raceId } }

    override suspend fun insertCheckpoints(checkpoints: List<CheckpointEntity>) {
        this.checkpoints.value = this.checkpoints.value + checkpoints
    }

    override suspend fun deleteCheckpointsForRace(raceId: Int) {
        checkpoints.value = checkpoints.value.filterNot { it.raceId == raceId }
    }

    override suspend fun revealedForRace(raceId: Int): List<CheckpointEntity> =
        checkpoints.value.filter { it.raceId == raceId && it.cost != null }

    override suspend fun reveal(id: Int, cost: Int, description: String) {
        checkpoints.value = checkpoints.value.map {
            if (it.id == id) it.copy(cost = cost, description = description) else it
        }
    }

    override suspend fun replaceAllForRace(raceId: Int, checkpoints: List<CheckpointEntity>) {
        deleteCheckpointsForRace(raceId)
        insertCheckpoints(checkpoints)
        callLog.add("replaceAllForRace")
    }
}

private class FakeLegendSyncMetaDao(private val callLog: MutableList<String>) : SyncMetaDao {
    private val store = mutableMapOf<Pair<String, String>, String>()

    override suspend fun getEtag(origin: String, resource: String): String? = store[origin to resource]

    override suspend fun upsert(meta: SyncMetaEntity) {
        store[meta.origin to meta.resource] = meta.etag
        callLog.add("upsertEtag")
    }
}
