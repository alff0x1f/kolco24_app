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
 * Field-test profile: `PRIORITY_HIGH_ACCURACY` (real GPS chip — `BALANCED` only gives WiFi/cell
 * ~city-block accuracy, useless for a race track) with a 15 s interval. **No** min-displacement filter
 * (`setMinUpdateDistanceMeters` is deliberately omitted): the framework-level distance gate is
 * irreversibly lossy — suppressed fixes never reach storage — and it saves no power (battery is driven
 * by interval+priority, the radio runs regardless). Keeping every delivered fix raw lets a far smarter
 * post-hoc filter run on the stored track (kinematic speed-gate using the on-foot model + accuracy +
 * dense neighbours), which a blind 10 m hardware gate would only hinder. The 300 s `maxUpdateDelay`
 * only defers *delivery* (batched ~every 5 min, saving CPU/app wakeups) — at a 15 s HIGH-accuracy
 * interval the GPS radio is effectively continuous, so battery cost is driven by the interval, not the
 * batch delay. The whole batch is forwarded so each point keeps its own `elapsedRealtimeNanos` (real
 * capture moment), not the delivery time. `requestLocationUpdates` is wrapped so a permission-revoke
 * race (`SecurityException`) or a GMS task failure routes to `onError` instead of crashing.
 */
class FusedLocationEngine(context: Context) : LocationEngine {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission") // permission is a hard precondition guaranteed by the launcher/service precheck.
    override fun start(onPoints: (List<RawFix>) -> Unit, onError: (Throwable) -> Unit) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L)
            .setMinUpdateIntervalMillis(15_000L)
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
