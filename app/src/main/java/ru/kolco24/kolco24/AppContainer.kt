package ru.kolco24.kolco24

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import ru.kolco24.kolco24.data.AdminAuthRepository
import ru.kolco24.kolco24.data.AdminTokenStore
import ru.kolco24.kolco24.data.InstallId
import ru.kolco24.kolco24.data.LegendRepository
import ru.kolco24.kolco24.data.MarkRepository
import ru.kolco24.kolco24.data.MemberChipBindingRepository
import ru.kolco24.kolco24.data.MemberTagsRepository
import ru.kolco24.kolco24.data.RaceRepository
import ru.kolco24.kolco24.data.TeamRepository
import ru.kolco24.kolco24.data.ThemePreference
import ru.kolco24.kolco24.data.api.ApiClient
import ru.kolco24.kolco24.data.api.AppSignatureInterceptor
import ru.kolco24.kolco24.data.api.ServerTimeInterceptor
import ru.kolco24.kolco24.data.db.AppDatabase
import ru.kolco24.kolco24.data.time.ClockAnchorStore
import ru.kolco24.kolco24.data.time.TrustedClock
import ru.kolco24.kolco24.data.track.TrackRepository
import ru.kolco24.kolco24.data.track.TrackState
import ru.kolco24.kolco24.ui.admin.ProvisionState

/**
 * Hand-rolled dependency container (no Hilt). Everything is `lazy` so nothing touches the
 * network or disk until first use. Held by [Kolco24App] for the process lifetime.
 */
class AppContainer(private val context: Context) {

    private val baseUrl: String = BuildConfig.API_BASE_URL

    private val installId: String by lazy { InstallId.fromSharedPreferences(context) }

    private val json: Json by lazy { Json { ignoreUnknownKeys = true } }

    /**
     * `Settings.Global.BOOT_COUNT` read **once per process** (P2). The value is invariant for the
     * process lifetime, so caching it eliminates a transient `getInt` failure mid-run; a single
     * failed read means `null` for the whole process (warm start off, monotonic-regression fallback).
     * API 24, no permission required.
     */
    private val cachedBootCount: Int? =
        runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()

    /** Persisted trusted-clock anchor (warm-start seed); backs [trustedClock]'s `persist`/`persisted`. */
    private val clockAnchorStore: ClockAnchorStore by lazy {
        ClockAnchorStore.fromSharedPreferences(context)
    }

    /**
     * Trusted-time core. Anchors server time to the monotonic `elapsedRealtime()` timer so a
     * wall-clock change can't move recorded take times. `persist`/`persisted` give a true warm start
     * within the same boot session (anchor survives a process restart). Fed by [ServerTimeInterceptor]
     * on every network `Date` header.
     */
    val trustedClock: TrustedClock by lazy {
        TrustedClock(
            elapsedProvider = { SystemClock.elapsedRealtime() },
            wallProvider = { System.currentTimeMillis() },
            bootCountProvider = { cachedBootCount },
            persist = clockAnchorStore::write,
            persisted = clockAnchorStore.read(),
        )
    }

    /**
     * HMAC signing interceptor shared by both the cloud [apiClient] and the LAN [localApiClient]:
     * the local race server validates the same 6 `X-App-*`/`X-Install-Id` headers with the same key
     * id / secret, so it must be signed identically (only the trusted-time re-anchor differs — see
     * [localApiClient]).
     */
    private val signatureInterceptor: AppSignatureInterceptor by lazy {
        AppSignatureInterceptor(
            keyId = BuildConfig.APP_KEY_ID,
            secret = BuildConfig.APP_SECRET,
            installIdProvider = { installId },
            appVersion = BuildConfig.VERSION_NAME,
            // Sign `ts` with trusted time (Task 4b): when the phone wall-clock has drifted past the
            // server's ±300 s window, `signingSeconds()` still yields a server-aligned `ts` (or honest
            // wall before the first anchor). Lambda fires at request time, so no construction cycle —
            // `trustedClock` doesn't touch `apiClient`.
            nowSeconds = { trustedClock.signingSeconds() },
            // Deferred to request time: invoked only after both `by lazy` blocks have initialized.
            // `token()` is a synchronous StateFlow.value read, so no init-time recursion and no
            // blocking on the interceptor thread. Bearer is never part of the canonical string.
            tokenProvider = { adminAuthRepository.token() },
        )
    }

