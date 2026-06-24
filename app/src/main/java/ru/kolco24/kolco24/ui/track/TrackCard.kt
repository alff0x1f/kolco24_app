package ru.kolco24.kolco24.ui.track

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ru.kolco24.kolco24.data.track.TrackState
import ru.kolco24.kolco24.ui.theme.OrangeCta

/**
 * Stateless GPS-track section for the «Команда» tab (rendered as a [Surface] card next to «Прочее»).
 * State is hoisted: the host owns the [TrackState], the metrics, and the start/stop/clear callbacks.
 *
 * - [Idle][TrackState.Idle] → a «Начать запись» button (disabled only when [hasTeam] is false). When
 *   [degradedAccuracy] (no GPS provider, only network) the start is **not** disabled — instead a quiet
 *   hint warns the track will be coarse. If a track already exists ([pointCount] > 0) the metrics and
 *   a «Очистить трек» action show below.
 * - [Recording][TrackState.Recording] → a pulsing dot + «N точек · ~Xм» live readout and a «Остановить»
 *   button. «Очистить трек» is intentionally absent while recording (a wipe mid-record would let the
 *   service keep inserting after the clear and make the counter jump).
 *
 * Not unit-tested (Compose, per repo convention); the pure length/metrics are covered by
 * `TrackMetricsTest`.
 */
@Composable
fun TrackCard(
    state: TrackState,
    pointCount: Int,
    lengthMeters: Double,
    hasTeam: Boolean,
    degradedAccuracy: Boolean,
    firstPointTime: String?,
    lastPointTime: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = state is TrackState.Recording

    Column(modifier = modifier.padding(bottom = 18.dp)) {
        Text(
            text = "GPS-трек",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (recording) {
                    RecordingHeader(pointCount = pointCount, lengthMeters = lengthMeters)
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Остановить")
                    }
                } else {
                    if (pointCount > 0) {
                        TrackMetrics(
                            pointCount = pointCount,
                            lengthMeters = lengthMeters,
                            firstPointTime = firstPointTime,
                            lastPointTime = lastPointTime,
                        )
                        Spacer(Modifier.height(14.dp))
                    } else {
                        Text(
                            text = "Запишите GPS-трек команды во время гонки.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    Button(
                        onClick = onStart,
                        enabled = hasTeam,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OrangeCta,
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Начать запись")
                    }
                    if (degradedAccuracy) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Только примерная геолокация (нет GPS).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (pointCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            onClick = onClear,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Очистить трек")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingHeader(pointCount: Int, lengthMeters: Double) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PulsingDot()
        Column {
            Text(
                text = "Идёт запись",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "$pointCount точек · ${formatLength(lengthMeters)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "track-pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "track-pulse-alpha",
    )
    Box(
        modifier = Modifier
            .size(12.dp)
            .alpha(pulse)
            .background(OrangeCta, CircleShape),
    )
}

@Composable
private fun TrackMetrics(
    pointCount: Int,
    lengthMeters: Double,
    firstPointTime: String?,
    lastPointTime: String?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Metric(label = "Точек", value = pointCount.toString())
        Metric(label = "Длина", value = formatLength(lengthMeters))
        val span = when {
            firstPointTime != null && lastPointTime != null -> "$firstPointTime–$lastPointTime"
            firstPointTime != null -> firstPointTime
            else -> "—"
        }
        Metric(label = "Время", value = span)
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Length readout: meters below 1 km, else one-decimal kilometers. */
private fun formatLength(meters: Double): String =
    if (meters < 1_000) "${Math.round(meters)}м"
    else {
        val tenths = Math.round(meters / 100.0)
        if (tenths % 10 == 0L) "${tenths / 10}км" else "${tenths / 10.0}км"
    }
