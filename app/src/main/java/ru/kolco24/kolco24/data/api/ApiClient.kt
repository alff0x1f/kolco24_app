package ru.kolco24.kolco24.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.kolco24.kolco24.data.api.dto.LegendResponse
import ru.kolco24.kolco24.data.api.dto.MemberTagsResponse
import ru.kolco24.kolco24.data.api.dto.RaceDto
import ru.kolco24.kolco24.data.api.dto.RacesResponse
import ru.kolco24.kolco24.data.api.dto.TeamsResponse
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Outcome of a single conditional fetch, parameterised by the payload type [T]. Network
 * (`IOException`) and parse (`SerializationException`) failures never escape as exceptions — they
 * collapse into [Error] with a `null` code, so callers always get a value back. The non-success
 * branches carry no payload and are typed `FetchResult<Nothing>`.
 */
sealed interface FetchResult<out T> {
    data class Success<T>(val data: T, val etag: String?) : FetchResult<T>
    data object NotModified : FetchResult<Nothing>
    data object Forbidden : FetchResult<Nothing>
    data class Error(val code: Int?) : FetchResult<Nothing>
}

/**
 * Outcome of a single POST, parameterised by the payload type [T]. Like [FetchResult], network
 * (`IOException`) and parse (`SerializationException`) failures never escape as exceptions: the
 * former collapses into [Offline], the latter into [Error] with a `null` code. The HTTP status is
 * mapped onto a dedicated branch so callers can react without inspecting raw codes. The non-success
 * branches carry no payload and are typed `PostResult<Nothing>`.
 */
sealed interface PostResult<out T> {
    data class Success<T>(val data: T) : PostResult<T>
    data object BadRequest : PostResult<Nothing>
    data object Unauthorized : PostResult<Nothing>
    data object Forbidden : PostResult<Nothing>
    data object Conflict : PostResult<Nothing>
    data object RateLimited : PostResult<Nothing>
    data object Offline : PostResult<Nothing>
    data class Error(val code: Int?) : PostResult<Nothing>
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
    private val baseUrl = baseUrl.trimEnd('/')
    private val racesUrl = "${this.baseUrl}/app/races/"

    /**
     * `GET /app/races/`. When [etag] is non-null it is echoed verbatim (with quotes) in
     * `If-None-Match`. `200` → [FetchResult.Success] with the parsed list and the response ETag;
     * `304` → [FetchResult.NotModified]; `403` → [FetchResult.Forbidden]; any other code →
     * [FetchResult.Error] with that code. Runs on [Dispatchers.IO].
     */
    suspend fun fetchRaces(etag: String?): FetchResult<List<RaceDto>> =
        conditionalGet(racesUrl, etag) { json.decodeFromString<RacesResponse>(it).races }

    /**
     * `GET /app/race/<raceId>/teams/`. Same conditional-request semantics as [fetchRaces];
     * `200` → [FetchResult.Success] with the parsed [TeamsResponse] and the response ETag.
     */
    suspend fun fetchTeams(raceId: Int, etag: String?): FetchResult<TeamsResponse> =
        conditionalGet("$baseUrl/app/race/$raceId/teams/", etag) {
            json.decodeFromString<TeamsResponse>(it)
        }

    /**
     * `GET /app/race/<raceId>/legend/`. Same conditional-request semantics as [fetchRaces];
     * `200` → [FetchResult.Success] with the parsed [LegendResponse] and the response ETag. A
     * hidden legend still returns `200` with an empty `checkpoints` list.
     */
    suspend fun fetchLegend(raceId: Int, etag: String?): FetchResult<LegendResponse> =
        conditionalGet("$baseUrl/app/race/$raceId/legend/", etag) {
            json.decodeFromString<LegendResponse>(it)
        }

    /**
     * `GET /app/race/<raceId>/member_tags/`. Same conditional-request semantics as [fetchRaces];
     * `200` → [FetchResult.Success] with the parsed [MemberTagsResponse] (the race's NFC chip pool)
     * and the response ETag.
     */
    suspend fun fetchMemberTags(raceId: Int, etag: String?): FetchResult<MemberTagsResponse> =
        conditionalGet("$baseUrl/app/race/$raceId/member_tags/", etag) {
            json.decodeFromString<MemberTagsResponse>(it)
        }

    /**
     * Shared conditional `GET`: signs/sends the request (signing via [okHttpClient]'s interceptor),
     * echoes [etag] verbatim in `If-None-Match` when non-null, and maps the response. [parse] turns
     * the `200` body into the payload [T]. Runs on [Dispatchers.IO]; `IOException`/
     * `SerializationException` collapse into [FetchResult.Error] with a `null` code.
     */
    private suspend fun <T> conditionalGet(
        url: String,
        etag: String?,
        parse: (String) -> T,
    ): FetchResult<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string().orEmpty()
                        FetchResult.Success(parse(body), response.header("ETag"))
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

    /**
     * Shared `POST` of an exact `application/json` [bodyBytes] to [url]. Signing is handled by
     * [okHttpClient]'s interceptor (which hashes the exact bytes). [parse] turns the body into the
     * payload [T] and is invoked **only** on the `200`/`201` branch — error bodies are never parsed,
     * so an empty-body endpoint with a `{ Unit }` parser is safe. Status mapping: `200`/`201` →
     * [PostResult.Success]; `400` → [PostResult.BadRequest]; `401` → [PostResult.Unauthorized];
     * `403` → [PostResult.Forbidden]; `409` → [PostResult.Conflict]; `429` → [PostResult.RateLimited];
     * any other code → [PostResult.Error]. Runs on [Dispatchers.IO]; `IOException` →
     * [PostResult.Offline], `SerializationException` → [PostResult.Error] with a `null` code.
     */
    internal suspend fun <T> post(
        url: String,
        bodyBytes: ByteArray,
        parse: (String) -> T,
    ): PostResult<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(bodyBytes.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200, 201 -> {
                        val body = response.body?.string().orEmpty()
                        PostResult.Success(parse(body))
                    }
                    400 -> PostResult.BadRequest
                    401 -> PostResult.Unauthorized
                    403 -> PostResult.Forbidden
                    409 -> PostResult.Conflict
                    429 -> PostResult.RateLimited
                    else -> PostResult.Error(response.code)
                }
            }
        } catch (_: IOException) {
            PostResult.Offline
        } catch (_: SerializationException) {
            PostResult.Error(null)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** OkHttp client with the 10 s connect/read timeouts and the signing interceptor. */
        fun defaultOkHttpClient(signatureInterceptor: AppSignatureInterceptor): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(signatureInterceptor)
                .build()
    }
}
