package ru.kolco24.kolco24.data.track

import ru.kolco24.kolco24.data.db.TrackPointEntity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Pure, Android-free GPX serialization (mirrors `ScanSession.kt`/`TrackModels.kt` — JVM-unit-testable,
 * no Android imports). The caller passes already-[filterPoints]-ed, reboot-safe ordered points; this
 * serializer stays dumb and total.
 *
 * The track is emitted as GPX 1.1: one `<trk>` with a `<name>`, then **one `<trkseg>` per consecutive
 * run of [TrackPointEntity.segmentId]**. Correctly ordered input keeps each recording session
 * contiguous, so a stop→start gap renders as separate segments instead of a teleport line (matching
 * how the server groups by `segment_id`). Each point's `<time>` uses `trustedMs ?: wallMs` formatted
 * as ISO-8601 UTC; `<ele>` is omitted when altitude is null. Numbers use [Locale.US] so the decimal
 * separator is always `.` regardless of device locale.
 */

private const val GPX_HEADER =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<gpx version=\"1.1\" creator=\"Kolco24\" xmlns=\"http://www.topografix.com/GPX/1/1\">"

/** Build the GPX document for [points] (assumed pre-filtered and ordered) under a single named track. */
fun buildGpx(points: List<TrackPointEntity>, trackName: String): String {
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val sb = StringBuilder()
    sb.append(GPX_HEADER).append('\n')
    sb.append("  <trk>\n")
    sb.append("    <name>").append(xmlEscape(trackName)).append("</name>\n")

    // Group consecutive runs into <trkseg>s by segmentId; callers own global ordering.
    var currentSegment: String? = null
    var segmentOpen = false
    for (p in points) {
        if (!segmentOpen || p.segmentId != currentSegment) {
            if (segmentOpen) sb.append("    </trkseg>\n")
            sb.append("    <trkseg>\n")
            segmentOpen = true
            currentSegment = p.segmentId
        }
        sb.append("      <trkpt lat=\"")
            .append(num(p.lat)).append("\" lon=\"").append(num(p.lon)).append("\">\n")
        if (p.altitude != null) {
            sb.append("        <ele>").append(num(p.altitude)).append("</ele>\n")
        }
        sb.append("        <time>").append(iso.format(java.util.Date(p.trustedMs ?: p.wallMs)))
            .append("</time>\n")
        sb.append("      </trkpt>\n")
    }
    if (segmentOpen) sb.append("    </trkseg>\n")

    sb.append("  </trk>\n")
    sb.append("</gpx>\n")
    return sb.toString()
}

/** A safe, date-stamped GPX filename, e.g. `kolco24-148-2026-06-26.gpx`. [teamLabel] is sanitized. */
fun gpxFileName(teamLabel: String, dateIso: String): String {
    val safe = teamLabel.trim().ifEmpty { "track" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
    return "kolco24-$safe-$dateIso.gpx"
}

private fun num(v: Double): String = String.format(Locale.US, "%.6f", v)

private fun xmlEscape(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(c)
    }
}
