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
import org.maplibre.android.camera.CameraUpdate
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
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import ru.kolco24.kolco24.BuildConfig
import ru.kolco24.kolco24.data.map.Bounds
import ru.kolco24.kolco24.data.map.MbtilesMetadata
import ru.kolco24.kolco24.data.track.SpeedBand
import ru.kolco24.kolco24.data.track.SpeedStroke
import ru.kolco24.kolco24.data.track.SpeedTrack
import ru.kolco24.kolco24.data.track.TrackStop
import ru.kolco24.kolco24.data.track.TrackPointLike
import ru.kolco24.kolco24.data.track.formatStopDuration
import ru.kolco24.kolco24.ui.theme.OrangeCta
import ru.kolco24.kolco24.ui.theme.SpeedGap
import ru.kolco24.kolco24.ui.theme.speedBandColor

private const val TRACK_SOURCE = "track"
private const val TRACK_LAYER = "track-layer"
private const val TRACK_DOT_LAYER = "track-dot-layer"
private const val SPEED_SOURCE = "speed"
private const val SPEED_CASING_LAYER = "speed-casing-layer"
private const val SPEED_GAP_LAYER = "speed-gap-layer"
private const val SPEED_LAYER = "speed-layer"
private const val STOPS_SOURCE = "stops"
private const val STOPS_LAYER = "stops-layer"
private const val PINS_SOURCE = "pins"
private const val PINS_LAYER = "pins-layer"
private const val FIT_PADDING_DP = 48
private const val LAST_LOCATION_ZOOM = 14.0
private const val SINGLE_POINT_ZOOM = 15.0
private const val TRACK_LINE_WIDTH_DP = 3f
/** A lone kept fix (1-point line) — a touch bigger than the line's half-width so it stays visible. */
private const val TRACK_DOT_RADIUS_DP = 3f
/** Dark casing under the speed colors so the light bands stay visible on any basemap. */
private const val SPEED_CASING_WIDTH_DP = 5f
private const val SPEED_CASING_ALPHA = 0.55f
/** Gap dash in line widths (≈ iOS `[4, 6]` pt at width 3). */
private val SPEED_GAP_DASH = arrayOf(1.4f, 2f)

