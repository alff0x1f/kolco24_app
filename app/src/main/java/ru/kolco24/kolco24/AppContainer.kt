package ru.kolco24.kolco24

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import ru.kolco24.kolco24.data.InstallId
import ru.kolco24.kolco24.data.RaceRepository
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

    /** Long-lived scope for fire-and-forget background work (e.g. startup refresh). */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
