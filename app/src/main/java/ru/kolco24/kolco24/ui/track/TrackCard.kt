package ru.kolco24.kolco24.ui.track

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.kolco24.kolco24.data.track.TargetUploadOutcome
import ru.kolco24.kolco24.data.track.TrackState
import ru.kolco24.kolco24.data.track.UploadResultKind
import ru.kolco24.kolco24.data.track.pointsLabel
import ru.kolco24.kolco24.data.track.pointsWord
import ru.kolco24.kolco24.data.track.relativeTimeRu
import ru.kolco24.kolco24.data.track.segmentsWord
import ru.kolco24.kolco24.ui.theme.OrangeCta

/**
 * One upload target's progress for the status row: how many of [total] points are uploaded to this
 * target and its last flush [outcome] (`null` until a flush has reported, or after a destructive
 * clear). UI-composition model, declared next to [TrackCard] so the host depends on this package
 * (never the reverse).
 */
data class TargetLine(val uploaded: Int, val outcome: TargetUploadOutcome?)

/**
 * The adaptive upload-status view-model the host derives by joining the durable per-target counts
 * with the transient in-memory outcomes for the selected scope. [fullyUploaded] is the calm-state
 * gate: everything counted and both targets caught up.
 */
data class TrackUploadStatus(val total: Int, val local: TargetLine, val cloud: TargetLine) {
    val fullyUploaded: Boolean get() = total > 0 && local.uploaded == total && cloud.uploaded == total
}

/**
 * Stateless GPS-track section for the «Команда» tab (rendered as a [Surface] card next to «Прочее»).
 * State is hoisted: the host owns the [TrackState], the metrics, and the start/stop/clear callbacks.
 *
 * - [Idle][TrackState.Idle] → a «Начать запись» button (disabled only when [hasTeam] is false). When
 *   [degradedAccuracy] (no GPS provider, only network) the start is **not** disabled — instead a quiet
 *   hint warns the track will be coarse. If a track already exists ([pointCount] > 0) the metrics show
 *   below, plus a secondary «Поделиться GPX» [OutlinedButton] ([onShare]) that exports the track via the
 *   system share-sheet.
 * - [Recording][TrackState.Recording] → a pulsing dot + «N точек» live readout and a «Остановить»
 *   button.
 *
 * Clearing the track lives in the Settings overlay («Запись трека» card), not here — it is a
 * destructive action and was moved out of this frequently-visited tab to avoid accidental taps.
 *
 * Not unit-tested (Compose, per repo convention). Track length is computed server-side from the
 * per-point `segment_id`, so there is no on-device length metric — instead the metrics show the
 * number of recording sessions ([segmentCount], distinct `segment_id`s) the host derives.
 */
@Composable
fun TrackCard(
    state: TrackState,
    pointCount: Int,
    segmentCount: Int,
    hasTeam: Boolean,
    degradedAccuracy: Boolean,
    firstPointTime: String?,
    lastPointTime: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    uploadStatus: TrackUploadStatus? = null,
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
                    RecordingHeader(pointCount = pointCount)
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
                            segmentCount = segmentCount,
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
                    if (pointCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onShare,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Поделиться GPX")
                        }
                    }
                    if (degradedAccuracy) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Только примерная геолокация (нет GPS).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Common footer (idle and recording): the upload-status diagnostics row. Hidden when
                // there is nothing to report (no scope / empty track).
                if (uploadStatus != null && uploadStatus.total > 0) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    UploadStatusRow(status = uploadStatus)
                }
            }
        }
    }
}

/**
 * Collapsible, deliberately-muted upload-status row at the bottom of [TrackCard]. Collapsed it is a
 * one-liner — a calm «Загружено» when both targets have caught up, otherwise the worst pending
 * target's last-attempt summary (counts + «N мин назад» + outcome). Expanded it shows one line per
 * server («Интернет» = cloud, «Локальный» = LAN). The displayed "now" advances on a 30-second ticker
 * so the relative time updates while the row is visible. Compose-untested per repo convention; only
 * the pure [relativeTimeRu] formatter is unit-tested.
 */
