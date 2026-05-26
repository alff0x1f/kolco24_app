package ru.kolco24.kolco24.ui.scan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ScanChip(
    val chipId: String?,
    val name: String?,
    val filled: Boolean,
)

private val MOCK_SCAN_CHIPS = listOf(
    ScanChip("597", "Маленков А.", true),
    ScanChip("601", "Иванов И.", true),
    ScanChip("604", "Сидоров П.", true),
    ScanChip(null, null, false),
    ScanChip(null, null, false),
    ScanChip(null, null, false),
)

@Composable
fun ScanScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val scanned = MOCK_SCAN_CHIPS.count { it.filled }
    val total = MOCK_SCAN_CHIPS.size
    val remaining = total - scanned

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item("top_bar") {
                ScanTopBar(onClose = onClose)
            }
            item("cp_waiting") {
                CpWaitingCard()
            }
            item("chip_section") {
                ChipSectionHeader(scanned = scanned, total = total)
            }
            item("chip_grid") {
                ChipGrid(chips = MOCK_SCAN_CHIPS)
            }
            item("chip_hint") {
                Text(
                    text = "Сканировать чипы можно в любом порядке",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item("hero_timer") {
                HeroTimerCard(seconds = 17f, total = 20f, remainingScans = remaining + 1)
            }
            item("nfc_banner") {
                NfcBanner(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun ScanTopBar(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Закрыть",
            )
        }
        Text(
            text = "Отметить КП",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        Text(
            text = "Готово",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun CpWaitingCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 14.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CpBadgeEmpty(size = 64.dp)
            Column {
                Text(
                    text = "Метка КП",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "КП не отсканирован",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Text(
                    text = "Поднесите телефон к чипу на КП",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CpBadgeEmpty(size: Dp) {
    val height = size * 0.86f
    val stripeHeight = height * 0.11f
    Box(
        modifier = Modifier
            .size(width = size, height = height)
            .background(Color.White, MaterialTheme.shapes.small)
            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(stripeHeight)
                .align(Alignment.TopStart)
                .background(Color(0xFFB3261E).copy(alpha = 0.78f))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(stripeHeight)
                .align(Alignment.BottomStart)
                .background(Color(0xFFB3261E).copy(alpha = 0.78f))
        )
        Text(
            text = "?",
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun ChipSectionHeader(scanned: Int, total: Int) {
    val allScanned = scanned == total
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Чипы команды",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$scanned / $total",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (allScanned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ChipGrid(chips: List<ScanChip>, modifier: Modifier = Modifier) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val rows = chips.chunked(2)
    val lastRowIdx = rows.lastIndex

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            rows.forEachIndexed { rowIdx, row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    val leftModifier = if (row.size == 2) {
                        Modifier.weight(1f).drawBehind {
                            drawLine(
                                color = dividerColor,
                                start = Offset(size.width, 0f),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    } else {
                        Modifier.weight(1f)
                    }
                    Box(modifier = leftModifier) { ChipSlot(chip = row[0]) }
                    if (row.size == 2) {
                        Box(modifier = Modifier.weight(1f)) { ChipSlot(chip = row[1]) }
                    } else {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
                if (rowIdx != lastRowIdx) {
                    HorizontalDivider(color = dividerColor)
                }
            }
        }
    }
}

@Composable
private fun ChipSlot(chip: ScanChip) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (chip.filled) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(28.dp),
            )
        } else {
            WaitingChipIcon()
        }
        Column(modifier = Modifier.weight(1f)) {
            if (chip.filled) {
                Text(
                    text = chip.name ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Чип ${chip.chipId}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                Text(
                    text = "Ожидание",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "NFC · scan",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun WaitingChipIcon() {
    val outlineColor = MaterialTheme.colorScheme.outline
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier.size(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.width / 2 - 1.5.dp.toPx()
            drawCircle(
                color = outlineColor,
                radius = radius,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                ),
            )
        }
        Icon(
            imageVector = Icons.Filled.Nfc,
            contentDescription = null,
            tint = onSurfaceVariantColor,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun HeroTimerCard(seconds: Float, total: Float, remainingScans: Int) {
    val pct = (seconds / total).coerceIn(0f, 1f)
    val ringColor = if (seconds < 5f) Color(0xFFFFB4AB) else Color(0xFFFFC98A)
    val trackColor = Color.White.copy(alpha = 0.12f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 14.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 4.dp.toPx()
                    val radius = size.width / 2 - strokeWidth / 2
                    drawCircle(
                        color = trackColor,
                        radius = radius,
                        style = Stroke(width = strokeWidth),
                    )
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * pct,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = seconds.toInt().toString(),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 28.sp,
                        ),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                    Text(
                        text = "сек",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.65f),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                    )
                    Text(
                        text = "Сканируйте",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.65f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "КП и ещё $remainingScans чипа",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Таймер сбрасывается на ${total.toInt()} с при каждом скане",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.65f),
                )
            }
        }
    }
}

@Composable
private fun NfcBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.tertiary, CircleShape)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "NFC активен",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Приложите телефон к КП или чипу команды",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
