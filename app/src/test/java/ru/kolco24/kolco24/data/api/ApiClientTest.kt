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
            buildCanonical("GET", recorded.path!!, recorded.getHeader("X-App-Ts")!!, EMPTY_BODY_SHA256),
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

    private val legendJson = """
        {
          "race": 8,
          "checkpoints": [
            {
              "id": 101,
              "number": 5,
              "cost": 10,
              "type": "kp",
              "description": "У пня"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun fetchLegend_success_returnsParsedBodyAndEtag() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"legend-v1\"")
                .setBody(legendJson),
        )

        val result = apiClient.fetchLegend(8, "\"old\"")

        assertTrue(result is FetchResult.Success)
        result as FetchResult.Success
        assertEquals(8, result.data.race)
        assertEquals(1, result.data.checkpoints.size)
        assertEquals(101, result.data.checkpoints[0].id)
        assertEquals(5, result.data.checkpoints[0].number)
        assertEquals(10, result.data.checkpoints[0].cost)
        assertEquals("kp", result.data.checkpoints[0].type)
        assertEquals("У пня", result.data.checkpoints[0].description)
        assertEquals("\"legend-v1\"", result.etag)

        val recorded = server.takeRequest()
        assertEquals("/app/race/8/legend/", recorded.path)
        assertEquals("\"old\"", recorded.getHeader("If-None-Match"))
    }

    @Test
    fun fetchLegend_emptyCheckpoints_returnsSuccessWithEmptyList() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"race":8,"checkpoints":[]}"""),
        )

        val result = apiClient.fetchLegend(8, null)

        assertTrue(result is FetchResult.Success)
        result as FetchResult.Success
        assertEquals(8, result.data.race)
        assertTrue(result.data.checkpoints.isEmpty())
    }

    @Test
    fun fetchLegend_notModified_returnsNotModified() = runTest {
        server.enqueue(MockResponse().setResponseCode(304))

        assertEquals(FetchResult.NotModified, apiClient.fetchLegend(8, "\"e\""))
    }

    @Test
    fun fetchLegend_forbidden_returnsForbidden() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(FetchResult.Forbidden, apiClient.fetchLegend(8, null))
    }

    @Test
    fun fetchLegend_serverError_returnsErrorWithCode() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(FetchResult.Error(500), apiClient.fetchLegend(8, null))
    }

    @Test
    fun fetchLegend_connectionDrop_returnsErrorWithNullCode() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(FetchResult.Error(null), apiClient.fetchLegend(8, null))
    }

    private val memberTagsJson = """
        {
          "member_tags": [
            {"number": 101, "nfc_uid": "04A2B3C4D5E680"},
            {"number": 102, "nfc_uid": "0489AB12CD34EF"}
          ]
        }
    """.trimIndent()

    @Test
    fun fetchMemberTags_success_returnsParsedBodyAndEtag() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"tags-v1\"")
                .setBody(memberTagsJson),
        )

        val result = apiClient.fetchMemberTags(8, "\"old\"")

        assertTrue(result is FetchResult.Success)
        result as FetchResult.Success
        assertEquals(2, result.data.memberTags.size)
        assertEquals(101, result.data.memberTags[0].number)
        assertEquals("04A2B3C4D5E680", result.data.memberTags[0].nfcUid)
        assertEquals("\"tags-v1\"", result.etag)

        val recorded = server.takeRequest()
        assertEquals("/app/race/8/member_tags/", recorded.path)
        assertEquals("\"old\"", recorded.getHeader("If-None-Match"))
    }

    @Test
    fun fetchMemberTags_notModified_returnsNotModified() = runTest {
        server.enqueue(MockResponse().setResponseCode(304))

        assertEquals(FetchResult.NotModified, apiClient.fetchMemberTags(8, "\"e\""))
    }

    @Test
    fun fetchMemberTags_forbidden_returnsForbidden() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(FetchResult.Forbidden, apiClient.fetchMemberTags(8, null))
    }

    @Test
    fun fetchMemberTags_serverError_returnsErrorWithCode() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(FetchResult.Error(500), apiClient.fetchMemberTags(8, null))
    }

    @Test
    fun fetchMemberTags_connectionDrop_returnsErrorWithNullCode() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(FetchResult.Error(null), apiClient.fetchMemberTags(8, null))
    }

    @Test
    fun fetchMemberTags_invalidJson_returnsError() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{ not json"))

        val result = apiClient.fetchMemberTags(8, null)

        assertTrue(result is FetchResult.Error)
    }

    // --- POST path (PostResult) ---

    private fun postUrl(path: String) = server.url(path).toString()

    @Test
    fun post_200_parsesBodyIntoSuccess_andSendsJsonBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"v":7}"""))

        val result = apiClient.post(postUrl("/app/x/"), """{"a":1}""".toByteArray()) { it }

        assertTrue(result is PostResult.Success)
        result as PostResult.Success
        assertEquals("""{"v":7}""", result.data)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/app/x/", recorded.path)
        assertEquals("""{"a":1}""", recorded.body.readUtf8())
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun post_201_parsesBodyIntoSuccess() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("ok"))

        val result = apiClient.post(postUrl("/app/x/"), ByteArray(0)) { it }

        assertTrue(result is PostResult.Success)
        assertEquals("ok", (result as PostResult.Success).data)
    }

    @Test
    fun post_emptyBody_doesNotInvokeParseOnError() = runTest {
        // A 401 with no body must not reach the parser (would otherwise crash on empty input).
        server.enqueue(MockResponse().setResponseCode(401))

        val result = apiClient.post(postUrl("/app/x/"), ByteArray(0)) {
            error("parse must not be called on error branch")
        }

        assertEquals(PostResult.Unauthorized, result)
    }

    @Test
    fun post_400_returnsBadRequest() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"bad"}"""))

        assertEquals(PostResult.BadRequest, apiClient.post(postUrl("/app/x/"), ByteArray(0)) { it })
    }

    @Test
    fun post_403_returnsForbidden() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(PostResult.Forbidden, apiClient.post(postUrl("/app/x/"), ByteArray(0)) { it })
    }

    @Test
    fun post_409_returnsConflict() = runTest {
        server.enqueue(MockResponse().setResponseCode(409))

        assertEquals(PostResult.Conflict, apiClient.post(postUrl("/app/x/"), ByteArray(0)) { it })
    }

    @Test
    fun post_429_returnsRateLimited() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        assertEquals(PostResult.RateLimited, apiClient.post(postUrl("/app/x/"), ByteArray(0)) { it })
    }

    @Test
    fun post_500_returnsErrorWithCode() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(PostResult.Error(500), apiClient.post(postUrl("/app/x/"), ByteArray(0)) { it })
    }

    @Test
    fun post_connectionDrop_returnsOffline() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(PostResult.Offline, apiClient.post(postUrl("/app/x/"), ByteArray(0)) { it })
    }

    @Test
    fun post_parseThrowsSerialization_returnsErrorWithNullCode() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{ not json"))

        val result: PostResult<String> = apiClient.post(postUrl("/app/x/"), ByteArray(0)) {
            throw kotlinx.serialization.SerializationException("bad json")
        }

        assertEquals(PostResult.Error(null), result)
    }

    // --- login / logout / bindTag (typed POST methods) ---

    @Test
    fun login_success_parsesTokenAndExpiry_andPostsCredentials() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"token":"tok-123","expires_at":"2026-07-21T14:03:00Z"}"""),
        )

        val result = apiClient.login("admin@kolco24.ru", "s3cret")

        assertTrue(result is PostResult.Success)
        result as PostResult.Success
        assertEquals("tok-123", result.data.token)
        assertEquals("2026-07-21T14:03:00Z", result.data.expiresAt)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/app/login/", recorded.path)
        val body = json.decodeFromString<Map<String, String>>(recorded.body.readUtf8())
        assertEquals("admin@kolco24.ru", body["email"])
        assertEquals("s3cret", body["password"])
    }

    @Test
    fun login_wrongCredentials_returnsUnauthorized() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"bad"}"""))

        assertEquals(PostResult.Unauthorized, apiClient.login("a@b.c", "nope"))
    }

    @Test
    fun login_tooManyAttempts_returnsRateLimited() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        assertEquals(PostResult.RateLimited, apiClient.login("a@b.c", "x"))
    }

    @Test
    fun logout_emptyBody200_returnsSuccessUnit() = runTest {
        // 200 with an empty body must map to Success(Unit), not Error — the parser ignores the body.
        server.enqueue(MockResponse().setResponseCode(200))

        val result = apiClient.logout()

        assertTrue(result is PostResult.Success)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/app/logout/", recorded.path)
        // Empty body sent.
        assertEquals("", recorded.body.readUtf8())
    }

    @Test
    fun bindTag_201_parsesResponseWithCode_andPostsBinding() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """{"bid":"b1","checkpoint_id":101,"number":5,"nfc_uid":"04A2B3","code":"DEADBEEF"}""",
                ),
        )

        val result = apiClient.bindTag(8, 101, "04A2B3")

        assertTrue(result is PostResult.Success)
        result as PostResult.Success
        assertEquals("b1", result.data.bid)
        assertEquals(101, result.data.checkpointId)
        assertEquals(5, result.data.number)
        assertEquals("04A2B3", result.data.nfcUid)
        assertEquals("DEADBEEF", result.data.code)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/app/race/8/tags/", recorded.path)
        val body = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(
            recorded.body.readUtf8(),
        )
        assertEquals("101", body["checkpoint_id"].toString())
        assertEquals("\"04A2B3\"", body["nfc_uid"].toString())
    }

    @Test
    fun bindTag_200_idempotentRebind_parsesResponseWithCode() = runTest {
        // The 200 (re-bind) body must still parse into TagBindResponse — the rewrite path needs `code`.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"bid":"b1","checkpoint_id":101,"number":5,"nfc_uid":"04A2B3","code":"DEADBEEF"}""",
                ),
        )

        val result = apiClient.bindTag(8, 101, "04A2B3")

        assertTrue(result is PostResult.Success)
        assertEquals("DEADBEEF", (result as PostResult.Success).data.code)
    }

    @Test
    fun bindTag_409_returnsConflict() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"already bound"}"""))

        assertEquals(PostResult.Conflict, apiClient.bindTag(8, 101, "04A2B3"))
    }

    @Test
    fun bindTag_404_returnsErrorWith404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(PostResult.Error(404), apiClient.bindTag(8, 999, "04A2B3"))
    }

    // --- fetchSync (SyncManifestDto) ---

    @Test
    fun fetchSync_fullManifest_parsesBothLeaseFields() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"race":8,"data_source":"local","lease_ttl_seconds":43200,"lease_expires_at":1718300000}""",
                ),
        )

        val result = apiClient.fetchSync(8)

        assertTrue(result is FetchResult.Success)
        result as FetchResult.Success
        assertEquals(8, result.data.race)
        assertEquals("local", result.data.dataSource)
        assertEquals(43200L, result.data.leaseTtlSeconds)
        assertEquals(1718300000L, result.data.leaseExpiresAt)

        val recorded = server.takeRequest()
        assertEquals("/app/race/8/sync/", recorded.path)
        assertNull(recorded.getHeader("If-None-Match"))
    }

    @Test
    fun fetchSync_stubbedManifest_bothLeaseFieldsNull() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"race":8,"data_source":"cloud"}"""),
        )

        val result = apiClient.fetchSync(8)

        assertTrue(result is FetchResult.Success)
        result as FetchResult.Success
        assertEquals("cloud", result.data.dataSource)
        assertNull(result.data.leaseTtlSeconds)
        assertNull(result.data.leaseExpiresAt)
    }

    @Test
    fun fetchSync_unknownVersionsKey_isIgnored() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"race":8,"data_source":"local","versions":{"teams":"abc123","legend":"def456"}}""",
                ),
        )

        val result = apiClient.fetchSync(8)

        assertTrue(result is FetchResult.Success)
        assertEquals("local", (result as FetchResult.Success).data.dataSource)
    }

    @Test
    fun fetchSync_404_returnsErrorWith404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(FetchResult.Error(404), apiClient.fetchSync(999))
    }

    @Test
    fun fetchSync_connectionDrop_returnsErrorWithNullCode() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(FetchResult.Error(null), apiClient.fetchSync(8))
    }
}
