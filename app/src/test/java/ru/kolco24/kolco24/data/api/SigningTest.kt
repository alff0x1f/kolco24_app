package ru.kolco24.kolco24.data.api

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SigningTest {

    @Test
    fun buildCanonical_matchesApiDocExample() {
        val canonical = buildCanonical("GET", "/app/race/8/teams/", "1718200000", EMPTY_BODY_SHA256)

        val expected = listOf(
            "GET",
            "/app/race/8/teams/",
            "1718200000",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        ).joinToString("\n")
        assertEquals(expected, canonical)
    }

    @Test
    fun buildCanonical_uppercasesMethod() {
        val canonical = buildCanonical("get", "/app/races/", "1718200000", EMPTY_BODY_SHA256)

        assertEquals("GET", canonical.substringBefore("\n"))
    }

    @Test
    fun sign_matchesExternallyComputedVector() {
        // Vector computed with Python's hmac: secret="test-secret-123" over the API.md
        // canonical example. Verify with:
        //   python3 -c 'import hmac,hashlib;print(hmac.new(b"test-secret-123",
        //   b"GET\n/app/race/8/teams/\n1718200000\n"
        //   b"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        //   hashlib.sha256).hexdigest())'
        val canonical = buildCanonical("GET", "/app/race/8/teams/", "1718200000", EMPTY_BODY_SHA256)

        val sig = sign("test-secret-123", canonical)

        assertEquals(
            "cf1c254fb2eac6c7efde1cff6efe9553878370299cd60a42be4d2105a8072588",
            sig,
        )
    }

    @Test
    fun sign_producesLowerCaseHex64Chars() {
        val sig = sign("secret", buildCanonical("GET", "/app/races/", "1", EMPTY_BODY_SHA256))

        assertEquals(64, sig.length)
        assertEquals(sig.lowercase(), sig)
    }

    @Test
    fun sha256Hex_emptyBytesMatchesConstant() {
        assertEquals(EMPTY_BODY_SHA256, sha256Hex(ByteArray(0)))
    }

    @Test
    fun sha256Hex_knownVector() {
        // python3 -c 'import hashlib;print(hashlib.sha256(b"abc").hexdigest())'
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun postBody_canonicalPlacesBodyHashAsFourthPart() {
        val body = """{"email":"a@b.c","password":"pw"}"""
        val computed = sha256Hex(body.toByteArray())
        val canonical = buildCanonical("POST", "/app/login/", "1718200000", computed)

        val parts = canonical.split("\n")
        assertEquals("POST", parts[0])
        assertEquals("/app/login/", parts[1])
        assertEquals("1718200000", parts[2])
        // The 4th canonical part is exactly the body hash (not EMPTY_BODY_SHA256).
        assertEquals(computed, parts[3])
    }

    @Test
    fun interceptor_addsBearerWhenTokenProviderNonNull() {
        val captured = captureSignedRequest(token = "tok-123") {
            Request.Builder().url("https://example.test/app/races/").get().build()
        }
        assertEquals("Bearer tok-123", captured.header("Authorization"))
    }

    @Test
    fun interceptor_noBearerWhenTokenNull() {
        val captured = captureSignedRequest(token = null) {
            Request.Builder().url("https://example.test/app/races/").get().build()
        }
        assertNull(captured.header("Authorization"))
    }

    @Test
    fun interceptor_postBodyHashSignsBodyBytes() {
        val body = """{"k":"v"}""".toRequestBody("application/json".toMediaType())
        val captured = captureSignedRequest(token = null) {
            Request.Builder().url("https://example.test/app/login/").post(body).build()
        }
        // ts is fixed at 100 by the fake clock below
        val expected = sign(
            "secret",
            buildCanonical("POST", "/app/login/", "100", sha256Hex("""{"k":"v"}""".toByteArray())),
        )
        assertEquals(expected, captured.header("X-App-Sig"))
    }

    @Test
    fun interceptor_emptyGetBodyUsesEmptyBodyHash() {
        val captured = captureSignedRequest(token = null) {
            Request.Builder().url("https://example.test/app/races/").get().build()
        }
        val expected = sign(
            "secret",
            buildCanonical("GET", "/app/races/", "100", EMPTY_BODY_SHA256),
        )
        assertEquals(expected, captured.header("X-App-Sig"))
    }

    @Test
    fun interceptor_retriesGetOnceOn403WhenTsChanged() {
        val ts = mutableListOf(100L, 200L).iterator()
        val codes = mutableListOf(403, 200).iterator()
        val result = runWithChain(
            nowSeconds = { ts.next() },
            codes = codes,
        ) { Request.Builder().url("https://example.test/app/races/").get().build() }

        assertEquals(2, result.proceedCount)
        // The retry re-signed with the new ts=200.
        assertEquals("200", result.signedRequests.last().header("X-App-Ts"))
        assertEquals(200, result.finalCode)
    }

    @Test
    fun interceptor_doesNotRetryGetWhenTsUnchanged() {
        val codes = mutableListOf(403).iterator()
        val result = runWithChain(
            nowSeconds = { 100L },
            codes = codes,
        ) { Request.Builder().url("https://example.test/app/races/").get().build() }

        assertEquals(1, result.proceedCount)
        assertEquals(403, result.finalCode)
    }

    @Test
    fun interceptor_doesNotRetryPostEvenWhenTsChangedAnd403() {
        val ts = mutableListOf(100L, 200L).iterator()
        val codes = mutableListOf(403).iterator()
        val body = """{"k":"v"}""".toRequestBody("application/json".toMediaType())
        val result = runWithChain(
            nowSeconds = { ts.next() },
            codes = codes,
        ) { Request.Builder().url("https://example.test/app/login/").post(body).build() }

        assertEquals(1, result.proceedCount)
        assertEquals(403, result.finalCode)
    }

    @Test
    fun interceptor_doesNotRetryGetOn200() {
        val ts = mutableListOf(100L, 200L).iterator()
        val codes = mutableListOf(200).iterator()
        val result = runWithChain(
            nowSeconds = { ts.next() },
            codes = codes,
        ) { Request.Builder().url("https://example.test/app/races/").get().build() }

        assertEquals(1, result.proceedCount)
        assertEquals(200, result.finalCode)
    }

    private class ChainResult(
        val proceedCount: Int,
        val finalCode: Int,
        val signedRequests: List<Request>,
    )

    /** Runs the interceptor against a chain that returns [codes] in order, tracking proceed calls. */
    private fun runWithChain(
        nowSeconds: () -> Long,
        codes: Iterator<Int>,
        build: () -> Request,
    ): ChainResult {
        val interceptor = AppSignatureInterceptor(
            keyId = "kid",
            secret = "secret",
            installIdProvider = { "install" },
            appVersion = "1.0",
            nowSeconds = nowSeconds,
            tokenProvider = { null },
        )
        val signedRequests = mutableListOf<Request>()
        var proceedCount = 0
        val chain = object : Interceptor.Chain {
            private val req = build()
            override fun request(): Request = req
            override fun proceed(request: Request): Response {
                proceedCount++
                signedRequests += request
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(codes.next())
                    .message("msg")
                    .body("".toResponseBody(null))
                    .build()
            }
            override fun connection() = null
            override fun call() = throw UnsupportedOperationException()
            override fun connectTimeoutMillis() = 0
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 0
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 0
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }
        val response = interceptor.intercept(chain)
        return ChainResult(proceedCount, response.code, signedRequests)
    }

    /** Runs the interceptor against [build] with a fixed clock and captures the signed request. */
    private fun captureSignedRequest(token: String?, build: () -> Request): Request {
        val interceptor = AppSignatureInterceptor(
            keyId = "kid",
            secret = "secret",
            installIdProvider = { "install" },
            appVersion = "1.0",
            nowSeconds = { 100L },
            tokenProvider = { token },
        )
        lateinit var signed: Request
        val chain = object : Interceptor.Chain {
            private val req = build()
            override fun request(): Request = req
            override fun proceed(request: Request): Response {
                signed = request
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .build()
            }
            override fun connection() = null
            override fun call() = throw UnsupportedOperationException()
            override fun connectTimeoutMillis() = 0
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 0
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 0
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }
        interceptor.intercept(chain)
        return signed
    }
}
