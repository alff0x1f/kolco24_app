package ru.kolco24.kolco24.data.track

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * GMS-backed [CurrentLocationProvider] (real adapter, untested per repo convention — the **choice** is
 * tested via `LocationEngineFactoryTest`).
 *
 * Uses `getCurrentLocation(CurrentLocationRequest, token)` — **not** the `(priority, token)` overload,
 * which may hand back a cached fix and would undermine the anti-fraud guarantee. The request forces a
 * **fresh** fix: `setMaxUpdateAgeMillis(0)` forbids any cache, `setPriority(PRIORITY_HIGH_ACCURACY)`
 * drives the GPS chip, `setDurationMillis(timeoutMs)` is the GMS-side timeout. The `Task` is bridged to
 * a coroutine via `suspendCancellableCoroutine`; on cancellation the GMS request is cancelled too. Any
 * failure (timeout, `SecurityException`, no fix) resolves to `null` — never throws.
 */
class FusedCurrentLocationProvider(context: Context) : CurrentLocationProvider {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @SuppressLint("MissingPermission") // permission is best-effort here; a SecurityException just resolves to null.
    override suspend fun current(timeoutMs: Long): RawFix? {
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0) // force a fresh fix, never a cached one
            .setDurationMillis(timeoutMs)
            .build()
        val cts = CancellationTokenSource()
        return try {
            // withTimeoutOrNull is a coroutine-level safety net in case GMS never fires any callback
            // (documented edge case on some devices). setDurationMillis is the GMS-side timeout;
            // this guard fires slightly after to avoid racing the GMS callback.
            withTimeoutOrNull(timeoutMs + TIMEOUT_SLACK_MS) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { cts.cancel() }
                    client.getCurrentLocation(request, cts.token)
                        .addOnSuccessListener { location ->
                            cont.resume(location?.toRawFix())
                        }
                        .addOnFailureListener {
                            cont.resume(null)
                        }
                        .addOnCanceledListener {
                            cont.resume(null)
                        }
                }
            }
        } catch (e: SecurityException) {
            null
        }
    }

    private companion object {
        // Extra slack so the GMS-side setDurationMillis timeout fires first under normal conditions.
        const val TIMEOUT_SLACK_MS = 2_000L
    }
}
