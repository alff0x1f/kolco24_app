package ru.kolco24.kolco24.data.track

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/**
 * Non-GMS [LocationEngine] fallback (real adapter, untested per repo convention — the engine
 * **choice** is tested via `LocationEngineFactoryTest`).
 *
 * Uses `GPS_PROVIDER` at a 60 s interval, falling back to `NETWORK_PROVIDER` when GPS is unavailable;
 * if neither provider exists it routes an error to `onError` instead of silently recording nothing.
 * Each fix is delivered as a singleton list (no batching API here).
 *
 * Note: unlike Fused there is no `maxUpdateDelay` equivalent, so under Doze on non-GMS devices the
 * updates may be throttled (device-dependent) — this is the reason Fused is preferred when GMS is
 * available; covered by the device test in Post-Completion.
 */
class LegacyLocationEngine(context: Context) : LocationEngine {

    private val locationManager: LocationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission") // permission is a hard precondition guaranteed by the launcher/service precheck.
    override fun start(onPoints: (List<RawFix>) -> Unit, onError: (Throwable) -> Unit) {
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            onError(IllegalStateException("no usable location provider"))
            return
        }
        val l = LocationListener { location: Location -> onPoints(listOf(location.toRawFix())) }
        listener = l
        try {
            locationManager.requestLocationUpdates(provider, 60_000L, 0f, l, Looper.getMainLooper())
        } catch (e: SecurityException) {
            onError(e)
        } catch (e: IllegalArgumentException) {
            onError(e)
        }
    }

    override fun stop() {
        listener?.let { locationManager.removeUpdates(it) }
        listener = null
    }
}