    /**
     * Shared signed `/app/` client. Exposed (not private) so the admin provisioning flow can issue
     * `bindTag` POSTs directly — there is no provisioning repository (the bind response isn't
     * persisted; the next legend refresh delivers the new tag via the existing `tags[]` array).
     */
    val apiClient: ApiClient by lazy {
        // onServerTime as a lambda breaks the construction cycle (like `tokenProvider`): it touches
        // `trustedClock` only at request time, after both `by lazy` blocks have initialized.
        val serverTimeInterceptor = ServerTimeInterceptor(
            onServerTime = { s, e, w, b -> trustedClock.onServerTime(s, e, w, b) },
            elapsed = { SystemClock.elapsedRealtime() },
            wall = { System.currentTimeMillis() },
            bootCount = { cachedBootCount },
        )
        ApiClient(
            baseUrl = baseUrl,
            okHttpClient = ApiClient.defaultOkHttpClient(signatureInterceptor, serverTimeInterceptor),
            json = json,
        )
    }

    /**
     * LAN upload client for the local race server (`BuildConfig.LOCAL_API_BASE_URL`, cleartext to
     * `192.168.1.5` only — see `res/xml/network_security_config.xml`). It is a **second host**, so:
     * (a) no [ServerTimeInterceptor] — we never anchor trusted time off a LAN server, and this keeps
     * `ServerTimeInterceptor`'s single-host assumption intact; (b) short 3 s connect/read timeouts so
     * an upload fails fast (→ `Offline`) when the phone is off the event's Wi-Fi, instead of hanging
     * ~10 s. It shares the [signatureInterceptor] (same key id / secret / 6 headers as cloud).
     */
    val localApiClient: ApiClient by lazy {
        ApiClient(
            baseUrl = BuildConfig.LOCAL_API_BASE_URL,
            okHttpClient = ApiClient.defaultOkHttpClient(
                signatureInterceptor,
                connectTimeoutMs = 3_000,
                readTimeoutMs = 3_000,
            ),
            json = json,
        )
    }

    private val database: AppDatabase by lazy { AppDatabase.build(context) }

    val raceRepository: RaceRepository by lazy {
        RaceRepository(
            apiClient = apiClient,
            raceDao = database.raceDao(),
            syncMetaDao = database.syncMetaDao(),
            origin = baseUrl,
        )
    }

    val teamRepository: TeamRepository by lazy {
        TeamRepository(
            apiClient = apiClient,
            teamDao = database.teamDao(),
            selectedTeamDao = database.selectedTeamDao(),
            syncMetaDao = database.syncMetaDao(),
            origin = baseUrl,
        )
    }

    val legendRepository: LegendRepository by lazy {
        LegendRepository(
            apiClient = apiClient,
            checkpointDao = database.checkpointDao(),
            tagDao = database.tagDao(),
            legendMetaDao = database.legendMetaDao(),
            syncMetaDao = database.syncMetaDao(),
            origin = baseUrl,
            json = json,
        )
    }

    val memberTagsRepository: MemberTagsRepository by lazy {
        MemberTagsRepository(
            apiClient = apiClient,
            memberTagDao = database.memberTagDao(),
            syncMetaDao = database.syncMetaDao(),
            origin = baseUrl,
        )
    }

    val memberChipBindingRepository: MemberChipBindingRepository by lazy {
        MemberChipBindingRepository(
            bindingDao = database.memberChipBindingDao(),
        )
    }

    val markRepository: MarkRepository by lazy {
        MarkRepository(
            markDao = database.markDao(),
        )
    }

    /**
     * Local-only GPS track store. Owns the [RawFix]→entity mapping; the recording service forwards
     * raw fixes. `bootCountProvider` is the per-process [cachedBootCount] (a fix is captured in the
     * running boot session), `idFactory` mints the client UUID, and `wallProvider`/`elapsedProvider`
     * read the wall/monotonic clocks once per batch for the wall back-projection.
     */
    val trackRepository: TrackRepository by lazy {
        TrackRepository(
            trackDao = database.trackDao(),
            trustedClock = trustedClock,
            bootCountProvider = { cachedBootCount },
            idFactory = { UUID.randomUUID().toString() },
            wallProvider = { System.currentTimeMillis() },
            elapsedProvider = { SystemClock.elapsedRealtime() },
        )
    }

