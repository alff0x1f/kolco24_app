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
import ru.kolco24.kolco24.data.api.dto.LoginRequest
import ru.kolco24.kolco24.data.api.dto.LoginResponse
import ru.kolco24.kolco24.data.api.dto.MemberTagsResponse
import ru.kolco24.kolco24.data.api.dto.RaceDto
import ru.kolco24.kolco24.data.api.dto.RacesResponse
import ru.kolco24.kolco24.data.api.dto.TagBindRequest
import ru.kolco24.kolco24.data.api.dto.TagBindResponse
import ru.kolco24.kolco24.data.api.dto.TeamsResponse
import ru.kolco24.kolco24.data.api.dto.TrackPointDto
import ru.kolco24.kolco24.data.api.dto.TrackUploadRequest
import ru.kolco24.kolco24.data.api.dto.TrackUploadResponse
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
     * `POST /app/login/` with the race-admin [email]/[password]. `200`/`201` →
     * [PostResult.Success] with the parsed [LoginResponse] (opaque bearer token + `expires_at`);
     * `401` → [PostResult.Unauthorized] (bad credentials); `429` → [PostResult.RateLimited]. The
     * request body is serialized once to bytes so the interceptor hashes exactly what is sent.
     */
    suspend fun login(email: String, password: String): PostResult<LoginResponse> {
        val bytes = json.encodeToString(LoginRequest(email, password)).toByteArray()
        return post("$baseUrl/app/login/", bytes) { json.decodeFromString<LoginResponse>(it) }
    }

    /**
     * `POST /app/logout/` with an empty body (still hashes the empty string → [EMPTY_BODY_SHA256]).
     * The parser is never invoked on an error branch and discards the (empty) success body, so a
     * `200` with no payload maps to [PostResult.Success] of [Unit].
     */
    suspend fun logout(): PostResult<Unit> =
        post("$baseUrl/app/logout/", ByteArray(0)) { }

    /**
     * `POST /app/race/<raceId>/tags/` — bind the chip [nfcUid] to checkpoint [checkpointId]. `201`
     * on a fresh bind / `200` on an idempotent re-bind → [PostResult.Success] with the parsed
     * [TagBindResponse] carrying the hex `code` to write onto the chip; `409` → [PostResult.Conflict]
     * (the chip is already bound to another КП); other statuses map per [post].
     */
    suspend fun bindTag(
        raceId: Int,
        checkpointId: Int,
        nfcUid: String,
    ): PostResult<TagBindResponse> {
        val bytes = json.encodeToString(TagBindRequest(checkpointId, nfcUid)).toByteArray()
        return post("$baseUrl/app/race/$raceId/tags/", bytes) {
            json.decodeFromString<TagBindResponse>(it)
        }
    }

    /**
     * `POST /app/race/<raceId>/track/` — upload a batch of GPS track [points] for [teamId]. `200`/`201`
     * → [PostResult.Success] with the parsed [TrackUploadResponse] (the accepted client `id`s for
     * idempotent upsert); other statuses map per [post]. The same method serves both upload targets
     * (cloud / local LAN) — the target is selected by the `ApiClient` instance, not a per-call URL.
     */
    suspend fun uploadTrack(
        raceId: Int,
        teamId: Int,
        points: List<TrackPointDto>,
    ): PostResult<TrackUploadResponse> {
        val bytes = json.encodeToString(TrackUploadRequest(teamId, points)).toByteArray()
        return post("$baseUrl/app/race/$raceId/track/", bytes) {
            json.decodeFromString<TrackUploadResponse>(it)
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

        /**
         * OkHttp client with the signing interceptor and configurable connect/read timeouts (default
         * 10 s — the cloud client's prior fixed behaviour, unchanged). The local LAN client passes
         * shorter timeouts (3 s) so an upload doesn't hang ~10 s when the phone is off the event's
         * Wi-Fi. The optional [serverTimeInterceptor] (the trusted-clock re-anchor) is added **after**
         * signing so it is the inner interceptor — by the time `proceed()` returns to the signing
         * interceptor the anchor is already updated (enables anchor-on-`403` self-heal in Task 4b).
         * The local client deliberately omits it (a LAN host must not anchor trusted time). No
         * response `Cache` is configured, so every `Date` header (incl. on `304`) is a live network
         * value.
         */
        fun defaultOkHttpClient(
            signatureInterceptor: AppSignatureInterceptor,
            serverTimeInterceptor: ServerTimeInterceptor? = null,
            connectTimeoutMs: Long = 10_000,
            readTimeoutMs: Long = 10_000,
        ): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .addInterceptor(signatureInterceptor)
                .apply { serverTimeInterceptor?.let { addInterceptor(it) } }
                .build()
    }
}
