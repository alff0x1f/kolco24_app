package ru.kolco24.kolco24.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.kolco24.kolco24.data.api.dto.RaceDto
import ru.kolco24.kolco24.data.api.dto.RacesResponse
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Outcome of a single conditional fetch. Network (`IOException`) and parse
 * (`SerializationException`) failures never escape as exceptions — they collapse into
 * [Error] with a `null` code, so callers always get a value back.
 */
sealed interface FetchResult {
    data class Success(val races: List<RaceDto>, val etag: String?) : FetchResult
    data object NotModified : FetchResult
    data object Forbidden : FetchResult
    data class Error(val code: Int?) : FetchResult
}

/**
 * Network access to the `/app/` API. Signing is handled by [AppSignatureInterceptor] inside
 * [okHttpClient]; this class only builds requests and maps responses.
 */
class ApiClient(
    baseUrl: String,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    private val racesUrl = "${baseUrl.trimEnd('/')}/app/races/"

    /**
     * `GET /app/races/`. When [etag] is non-null it is echoed verbatim (with quotes) in
     * `If-None-Match`. `200` → [FetchResult.Success] with the parsed list and the response ETag;
     * `304` → [FetchResult.NotModified]; `403` → [FetchResult.Forbidden]; any other code →
     * [FetchResult.Error] with that code. Runs on [Dispatchers.IO].
     */
    suspend fun fetchRaces(etag: String?): FetchResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(racesUrl)
            .get()
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string().orEmpty()
                        val parsed = json.decodeFromString<RacesResponse>(body)
                        FetchResult.Success(parsed.races, response.header("ETag"))
                    }
                    304 -> FetchResult.NotModified
                    403 -> FetchResult.Forbidden
                    else -> FetchResult.Error(response.code)
                }
            }
        } catch (_: IOException) {
            FetchResult.Error(null)
        } catch (_: SerializationException) {
            FetchResult.Error(null)
        }
    }

    companion object {
        /** OkHttp client with the 10 s connect/read timeouts and the signing interceptor. */
        fun defaultOkHttpClient(signatureInterceptor: AppSignatureInterceptor): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(signatureInterceptor)
                .build()
    }
}
