package ru.kolco24.kolco24.data.track

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Picks the concrete [LocationEngine] for a device. The decision is split into a pure
 * [chooseEngineType] (JVM-unit-tested) and a thin Android adapter [create] (untested per repo
 * convention — it only reads GMS availability and constructs the chosen engine).
 */
object LocationEngineFactory {

    /** Pure engine selection: GMS present → Fused (battery-friendly batching), else Legacy. */
    fun chooseEngineType(gmsAvailable: Boolean): EngineType =
        if (gmsAvailable) EngineType.Fused else EngineType.Legacy

    /** Build the engine for [context], choosing by `GoogleApiAvailability` (== `SUCCESS`). */
    fun create(context: Context): LocationEngine {
        val gmsAvailable = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        return when (chooseEngineType(gmsAvailable)) {
            EngineType.Fused -> FusedLocationEngine(context)
            EngineType.Legacy -> LegacyLocationEngine(context)
        }
    }
}
