package ru.kolco24.kolco24.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.kolco24.kolco24.data.api.ApiClient
import ru.kolco24.kolco24.data.api.AppSignatureInterceptor
import ru.kolco24.kolco24.data.db.CategoryEntity
import ru.kolco24.kolco24.data.db.SelectedTeamDao
import ru.kolco24.kolco24.data.db.SelectedTeamEntity
import ru.kolco24.kolco24.data.db.SyncMetaDao
import ru.kolco24.kolco24.data.db.SyncMetaEntity
import ru.kolco24.kolco24.data.db.TeamDao
import ru.kolco24.kolco24.data.db.TeamEntity

class TeamRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var localServer: MockWebServer
    private lateinit var teamDao: FakeTeamDao
    private lateinit var selectedTeamDao: FakeSelectedTeamDao
    private lateinit var syncMetaDao: FakeTeamsSyncMetaDao
    private lateinit var repository: TeamRepository
    private lateinit var pinnedRepository: TeamRepository
    private lateinit var origin: String
    private lateinit var localOrigin: String
    private lateinit var apiClient: ApiClient
    private lateinit var localApiClient: ApiClient

    private val json = Json { ignoreUnknownKeys = true }
    private val callLog = mutableListOf<String>()

    private fun teamsJson() = """
        {
          "race": 8,
          "categories": [
            { "id": 1, "code": "M", "short_name": "Муж", "name": "Мужская", "order": 2 }
          ],
          "teams": [
            {
              "id": 201,
              "teamname": "Барсы",
              "start_number": "201",
              "category2": 1,
              "ucount": 2,
              "paid_people": 2.0,
              "start_time": 1718200000,
              "finish_time": 0,
              "members": [
                { "name": "Иван", "number_in_team": 1 },
                { "name": "Пётр", "number_in_team": 2 }
              ]
            }
          ]
        }
    """.trimIndent()

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
        teamDao = FakeTeamDao(callLog)
        selectedTeamDao = FakeSelectedTeamDao()
        syncMetaDao = FakeTeamsSyncMetaDao(callLog)
        repository = TeamRepository(
            apiClient, teamDao, selectedTeamDao, syncMetaDao, origin,
            localApiClient, localOrigin, isRacePinned = { false },
        )
        // A SyncSource.Local call is only ever legitimately made while pinned (see the
        // Local-not-pinned guard in refreshTeams) — Local-source tests use this instance.
        pinnedRepository = TeamRepository(
            apiClient, teamDao, selectedTeamDao, syncMetaDao, origin,
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
        teamDao.setTeams(listOf(teamEntity(99, raceId = 8, name = "Stale")))
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(teamsJson()),
        )

        assertEquals(RefreshResult.Updated, repository.refreshTeams(8))

        val teams = repository.teamsForRace(8).first()
        assertEquals(1, teams.size)
        val team = teams[0]
        assertEquals(201, team.id)
        assertEquals(8, team.raceId)
        assertEquals("Барсы", team.teamname)
        assertEquals("201", team.startNumber)
        assertEquals(1, team.categoryId)
        assertEquals(2.0, team.paidPeople, 0.0)
        assertEquals(2, team.members.size)
        assertEquals("Иван", team.members[0].name)
        assertEquals(2, team.members[1].numberInTeam)

        val categories = repository.categoriesForRace(8).first()
        assertEquals(1, categories.size)
        assertEquals("Муж", categories[0].shortName)
        assertEquals(2, categories[0].sortOrder)

        assertEquals("\"v1\"", syncMetaDao.getEtag(origin, "race/8/teams"))
    }

    @Test
    fun success_writesDataBeforeEtag() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(teamsJson()),
        )

        repository.refreshTeams(8)

        assertEquals(listOf("deleteEtag", "replaceAllForRace", "upsertEtag"), callLog)
    }

    @Test
    fun success_withoutEtag_storesTeamsButSkipsEtagSave() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(teamsJson()))

        assertEquals(RefreshResult.Updated, repository.refreshTeams(8))

        assertEquals(1, repository.teamsForRace(8).first().size)
        assertNull(syncMetaDao.getEtag(origin, "race/8/teams"))
        assertEquals(listOf("deleteEtag", "replaceAllForRace"), callLog)
    }

    @Test
    fun notModified_leavesDataUntouched() = runTest {
        teamDao.setTeams(listOf(teamEntity(201, raceId = 8, name = "Cached")))
        syncMetaDao.upsert(SyncMetaEntity(origin, "race/8/teams", "\"v1\""))
        callLog.clear()
        server.enqueue(MockResponse().setResponseCode(304))

        assertEquals(RefreshResult.NotModified, repository.refreshTeams(8))

        val teams = repository.teamsForRace(8).first()
        assertEquals(1, teams.size)
        assertEquals("Cached", teams[0].teamname)
        assertTrue("DAO must not be written on 304", callLog.none { it == "replaceAllForRace" })
    }

    @Test
    fun offline_returnsOfflineAndLeavesDataUntouched() = runTest {
        teamDao.setTeams(listOf(teamEntity(201, raceId = 8, name = "Cached")))
        syncMetaDao.upsert(SyncMetaEntity(origin, "race/8/teams", "\"existing\""))
        callLog.clear()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(RefreshResult.Offline, repository.refreshTeams(8))

        assertEquals(1, repository.teamsForRace(8).first().size)
        assertEquals("\"existing\"", syncMetaDao.getEtag(origin, "race/8/teams"))
        assertTrue(callLog.none { it == "replaceAllForRace" })
    }

    @Test
    fun forbidden_returnsForbidden() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(RefreshResult.Forbidden, repository.refreshTeams(8))
    }

    @Test
    fun serverError_returnsHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(RefreshResult.HttpError(500), repository.refreshTeams(8))
    }

    @Test
    fun differentRaceIds_useDifferentSyncResources() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"a\"").setBody(teamsJson()),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"b\"").setBody(teamsJson()),
        )

        repository.refreshTeams(8)
        repository.refreshTeams(9)

        assertEquals("\"a\"", syncMetaDao.getEtag(origin, "race/8/teams"))
        assertEquals("\"b\"", syncMetaDao.getEtag(origin, "race/9/teams"))

        // Both refreshes hit the correct race-specific paths.
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertTrue(first.path!!.contains("/app/race/8/teams/"))
        assertTrue(second.path!!.contains("/app/race/9/teams/"))
    }

    @Test
    fun secondRefresh_sendsStoredEtagForSameRace() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(teamsJson()),
        )
        server.enqueue(MockResponse().setResponseCode(304))

        repository.refreshTeams(8)
        server.takeRequest()
        repository.refreshTeams(8)

        val second = server.takeRequest()
        assertEquals("\"v1\"", second.getHeader("If-None-Match"))
    }

    @Test
    fun selectTeam_upsertsSingleRow() = runTest {
        repository.selectTeam(raceId = 8, teamId = 201)
        assertEquals(SelectedTeamEntity(id = 1, raceId = 8, teamId = 201), repository.selectedTeam.first())

        repository.selectTeam(raceId = 9, teamId = 305)
        assertEquals(SelectedTeamEntity(id = 1, raceId = 9, teamId = 305), repository.selectedTeam.first())
    }

    @Test
    fun localSource_hitsLocalClientAndStoresEtagUnderLocalOrigin() = runTest {
        localServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"local-v1\"").setBody(teamsJson()),
        )

        assertEquals(RefreshResult.Updated, pinnedRepository.refreshTeams(8, SyncSource.Local))

        assertEquals(1, pinnedRepository.teamsForRace(8).first().size)
        assertEquals("\"local-v1\"", syncMetaDao.getEtag(localOrigin, "race/8/teams"))
        assertNull("cloud origin must stay untouched", syncMetaDao.getEtag(origin, "race/8/teams"))
        assertEquals(0, server.requestCount)
        assertEquals(1, localServer.requestCount)
    }

    @Test
    fun localSource_invalidatesStaleCloudEtag() = runTest {
        // A prior cloud fetch left an ETag; switching to Local must drop it so a later
        // switch-back to Cloud can't earn a 304 against rows this Local fetch just overwrote.
        syncMetaDao.upsert(SyncMetaEntity(origin, "race/8/teams", "\"cloud-v1\""))
        localServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"local-v1\"").setBody(teamsJson()),
        )

        assertEquals(RefreshResult.Updated, pinnedRepository.refreshTeams(8, SyncSource.Local))

        assertNull(syncMetaDao.getEtag(origin, "race/8/teams"))
        assertEquals("\"local-v1\"", syncMetaDao.getEtag(localOrigin, "race/8/teams"))
    }

    @Test
    fun cloudSource_invalidatesStaleLocalEtag() = runTest {
        syncMetaDao.upsert(SyncMetaEntity(localOrigin, "race/8/teams", "\"local-v1\""))
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(teamsJson()),
        )

        assertEquals(RefreshResult.Updated, repository.refreshTeams(8))

        assertNull(syncMetaDao.getEtag(localOrigin, "race/8/teams"))
        assertEquals("\"v1\"", syncMetaDao.getEtag(origin, "race/8/teams"))
    }

    @Test
    fun cloudSource_pinnedRace_skipsWithoutTouchingNetworkOrData() = runTest {
        teamDao.setTeams(listOf(teamEntity(201, raceId = 8, name = "Cached")))
        val pinnedRepo = TeamRepository(
            apiClient, teamDao, selectedTeamDao, syncMetaDao, origin,
            localApiClient, localOrigin, isRacePinned = { true },
        )

        assertEquals(RefreshResult.Skipped, pinnedRepo.refreshTeams(8, SyncSource.Cloud))

        assertEquals(0, server.requestCount)
        val teams = pinnedRepo.teamsForRace(8).first()
        assertEquals(1, teams.size)
        assertEquals("Cached", teams[0].teamname)
    }

    @Test
    fun cloudSource_pinAppearingMidFlight_doesNotPersist() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(teamsJson()),
        )
        var checks = 0
        // false on the entry guard, true on the pre-persist re-check — simulates the pin landing
        // while the cloud fetch was in flight.
        val pinnedRepo = TeamRepository(
            apiClient, teamDao, selectedTeamDao, syncMetaDao, origin,
            localApiClient, localOrigin, isRacePinned = { checks++ > 0 },
        )

        assertEquals(RefreshResult.Skipped, pinnedRepo.refreshTeams(8, SyncSource.Cloud))

        assertTrue("in-flight response must not persist once pinned", pinnedRepo.teamsForRace(8).first().isEmpty())
        assertNull(syncMetaDao.getEtag(origin, "race/8/teams"))
    }

    @Test
    fun localSource_unpinnedRace_skipsWithoutTouchingNetworkOrData() = runTest {
        teamDao.setTeams(listOf(teamEntity(201, raceId = 8, name = "Cached")))
        val unpinnedRepo = TeamRepository(
            apiClient, teamDao, selectedTeamDao, syncMetaDao, origin,
            localApiClient, localOrigin, isRacePinned = { false },
        )

        assertEquals(RefreshResult.Skipped, unpinnedRepo.refreshTeams(8, SyncSource.Local))

        assertEquals(0, localServer.requestCount)
        val teams = unpinnedRepo.teamsForRace(8).first()
        assertEquals(1, teams.size)
        assertEquals("Cached", teams[0].teamname)
    }

    @Test
    fun localSource_unpinDisappearingMidFlight_doesNotPersist() = runTest {
        localServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"local-v1\"").setBody(teamsJson()),
        )
        var checks = 0
        // true on the entry guard, false on the pre-persist re-check — simulates the switch
        // turning local mode off (e.g. via exitLocalMode) while the LAN fetch was in flight.
        val unpinningRepo = TeamRepository(
            apiClient, teamDao, selectedTeamDao, syncMetaDao, origin,
            localApiClient, localOrigin, isRacePinned = { checks++ == 0 },
        )

        assertEquals(RefreshResult.Skipped, unpinningRepo.refreshTeams(8, SyncSource.Local))

        assertTrue(
            "in-flight LAN response must not persist once unpinned",
            unpinningRepo.teamsForRace(8).first().isEmpty(),
        )
        assertNull(syncMetaDao.getEtag(localOrigin, "race/8/teams"))
    }

    @Test
    fun unpinnedCloud_behaviorUnchanged() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(teamsJson()),
        )

        assertEquals(RefreshResult.Updated, repository.refreshTeams(8, SyncSource.Cloud))

        assertEquals(1, repository.teamsForRace(8).first().size)
        assertEquals("\"v1\"", syncMetaDao.getEtag(origin, "race/8/teams"))
    }

    private fun teamEntity(id: Int, raceId: Int, name: String) = TeamEntity(
        id = id,
        raceId = raceId,
        teamname = name,
        startNumber = null,
        categoryId = null,
        ucount = 0,
        paidPeople = 0.0,
        startTime = 0,
        finishTime = 0,
        members = emptyList(),
    )
}

