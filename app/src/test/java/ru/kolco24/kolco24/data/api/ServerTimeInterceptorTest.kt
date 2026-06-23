package ru.kolco24.kolco24.data.api

import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerTimeInterceptorTest {

    /** One captured `onServerTime(...)` invocation. */
    private data class Anchored(
        val serverMs: Long,
        val anchorElapsed: Long,
        val wallNow: Long,
        val bootNow: Int?,
    )

    // "Thu, 01 Jan 1970 00:00:10 GMT" parses to exactly 10_000 ms — unambiguous, tz-free.
    private val dateHeader = "Thu, 01 Jan 1970 00:00:10 GMT"
    private val dateEpochMs = 10_000L

    @Test
    fun networkDate_invokesOnServerTimeWithEpochAndMidpoint() {
        val captured = run(
            elapsedReadings = listOf(1_000L, 1_400L), // rtt = 400
            headers = headersOf("Date", dateHeader),
            network = true,
            wall = 999_000L,
            boot = 7,
        )

        assertEquals(1, captured.size)
        val a = captured.single()
        assertEquals(dateEpochMs, a.serverMs)
        assertEquals(1_200L, a.anchorElapsed) // 1000 + 400/2
        assertEquals(999_000L, a.wallNow)
        assertEquals(7, a.bootNow)
    }

    @Test
    fun cachedResponse_doesNotInvoke() {
        val captured = run(
            elapsedReadings = listOf(1_000L, 1_400L),
            headers = headersOf("Date", dateHeader),
            network = false, // cache hit: networkResponse == null
        )
        assertTrue(captured.isEmpty())
    }

    @Test
    fun missingDateHeader_doesNotInvoke() {
        val captured = run(
            elapsedReadings = listOf(1_000L, 1_400L),
            headers = headersOf("Content-Type", "application/json"),
            network = true,
        )
        assertTrue(captured.isEmpty())
    }

    @Test
    fun malformedDateHeader_doesNotInvoke() {
        val captured = run(
            elapsedReadings = listOf(1_000L, 1_400L),
            headers = headersOf("Date", "not-a-date"),
            network = true,
        )
        assertTrue(captured.isEmpty())
    }

    @Test
    fun rttAtMax_accepts() {
        val captured = run(
            elapsedReadings = listOf(0L, 10_000L), // rtt = 10_000 == maxRttMs
            headers = headersOf("Date", dateHeader),
            network = true,
        )
        assertEquals(1, captured.size)
        assertEquals(5_000L, captured.single().anchorElapsed) // 0 + 10_000/2
    }

    @Test
    fun rttOverMax_rejects() {
        val captured = run(
            elapsedReadings = listOf(0L, 10_001L), // rtt = 10_001 > maxRttMs
            headers = headersOf("Date", dateHeader),
            network = true,
        )
        assertTrue(captured.isEmpty())
    }

    @Test
    fun negativeRtt_rejects() {
        val captured = run(
            elapsedReadings = listOf(5_000L, 4_900L), // rtt = -100 (anomaly)
            headers = headersOf("Date", dateHeader),
            network = true,
        )
        assertTrue(captured.isEmpty())
    }

    @Test
    fun outOfOrderResponses_passEachOwnMidpoint() {
        // Two requests through the same interceptor; the second snapshots smaller elapsed values
        // (out-of-order arrival). Ordering is TrustedClock's job — here we only assert each call
        // forwards its own midpoint/epoch correctly.
        val captured = mutableListOf<Anchored>()
        val interceptor = interceptor(captured, listOf(2_000L, 2_400L, 1_000L, 1_200L))
        interceptor.intercept(chain(headersOf("Date", dateHeader), network = true))
        interceptor.intercept(chain(headersOf("Date", dateHeader), network = true))

        assertEquals(2, captured.size)
        assertEquals(2_200L, captured[0].anchorElapsed) // 2000 + 400/2
        assertEquals(1_100L, captured[1].anchorElapsed) // 1000 + 200/2
        assertEquals(dateEpochMs, captured[0].serverMs)
        assertEquals(dateEpochMs, captured[1].serverMs)
    }

    @Test
    fun nullBootCount_isForwarded() {
        val captured = run(
            elapsedReadings = listOf(1_000L, 1_200L),
            headers = headersOf("Date", dateHeader),
            network = true,
            boot = null,
        )
        assertEquals(1, captured.size)
        assertNull(captured.single().bootNow)
    }

    // ---- helpers ----

    private fun run(
        elapsedReadings: List<Long>,
        headers: Headers,
        network: Boolean,
        wall: Long = 0L,
        boot: Int? = 0,
    ): List<Anchored> {
        val captured = mutableListOf<Anchored>()
        val interceptor = interceptor(captured, elapsedReadings, wall, boot)
        interceptor.intercept(chain(headers, network))
        return captured
    }

    private fun interceptor(
        captured: MutableList<Anchored>,
        elapsedReadings: List<Long>,
        wall: Long = 0L,
        boot: Int? = 0,
    ): ServerTimeInterceptor {
        val readings = ArrayDeque(elapsedReadings)
        return ServerTimeInterceptor(
            onServerTime = { s, e, w, b -> captured.add(Anchored(s, e, w, b)) },
            elapsed = { readings.removeFirst() },
            wall = { wall },
            bootCount = { boot },
        )
    }

    /** A fake chain whose `proceed` returns a response with [headers], optionally network-backed. */
    private fun chain(headers: Headers, network: Boolean): Interceptor.Chain =
        object : Interceptor.Chain {
            private val req = Request.Builder().url("https://example.test/app/races/").get().build()
            override fun request(): Request = req
            override fun proceed(request: Request): Response {
                val builder = Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .headers(headers)
                if (network) {
                    builder.networkResponse(
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .headers(headers)
                            .build(),
                    )
                }
                return builder.build()
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
}
