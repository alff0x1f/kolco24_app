package ru.kolco24.kolco24.data.track

import java.util.Locale

/**
 * Pure speed coloring of the track (port of iOS `Core/Track/TrackSpeed.swift`). Runs over the
 * spike-filtered [trackLines] output and is display-only — the DB, upload and GPX stay raw.
 *
 * A step's speed is the straight distance between the ends of a ~90 s window around it divided by
 * the window duration. At 15 s sampling a walking step (~17 m) is comparable to GPS noise, so a
 * point-to-point speed is useless; summing the path over the window would add noise on every step
 * (a phone lying still would «walk» 3 km/h).
 */

/** Target window span for a step's speed. */
const val SPEED_WINDOW_MS = 90_000L

/** A step longer than this is «long»: a window never grows across it; it is judged on its own. */
const val SPEED_GAP_MS = 180_000L

/** Lower bounds (km/h, inclusive) of [SpeedBand.Slow], [SpeedBand.Walk], [SpeedBand.Brisk], [SpeedBand.Fast]. */
val SPEED_BAND_LIMITS_KMH = listOf(1.0, 3.0, 5.0, 7.0)

/** A long step is a stop only when its ends are within `max(this, acc₁ + acc₂)` meters… */
const val LONG_STEP_STOP_MIN_RADIUS_M = 50.0

/** …but never more than this: coarse fixes must not turn an hour of moving without GPS into a stop. */
const val LONG_STEP_STOP_MAX_RADIUS_M = 150.0

/** A stop shorter than this gets no marker on the map. */
const val STOP_MIN_DURATION_MS = 300_000L

/** Speed band for a rogaine on foot with a backpack. */
enum class SpeedBand {
    /** < 1 km/h — a stop, searching for a КП in place. */
    Stop,
    /** 1–3 km/h — windfall, bog, steep climb. */
    Slow,
    /** 3–5 km/h — walking cross-country. */
    Walk,
    /** 5–7 km/h — brisk walk on a road. */
    Brisk,
    /** ≥ 7 km/h — running, downhill, bike. */
    Fast,
}

fun speedBand(kmh: Double): SpeedBand {
    val index = SPEED_BAND_LIMITS_KMH.indexOfLast { kmh >= it } + 1
    return SpeedBand.entries[index]
}

/** Map legend label of a band: «<1», «1–3», …, «7+» (km/h). */
fun speedBandLegendLabel(band: SpeedBand): String {
    val limits = SPEED_BAND_LIMITS_KMH.map { it.toInt().toString() }
    val i = band.ordinal
    return when (i) {
        0 -> "<${limits[0]}"
        limits.size -> "${limits[i - 1]}+"
        else -> "${limits[i - 1]}–${limits[i]}"
    }
}

private fun stepDistanceMeters(a: TrackPointLike, b: TrackPointLike): Double =
    haversineMeters(a.lat, a.lon, b.lat, b.lon)

/** Speed (m/s) between [a] and [b], time floored at 1 s. */
private fun averageSpeedMps(a: TrackPointLike, b: TrackPointLike): Double {
    val dtMs = maxOf(trackPointTimeMs(b) - trackPointTimeMs(a), 1000L)
    return stepDistanceMeters(a, b) / (dtMs / 1000.0)
}

/**
 * Step speeds (m/s) of a line, `line.size - 1` values. A long step (> [SPEED_GAP_MS]) gets its own
 * speed. A normal step's window grows alternately left and right (never across a long step; blocked
 * on one side, it keeps growing on the other) until it spans [SPEED_WINDOW_MS] or the line ends.
 */
fun stepSpeedsMps(line: List<TrackPointLike>): List<Double> {
    if (line.size < 2) return emptyList()
    val times = line.map(::trackPointTimeMs)
    val last = line.size - 1
    val isLong = (0 until last).map { times[it + 1] - times[it] > SPEED_GAP_MS }

    return (0 until last).map { k ->
        if (isLong[k]) return@map averageSpeedMps(line[k], line[k + 1])
        var a = k
        var b = k + 1
        var growLeft = true
        while (times[b] - times[a] < SPEED_WINDOW_MS) {
            val canLeft = a > 0 && !isLong[a - 1]
            val canRight = b < last && !isLong[b]
            if (!canLeft && !canRight) break
            if ((growLeft && canLeft) || !canRight) a-- else b++
            growLeft = !growLeft
        }
        averageSpeedMps(line[a], line[b])
    }
}