/** In-memory [TeamDao] backed by [MutableStateFlow]s so the observe queries reflect writes. */
private class FakeTeamDao(private val callLog: MutableList<String>) : TeamDao {
    private val teams = MutableStateFlow<List<TeamEntity>>(emptyList())
    private val categories = MutableStateFlow<List<CategoryEntity>>(emptyList())

    fun setTeams(value: List<TeamEntity>) {
        teams.value = value
    }

    override fun observeTeamsForRace(raceId: Int): Flow<List<TeamEntity>> =
        teams.map { list -> list.filter { it.raceId == raceId } }

    override fun observeCategoriesForRace(raceId: Int): Flow<List<CategoryEntity>> =
        categories.map { list -> list.filter { it.raceId == raceId } }

    override fun observeTeamById(teamId: Int): Flow<TeamEntity?> =
        teams.map { list -> list.firstOrNull { it.id == teamId } }

    override suspend fun insertTeams(teams: List<TeamEntity>) {
        this.teams.value = this.teams.value + teams
    }

    override suspend fun insertCategories(categories: List<CategoryEntity>) {
        this.categories.value = this.categories.value + categories
    }

    override suspend fun deleteTeamsForRace(raceId: Int) {
        teams.value = teams.value.filterNot { it.raceId == raceId }
    }