    /** GPS-track recording state: written by `TrackRecordingService`, read by the UI. */
    val trackRecordingState: MutableStateFlow<TrackState> = MutableStateFlow(TrackState.Idle)

    /** User-controlled app theme preference (System/Light/Dark), persisted in SharedPreferences. */
    val themePreference: ThemePreference by lazy { ThemePreference.fromSharedPreferences(context) }

    /** Persisted race-admin session store (token/email/expiry) backing [adminAuthRepository]. */
    private val adminTokenStore: AdminTokenStore by lazy {
        AdminTokenStore.fromSharedPreferences(context)
    }

    /** Reactive race-admin session; its [AdminAuthRepository.token] feeds the interceptor's bearer. */
    val adminAuthRepository: AdminAuthRepository by lazy {
        AdminAuthRepository(
            apiClient = apiClient,
            store = adminTokenStore,
        )
    }

    /** Long-lived scope for fire-and-forget background work (e.g. startup refresh). */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Application-scoped guard for the chip-provisioning bind+write job. Lives here so the same
     * lock instance survives overlay close/reopen and activity rotation — a composition-scoped
     * [remember] would reset to `false` on recreation while the old job is still running.
     */
    val provisioningLock: AtomicBoolean = AtomicBoolean(false)

    /**
     * App-scoped provisioning scan-zone state. Lives here (not in the composition) so it survives
     * overlay close/reopen and activity rotation: the [applicationScope] bind+write job writes to
     * this flow directly, and [ProvisioningScreen] collects it via [collectAsState]. This eliminates
     * the race where a [finally]-released lock allows a new job to set the state, only for the
     * previous [finally] to clobber it — the lock and the state are now updated in a single step.
     */
    val provisioningState: MutableStateFlow<ProvisionState> =
        MutableStateFlow(ProvisionState.WaitingForChip)

    /**
     * App-scoped fresh-token map for the provisioning session, keyed by checkpoint id. Survives
     * activity rotation so the chip-rack pills remain visible if the device is rotated while a
     * bind+write job is in flight. The [applicationScope] job writes here directly (thread-safe);
     * [ProvisioningScreen] collects it via [collectAsState]. Reset in [DisposableEffect] onDispose
     * alongside [provisioningState] and [provisioningActivePage] on intentional close (not rotation).
     */
    val provisioningFreshTokens: MutableStateFlow<Map<Int, List<String>>> =
        MutableStateFlow(emptyMap())

    /**
     * The pager page that was active when the last bind+write job started. Persisted app-scoped so a
     * close/reopen or rotation can restore the pager to the correct checkpoint. Reset to 0 alongside
     * [provisioningState] and [provisioningFreshTokens] on intentional close (not rotation).
     */
    val provisioningActivePage: MutableStateFlow<Int> = MutableStateFlow(0)

    /**
     * The race that owns the current provisioning session. Set when a bind+write job starts and
     * cleared on cleanup. Used by [DisposableEffect] in [ProvisioningScreen] to distinguish a
     * same-session reopen (cancel cleanup) from a cross-race open (reset stale state instead).
     */
    val provisioningActiveRaceId: MutableStateFlow<Int?> = MutableStateFlow(null)

    /**
     * App-scoped deferred-cleanup flag for the provisioning flow. Set by [DisposableEffect]'s
     * `onDispose` when the overlay is closed (not rotated) while a bind+write job is still running;
     * cleared (via [AtomicBoolean.compareAndSet]) by whichever path — the job's `finally` block or
     * `onDispose` itself — wins the race. Lives here (not in the composition) so that after an
     * activity rotation the same flag instance is visible to both the surviving job and the new
     * `DisposableEffect` that re-arms the hook.
     */
    val provisioningPendingCleanup: AtomicBoolean = AtomicBoolean(false)

    /**
     * Debug/testing only — wipes every Room table (races, categories, teams, selected_team,
     * checkpoints) plus the `sync_meta` ETags, so the next refresh re-fetches everything from
     * scratch. Blocking; call from a background dispatcher.
     */
    fun clearDatabase() = database.clearAllTables()
}