/** How a track step is drawn. */
sealed interface SpeedStroke {
    data class Band(val band: SpeedBand) : SpeedStroke

    /** A long step while moving (GPS hole) — its average speed would look like slow walking. */
    data object Gap : SpeedStroke
}

/**
 * Step strokes of a line, `line.size - 1` values. A normal step is its window speed band. A long
 * step is a stop when its ends are close (a phone at rest may yield no fixes), else
 * [SpeedStroke.Gap]. Judged by distance, not average speed: an hour without GPS while moving with
 * ends 800 m apart averages 0.8 km/h — a false «1 ч» stop.
 */
fun stepStrokes(line: List<TrackPointLike>): List<SpeedStroke> {
    val speeds = stepSpeedsMps(line)
    return speeds.indices.map { k ->
        val a = line[k]
        val b = line[k + 1]
        if (trackPointTimeMs(b) - trackPointTimeMs(a) <= SPEED_GAP_MS) {
            return@map SpeedStroke.Band(speedBand(speeds[k] * 3.6))
        }
        val radius = maxOf(LONG_STEP_STOP_MIN_RADIUS_M, a.accuracy.toDouble() + b.accuracy.toDouble())
            .coerceAtMost(LONG_STEP_STOP_MAX_RADIUS_M)
        if (stepDistanceMeters(a, b) <= radius) SpeedStroke.Band(SpeedBand.Stop) else SpeedStroke.Gap
    }
}

/** A run of consecutive steps with one stroke; [points] has ≥ 2 items, neighbour runs share the boundary point. */
data class SpeedRun(val stroke: SpeedStroke, val points: List<TrackPointLike>)

/** Stroke runs over all lines. Runs never cross lines; one-point lines give no runs. */
fun speedRuns(lines: List<List<TrackPointLike>>): List<SpeedRun> =
    lines.flatMap { lineRuns(it, stepStrokes(it)) }

private fun lineRuns(line: List<TrackPointLike>, strokes: List<SpeedStroke>): List<SpeedRun> {
    val runs = mutableListOf<SpeedRun>()
    var start = 0
    for (k in strokes.indices) {
        if (k == strokes.size - 1 || strokes[k + 1] != strokes[k]) {
            runs.add(SpeedRun(strokes[k], line.subList(start, k + 2)))
            start = k + 1
        }
    }
    return runs
}

/** A stop: centroid of its points and the time of its first/last point. */
data class TrackStop(val lat: Double, val lon: Double, val startMs: Long, val endMs: Long)

/**
 * Stops over all lines: every [SpeedBand.Stop] run lasting at least [STOP_MIN_DURATION_MS]. Stop
 * markers always agree with the coloring; they never cross lines.
 */
fun trackStops(lines: List<List<TrackPointLike>>): List<TrackStop> =
    lines.flatMap { runStops(lineRuns(it, stepStrokes(it))) }

private fun runStops(runs: List<SpeedRun>): List<TrackStop> = runs.mapNotNull { run ->
    if (run.stroke != SpeedStroke.Band(SpeedBand.Stop)) return@mapNotNull null
    val startMs = trackPointTimeMs(run.points.first())
    val endMs = trackPointTimeMs(run.points.last())
    if (endMs - startMs < STOP_MIN_DURATION_MS) return@mapNotNull null
    TrackStop(
        lat = run.points.sumOf { it.lat } / run.points.size,
        lon = run.points.sumOf { it.lon } / run.points.size,
        startMs = startMs,
        endMs = endMs,
    )
}

/** «3 мин», «59 мин», «1 ч 05 мин» — minutes floored. */
fun formatStopDuration(ms: Long): String {
    val minutes = ms / 60_000
    if (minutes < 60) return "$minutes мин"
    return "${minutes / 60} ч " + String.format(Locale.US, "%02d мин", minutes % 60)
}

/** Track coloring: stroke runs and stops (strokes computed once per line). */
data class SpeedTrack(val runs: List<SpeedRun>, val stops: List<TrackStop>) {
    companion object {
        fun of(lines: List<List<TrackPointLike>>): SpeedTrack {
            val perLine = lines.map { lineRuns(it, stepStrokes(it)) }
            return SpeedTrack(runs = perLine.flatten(), stops = perLine.flatMap(::runStops))
        }
    }
}
