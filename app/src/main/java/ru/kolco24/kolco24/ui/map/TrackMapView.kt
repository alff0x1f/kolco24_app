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
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import ru.kolco24.kolco24.data.track.TrackPointLike
import ru.kolco24.kolco24.ui.theme.OrangeCta

private const val TRACK_SOURCE = "track"
private const val TRACK_LAYER = "track-layer"
private const val PINS_SOURCE = "pins"
private const val PINS_LAYER = "pins-layer"
private const val FIT_PADDING_DP = 48
private const val LAST_LOCATION_ZOOM = 14.0
private const val SINGLE_POINT_ZOOM = 15.0
private const val TRACK_LINE_WIDTH_DP = 3f

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

/**
 * MapLibre map with the team's [track] and taken-КП [pins] over [styleSource]. Must only be composed
 * while the map tab is the settled pager page — each composition owns a native `MapView`
 * (+ a GPS client via the location component when [locationPermitted]).
 *
 * Every style (re)load — first show and each `Offline ↔ Online` switch — re-adds the pin icons,
 * track/pin sources and layers and re-activates the location component. Later data changes only
 * `setGeoJson` the existing sources; the track GeoJSON is built off the main thread (it grows every
 * GPS fix). The camera is (re)framed ([cameraFrame]) on every style load, when [frameKey] (the
 * selected team) changes, and — without file bounds — when the data goes from empty to non-empty.
 *
 * [onPinClick] gets the tapped pin's `checkpointId`, or `null` for a tap that missed every pin.
 */
@Composable
fun TrackMapView(
    styleSource: MapStyleSource,
    track: List<TrackPointLike>,
    pins: List<MapPin>,
    frameKey: Any?,
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

    val trackJson by produceState(trackGeoJson(emptyList()), track) {
        value = withContext(Dispatchers.Default) { trackGeoJson(track) }
    }
    val pinsJson = remember(pins) { pinsGeoJson(pins) }
    val pinNumbers = remember(pins) { pins.mapTo(HashSet()) { it.number } }

    val latestTrack by rememberUpdatedState(track)
    val latestPins by rememberUpdatedState(pins)
    val latestTrackGeoJson by rememberUpdatedState(trackJson)
    val latestPinsGeoJson by rememberUpdatedState(pinsJson)
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
        // Kept deliberately: MapLibre asks hosts to forward onLowMemory (it drops its tile caches).
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

    // (Re)load the style when the base layer (or its metadata) changes, then rebuild everything on it.
    // MapLibre keeps a single pending style callback — a superseded setStyle's callback never fires.
    LaunchedEffect(map, styleSource) {
        val m = map ?: return@LaunchedEffect
        loadedStyle = null
        m.setStyle(Style.Builder().fromJson(styleJson(styleSource))) { style ->
            latestPinNumbers.forEach { addPinImage(context, style, it) }
            style.addSource(GeoJsonSource(TRACK_SOURCE, latestTrackGeoJson))
            style.addLayer(
                LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
                    PropertyFactory.lineColor(OrangeCta.toArgb()),
                    PropertyFactory.lineWidth(TRACK_LINE_WIDTH_DP),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addSource(GeoJsonSource(PINS_SOURCE, latestPinsGeoJson))
            style.addLayer(
                SymbolLayer(PINS_LAYER, PINS_SOURCE).withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
            )
            applyLocation(context, m, style, latestLocationPermitted, newStyle = true)
            loadedStyle = style
        }
    }

    // `loadedStyle` is only set from the style-loaded callback (and cleared before every setStyle /
    // on dispose), so a non-null value is always a fully loaded style.
    LaunchedEffect(loadedStyle, trackJson) {
        val style = loadedStyle ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.setGeoJson(trackJson)
    }

    LaunchedEffect(loadedStyle, pinNumbers, pinsJson) {
        val style = loadedStyle ?: return@LaunchedEffect
        pinNumbers.forEach { addPinImage(context, style, it) }
        style.getSourceAs<GeoJsonSource>(PINS_SOURCE)?.setGeoJson(pinsJson)
    }

    LaunchedEffect(loadedStyle, locationPermitted) {
        val m = map ?: return@LaunchedEffect
        val style = loadedStyle ?: return@LaunchedEffect
        applyLocation(context, m, style, locationPermitted, newStyle = false)
    }

    // Camera: on every style load, on a team switch, and (without file bounds) when the first track
    // point / pin arrives after a no-data frame. With file bounds the data never moves the camera.
    val metadata = (styleSource as? MapStyleSource.Offline)?.metadata
    val hasData = track.isNotEmpty() || pins.isNotEmpty()
    val dataKey = if (metadata?.bounds == null) hasData else null
    LaunchedEffect(loadedStyle, frameKey, dataKey) {
        val m = map ?: return@LaunchedEffect
        if (loadedStyle == null) return@LaunchedEffect
        applyCamera(context, m, metadata, dataBounds(latestTrack, latestPins))
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun addPinImage(context: Context, style: Style, number: Int) {
    val name = pinIconName(number)
    if (style.getImage(name) == null) style.addImage(name, pinBitmap(context, number))
}

// Deliberately re-checks what the host's `locationPermitted` already says: it is the guard lint's
// MissingPermission suppression on applyLocation relies on, right at the activation call site.
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
 * Frames the camera per [cameraFrame]. Zoom preferences follow MBTiles `minzoom`/`maxzoom` (else
 * MapLibre's limits); file bounds also clamp the camera target (0 padding), a data fit uses 48 dp.
 */
private fun applyCamera(
    context: Context,
    map: MapLibreMap,
    metadata: MbtilesMetadata?,
    dataBounds: Bounds?,
) {
    // Native setMinZoom/setMaxZoom silently ignore a value past the *current* opposite limit (10–13 →
    // 15–18 would keep min 10), and the effective min zoom is constrained by the target bounds — so
    // clear the bounds and widen to MapLibre's full range first, then apply the new pair min → max.
    map.setLatLngBoundsForCameraTarget(null)
    map.setMinZoomPreference(MapLibreConstants.MINIMUM_ZOOM.toDouble())
    map.setMaxZoomPreference(MapLibreConstants.MAXIMUM_ZOOM.toDouble())
    metadata?.minZoom?.let { map.setMinZoomPreference(it.toDouble()) }
    metadata?.maxZoom?.let { map.setMaxZoomPreference(it.toDouble()) }

    val frame = cameraFrame(metadata?.bounds, dataBounds)
    map.setLatLngBoundsForCameraTarget((frame as? CameraFrame.FileBounds)?.bounds?.toLatLngBounds())
    when (frame) {
        is CameraFrame.FileBounds ->
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(frame.bounds.toLatLngBounds(), 0))
        is CameraFrame.SinglePoint ->
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(frame.lat, frame.lon), SINGLE_POINT_ZOOM))
        is CameraFrame.FitData -> {
            val padding = (FIT_PADDING_DP * context.resources.displayMetrics.density).toInt()
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(frame.bounds.toLatLngBounds(), padding))
        }
        CameraFrame.NoData -> {
            val last = runCatching {
                map.locationComponent.takeIf { it.isLocationComponentActivated }?.lastKnownLocation
            }.getOrNull()
            if (last != null) {
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(last.latitude, last.longitude), LAST_LOCATION_ZOOM),
                )
            } else {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, DEFAULT_ZOOM))
            }
        }
    }
}

private fun Bounds.toLatLngBounds(): LatLngBounds = LatLngBounds.from(north, east, south, west)
