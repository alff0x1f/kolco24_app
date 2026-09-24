package ru.kolco24.kolco24.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.constants.MapLibreConstants
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import ru.kolco24.kolco24.BuildConfig
import ru.kolco24.kolco24.data.map.Bounds
import ru.kolco24.kolco24.data.map.MbtilesMetadata
import ru.kolco24.kolco24.ui.theme.OrangeCta

/** Base layer of the map: the race's downloaded MBTiles file, or online OSM tiles. */
sealed interface MapStyleSource {
    /** Absolute path of a downloaded `<raceId>.mbtiles`. */
    data class Offline(val path: String) : MapStyleSource
    data object Online : MapStyleSource
}

private const val BASE_SOURCE = "base"
private const val TRACK_SOURCE = "track"
private const val TRACK_LAYER = "track-layer"
private const val PINS_SOURCE = "pins"
private const val PINS_LAYER = "pins-layer"
private const val OSM_TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
private const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"
private const val FIT_PADDING_DP = 48
private const val SINGLE_POINT_ZOOM = 15.0
private const val LAST_LOCATION_ZOOM = 14.0

/** Nothing to frame at all: Moscow region at a regional zoom. */
private val DEFAULT_CENTER = LatLng(55.75, 37.62)
private const val DEFAULT_ZOOM = 8.0

/**
 * One-time MapLibre bootstrap, done lazily before the first [MapView] (never on a cold start that
 * does not open the map tab). MapLibre's HTTP stack gets a client that **replaces** the User-Agent
 * with `Kolco24-Android/<versionName>` — the OSM tile usage policy requires an identifying UA.
 * Main-thread only.
 */
private object MapLibreInit {
    private var initialized = false

    fun ensure(context: Context) {
        if (initialized) return
        MapLibre.getInstance(context.applicationContext)
        val userAgent = "Kolco24-Android/${BuildConfig.VERSION_NAME}"
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
                }
                .build(),
        )
        initialized = true
    }
}

/** Style JSON with a single raster base layer (see «Итоги spike (Task 1)» in the map-tab plan). */
private fun styleJson(source: MapStyleSource): String = buildJsonObject {
    put("version", 8)
    putJsonObject("sources") {
        putJsonObject(BASE_SOURCE) {
            put("type", "raster")
            when (source) {
                is MapStyleSource.Offline -> put("url", "mbtiles://" + source.path)
                MapStyleSource.Online -> {
                    putJsonArray("tiles") { add(OSM_TILES) }
                    put("maxzoom", 19)
                    put("attribution", OSM_ATTRIBUTION)
                }
            }
            // MapLibre's raster default is 512 — both OSM and our MBTiles are 256 px tiles.
            put("tileSize", 256)
        }
    }
    putJsonArray("layers") {
        addJsonObject {
            put("id", BASE_SOURCE)
            put("type", "raster")
            put("source", BASE_SOURCE)
        }
    }
}.toString()

/**
 * MapLibre map with the team's track and taken-КП pins over [styleSource]. Must only be composed
 * while the map tab is the settled pager page — each composition owns a native `MapView`
 * (+ a GPS client via the location component when [locationPermitted]).
 *
 * Every style (re)load — first show and each `Offline ↔ Online` switch — re-adds the pin icons,
 * track/pin sources and layers, re-activates the location component and re-frames the camera.
 * Later [trackGeoJson]/[pinsGeoJson] changes only `setGeoJson` the existing sources.
 *
 * [onPinClick] gets the tapped pin's `checkpointId`, or `null` for a tap that missed every pin.
 */
