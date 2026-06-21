package ru.kolco24.kolco24.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.kolco24.kolco24.data.api.ApiClient
import ru.kolco24.kolco24.data.api.PostResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The race-admin auth state. [LoggedOut] is the resting state; [LoggedIn] carries the opaque 30-day
 * bearer [token] used by the signing interceptor, the [email] shown in the UI, and the raw ISO
 * [expiresAt] string from the server (UTC, `Z`-suffixed) used for the lazy expiry check.
 */
sealed interface AdminSession {
    data object LoggedOut : AdminSession
    data class LoggedIn(val email: String, val token: String, val expiresAt: String) : AdminSession
}

/** Result of a [AdminAuthRepository.login] call, surfaced to the login form. */
sealed interface LoginOutcome {
    data object Success : LoginOutcome
    data object InvalidCredentials : LoginOutcome
    data object RateLimited : LoginOutcome
    data object Offline : LoginOutcome
    data object Error : LoginOutcome
}

/**
 * Reactive source of truth for the race-admin session. The [session] flow is seeded **synchronously**
 * at construction from [store] (a persisted [StoredSession] past its expiry is treated as
 * [AdminSession.LoggedOut] and cleared), so the first composed frame already reflects the stored
 * state and the OkHttp interceptor can read [token] without blocking.
 *
 * Constructor injection (mirroring how repositories take DAOs) keeps it unit-testable: a
 * MockWebServer-backed [apiClient] drives the network outcomes, an injected [store] is asserted on,
 * and [nowUtcIso] is overridden to pin the expiry boundary.
 *
 * @param nowUtcIso current time formatted as `yyyy-MM-dd'T'HH:mm:ss'Z'` in UTC — the **exact** shape
 *   the server uses for `expires_at`, so [isExpired] can lexicographically compare the two strings.
 */
class AdminAuthRepository(
    private val apiClient: ApiClient,
    private val store: AdminTokenStore,
    private val nowUtcIso: () -> String = ::utcNowIso,
) {
    private val _session = MutableStateFlow(seedSession())

    /** The current admin session; emits on every login/logout/expiry transition. */
    val session: StateFlow<AdminSession> = _session.asStateFlow()

    /**
     * Synchronous bearer-token read for the signing interceptor thread (no suspend, no I/O). Returns
     * the token while [AdminSession.LoggedIn], `null` otherwise.
     */
    fun token(): String? = (_session.value as? AdminSession.LoggedIn)?.token

    /**
     * Attempts a login. On [PostResult.Success] the token/email/expiry are persisted and the flow
     * transitions to [AdminSession.LoggedIn]; failures leave the session untouched. The status is
     * mapped to a [LoginOutcome] for the UI via the pure [loginOutcome].
     */
    suspend fun login(email: String, password: String): LoginOutcome {
        val result = apiClient.login(email, password)
        if (result is PostResult.Success) {
            store.write(result.data.token, email, result.data.expiresAt)
            _session.value = AdminSession.LoggedIn(email, result.data.token, result.data.expiresAt)
        }
        return loginOutcome(result)
    }

    /**
     * Logs out: fires `POST /app/logout/` best-effort (the server revokes the token) and **always**
     * clears the local store and drops to [AdminSession.LoggedOut] afterwards — even when the network
     * call fails offline, so the local session can never be stuck logged-in.
     */
    suspend fun logout() {
        try {
            apiClient.logout()
        } finally {
            store.clear()
            _session.value = AdminSession.LoggedOut
        }
    }

    /**
     * Called when a protected request returns `401` (token revoked/expired server-side): clears the
     * local store and drops to [AdminSession.LoggedOut] so the UI returns to the login form.
     */
    fun onUnauthorized() {
        store.clear()
        _session.value = AdminSession.LoggedOut
    }

    private fun seedSession(): AdminSession {
        val stored = store.read() ?: return AdminSession.LoggedOut
        return if (isExpired(stored.expiresAt, nowUtcIso())) {
            store.clear()
            AdminSession.LoggedOut
        } else {
            AdminSession.LoggedIn(stored.email, stored.token, stored.expiresAt)
        }
    }
}

/**
 * Maps a login [PostResult] to a [LoginOutcome]: `401` → [LoginOutcome.InvalidCredentials] (the
 * ambiguous bad-credentials case), `429` → [LoginOutcome.RateLimited], `IOException` →
 * [LoginOutcome.Offline], anything else → [LoginOutcome.Error].
 */
fun loginOutcome(result: PostResult<*>): LoginOutcome = when (result) {
    is PostResult.Success -> LoginOutcome.Success
    PostResult.Unauthorized -> LoginOutcome.InvalidCredentials
    PostResult.RateLimited -> LoginOutcome.RateLimited
    PostResult.Offline -> LoginOutcome.Offline
    PostResult.BadRequest,
    PostResult.Conflict,
    PostResult.Forbidden,
    is PostResult.Error -> LoginOutcome.Error
}

/** User-facing RU message for a failed login [outcome] (empty for [LoginOutcome.Success]). */
fun adminErrorMessage(outcome: LoginOutcome): String = when (outcome) {
    LoginOutcome.Success -> ""
    // Deliberately ambiguous: never reveal whether the email or the password was wrong.
    LoginOutcome.InvalidCredentials -> "Неверный email или пароль"
    LoginOutcome.RateLimited -> "Слишком много попыток входа. Попробуйте позже"
    LoginOutcome.Offline -> "Нет соединения с сервером"
    LoginOutcome.Error -> "Не удалось войти. Попробуйте ещё раз"
}

/**
 * Whether [expiresAt] is at or before [nowUtcIso]. Both must be fixed-width UTC strings of the shape
 * `yyyy-MM-dd'T'HH:mm:ss'Z'`, which makes a plain lexicographic compare correct (no `java.time`). The
 * exact-equality boundary counts as expired.
 */
fun isExpired(expiresAt: String, nowUtcIso: String): Boolean = nowUtcIso >= expiresAt

/** `Date()` formatted as a fixed-width UTC `yyyy-MM-dd'T'HH:mm:ss'Z'` string (see [isExpired]). */
private fun utcNowIso(): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date())
}
