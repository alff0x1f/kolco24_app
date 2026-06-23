package ru.kolco24.kolco24.data.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Re-anchors [ru.kolco24.kolco24.data.time.TrustedClock] from the HTTP `Date` response header on
 * every **network** response — a free, backend-free source of server time (no protocol changes).
 *
 * It snapshots the monotonic [elapsed] reading on both sides of `proceed()` and pins the parsed
 * server time to an **RTT-corrected, overflow-safe midpoint** (`elapsedBefore + rtt / 2`, not
 * `(before + after) / 2` which could overflow `Long`), subtracting roughly half the round-trip.
 *
 * Re-anchoring is gated so only a genuinely live `Date` is trusted:
 * - **network only** (`response.networkResponse != null`) — a cache hit carries a stale `Date`;
 * - **RTT in `0..maxRttMs`** — a negative RTT is a clock/timing anomaly, and an over-long RTT makes
 *   the midpoint correction too coarse, so both are dropped;
 * - **`Date` present and parseable** — `getDate` returns `null` for a missing/malformed header.
 *
 * Trust in the source is structural: this interceptor is installed only on the single-host
 * `/app/` [okhttp3.OkHttpClient] (`BuildConfig.API_BASE_URL` over HTTPS), so no `request.url.host`
 * check is done. **If a second host is ever added to this client, add a host gate here** before
 * re-anchoring (the only thing standing between a foreign `Date` and the trusted clock).
 *
 * @param onServerTime invoked on accept with `(serverMs, anchorElapsed, wallNow, bootNow)`; the
 *   ordering/out-of-order handling lives in [ru.kolco24.kolco24.data.time.TrustedClock.onServerTime].
 * @param elapsed raw `SystemClock.elapsedRealtime()`.
 * @param wall `System.currentTimeMillis()` (forensics only — captured at re-anchor).
 * @param bootCount cached `Settings.Global.BOOT_COUNT` (boot-session identity).
 * @param maxRttMs upper bound on an acceptable round-trip (default 10 s).
 */
class ServerTimeInterceptor(
    private val onServerTime: (serverMs: Long, anchorElapsed: Long, wallNow: Long, bootNow: Int?) -> Unit,
    private val elapsed: () -> Long,
    private val wall: () -> Long,
    private val bootCount: () -> Int?,
    private val maxRttMs: Long = 10_000,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val elapsedBefore = elapsed()
        val response = chain.proceed(chain.request())
        val elapsedAfter = elapsed()
        val rtt = elapsedAfter - elapsedBefore

        // Network only (a cache hit's Date is stale) and RTT in range (negative = anomaly).
        if (response.networkResponse != null && rtt in 0..maxRttMs) {
            val anchorElapsed = elapsedBefore + rtt / 2 // overflow-safe midpoint
            response.headers.getDate("Date")?.time?.let { serverMs ->
                onServerTime(serverMs, anchorElapsed, wall(), bootCount())
            }
        }
        return response
    }
}
