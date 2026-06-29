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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
                // The upload-status pill sits with the track info (metrics / recording header), shown
                // only when there is something to report (a scope with points).
                val upload = uploadStatus?.takeIf { it.total > 0 }
                if (recording) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        RecordingHeader(pointCount = pointCount, modifier = Modifier.weight(1f))
                        if (upload != null) UploadStatusRow(status = upload)
                    }
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
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            TrackMetrics(
                                pointCount = pointCount,
                                segmentCount = segmentCount,
                                firstPointTime = firstPointTime,
                                lastPointTime = lastPointTime,
                                modifier = Modifier.weight(1f),
                            )
                            if (upload != null) UploadStatusRow(status = upload)
                        }
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
            }
        }
    }
}

/**
 * Collapsible, deliberately-muted upload-status row at the bottom of [TrackCard]. Collapsed it is a
 * one-liner — a calm «Загружено» when both targets have caught up, otherwise the worst pending
 * target's last-attempt summary (counts + «N мин назад» + outcome). Expanded it shows one line per
 * server («Интернет» = cloud, «Финиш» = LAN). The displayed "now" advances on a 30-second ticker
 * so the relative time updates while the row is visible. Compose-untested per repo convention; only
 * the pure [relativeTimeRu] formatter is unit-tested.
 */
/**
 * The upload-status footer, a **chat-style delivery receipt**: a track point really travels to two
 * destinations («Интернет» / «Финиш»), so the indicator borrows the messenger language of ticks.
 *
 * Collapsed, it is a single tappable pill in the card's top-right corner showing **the cloud target
 * only** — a glyph + mono `n/total`. Tapping opens a small receipt [Popup] below the pill with both
 * targets in full. The pill is clipped to a capsule so its tap ripple stays a clean pill (no stray
 * rectangle).
 */
@Composable
private fun UploadStatusRow(status: TrackUploadStatus, modifier: Modifier = Modifier) {
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    var expanded by remember { mutableStateOf(false) }
    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }

    Box(modifier = modifier) {
        CloudReceiptPill(status = status, onClick = { expanded = !expanded })
        if (expanded) {
            // Anchor the bubble's top-right under the pill's bottom-right (opens downward); fall back
            // above the pill if there is no room below. Clamp to the window's left edge.
            val positionProvider = remember(gapPx) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        val x = (anchorBounds.right - popupContentSize.width).coerceAtLeast(0)
                        val below = anchorBounds.bottom + gapPx
                        val y = if (below + popupContentSize.height <= windowSize.height) {
                            below
                        } else {
                            (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
                        }
                        return IntOffset(x, y)
                    }
                }
            }
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                UploadReceiptCard(status = status, nowMs = nowMs)
            }
        }
    }
}

/**
 * The collapsed glance: cloud target only, `[glyph] n/total`. The glyph is the qualitative signal —
 * a green double-check when caught up, a red cloud-off when offline/errored, a muted cloud-up while
 * in flight — and the mono digit is the quantitative one.
 */
@Composable
private fun CloudReceiptPill(status: TrackUploadStatus, onClick: () -> Unit) {
    val cloud = status.cloud
    val done = cloud.uploaded >= status.total
    val kind = cloud.outcome?.kind
    val isError = kind == UploadResultKind.Error || kind == UploadResultKind.Offline
    val glyph = when {
        done -> Icons.Filled.DoneAll
        isError -> Icons.Outlined.CloudOff
        else -> Icons.Outlined.CloudUpload
    }
    val tint = when {
        done -> MaterialTheme.colorScheme.tertiary
        isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            glyph,
            contentDescription = "Статус загрузки трека",
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = "${cloud.uploaded}/${status.total}",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The tap-to-reveal receipt: both targets in full, each as a [ReceiptLine]. */
@Composable
private fun UploadReceiptCard(status: TrackUploadStatus, nowMs: Long) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.widthIn(min = 224.dp, max = 288.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = "Загрузка трека",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            ReceiptLine(
                label = "Интернет",
                total = status.total,
                line = status.cloud,
                nowMs = nowMs,
                offlineLabel = "нет интернета",
            )
            Spacer(Modifier.height(8.dp))
            ReceiptLine(
                label = "Финиш",
                total = status.total,
                line = status.local,
                nowMs = nowMs,
                offlineLabel = "сервер недоступен",
            )
        }
    }
}

/**
 * One target's receipt row: a leading tick (green double-check = done, red cloud-off = problem,
 * muted single check = sent-and-pending), the target label, and the mono `n/total`. When still
 * pending with a reported outcome, a muted second line gives «time · статус».
 */
@Composable
private fun ReceiptLine(
    label: String,
    total: Int,
    line: TargetLine,
    nowMs: Long,
    offlineLabel: String,
) {
    val done = line.uploaded >= total
    val outcome = line.outcome
    val isError = outcome?.kind == UploadResultKind.Error || outcome?.kind == UploadResultKind.Offline
    val glyph = when {
        done -> Icons.Filled.DoneAll
        isError -> Icons.Outlined.CloudOff
        else -> Icons.Filled.Check
    }
    val glyphTint = when {
        done -> MaterialTheme.colorScheme.tertiary
        isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(glyph, contentDescription = null, tint = glyphTint, modifier = Modifier.size(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${line.uploaded}/$total",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!done && outcome != null) {
            Text(
                text = "${relativeTimeRu(outcome.atWallMs, nowMs)} · " +
                    outcomeLabelRu(outcome.kind, offlineLabel),
                style = MaterialTheme.typography.labelSmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 24.dp, top = 2.dp),
            )
        }
    }
}

/** «ok» / target-specific offline text / «ошибка» — the short pending-target outcome label. */
private fun outcomeLabelRu(kind: UploadResultKind, offlineLabel: String): String = when (kind) {
    UploadResultKind.Ok -> "ok"
    UploadResultKind.Offline -> offlineLabel
    UploadResultKind.Error -> "ошибка"
}

@Composable
private fun RecordingHeader(pointCount: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
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
