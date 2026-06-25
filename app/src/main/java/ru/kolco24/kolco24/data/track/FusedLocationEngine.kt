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
 * Parameterized by a [TrackProfile] (the priority/interval/maxDelay source). Both profiles keep
 * `PRIORITY_HIGH_ACCURACY` (real GPS chip — `BALANCED` only gives WiFi/cell ~city-block accuracy,
 * useless for a race track); all the [TrackProfile.Economy] battery saving comes from the longer
 * interval (3 min duty-cycles the radio between fixes), not the priority. **No** min-displacement
 * filter (`setMinUpdateDistanceMeters` is deliberately omitted): the framework-level distance gate is
 * irreversibly lossy — suppressed fixes never reach storage — and it saves no power (battery is driven
 * by interval+priority, the radio runs regardless). Keeping every delivered fix raw lets a far smarter
 * post-hoc filter run on the stored track (kinematic speed-gate using the on-foot model + accuracy +
 * dense neighbours), which a blind 10 m hardware gate would only hinder. The [TrackProfile.maxDelayMs]
 * `maxUpdateDelay` only defers *delivery* (Precise batches ~once a minute, ~4 fixes/batch) — it does
 * **not** gate the GPS radio (at a 15 s HIGH-accuracy interval the radio is effectively continuous, so
 * battery is driven by the interval, not the batch delay; the delay only trades CPU/app wakeups).
 * Precise keeps it short (60 s, not the prior 300 s) so the take's points persist to Room ~once a
 * minute and the **data-loss window on a hard kill / dead battery is ≤1 min** rather than ≤5 —
 * important for the anti-fraud proof-of-path; it also keeps the live `TrackCard` counter fresh. The
 * whole batch is forwarded so each point keeps its own `elapsedRealtimeNanos` (real capture moment),
 * not the delivery time. `requestLocationUpdates` is wrapped so a permission-revoke race
 * (`SecurityException`) or a GMS task failure routes to `onError` instead of crashing.
 */
class FusedLocationEngine(context: Context, private val profile: TrackProfile) : LocationEngine {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission") // permission is a hard precondition guaranteed by the launcher/service precheck.
    override fun start(onPoints: (List<RawFix>) -> Unit, onError: (Throwable) -> Unit) {
        val priority =
            if (profile.highAccuracy) Priority.PRIORITY_HIGH_ACCURACY
            else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, profile.intervalMs)
            .setMinUpdateIntervalMillis(profile.intervalMs)
            .setMaxUpdateDelayMillis(profile.maxDelayMs)
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

    override fun flush(onComplete: () -> Unit) {
        // The GMS Task completes after the buffered locations are delivered to onLocationResult, so the
        // points are enqueued for insert (applicationScope.launch) by the time onComplete runs.
        client.flushLocations().addOnCompleteListener { onComplete() }
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
