package ru.kolco24.kolco24.data.track

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.kolco24.kolco24.data.api.ApiClient
import ru.kolco24.kolco24.data.api.AppSignatureInterceptor
import ru.kolco24.kolco24.data.api.PostResult
import ru.kolco24.kolco24.data.api.dto.TrackPointDto
import ru.kolco24.kolco24.data.api.dto.toDto
import ru.kolco24.kolco24.data.db.TrackPointEntity

class TrackUploadTest {

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

    private fun entity(
        id: String,
        trustedMs: Long? = 1_718_900_000_123L,
        bootCount: Int? = 7,
    ) = TrackPointEntity(
        id = id,
        raceId = 8,
        teamId = 42,
        lat = 55.75,
        lon = 37.61,
        accuracy = 12.4f,
        gpsTimeMs = 1_718_900_000_000L,
        elapsedRealtimeAt = 9_876_543L,
        bootCount = bootCount,
        wallMs = 1_718_900_000_000L,
        trustedMs = trustedMs,
        uploadedLocal = false,
        uploadedCloud = false,
    )

    @Test
    fun uploadTrack_200_returnsAcceptedIds_andPostsBatchToTrackUrl() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"accepted":["a","b"]}"""),
        )

        val points = listOf(entity("a").toDto(), entity("b").toDto())
        val result = apiClient.uploadTrack(8, 42, points)

        assertTrue(result is PostResult.Success)
        result as PostResult.Success
        assertEquals(listOf("a", "b"), result.data.accepted)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/app/race/8/track/", recorded.path)
        val body = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(
            recorded.body.readUtf8(),
        )
        assertEquals("42", body["team_id"].toString())
    }

    @Test
    fun toDto_mapsFixMomentFieldsAndDropsLocalOnly() {
        val dto = entity("uuid-1").toDto()

        assertEquals("uuid-1", dto.id)
        assertEquals(55.75, dto.lat, 0.0)
        assertEquals(37.61, dto.lon, 0.0)
        assertEquals(12.4f, dto.accuracy)
        assertEquals(1_718_900_000_000L, dto.gpsTimeMs)
        assertEquals(1_718_900_000_123L, dto.trustedMs)
        assertEquals(9_876_543L, dto.elapsedAt)
        assertEquals(7, dto.bootCount)
    }

    @Test
    fun toDto_nullTrustedAndBoot_serializeAsJsonNull() {
        val dto = entity("uuid-2", trustedMs = null, bootCount = null).toDto()
        val encoded = json.encodeToString(TrackPointDto.serializer(), dto)

        assertTrue(encoded.contains("\"trusted_ms\":null"))
        assertTrue(encoded.contains("\"boot_count\":null"))
    }

    @Test
    fun uploadTrack_emptyBatch_postsEmptyPointsList() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":[]}"""))

        val result = apiClient.uploadTrack(8, 42, emptyList())

        assertTrue(result is PostResult.Success)
        assertTrue((result as PostResult.Success).data.accepted.isEmpty())

        val recorded = server.takeRequest()
        val body = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(
            recorded.body.readUtf8(),
        )
        assertEquals("[]", body["points"].toString())
    }

    @Test
    fun uploadTrack_403_returnsForbidden() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(PostResult.Forbidden, apiClient.uploadTrack(8, 42, emptyList()))
    }

    @Test
    fun uploadTrack_401_returnsUnauthorized() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(PostResult.Unauthorized, apiClient.uploadTrack(8, 42, emptyList()))
    }

    @Test
    fun uploadTrack_400_returnsBadRequest() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        assertEquals(PostResult.BadRequest, apiClient.uploadTrack(8, 42, emptyList()))
    }

    @Test
    fun uploadTrack_429_returnsRateLimited() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        assertEquals(PostResult.RateLimited, apiClient.uploadTrack(8, 42, emptyList()))
    }

    @Test
    fun uploadTrack_offline_returnsOffline() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(PostResult.Offline, apiClient.uploadTrack(8, 42, emptyList()))
    }

    /**
     * Builds a second [ApiClient] the way [AppContainer.localApiClient] does — shared signature
     * interceptor, NO [ServerTimeInterceptor], short timeouts via [ApiClient.defaultOkHttpClient]
     * — pointed at the same MockWebServer. The local upload target is just another instance of the
     * same class, so the same `uploadTrack` method drives it (status mapping + offline).
     */
    private fun localApiClientFor(server: MockWebServer): ApiClient {
        val interceptor = AppSignatureInterceptor(
            keyId = "android-v1",
            secret = "test-secret-123",
            installIdProvider = { "install-abc" },
            appVersion = "2.0.1",
            nowSeconds = { 1718200000L },
        )
        val client = ApiClient.defaultOkHttpClient(
            interceptor,
            connectTimeoutMs = 3_000,
            readTimeoutMs = 3_000,
        )
        return ApiClient(server.url("/").toString(), client, json)
    }

    @Test
    fun localInstance_uploadTrack_200_returnsAccepted() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":["a"]}"""))

        val local = localApiClientFor(server)
        val result = local.uploadTrack(8, 42, listOf(entity("a").toDto()))

        assertTrue(result is PostResult.Success)
        assertEquals(listOf("a"), (result as PostResult.Success).data.accepted)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/app/race/8/track/", recorded.path)
    }

    @Test
    fun localInstance_uploadTrack_403_returnsForbidden() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(PostResult.Forbidden, localApiClientFor(server).uploadTrack(8, 42, emptyList()))
    }

    @Test
    fun localInstance_uploadTrack_offline_returnsOffline() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(PostResult.Offline, localApiClientFor(server).uploadTrack(8, 42, emptyList()))
    }
}
