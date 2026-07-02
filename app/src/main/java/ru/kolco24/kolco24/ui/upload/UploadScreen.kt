package ru.kolco24.kolco24.ui.upload

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.kolco24.kolco24.data.track.UploadResultKind
import ru.kolco24.kolco24.data.track.relativeTimeRu
import ru.kolco24.kolco24.ui.common.RefreshableList

/**
 * «Загрузка данных» overlay — the single consolidated place to check upload status, reached from
 * Команда → Прочее. Renders up to three [UploadSection]s (Отметки, Фото, GPS-трек), each hidden when
 * its scope has nothing to report. Stateless; the host derives all three [TrackUploadStatus] values
 * and owns [refreshing]/[onRefresh] for the pull-to-refresh gesture on the content branch — the empty
 * state offers no gesture (nothing to upload by construction, mirrors `LegendScreen`). The PTR outcome
 * has no snackbar: the receipt lines themselves are the content that updates.
 * Compose, untested by convention — only [showFinishLine] below is unit-tested.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    marks: TrackUploadStatus?,
    photos: TrackUploadStatus?,
    track: TrackUploadStatus?,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val hasAny = listOf(marks, photos, track).any { it != null && it.total > 0 }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) { detectTapGestures {} },
    ) {
        TopAppBar(
            title = { Text("Загрузка данных") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        if (hasAny) {
            RefreshableList(isRefreshing = refreshing, onRefresh = onRefresh) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    UploadSection(title = "Отметки", status = marks, nowMs = nowMs)
                    UploadSection(title = "Фото", status = photos, nowMs = nowMs)
                    UploadSection(title = "GPS-трек", status = track, nowMs = nowMs)
                }
            }
        } else {
            UploadEmptyState(modifier = Modifier.fillMaxSize())
        }
    }
}

/** One section card: a header, the always-shown «Интернет» line, and «Финиш» once it reports. */
@Composable
private fun UploadSection(title: String, status: TrackUploadStatus?, nowMs: Long) {
    if (status == null || status.total <= 0) return
    Column(modifier = Modifier.padding(bottom = 18.dp)) {
        Text(
            text = title,
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
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                ReceiptLine(
                    label = "Интернет",
                    total = status.total,
                    line = status.cloud,
                    nowMs = nowMs,
                    offlineLabel = "нет интернета",
                )
                if (showFinishLine(status.local)) {
                    Spacer(Modifier.height(10.dp))
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
    }
}

/**
 * «Финиш» (LAN target) only shows once it has something to say — either it has already reported an
 * [TargetLine.outcome], or it has uploaded at least one point. Until then the LAN server may simply be
 * unreachable (the common case away from the finish line), so staying silent avoids a permanent,
 * meaningless "0/N" line.
 */
internal fun showFinishLine(line: TargetLine): Boolean = line.outcome != null || line.uploaded > 0

/**
 * One target's receipt row: a leading tick (green double-check = done, red cloud-off = problem,
 * muted single check = sent-and-pending), the target label, and the mono `n/total`. When still
 * pending with a reported outcome, a muted second line gives «time · статус». The canonical copy —
 * `TrackCard`/`MarksScreen` no longer carry their own.
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

/** Nothing recorded yet in any of the three scopes. */
@Composable
private fun UploadEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudUpload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Пока нечего загружать",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Здесь появится статус загрузки отметок, фото и GPS-трека, когда они будут записаны.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