@Composable
fun TrackMapView(
    styleSource: MapStyleSource,
    metadata: MbtilesMetadata?,
    trackGeoJson: String,
    pinsGeoJson: String,
    pinNumbers: Set<Int>,
    locationPermitted: Boolean,
    onPinClick: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapLibreInit.ensure(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    val styleGeneration = remember { intArrayOf(0) }

    val latestTrack by rememberUpdatedState(trackGeoJson)
    val latestPins by rememberUpdatedState(pinsGeoJson)
    val latestPinNumbers by rememberUpdatedState(pinNumbers)
    val latestLocationPermitted by rememberUpdatedState(locationPermitted)
    val latestOnPinClick by rememberUpdatedState(onPinClick)

    // Lifecycle: forward the host lifecycle to the MapView, tracking what was actually dispatched
    // so onDispose unwinds exactly those calls before onDestroy.
    DisposableEffect(mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        val memoryCallbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = mapView.onLowMemory()
            override fun onTrimMemory(level: Int) = Unit
        }
        val lifecycle = lifecycleOwner.lifecycle
        lifecycle.addObserver(observer)
        context.registerComponentCallbacks(memoryCallbacks)
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.addOnMapClickListener { latLng ->
                val style = m.style
                if (style == null || !style.isFullyLoaded) return@addOnMapClickListener false
                val point = m.projection.toScreenLocation(latLng)
                val id = m.queryRenderedFeatures(point, PINS_LAYER).firstNotNullOfOrNull { feature ->
                    runCatching { feature.getNumberProperty("id")?.toInt() }.getOrNull()
                }
                latestOnPinClick(id)
                id != null
            }
            map = m
        }
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(memoryCallbacks)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
            map = null
            loadedStyle = null
        }
    }

    // (Re)load the style when the base layer or its metadata changes, then rebuild everything on it.
    LaunchedEffect(map, styleSource, metadata) {
        val m = map ?: return@LaunchedEffect
        val generation = ++styleGeneration[0]
        loadedStyle = null
        m.setStyle(Style.Builder().fromJson(styleJson(styleSource))) { style ->
            // A newer setStyle superseded this one — its own callback does the work.
            if (generation != styleGeneration[0]) return@setStyle
            latestPinNumbers.forEach { addPinImage(context, style, it) }
            style.addSource(GeoJsonSource(TRACK_SOURCE, latestTrack))
            style.addLayer(
                LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
                    PropertyFactory.lineColor(OrangeCta.toArgb()),
                    PropertyFactory.lineWidth(3f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addSource(GeoJsonSource(PINS_SOURCE, latestPins))
            style.addLayer(
                SymbolLayer(PINS_LAYER, PINS_SOURCE).withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
            )
            applyLocation(context, m, style, latestLocationPermitted, newStyle = true)
            applyCamera(context, m, metadata, latestTrack, latestPins)
            loadedStyle = style
        }
    }

    LaunchedEffect(loadedStyle, trackGeoJson) {
        val style = loadedStyle?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.setGeoJson(trackGeoJson)
    }

    LaunchedEffect(loadedStyle, pinNumbers, pinsGeoJson) {
        val style = loadedStyle?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        pinNumbers.forEach { addPinImage(context, style, it) }
        style.getSourceAs<GeoJsonSource>(PINS_SOURCE)?.setGeoJson(pinsGeoJson)
    }

    LaunchedEffect(loadedStyle, locationPermitted) {
        val m = map ?: return@LaunchedEffect
        val style = loadedStyle?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        applyLocation(context, m, style, locationPermitted, newStyle = false)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun addPinImage(context: Context, style: Style, number: Int) {
    val name = pinIconName(number)
    if (style.getImage(name) == null) style.addImage(name, PinBitmaps.get(context, number))
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * «Я» dot: activated on [style] only with a location permission (a second GPS client next to
 * `TrackRecordingService`, alive only while the map is composed). No camera tracking. [newStyle]
 * forces re-activation — the component's layers belong to the style it was activated on.
 */
@SuppressLint("MissingPermission") // guarded by hasLocationPermission
private fun applyLocation(
    context: Context,
    map: MapLibreMap,
    style: Style,
    permitted: Boolean,
    newStyle: Boolean,
) {
    val component = map.locationComponent
    if (permitted && hasLocationPermission(context)) {
        runCatching {
            if (newStyle || !component.isLocationComponentActivated) {
                component.activateLocationComponent(
                    LocationComponentActivationOptions.builder(context, style)
                        .useDefaultLocationEngine(true)
                        .build(),
                )
            }
            component.isLocationComponentEnabled = true
            component.cameraMode = CameraMode.NONE
            component.renderMode = RenderMode.COMPASS
        }
    } else if (component.isLocationComponentActivated) {
        runCatching { component.isLocationComponentEnabled = false }
    }
}

/**
 * Initial camera for a freshly loaded style. Offline with MBTiles `bounds`: the camera target is
 * clamped to the bounds and the view frames them; zoom preferences follow `minzoom`/`maxzoom`.
 * Otherwise: fit track + pins (48 dp padding), else the last known location, else a default frame.
 */
private fun applyCamera(
    context: Context,
    map: MapLibreMap,
    metadata: MbtilesMetadata?,
    trackGeoJson: String,
    pinsGeoJson: String,
) {
    map.setMinZoomPreference(metadata?.minZoom?.toDouble() ?: MapLibreConstants.MINIMUM_ZOOM.toDouble())
    map.setMaxZoomPreference(metadata?.maxZoom?.toDouble() ?: MapLibreConstants.MAXIMUM_ZOOM.toDouble())

    val fileBounds = metadata?.bounds
    if (fileBounds != null) {
        val bounds = fileBounds.toLatLngBounds()
        map.setLatLngBoundsForCameraTarget(bounds)
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0))
        return
    }
    map.setLatLngBoundsForCameraTarget(null)

    val dataBounds = geoJsonBounds(trackGeoJson, pinsGeoJson)
    if (dataBounds != null) {
        if (dataBounds.west == dataBounds.east && dataBounds.south == dataBounds.north) {
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(dataBounds.north, dataBounds.east), SINGLE_POINT_ZOOM),
            )
        } else {
            val padding = (FIT_PADDING_DP * context.resources.displayMetrics.density).toInt()
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(dataBounds.toLatLngBounds(), padding))
        }
        return
    }

    val last = runCatching {
        map.locationComponent.takeIf { it.isLocationComponentActivated }?.lastKnownLocation
    }.getOrNull()
    if (last != null) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(last.latitude, last.longitude), LAST_LOCATION_ZOOM))
    } else {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, DEFAULT_ZOOM))
    }
}

private fun Bounds.toLatLngBounds(): LatLngBounds = LatLngBounds.from(north, east, south, west)
