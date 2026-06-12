package ru.kolco24.kolco24.data.api

import okhttp3.Interceptor
import okhttp3.Response
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** hex SHA-256 of an empty body — constant for all GET requests (see docs/API.md). */
const val EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

/**
 * Builds the canonical string the server expects to be signed: four parts joined by `\n`
 * (no trailing newline). See docs/API.md.
 *
 * `fullPath` must be the path that is actually sent, including the trailing slash and
 * query string if any. The empty-body hash is appended as the fourth part.
 */
fun buildCanonical(method: String, fullPath: String, ts: String): String =
    listOf(method.uppercase(), fullPath, ts, EMPTY_BODY_SHA256).joinToString("\n")

/** lower-case hex HMAC-SHA256 of [canonical] keyed by [secret] (both UTF-8). */
fun sign(secret: String, canonical: String): String {
    val mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    }
    return mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * Signs every outgoing request with the six `X-App-*` headers required by the API.
 *
 * `ts` is read fresh on each invocation (including retries), so the signature is always
 * recomputed against the current time — the ±300 s window stays satisfied on retries.
 */
class AppSignatureInterceptor(
    private val keyId: String,
    private val secret: String,
    private val installIdProvider: () -> String,
    private val appVersion: String,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val fullPath = buildString {
            append(url.encodedPath)
            url.encodedQuery?.let { append("?").append(it) }
        }
        val ts = nowSeconds().toString()
        val canonical = buildCanonical(request.method, fullPath, ts)
        val sig = sign(secret, canonical)

        val signed = request.newBuilder()
            .header("X-App-Key-Id", keyId)
            .header("X-App-Sig", sig)
            .header("X-App-Ts", ts)
            .header("X-Install-Id", installIdProvider())
            .header("X-App-Platform", "android")
            .header("X-App-Version", appVersion)
            .build()
        return chain.proceed(signed)
    }
}
