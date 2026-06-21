package ru.kolco24.kolco24

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import ru.kolco24.kolco24.data.db.AppDatabase

/**
 * Hand-rolled dependency container (no Hilt). Everything is `lazy` so nothing touches the
 * network or disk until first use. Held by [Kolco24App] for the process lifetime.
 */
class AppContainer(private val context: Context) {

    private val baseUrl: String = BuildConfig.API_BASE_URL

    private val installId: String by lazy { InstallId.fromSharedPreferences(context) }

    private val json: Json by lazy { Json { ignoreUnknownKeys = true } }

    private val apiClient: ApiClient by lazy {
        val interceptor = AppSignatureInterceptor(
            keyId = BuildConfig.APP_KEY_ID,
            secret = BuildConfig.APP_SECRET,
            installIdProvider = { installId },
            appVersion = BuildConfig.VERSION_NAME,
            // Deferred to request time: invoked only after both `by lazy` blocks have initialized.
            // `token()` is a synchronous StateFlow.value read, so no init-time recursion and no
            // blocking on the interceptor thread. Bearer is never part of the canonical string.
            tokenProvider = { adminAuthRepository.token() },
        )
        ApiClient(
            baseUrl = baseUrl,
            okHttpClient = ApiClient.defaultOkHttpClient(interceptor),
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
     * Debug/testing only — wipes every Room table (races, categories, teams, selected_team,
     * checkpoints) plus the `sync_meta` ETags, so the next refresh re-fetches everything from
     * scratch. Blocking; call from a background dispatcher.
     */
    fun clearDatabase() = database.clearAllTables()
}
