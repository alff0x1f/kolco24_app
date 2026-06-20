package ru.kolco24.kolco24.ui.marks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.takenPointCount
import ru.kolco24.kolco24.data.totalScore
import ru.kolco24.kolco24.ui.theme.OrangeCta
import ru.kolco24.kolco24.ui.theme.RobotoMono

data class Mark(
    val number: String,
    val cost: Int,
    val kind: MarkKind,
    val time: String,
    val isRecent: Boolean = false,
)

enum class MarkKind { NFC, PHOTO }

/**
 * Pure mapping of the local take events into display tiles — **one tile per event** (a repeat take of
 * the same checkpoint shows as a separate tile). [marks] is expected newest-first (as `observeMarks`
 * delivers). The single most-recent event (max `takenAt`) is flagged [Mark.isRecent]. Uses
 * [SimpleDateFormat] (not `java.time`) for minSdk-24/no-desugaring compatibility.
 */
fun marksToTiles(marks: List<MarkEntity>): List<Mark> {
    val fmt = SimpleDateFormat("HH:mm", Locale.US)
    val recentAt = marks.maxByOrNull { it.takenAt }?.takenAt
    return marks.map { m ->
        Mark(
            number = m.checkpointNumber.toString().padStart(2, '0'),
            cost = m.cost,
            kind = if (m.method == "photo") MarkKind.PHOTO else MarkKind.NFC,
            time = fmt.format(Date(m.takenAt)),
            isRecent = m.takenAt == recentAt,
        )
    }
}

private val PHOTO_GRADIENTS = listOf(
    listOf(Color(0xFFDCD3B0), Color(0xFF8FA178), Color(0xFF5B6A4A)),
    listOf(Color(0xFFB7C4D3), Color(0xFF6E7E94), Color(0xFF2C3845)),
    listOf(Color(0xFFC8BFA6), Color(0xFF897E62), Color(0xFF4A4233)),
)

private val RedBand = Color(0xFFB01528)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarksScreen(
    marks: List<MarkEntity> = emptyList(),
    onScanClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val totalKp = takenPointCount(marks)
    val score = totalScore(marks)
    val tiles = marksToTiles(marks)

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Отметки") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp),
            ) {
                item("metrics") {
                    // «ДО КВ» has no real source yet — placeholder until control-time lands.
                    MetricsCard(kpCount = totalKp, score = score, timeToKv = "—")
                }
                if (tiles.isEmpty()) {
                    item("empty") {
                        MarksEmpty(modifier = Modifier.padding(top = 64.dp))
                    }
                } else {
                    item("tile_grid") {
                        TileGrid(marks = tiles)
                    }
                }
                item("nfc_banner") {
                    NfcBanner(modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {},
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(start = 14.dp, end = 18.dp),
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp), tint = OrangeCta)
                    Spacer(Modifier.width(8.dp))
                    Text("Фото", style = MaterialTheme.typography.labelLarge)
                }
                Button(
                    onClick = onScanClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OrangeCta,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 20.dp),
                ) {
                    Icon(Icons.Filled.Nfc, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Отметить КП", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun MarksEmpty(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Пока нет отметок",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Отметьте КП кнопкой «Отметить КП»",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetricsCard(kpCount: Int, score: Int, timeToKv: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetricItem(value = "$kpCount", unit = "КП", label = "ВЗЯТО", modifier = Modifier.weight(1f))
            VerticalDivider(
                modifier = Modifier.height(36.dp).padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            MetricItem(value = "$score", unit = "бал.", label = "СУММА", modifier = Modifier.weight(1f))
            VerticalDivider(
                modifier = Modifier.height(36.dp).padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            MetricItem(value = timeToKv, label = "ДО КВ", isWarn = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricItem(
    value: String,
    unit: String? = null,
    label: String,
    isWarn: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
                fontFamily = if (isWarn) RobotoMono else null,
                color = if (isWarn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (unit != null) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TileGrid(marks: List<Mark>, modifier: Modifier = Modifier) {
    val rows = marks.chunked(4)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { rowIdx, rowMarks ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rowMarks.forEachIndexed { colIdx, mark ->
                    Box(modifier = Modifier.weight(1f)) {
                        MarkTile(mark = mark, gradientIndex = (rowIdx * 4 + colIdx) % 3)
                    }
                }
                repeat(4 - rowMarks.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MarkTile(mark: Mark, gradientIndex: Int) {
    when (mark.kind) {
        MarkKind.NFC -> NfcTile(mark = mark)
        MarkKind.PHOTO -> PhotoTile(mark = mark, gradientIndex = gradientIndex)
    }
}

@Composable
private fun NfcTile(mark: Mark) {
    val recentBorder = if (mark.isRecent) Modifier.border(2.dp, MaterialTheme.colorScheme.tertiary) else Modifier
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .then(recentBorder),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.10f)
                .align(Alignment.TopStart)
                .background(RedBand.copy(alpha = 0.78f))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.10f)
                .align(Alignment.BottomStart)
                .background(RedBand.copy(alpha = 0.78f))
        )
        Text(
            text = mark.number,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
            ),
            fontFamily = RobotoMono,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PhotoTile(mark: Mark, gradientIndex: Int) {
    val gradient = PHOTO_GRADIENTS[gradientIndex]
    val recentBorder = if (mark.isRecent) Modifier.border(2.dp, MaterialTheme.colorScheme.tertiary) else Modifier
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Brush.linearGradient(gradient))
            .then(recentBorder),
    ) {
        Surface(
            modifier = Modifier
                .padding(6.dp)
                .align(Alignment.TopStart),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 1.dp,
        ) {
            Text(
                text = mark.number,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = RobotoMono,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
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