    override suspend fun deleteCategoriesForRace(raceId: Int) {
        categories.value = categories.value.filterNot { it.raceId == raceId }
    }

    override suspend fun replaceAllForRace(
        raceId: Int,
        categories: List<CategoryEntity>,
        teams: List<TeamEntity>,
    ) {
        deleteTeamsForRace(raceId)
        deleteCategoriesForRace(raceId)
        insertCategories(categories)
        insertTeams(teams)
        callLog.add("replaceAllForRace")
    }
}

/** In-memory [SelectedTeamDao] holding the single selected-team row. */
private class FakeSelectedTeamDao : SelectedTeamDao {
    private val state = MutableStateFlow<SelectedTeamEntity?>(null)

    override fun observe(): Flow<SelectedTeamEntity?> = state

    override suspend fun upsert(selected: SelectedTeamEntity) {
        state.value = selected
    }

    override suspend fun clear() {
        state.value = null
    }
}

/** In-memory [SyncMetaDao] keyed by `(origin, resource)`. */
private class FakeTeamsSyncMetaDao(private val callLog: MutableList<String>) : SyncMetaDao {
    private val store = mutableMapOf<Pair<String, String>, String>()

    override suspend fun getEtag(origin: String, resource: String): String? = store[origin to resource]

    override fun observeEtagsExist(origin: String, resource1: String, resource2: String): Flow<Boolean> =
        flowOf(store.containsKey(origin to resource1) || store.containsKey(origin to resource2))

    override suspend fun upsert(meta: SyncMetaEntity) {
        store[meta.origin to meta.resource] = meta.etag
        callLog.add("upsertEtag")
    }

    override suspend fun deleteEtag(origin: String, resource: String) {
        store.remove(origin to resource)
        callLog.add("deleteEtag")
    }
}
