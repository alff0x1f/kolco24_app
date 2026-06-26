package ru.kolco24.kolco24.data.track

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Non-GMS [CurrentLocationProvider] fallback (real adapter, untested per repo convention — the
 * **choice** is tested via `LocationEngineFactoryTest`).
 *
 * On API ≥ 30 it uses `LocationManager.getCurrentLocation` (the framework one-shot); on API 24–29 it
 * registers a single-shot `requestLocationUpdates` listener and removes it after the first fix. Tries
 * `GPS_PROVIDER` first, falling back to `NETWORK_PROVIDER` when GPS is unavailable or times out
 * (e.g. indoors). Wrapped in a [withTimeoutOrNull] so a never-firing provider resolves to `null`.
 *
 * **Freshness guard:** unlike Fused, the legacy one-shot APIs can hand back a recent OS cache, so any
 * fix older than [MAX_FIX_AGE_MS] (by `elapsedRealtimeNanos`) is dropped → `null`. For anti-fraud a
 * missing coordinate beats a stale one. Any platform error resolves to `null` — never throws.
 */
class LegacyCurrentLocationProvider(context: Context) : CurrentLocationProvider {

    private val locationManager: LocationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override suspend fun current(timeoutMs: Long): RawFix? {
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!gpsEnabled && !networkEnabled) return null

        // Try GPS first (if available). On timeout or failure fall back to NETWORK — a cell/Wi-Fi
        // fix is coarser but still valid anti-cheat evidence when GPS can't acquire indoors.
        // When network is available as a fallback, reserve NETWORK_FALLBACK_TIMEOUT_MS so the
        // total never exceeds timeoutMs (GPS timeout + network timeout ≤ timeoutMs).
        if (gpsEnabled) {
            val gpsTimeout = if (networkEnabled) {
                (timeoutMs - NETWORK_FALLBACK_TIMEOUT_MS).coerceAtLeast(1_000L)
            } else {
                timeoutMs
            }
            val location = withTimeoutOrNull(gpsTimeout) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestModern(LocationManager.GPS_PROVIDER)
                } else {
                    requestLegacy(LocationManager.GPS_PROVIDER)
                }
            }
            val fresh = location?.takeIf { it.isFresh() }?.toRawFix()
            if (fresh != null) return fresh
            if (!networkEnabled) return null
        }

        // Network fallback: cell/Wi-Fi fixes typically arrive within 1–2 s when GPS was tried
        // first. When GPS is unavailable entirely, use the full caller budget so a slow NETWORK
        // provider still gets a fair chance.
        val networkTimeout = if (gpsEnabled) NETWORK_FALLBACK_TIMEOUT_MS else timeoutMs
        val location = withTimeoutOrNull(networkTimeout) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requestModern(LocationManager.NETWORK_PROVIDER)
            } else {
                requestLegacy(LocationManager.NETWORK_PROVIDER)
            }
        } ?: return null
        return location.takeIf { it.isFresh() }?.toRawFix()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission") // permission is best-effort here; a SecurityException just resolves to null.
    private suspend fun requestModern(provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            try {
                locationManager.getCurrentLocation(
                    provider,
                    signal,
                    { it.run() }, // direct executor; the consumer just resumes
                ) { location -> cont.resume(location) }
            } catch (e: SecurityException) {
                cont.resume(null)
            } catch (e: IllegalArgumentException) {
                cont.resume(null)
            } catch (e: RuntimeException) {
                cont.resume(null)
            }
        }

    @SuppressLint("MissingPermission") // permission is best-effort here; a SecurityException just resolves to null.
    private suspend fun requestLegacy(provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val resumed = AtomicBoolean(false)
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (resumed.compareAndSet(false, true)) {
                        locationManager.removeUpdates(this)
                        cont.resume(location)
                    }
                }

                override fun onProviderEnabled(p: String) {}

                override fun onProviderDisabled(p: String) {
                    if (resumed.compareAndSet(false, true)) {
                        locationManager.removeUpdates(this)
                        cont.resume(null)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(p: String?, status: Int, extras: android.os.Bundle?) {}
            }
            cont.invokeOnCancellation { locationManager.removeUpdates(listener) }
            try {
                locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                if (resumed.compareAndSet(false, true)) cont.resume(null)
            } catch (e: IllegalArgumentException) {
                if (resumed.compareAndSet(false, true)) cont.resume(null)
            } catch (e: RuntimeException) {
                if (resumed.compareAndSet(false, true)) cont.resume(null)
            }
        }

    /** Fresh iff the fix's monotonic age is within [MAX_FIX_AGE_MS] and non-negative. */
    private fun Location.isFresh(): Boolean {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000
        return ageMs in 0..MAX_FIX_AGE_MS
    }

    private companion object {
        // Network fixes typically arrive in < 2 s; a short window avoids excessive extra wait after
        // GPS timeout (which already consumed timeoutMs).
        const val NETWORK_FALLBACK_TIMEOUT_MS = 3_000L
    }
}
