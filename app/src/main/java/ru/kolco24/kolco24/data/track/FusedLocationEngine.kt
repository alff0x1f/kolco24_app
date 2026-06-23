package ru.kolco24.kolco24.data.track

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * GMS-backed [LocationEngine] (real adapter, untested per repo convention — the engine **choice** is
 * tested via `LocationEngineFactoryTest`).
 *
 * Battery is the priority: `PRIORITY_BALANCED_POWER_ACCURACY` with a 60 s interval and a 300 s
 * `maxUpdateDelay` lets the GPS radio sleep and deliver fixes in a batch ~every 5 min. The whole batch
 * is forwarded so each point keeps its own `elapsedRealtimeNanos` (real capture moment), not the
 * delivery time. `requestLocationUpdates` is wrapped so a permission-revoke race (`SecurityException`)
 * or a GMS task failure routes to `onError` instead of crashing.
 */
class FusedLocationEngine(context: Context) : LocationEngine {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission") // permission is a hard precondition guaranteed by the launcher/service precheck.
    override fun start(onPoints: (List<RawFix>) -> Unit, onError: (Throwable) -> Unit) {
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L)
            .setMinUpdateIntervalMillis(60_000L)
            .setMaxUpdateDelayMillis(300_000L)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val fixes = result.locations.map { it.toRawFix() }
                if (fixes.isNotEmpty()) onPoints(fixes)
            }
        }
        callback = cb
        try {
            client.requestLocationUpdates(request, cb, Looper.getMainLooper())
                .addOnFailureListener { e -> onError(e) }
        } catch (e: SecurityException) {
            onError(e)
        } catch (e: RuntimeException) {
            onError(e)
        }
    }

    override fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }
}

/** Map a platform [Location] to a pure [RawFix] (`time` → `gpsTimeMs`, monotonic nanos preserved). */
internal fun Location.toRawFix(): RawFix = RawFix(
    lat = latitude,
    lon = longitude,
    accuracy = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    gpsTimeMs = time,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
)
