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

    /**
     * Bumped by [logout]; a [login] started before a logout must not resurrect the session when its
     * response lands afterwards (parallel cloud + LAN login: one answers, the admin taps «Выйти», the
     * other answers late). Check-and-persist and bump-and-clear hold [lock] so neither interleaves.
     */
    private var generation = 0
    private val lock = Any()

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
     * mapped to a [LoginOutcome] for the UI via the pure [loginOutcome]. A success that lands after a
     * [logout] issued during the request is dropped and reported as [LoginOutcome.Error].
     */
    suspend fun login(email: String, password: String): LoginOutcome {
        val startedAt = synchronized(lock) { generation }
        val result = apiClient.login(email, password)
        if (result is PostResult.Success) {
            synchronized(lock) {
                if (generation != startedAt) return LoginOutcome.Error
                store.write(result.data.token, email, result.data.expiresAt)
                _session.value = AdminSession.LoggedIn(email, result.data.token, result.data.expiresAt)
            }
        }
        return loginOutcome(result)
    }

    /**
     * Logs out: fires `POST /app/logout/` best-effort (the server revokes the token) and **always**
     * clears the local store and drops to [AdminSession.LoggedOut] afterwards — even when the network
     * call fails offline, so the local session can never be stuck logged-in. Also cancels any in-flight
     * [login]; when already logged out it does only that (no network call).
     */
    suspend fun logout() {
        synchronized(lock) { generation++ }
        if (_session.value is AdminSession.LoggedOut) return
        try {
            apiClient.logout()
        } finally {
            synchronized(lock) {
                store.clear()
                _session.value = AdminSession.LoggedOut
            }
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

/**
 * Folds the per-server outcomes of one parallel cloud + LAN login into the single outcome the form shows.
 * A server's real answer beats "unreachable": in the forest cloud is almost always [LoginOutcome.Offline],
 * which must not hide a LAN «неверный пароль». Rank: Success > InvalidCredentials > RateLimited > Error >
 * Offline. An empty list (nothing attempted) is [LoginOutcome.Error].
 */
fun combinedLoginOutcome(outcomes: List<LoginOutcome>): LoginOutcome =
    outcomes.maxByOrNull { LOGIN_OUTCOME_RANK.indexOf(it) } ?: LoginOutcome.Error

private val LOGIN_OUTCOME_RANK = listOf(
    LoginOutcome.Offline,
    LoginOutcome.Error,
    LoginOutcome.RateLimited,
    LoginOutcome.InvalidCredentials,
    LoginOutcome.Success,
)

/**
 * Subtitle of the Settings «Администратор» row: «Войти» with no session, the email when both servers are
 * logged in (cloud's), and the email plus which single server is active otherwise.
 */
fun adminRowSubtitle(cloud: AdminSession, local: AdminSession): String = when {
    cloud is AdminSession.LoggedIn && local is AdminSession.LoggedIn -> cloud.email
    cloud is AdminSession.LoggedIn -> "${cloud.email} · только Cloud"
    local is AdminSession.LoggedIn -> "${local.email} · только LAN"
    else -> "Войти"
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
