package ru.kolco24.kolco24.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import ru.kolco24.kolco24.data.time.ClockStatus

/**
 * Format a wall-vs-trusted skew (ms, signed) as a direction-less «N мин» string.
 *
 * Pure (Android-free, JVM-tested): rounds **by magnitude** — `Math.round(abs(skewMs) / 60_000.0)`.
 * `round` (not `ceil`) per the test cases: 60_001 → «1 мин», 90_000 → «2 мин» (1.5→2), 119_000 →
 * «2 мин». The banner only shows on [ClockStatus.Skewed] (skew always `> 60_000`), so `round` yields
 * ≥ «1 мин». The text carries no direction, so we take the absolute value (a slow clock gives a
 * negative skew — never «−2 мин»); `abs` is computed in `Double` so `Long.MIN_VALUE` cannot trap.
 */
fun formatSkewMinutes(skewMs: Long): String {
    val minutes = Math.round(abs(skewMs.toDouble()) / 60_000.0)
    return "$minutes мин"
}

/**
 * Global clock-skew nag, rendered under the per-tab `TopAppBar` (single visibility rule, P2): shows
 * **only** on [ClockStatus.Skewed]. [ClockStatus.NoSync] renders nothing globally (the soft
 * unverified-time notice lives only in the scan overlay, where time is written); [ClockStatus.Ok]
 * renders nothing.
 */
@Composable
fun ClockWarningBanner(status: ClockStatus, modifier: Modifier = Modifier) {
    val skewed = status as? ClockStatus.Skewed ?: return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "Часы телефона расходятся с сервером на " +
                    "${formatSkewMinutes(skewed.skewMs)} — проверьте дату и время",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * Scan-overlay clock notice: a bright accent on [ClockStatus.Skewed] (mirrors the global banner) and
 * a **soft** unverified-time plate on [ClockStatus.NoSync] — the **only** place [ClockStatus.NoSync]
 * is surfaced. The take is still written either way; this is informational pressure. [ClockStatus.Ok]
 * renders nothing.
 */
@Composable
fun ScanClockBanner(status: ClockStatus, modifier: Modifier = Modifier) {
    when (status) {
        is ClockStatus.Skewed -> ScanClockRow(
            modifier = modifier,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            icon = Icons.Filled.Schedule,
            text = "Часы телефона расходятся с сервером на " +
                "${formatSkewMinutes(status.skewMs)} — проверьте дату и время",
        )
        ClockStatus.NoSync -> ScanClockRow(
            modifier = modifier,
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = Icons.Outlined.CloudOff,
            text = "Время не подтверждено — подключитесь к сети. Отметка всё равно будет сохранена.",
        )
        ClockStatus.Ok -> Unit
    }
}

@Composable
private fun ScanClockRow(
    modifier: Modifier,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = content,
            )
        }
    }
}
