package ru.kolco24.kolco24.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import ru.kolco24.kolco24.data.api.ApiClient
import ru.kolco24.kolco24.data.api.AppSignatureInterceptor
import ru.kolco24.kolco24.data.db.RaceDao
import ru.kolco24.kolco24.data.db.RaceEntity
import ru.kolco24.kolco24.data.db.SyncMetaDao
import ru.kolco24.kolco24.data.db.SyncMetaEntity

class RaceRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var raceDao: FakeRaceDao
    private lateinit var syncMetaDao: FakeSyncMetaDao
    private lateinit var repository: RaceRepository
    private lateinit var origin: String

    private val json = Json { ignoreUnknownKeys = true }
    private val callLog = mutableListOf<String>()

    private fun racesJson(id: Int, name: String) = """
        {
          "races": [
            {
              "id": $id,
              "name": "$name",
              "slug": "race-$id",
              "date": "2026-06-20",
              "date_end": null,
              "place": "Сосновый бор",
              "reg_status": "open",
              "is_legend_visible": true
            }
          ]
        }
    """.trimIndent()

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
        raceDao = FakeRaceDao(callLog)
        syncMetaDao = FakeSyncMetaDao(callLog)
        repository = RaceRepository(apiClient, raceDao, syncMetaDao, origin)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun success_replacesTableAndStoresEtag() = runTest {
        raceDao.set(listOf(entity(99, "Stale race")))
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(racesJson(8, "Кольцо24")),
        )

        assertEquals(RefreshResult.Updated, repository.refreshRaces())

        val stored = repository.races.first()
        assertEquals(1, stored.size)
        assertEquals(8, stored[0].id)
        assertEquals("Кольцо24", stored[0].name)
        assertEquals("\"v1\"", syncMetaDao.getEtag(origin, "races"))
    }

    @Test
    fun notModified_leavesDatabaseUntouched() = runTest {
        raceDao.set(listOf(entity(8, "Cached race")))
        syncMetaDao.upsert(SyncMetaEntity(origin, "races", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(304))

        assertEquals(RefreshResult.NotModified, repository.refreshRaces())

        val stored = repository.races.first()
        assertEquals(1, stored.size)
        assertEquals("Cached race", stored[0].name)
        assertEquals("\"v1\"", syncMetaDao.getEtag(origin, "races"))
    }

    @Test
    fun secondRefresh_sendsStoredEtag() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(racesJson(8, "Кольцо24")),
        )
        server.enqueue(MockResponse().setResponseCode(304))

        repository.refreshRaces()
        server.takeRequest() // first request, no stored etag yet
        repository.refreshRaces()

        val second = server.takeRequest()
        assertEquals("\"v1\"", second.getHeader("If-None-Match"))
    }

    @Test
    fun offline_returnsOfflineAndLeavesDatabaseUntouched() = runTest {
        raceDao.set(listOf(entity(8, "Cached race")))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(RefreshResult.Offline, repository.refreshRaces())

        val stored = repository.races.first()
        assertEquals(1, stored.size)
        assertEquals("Cached race", stored[0].name)
        assertNull(syncMetaDao.getEtag(origin, "races"))
    }

    @Test
    fun forbidden_returnsForbidden() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(RefreshResult.Forbidden, repository.refreshRaces())
    }

    @Test
    fun serverError_returnsHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(RefreshResult.HttpError(500), repository.refreshRaces())
    }

    @Test
    fun success_withoutEtag_storesRacesButSkipsEtagSave() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(racesJson(8, "Кольцо24")))

        assertEquals(RefreshResult.Updated, repository.refreshRaces())

        assertEquals(1, repository.races.first().size)
        assertNull(syncMetaDao.getEtag(origin, "races"))
    }

    @Test
    fun success_withEmptyList_clearsTable() = runTest {
        raceDao.set(listOf(entity(99, "Stale race")))
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody("""{"races":[]}"""),
        )

        assertEquals(RefreshResult.Updated, repository.refreshRaces())

        assertEquals(0, repository.races.first().size)
    }

    @Test
    fun success_writesDataBeforeEtag() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(racesJson(8, "Кольцо24")),
        )

        repository.refreshRaces()

        assertEquals(listOf("replaceAll", "upsertEtag"), callLog)
    }

    private fun entity(id: Int, name: String) = RaceEntity(
        id = id,
        name = name,
        slug = "race-$id",
        date = "2026-06-20",
        dateEnd = null,
        place = "Сосновый бор",
        regStatus = "open",
        isLegendVisible = true,
    )
}

/** In-memory [RaceDao] backed by a [MutableStateFlow] so [observeRaces] reflects writes. */
private class FakeRaceDao(private val callLog: MutableList<String> = mutableListOf()) : RaceDao {
    private val state = MutableStateFlow<List<RaceEntity>>(emptyList())

    fun set(races: List<RaceEntity>) {
        state.value = races
    }

    override fun observeRaces(): Flow<List<RaceEntity>> = state

    override suspend fun insertAll(races: List<RaceEntity>) {
        state.value = state.value + races
    }

    override suspend fun deleteAll() {
        state.value = emptyList()
    }

    override suspend fun replaceAll(races: List<RaceEntity>) {
        deleteAll()
        insertAll(races)
        callLog.add("replaceAll")
    }
}

/** In-memory [SyncMetaDao] keyed by `(origin, resource)`. */
private class FakeSyncMetaDao(private val callLog: MutableList<String> = mutableListOf()) : SyncMetaDao {
    private val store = mutableMapOf<Pair<String, String>, String>()

    override suspend fun getEtag(origin: String, resource: String): String? = store[origin to resource]

    override suspend fun upsert(meta: SyncMetaEntity) {
        store[meta.origin to meta.resource] = meta.etag
        callLog.add("upsertEtag")
    }
}
