package ru.kolco24.kolco24.data.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var apiClient: ApiClient

    private val keyId = "android-v1"
    private val secret = "test-secret-123"
    private val installId = "install-abc"
    private val appVersion = "2.0.1"
    private val ts = 1718200000L

    private val json = Json { ignoreUnknownKeys = true }

    private val racesJson = """
        {
          "races": [
            {
              "id": 8,
              "name": "Кольцо24 2026",
              "slug": "kolco24-2026",
              "date": "2026-06-20",
              "date_end": "2026-06-21",
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
        val interceptor = AppSignatureInterceptor(
            keyId = keyId,
            secret = secret,
            installIdProvider = { installId },
            appVersion = appVersion,
            nowSeconds = { ts },
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        apiClient = ApiClient(server.url("/").toString(), client, json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun success_returnsRacesAndEtag_andSendsAllSignatureHeaders() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"a1b2c3d4e5f6a7b8\"")
                .setBody(racesJson),
        )

        val result = apiClient.fetchRaces("\"old-etag\"")

        assertTrue(result is FetchResult.Success)
        result as FetchResult.Success
        assertEquals(1, result.data.size)
        assertEquals(8, result.data[0].id)
        assertEquals("Кольцо24 2026", result.data[0].name)
        assertEquals("\"a1b2c3d4e5f6a7b8\"", result.etag)

        val recorded = server.takeRequest()
        assertEquals(keyId, recorded.getHeader("X-App-Key-Id"))
        assertNotNull(recorded.getHeader("X-App-Sig"))
        assertEquals(ts.toString(), recorded.getHeader("X-App-Ts"))
        assertEquals(installId, recorded.getHeader("X-Install-Id"))
        assertEquals("android", recorded.getHeader("X-App-Platform"))
        assertEquals(appVersion, recorded.getHeader("X-App-Version"))
        // If-None-Match is echoed verbatim, quotes included.
        assertEquals("\"old-etag\"", recorded.getHeader("If-None-Match"))
    }

    @Test
    fun request_pathIsRacesWithTrailingSlash_andSignatureMatches() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(racesJson))

        apiClient.fetchRaces(null)

        val recorded = server.takeRequest()
        // The signed path must be exactly what is sent — the #1 cause of 403s.
        assertEquals("/app/races/", recorded.path)
        val expectedSig = sign(
            secret,
            buildCanonical("GET", recorded.path!!, recorded.getHeader("X-App-Ts")!!),
        )
        assertEquals(expectedSig, recorded.getHeader("X-App-Sig"))
        assertNull(recorded.getHeader("If-None-Match"))
    }

    @Test
    fun notModified_returnsNotModified() = runTest {
        server.enqueue(MockResponse().setResponseCode(304))

        assertEquals(FetchResult.NotModified, apiClient.fetchRaces("\"e\""))
    }

    @Test
    fun forbidden_returnsForbidden() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"detail":"Forbidden"}"""),
        )

        assertEquals(FetchResult.Forbidden, apiClient.fetchRaces(null))
    }

    @Test
    fun serverError_returnsErrorWithCode() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(FetchResult.Error(500), apiClient.fetchRaces(null))
    }

    @Test
    fun connectionDrop_returnsErrorWithNullCode() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(FetchResult.Error(null), apiClient.fetchRaces(null))
    }

    @Test
    fun invalidJson_returnsError() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{ not json"))

        val result = apiClient.fetchRaces(null)

        assertTrue(result is FetchResult.Error)
    }

    private val teamsJson = """
        {
          "race": 8,
          "categories": [
            { "id": 1, "code": "M", "short_name": "Муж", "name": "Мужская", "order": 1 }
          ],
          "teams": [
            {
              "id": 42,
              "teamname": "Лоси",
              "start_number": "201",
              "category2": 1,
              "ucount": 2,
              "paid_people": 2.0,
              "start_time": 1718200000,
              "finish_time": 0,
              "members": [
                { "name": "Иван", "number_in_team": 1 }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun fetchTeams_success_returnsParsedBodyAndEtag() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"teams-v1\"")
                .setBody(teamsJson),
        )

        val result = apiClient.fetchTeams(8, "\"old\"")

        assertTrue(result is FetchResult.Success)
        result as FetchResult.Success
        assertEquals(8, result.data.race)
        assertEquals(1, result.data.categories.size)
        assertEquals(1, result.data.teams.size)
        assertEquals("Лоси", result.data.teams[0].teamname)
        assertEquals("201", result.data.teams[0].startNumber)
        assertEquals("\"teams-v1\"", result.etag)

        val recorded = server.takeRequest()
        assertEquals("/app/race/8/teams/", recorded.path)
        assertEquals("\"old\"", recorded.getHeader("If-None-Match"))
    }

    @Test
    fun fetchTeams_notModified_returnsNotModified() = runTest {
        server.enqueue(MockResponse().setResponseCode(304))

        assertEquals(FetchResult.NotModified, apiClient.fetchTeams(8, "\"e\""))
    }

    @Test
    fun fetchTeams_forbidden_returnsForbidden() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(FetchResult.Forbidden, apiClient.fetchTeams(8, null))
    }

    @Test
    fun fetchTeams_serverError_returnsErrorWithCode() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(FetchResult.Error(500), apiClient.fetchTeams(8, null))
    }

    @Test
    fun fetchTeams_connectionDrop_returnsErrorWithNullCode() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(FetchResult.Error(null), apiClient.fetchTeams(8, null))
    }

    @Test
    fun fetchTeams_invalidJson_returnsError() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{ not json"))

        val result = apiClient.fetchTeams(8, null)

        assertTrue(result is FetchResult.Error)
    }
}