@Composable
private fun UploadStatusRow(status: TrackUploadStatus) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(status.fullyUploaded) { if (status.fullyUploaded) expanded = false }
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(top = 10.dp),
    ) {
        if (expanded) {
            ExpandedStatus(status = status, nowMs = nowMs)
        } else if (status.fullyUploaded) {
            CollapsedDone()
        } else {
            CollapsedPending(status = status, nowMs = nowMs)
        }
    }
}

@Composable
private fun CollapsedDone() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "Загружено",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun CollapsedPending(status: TrackUploadStatus, nowMs: Long) {
    val worst = worstPendingTarget(status)
    val outcome = worst?.line?.outcome
    val isError = outcome?.kind == UploadResultKind.Error || outcome?.kind == UploadResultKind.Offline
    val glyphTint =
        if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val text = when {
        worst != null && outcome != null ->
            "${worst.label}: ${worst.line.uploaded}/${status.total} · " +
                "${relativeTimeRu(outcome.atWallMs, nowMs)} · ${outcomeLabelRu(outcome.kind)}"
        else -> {
            val remaining = maxOf(
                status.total - status.local.uploaded,
                status.total - status.cloud.uploaded,
            )
            "Загрузка · осталось $remaining"
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (isError) Icons.Outlined.CloudOff else Icons.Outlined.CloudUpload,
            contentDescription = null,
            tint = glyphTint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ExpandedStatus(status: TrackUploadStatus, nowMs: Long) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Загрузка трека",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ExpandLess,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        ServerLine(label = "Интернет", total = status.total, line = status.cloud, nowMs = nowMs)
        Spacer(Modifier.height(4.dp))
        ServerLine(label = "Локальный", total = status.total, line = status.local, nowMs = nowMs)
    }
}

@Composable
private fun ServerLine(label: String, total: Int, line: TargetLine, nowMs: Long) {
    val done = line.uploaded >= total
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = "${line.uploaded}/$total",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (done) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp),
            )
        } else {
            val outcome = line.outcome
            if (outcome != null) {
                Text(
                    text = "${relativeTimeRu(outcome.atWallMs, nowMs)} · ${outcomeLabelRu(outcome.kind)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (outcome.kind == UploadResultKind.Ok) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

/** «ok» / «нет сети» / «ошибка» — the short outcome label shown next to a pending target. */
private fun outcomeLabelRu(kind: UploadResultKind): String = when (kind) {
    UploadResultKind.Ok -> "ok"
    UploadResultKind.Offline -> "нет сети"
    UploadResultKind.Error -> "ошибка"
}

/** A labelled target line for the collapsed summary. */
private data class PendingTarget(val label: String, val line: TargetLine)

/**
 * Pick the target to summarise when collapsed-and-pending: among the targets that are behind
 * (`uploaded < total`), prefer the one with the most alarming last outcome (Error > Offline > Ok >
 * none). Returns `null` when no target is behind (caller then shows the calm done state).
 */
private fun worstPendingTarget(status: TrackUploadStatus): PendingTarget? {
    val behind = listOf(
        PendingTarget("Интернет", status.cloud),
        PendingTarget("Локальный", status.local),
    ).filter { it.line.uploaded < status.total }
    if (behind.isEmpty()) return null
    return behind.minByOrNull { target ->
        when (target.line.outcome?.kind) {
            UploadResultKind.Error -> 0
            UploadResultKind.Offline -> 1
            UploadResultKind.Ok -> 2
            null -> 3
        }
    }
}

@Composable
private fun RecordingHeader(pointCount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PulsingDot()
        Column {
            Text(
                text = "Идёт запись",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = pointsLabel(pointCount),
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
    segmentCount: Int,
    firstPointTime: String?,
    lastPointTime: String?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // Labels decline by their count so the value+label reads grammatically (82 точки, 3 сегмента).
        Metric(label = pointsWord(pointCount).replaceFirstChar { it.uppercase() }, value = pointCount.toString())
        Metric(label = segmentsWord(segmentCount).replaceFirstChar { it.uppercase() }, value = segmentCount.toString())
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
