package ru.kolco24.kolco24.data.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.kolco24.kolco24.data.api.dto.MarkDto
import ru.kolco24.kolco24.data.api.dto.PresentMemberDto
import ru.kolco24.kolco24.data.api.dto.TakeLocationDto

class ApiClientMarksTest {

    private lateinit var server: MockWebServer
    private lateinit var apiClient: ApiClient

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val interceptor = AppSignatureInterceptor(
            keyId = "android-v1",
            secret = "test-secret-123",
            installIdProvider = { "install-abc" },
            appVersion = "2.0.1",
            nowSeconds = { 1718200000L },
        )
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        apiClient = ApiClient(server.url("/").toString(), client, json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun markDto(id: String = "mark-1") = MarkDto(
        id = id,
        checkpointId = 264,
        method = "nfc",
        cpCode = "9f1a2b3c4d5e6f70",
        cpNfcUid = "04A2B3C4D5E680",
        present = listOf(
            PresentMemberDto(nfcUid = "04F1E2", code = "c3d4", number = 101, numberInTeam = 1),
            PresentMemberDto(nfcUid = null, code = null, number = 0, numberInTeam = 2),
        ),
        expectedCount = 4,
        complete = true,
        trustedMs = 1_718_900_000_123L,
        wallMs = 1_718_900_000_000L,
        elapsedAt = 9_876_543L,
        bootCount = 7,
        location = TakeLocationDto(
            lat = 55.75,
            lon = 37.61,
            accuracy = 12.4f,
            altitude = 184.2,
            verticalAccuracy = 3.2f,
            gpsTimeMs = 1_718_900_000_001L,
            elapsedAt = 9_870_000L,
        ),
    )

    @Test
    fun uploadMarks_200_returnsAcceptedIds_andPostsBatchToMarksUrl() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"accepted":["mark-1","mark-2"]}"""),
        )

        val result = apiClient.uploadMarks(8, 42, "install-abc", listOf(markDto("mark-1"), markDto("mark-2")))

        assertTrue(result is PostResult.Success)
        result as PostResult.Success
        assertEquals(listOf("mark-1", "mark-2"), result.data.accepted)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/app/race/8/marks/", recorded.path)
        val bodyText = recorded.body.readUtf8()
        val body = json.decodeFromString<Map<String, JsonElement>>(bodyText)
        assertEquals("42", body["team_id"].toString())
        assertTrue(bodyText.contains("\"source_install_id\":\"install-abc\""))
        // snake_case wire fields are present
        assertTrue(bodyText.contains("\"checkpoint_id\":264"))
        assertTrue(bodyText.contains("\"cp_nfc_uid\":\"04A2B3C4D5E680\""))
        assertTrue(bodyText.contains("\"cp_code\":\"9f1a2b3c4d5e6f70\""))
        assertTrue(bodyText.contains("\"number_in_team\":1"))
        assertTrue(bodyText.contains("\"expected_count\":4"))
        assertTrue(bodyText.contains("\"wall_ms\":1718900000000"))
    }

    @Test
    fun uploadMarks_emptyBatch_postsValidBody_andReturnsAcceptedEmpty() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":[]}"""))

        val result = apiClient.uploadMarks(8, 42, "install-abc", emptyList())

        assertTrue(result is PostResult.Success)
        assertTrue((result as PostResult.Success).data.accepted.isEmpty())

        val recorded = server.takeRequest()
        val bodyText = recorded.body.readUtf8()
        val body = json.decodeFromString<Map<String, JsonElement>>(bodyText)
        assertEquals("42", body["team_id"].toString())
        assertEquals("[]", body["marks"].toString())
    }

    @Test
    fun uploadMarks_400_returnsBadRequest() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        assertEquals(PostResult.BadRequest, apiClient.uploadMarks(8, 42, "install-abc", emptyList()))
    }

    @Test
    fun uploadMarks_403_returnsForbidden() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(PostResult.Forbidden, apiClient.uploadMarks(8, 42, "install-abc", emptyList()))
    }

    @Test
    fun uploadMarks_404_returnsError404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(PostResult.Error(404), apiClient.uploadMarks(8, 42, "install-abc", emptyList()))
    }

    @Test
    fun uploadMarks_429_returnsRateLimited() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        assertEquals(PostResult.RateLimited, apiClient.uploadMarks(8, 42, "install-abc", emptyList()))
    }

    @Test
    fun uploadMarks_offline_returnsOffline() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(PostResult.Offline, apiClient.uploadMarks(8, 42, "install-abc", emptyList()))
    }
}