/** Nothing to frame at all: Ufa region at a regional zoom. */
private val DEFAULT_CENTER = LatLng(54.74, 55.96)
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
 * MapLibre map with the team's [trackLines] (one drawn part per line; a 1-point line is drawn as a dot
 * by a circle layer on the same source) and taken-КП [pins] over [styleSource]. With [speedTrack]
 * (speed coloring on) the lines are drawn from its runs instead — a dark casing under the band runs
 * (not under gaps, or the dash would read as a solid line), dashed grey gaps, one color per band —
 * and its stops get «12 мин» label icons (tap → [onStopClick] with the stop's `startMs`); 1-point
 * lines stay orange dots. Must only be composed
 * while the map tab is the settled pager page — each composition owns a native `MapView`
 * (+ a GPS client via the location component when [locationPermitted]).
 *
 * Every style (re)load — first show and each `Offline ↔ Online` switch — re-adds the pin icons,
 * track/pin sources and layers and re-activates the location component. Later data changes only
 * `setGeoJson` the existing sources; the track GeoJSON is built off the main thread (it grows every
 * GPS fix). The camera is (re)framed ([cameraFrame]) on every style load, when [frameKey] (the
 * selected team) changes, and — without file bounds — when the data goes from empty to non-empty.
 *
 * [onPinClick] gets the tapped pin's `checkpointId`, or `null` for a tap that missed every pin and stop.
 *
 * [cameraCommand] runs once the style is loaded, then [onCameraCommandDone] reports whether it could
 * move (`false`: no file bounds, or no location fix yet); the host clears the command there.
 */
@Composable
fun TrackMapView(
    styleSource: MapStyleSource,
    trackLines: List<List<TrackPointLike>>,
    speedTrack: SpeedTrack?,
    pins: List<MapPin>,
    frameKey: Any?,
    locationPermitted: Boolean,
    onPinClick: (Int?) -> Unit,
    onStopClick: (Long) -> Unit,
    cameraCommand: MapCameraCommand?,
    onCameraCommandDone: (Boolean) -> Unit,
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

    // Track, speed runs and stops are built together from one (trackLines, speedTrack) pair and applied
    // together, so a mode switch never shows the plain line and the colored runs out of step. In speed
    // mode the track source keeps only the 1-point dots; the lines come from the speed source.
    val sources by produceState(TrackSources.Empty, trackLines, speedTrack) {
        value = withContext(Dispatchers.Default) {
            TrackSources(
                trackJson = trackGeoJson(if (speedTrack != null) trackLines.filter { it.size == 1 } else trackLines),
                speedJson = speedRunsGeoJson(speedTrack?.runs.orEmpty()),
                stops = speedTrack?.stops.orEmpty(),
            )
        }
    }
    val stops = sources.stops
    val stopsJson = remember(stops) { stopsGeoJson(stops) }
    val stopLabels = remember(stops) { stops.mapTo(HashSet()) { formatStopDuration(it.endMs - it.startMs) } }
    val pinsJson = remember(pins) { pinsGeoJson(pins) }
    val pinNumbers = remember(pins) { pins.mapTo(HashSet()) { it.number } }

    val latestTrackLines by rememberUpdatedState(trackLines)
    val latestPins by rememberUpdatedState(pins)
    val latestSources by rememberUpdatedState(sources)
    val latestStopsGeoJson by rememberUpdatedState(stopsJson)
    val latestStopLabels by rememberUpdatedState(stopLabels)
    val latestPinsGeoJson by rememberUpdatedState(pinsJson)
    val latestPinNumbers by rememberUpdatedState(pinNumbers)
    val latestLocationPermitted by rememberUpdatedState(locationPermitted)
    val latestOnPinClick by rememberUpdatedState(onPinClick)
    val latestOnStopClick by rememberUpdatedState(onStopClick)

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
                val stopStart = if (id != null) {
                    null
                } else {
                    m.queryRenderedFeatures(point, STOPS_LAYER).firstNotNullOfOrNull { feature ->
                        runCatching { feature.getNumberProperty("start")?.toLong() }.getOrNull()
                    }
                }
                if (stopStart != null) latestOnStopClick(stopStart) else latestOnPinClick(id)
                id != null || stopStart != null
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
            latestStopLabels.forEach { addStopImage(context, style, it) }
            style.addSource(GeoJsonSource(TRACK_SOURCE, latestSources.trackJson))
            style.addSource(GeoJsonSource(SPEED_SOURCE, latestSources.speedJson))
            style.addLayer(
                LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
                    PropertyFactory.lineColor(OrangeCta.toArgb()),
                    PropertyFactory.lineWidth(TRACK_LINE_WIDTH_DP),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            addSpeedLayers(style)
            // 1-point lines (trackGeoJson's dot feature): same colour as the line. Filtered to the dot
            // feature — a circle layer would otherwise also draw a circle at every line vertex.
            style.addLayer(
                CircleLayer(TRACK_DOT_LAYER, TRACK_SOURCE)
                    .withFilter(Expression.has(TRACK_DOT_PROPERTY))
                    .withProperties(
                        PropertyFactory.circleColor(OrangeCta.toArgb()),
                        PropertyFactory.circleRadius(TRACK_DOT_RADIUS_DP),
                    ),
            )
            // Stops sit below the КП pins (a stop is often at a КП).
            style.addSource(GeoJsonSource(STOPS_SOURCE, latestStopsGeoJson))
            style.addLayer(
                SymbolLayer(STOPS_LAYER, STOPS_SOURCE).withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
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
    LaunchedEffect(loadedStyle, sources) {
        val style = loadedStyle ?: return@LaunchedEffect
        stopLabels.forEach { addStopImage(context, style, it) }
        style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.setGeoJson(sources.trackJson)
        style.getSourceAs<GeoJsonSource>(SPEED_SOURCE)?.setGeoJson(sources.speedJson)
        style.getSourceAs<GeoJsonSource>(STOPS_SOURCE)?.setGeoJson(stopsJson)
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
    // hasData follows the (filtered) lines on purpose: they arrive asynchronously, so framing on the
    // raw track would fit an empty frame. Accepted edge: with no file bounds and no pins, toggling
    // «Все точки» when the filter hid every point flips hasData and re-frames once.
    val metadata = (styleSource as? MapStyleSource.Offline)?.metadata
    val hasData = trackLines.isNotEmpty() || pins.isNotEmpty()
    val dataKey = if (metadata?.bounds == null) hasData else null
    LaunchedEffect(loadedStyle, frameKey, dataKey) {
        val m = map ?: return@LaunchedEffect
        if (loadedStyle == null) return@LaunchedEffect
        applyCamera(context, m, metadata, dataBounds(latestTrackLines.flatten(), latestPins))
    }

    LaunchedEffect(loadedStyle, cameraCommand) {
        val m = map ?: return@LaunchedEffect
        val command = cameraCommand ?: return@LaunchedEffect
        if (loadedStyle == null) return@LaunchedEffect
        onCameraCommandDone(runCameraCommand(m, command, metadata))
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private class TrackSources(val trackJson: String, val speedJson: String, val stops: List<TrackStop>) {
    companion object {
        val Empty = TrackSources(trackGeoJson(emptyList()), speedRunsGeoJson(emptyList()), emptyList())
    }
}

private fun addPinImage(context: Context, style: Style, number: Int) {
    val name = pinIconName(number)
    if (style.getImage(name) == null) style.addImage(name, pinBitmap(context, number))
}

private fun addStopImage(context: Context, style: Style, label: String) {
    val name = stopIconName(label)
    if (style.getImage(name) == null) style.addImage(name, stopBitmap(context, label))
}

/** Speed-run layers, bottom to top: casing under band runs, dashed grey gaps, band colors. */
private fun addSpeedLayers(style: Style) {
    val stroke = Expression.get(SPEED_STROKE_PROPERTY)
    val isGap = Expression.eq(stroke, Expression.literal(SPEED_GAP_KEY))
    val notGap = Expression.neq(stroke, Expression.literal(SPEED_GAP_KEY))
    style.addLayer(
        LineLayer(SPEED_CASING_LAYER, SPEED_SOURCE)
            .withFilter(notGap)
            .withProperties(
                PropertyFactory.lineColor(android.graphics.Color.BLACK),
                PropertyFactory.lineOpacity(SPEED_CASING_ALPHA),
                PropertyFactory.lineWidth(SPEED_CASING_WIDTH_DP),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
    )
    style.addLayer(
        LineLayer(SPEED_GAP_LAYER, SPEED_SOURCE)
            .withFilter(isGap)
            .withProperties(
                PropertyFactory.lineColor(SpeedGap.toArgb()),
                PropertyFactory.lineWidth(TRACK_LINE_WIDTH_DP),
                PropertyFactory.lineDasharray(SPEED_GAP_DASH),
                PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
    )
    val bandColors = SpeedBand.entries.map { band ->
        Expression.stop(speedStrokeKey(SpeedStroke.Band(band)), Expression.color(speedBandColor(band).toArgb()))
    }
    style.addLayer(
        LineLayer(SPEED_LAYER, SPEED_SOURCE)
            .withFilter(notGap)
            .withProperties(
                PropertyFactory.lineColor(
                    Expression.match(stroke, Expression.color(OrangeCta.toArgb()), *bandColors.toTypedArray()),
                ),
                PropertyFactory.lineWidth(TRACK_LINE_WIDTH_DP),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
    )
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

/** File [bounds] fitted edge to edge, but never below the zoom where the file is drawn ([raceMapZoom]). */
private fun raceMapCamera(map: MapLibreMap, bounds: Bounds, minZoom: Int?): CameraUpdate {
    val latLngBounds = bounds.toLatLngBounds()
    val fit = map.getCameraForLatLngBounds(latLngBounds, intArrayOf(0, 0, 0, 0))
        ?: return CameraUpdateFactory.newLatLngBounds(latLngBounds, 0)
    return CameraUpdateFactory.newLatLngZoom(fit.target ?: latLngBounds.center, raceMapZoom(fit.zoom, minZoom))
}

/** Animates the camera per [command]; `false` when there is nothing to move to. */
private fun runCameraCommand(map: MapLibreMap, command: MapCameraCommand, metadata: MbtilesMetadata?): Boolean {
    val update = when (command) {
        MapCameraCommand.RaceMap -> {
            val bounds = metadata?.bounds ?: return false
            raceMapCamera(map, bounds, metadata.minZoom)
        }
        MapCameraCommand.MyLocation -> {
            val last = runCatching {
                map.locationComponent
                    .takeIf { it.isLocationComponentActivated && it.isLocationComponentEnabled }
                    ?.lastKnownLocation
            }.getOrNull() ?: return false
            val zoom = maxOf(map.cameraPosition.zoom, SINGLE_POINT_ZOOM)
            CameraUpdateFactory.newLatLngZoom(LatLng(last.latitude, last.longitude), zoom)
        }
    }
    map.animateCamera(update)
    return true
}

/**
 * Frames the camera per [cameraFrame]: file bounds edge to edge (0 padding), a data fit with 48 dp.
 * Zoom out and panning are free — OSM lies under the race file — and the max zoom follows MBTiles
 * `maxzoom` (else MapLibre's limit), past which the file would only be upscaled.
 */
private fun applyCamera(
    context: Context,
    map: MapLibreMap,
    metadata: MbtilesMetadata?,
    dataBounds: Bounds?,
) {
    // Native setMaxZoom silently ignores a value below the *current* min — widen to MapLibre's full
    // range first, then apply the file's max.
    map.setMinZoomPreference(MapLibreConstants.MINIMUM_ZOOM.toDouble())
    map.setMaxZoomPreference(MapLibreConstants.MAXIMUM_ZOOM.toDouble())
    metadata?.maxZoom?.let { map.setMaxZoomPreference(it.toDouble()) }

    val frame = cameraFrame(metadata?.bounds, dataBounds)
    when (frame) {
        is CameraFrame.FileBounds ->
            map.moveCamera(raceMapCamera(map, frame.bounds, metadata?.minZoom))
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
