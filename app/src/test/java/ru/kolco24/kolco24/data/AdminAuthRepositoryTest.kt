package ru.kolco24.kolco24.data

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

class AdminAuthRepositoryTest {

    /** In-memory fake of the [AdminTokenStore]'s multi-key store; `null` save removes the key. */
    private class FakeStore(seed: Map<String, String> = emptyMap()) {
        val map = seed.toMutableMap()
        fun store(): AdminTokenStore = AdminTokenStore(
            load = { map[it] },
            save = { key, value -> if (value == null) map.remove(key) else map[key] = value },
        )
    }

    private lateinit var server: MockWebServer
    private lateinit var apiClient: ApiClient
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val interceptor = AppSignatureInterceptor(
            keyId = "android-v1",
            secret = "test-secret",
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

    private fun repo(
        store: AdminTokenStore,
        now: String = "2026-01-01T00:00:00Z",
    ) = AdminAuthRepository(apiClient, store, nowUtcIso = { now })

    // --- pure helpers ---

    @Test
    fun loginOutcome_mapsEachBranch() {
        assertEquals(
            LoginOutcome.Success,
            loginOutcome(ru.kolco24.kolco24.data.api.PostResult.Success("x")),
        )
        assertEquals(
            LoginOutcome.InvalidCredentials,
            loginOutcome(ru.kolco24.kolco24.data.api.PostResult.Unauthorized),
        )
        assertEquals(
            LoginOutcome.RateLimited,
            loginOutcome(ru.kolco24.kolco24.data.api.PostResult.RateLimited),
        )
        assertEquals(
            LoginOutcome.Offline,
            loginOutcome(ru.kolco24.kolco24.data.api.PostResult.Offline),
        )
        assertEquals(
            LoginOutcome.Error,
            loginOutcome(ru.kolco24.kolco24.data.api.PostResult.Forbidden),
        )
        assertEquals(
            LoginOutcome.Error,
            loginOutcome(ru.kolco24.kolco24.data.api.PostResult.BadRequest),
        )
        assertEquals(
            LoginOutcome.Error,
            loginOutcome(ru.kolco24.kolco24.data.api.PostResult.Error(500)),
        )
    }

    @Test
    fun adminErrorMessage_strings() {
        assertEquals("", adminErrorMessage(LoginOutcome.Success))
        assertEquals("Неверный email или пароль", adminErrorMessage(LoginOutcome.InvalidCredentials))
        assertEquals(
            "Слишком много попыток входа. Попробуйте позже",
            adminErrorMessage(LoginOutcome.RateLimited),
        )
        assertEquals("Нет соединения с сервером", adminErrorMessage(LoginOutcome.Offline))
        assertEquals("Не удалось войти. Попробуйте ещё раз", adminErrorMessage(LoginOutcome.Error))
    }

    @Test
    fun isExpired_pastIsTrue_futureIsFalse_boundaryIsExpired() {
        val now = "2026-06-21T12:00:00Z"
        assertTrue(isExpired("2026-06-21T11:59:59Z", now)) // expiry before now → expired
        assertFalse(isExpired("2026-06-21T12:00:01Z", now)) // expiry after now → valid
        assertTrue(isExpired("2026-06-21T12:00:00Z", now)) // exact boundary → expired
    }

    // --- seeding ---

    @Test
    fun seed_pastExpiry_isLoggedOut_andClearsStore() {
        val fake = FakeStore(
            mapOf(
                "admin_token" to "tok",
                "admin_email" to "a@b.ru",
                "admin_token_expires_at" to "2025-01-01T00:00:00Z",
            ),
        )
        val repo = repo(fake.store(), now = "2026-01-01T00:00:00Z")

        assertEquals(AdminSession.LoggedOut, repo.session.value)
        assertNull(repo.token())
        assertNull(fake.map["admin_token"])
    }

    @Test
    fun seed_futureExpiry_isLoggedIn_andTokenAvailable() {
        val fake = FakeStore(
            mapOf(
                "admin_token" to "tok-xyz",
                "admin_email" to "admin@kolco24.ru",
                "admin_token_expires_at" to "2099-01-01T00:00:00Z",
            ),
        )
        val repo = repo(fake.store(), now = "2026-01-01T00:00:00Z")

        val s = repo.session.value
        assertTrue(s is AdminSession.LoggedIn)
        s as AdminSession.LoggedIn
        assertEquals("admin@kolco24.ru", s.email)
        assertEquals("tok-xyz", s.token)
        assertEquals("tok-xyz", repo.token())
    }

    @Test
    fun seed_emptyStore_isLoggedOut() {
        val repo = repo(FakeStore().store())
        assertEquals(AdminSession.LoggedOut, repo.session.value)
    }

    // --- login ---

    @Test
    fun login_success_persistsAndUpdatesFlow() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"token":"new-tok","expires_at":"2099-07-21T14:03:00Z"}"""),
        )
        val fake = FakeStore()
        val repo = repo(fake.store())

        val outcome = repo.login("admin@kolco24.ru", "s3cret")

        assertEquals(LoginOutcome.Success, outcome)
        val s = repo.session.value
        assertTrue(s is AdminSession.LoggedIn)
        s as AdminSession.LoggedIn
        assertEquals("new-tok", s.token)
        assertEquals("admin@kolco24.ru", s.email)
        assertEquals("2099-07-21T14:03:00Z", s.expiresAt)
        assertEquals("new-tok", fake.map["admin_token"])
        assertEquals("admin@kolco24.ru", fake.map["admin_email"])
    }

    @Test
    fun login_wrongCredentials_returnsInvalidAndDoesNotPersist() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"bad"}"""))
        val fake = FakeStore()
        val repo = repo(fake.store())

        assertEquals(LoginOutcome.InvalidCredentials, repo.login("a@b.ru", "nope"))
        assertEquals(AdminSession.LoggedOut, repo.session.value)
        assertNull(fake.map["admin_token"])
    }

    @Test
    fun login_rateLimited_returnsRateLimited() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(LoginOutcome.RateLimited, repo(FakeStore().store()).login("a@b.ru", "x"))
    }

    @Test
    fun login_offline_returnsOffline() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(LoginOutcome.Offline, repo(FakeStore().store()).login("a@b.ru", "x"))
    }

    // --- logout / onUnauthorized ---

    @Test
    fun logout_clearsLocally_evenWhenOffline() = runTest {
        // Server drops the connection → apiClient.logout() returns Offline, but the local session
        // and store must still be cleared.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val fake = FakeStore(
            mapOf(
                "admin_token" to "tok",
                "admin_email" to "a@b.ru",
                "admin_token_expires_at" to "2099-01-01T00:00:00Z",
            ),
        )
        val repo = repo(fake.store())
        assertTrue(repo.session.value is AdminSession.LoggedIn)

        repo.logout()

        assertEquals(AdminSession.LoggedOut, repo.session.value)
        assertNull(fake.map["admin_token"])
        assertNull(fake.map["admin_email"])
        assertNull(fake.map["admin_token_expires_at"])
    }

    @Test
    fun logout_clearsLocally_whenServerSucceeds() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val fake = FakeStore(
            mapOf(
                "admin_token" to "tok",
                "admin_email" to "a@b.ru",
                "admin_token_expires_at" to "2099-01-01T00:00:00Z",
            ),
        )
        val repo = repo(fake.store())

        repo.logout()

        assertEquals(AdminSession.LoggedOut, repo.session.value)
        assertNull(fake.map["admin_token"])
    }

    @Test
    fun onUnauthorized_clearsStoreAndSession() {
        val fake = FakeStore(
            mapOf(
                "admin_token" to "tok",
                "admin_email" to "a@b.ru",
                "admin_token_expires_at" to "2099-01-01T00:00:00Z",
            ),
        )
        val repo = repo(fake.store())
        assertTrue(repo.session.value is AdminSession.LoggedIn)

        repo.onUnauthorized()

        assertEquals(AdminSession.LoggedOut, repo.session.value)
        assertNull(repo.token())
        assertNull(fake.map["admin_token"])
    }
}
