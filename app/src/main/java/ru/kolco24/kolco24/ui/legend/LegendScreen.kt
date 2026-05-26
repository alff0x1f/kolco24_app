package ru.kolco24.kolco24.ui.legend

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Will become a Room @Entity in the next step
data class Checkpoint(
    val number: String,
    val cost: Int,
    val name: String,
    val taken: Boolean,
)

private val MOCK_CHECKPOINTS = listOf(
    Checkpoint("00", 0, "Тест", taken = true),
    Checkpoint("01", 5, "Дерево в 20м на северо-восток от геоглифа", taken = false),
    Checkpoint("02", 2, "Отдельно стоящая сухая берёза", taken = true),
    Checkpoint("03", 4, "Дерево в лощине под скалами", taken = false),
    Checkpoint("04", 3, "Дерево в лесополосе", taken = true),
    Checkpoint("05", 2, "Дерево на слиянии двух рек", taken = false),
    Checkpoint("06", 3, "Отдельно стоящая берёза", taken = false),
    Checkpoint("07", 4, "Триангулятор на вершине", taken = true),
    Checkpoint("08", 4, "Четырёхствольная берёза в 20м от подножия скал, на курумнике", taken = false),
    Checkpoint("09", 4, "Горизонтальное дерево в 40м от подножия", taken = false),
    Checkpoint("10", 5, "Скальный останец на хребте", taken = false),
    Checkpoint("11", 5, "Слияние ручья и реки", taken = false),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegendScreen(modifier: Modifier = Modifier) {
    var showOnlyOpen by rememberSaveable { mutableStateOf(false) }

    val checkpoints = MOCK_CHECKPOINTS.sortedBy { it.number }
    val visible = if (showOnlyOpen) checkpoints.filter { !it.taken } else checkpoints

    val takenCount = checkpoints.count { it.taken }
    val totalCount = checkpoints.size
    val takenScore = checkpoints.filter { it.taken }.sumOf { it.cost }
    val totalScore = checkpoints.sumOf { it.cost }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Легенда") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item("score") {
                ScoreCard(
                    takenScore = takenScore,
                    totalScore = totalScore,
                    takenCount = takenCount,
                    totalCount = totalCount,
                )
            }

            item("chips") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LegendFilterChip(
                        selected = !showOnlyOpen,
                        onClick = { showOnlyOpen = false },
                        label = "Все $totalCount",
                    )
                    LegendFilterChip(
                        selected = showOnlyOpen,
                        onClick = { showOnlyOpen = true },
                        label = "Не взятые ${totalCount - takenCount}",
                    )
                }
            }

            item("list") {
                CheckpointListCard(checkpoints = visible)
            }
        }
    }
}

@Composable
private fun ScoreCard(
    takenScore: Int,
    totalScore: Int,
    takenCount: Int,
    totalCount: Int,
) {
    val progress = if (totalScore > 0) takenScore.toFloat() / totalScore else 0f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "$takenScore",
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "/ $totalScore баллов",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                Text(
                    text = "$takenCount/$totalCount КП",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.tertiary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
    }
}

@Composable
private fun CheckpointListCard(checkpoints: List<Checkpoint>) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                checkpoints.forEachIndexed { index, cp ->
                    CheckpointRow(cp = cp, isLast = index == checkpoints.size - 1)
                }
            }
        }

        Text(
            text = "Слева — стоимость и номер КП. Галочка = отметка засчитана.",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    val backgroundColor = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent
    val contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    val border = if (selected) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = backgroundColor,
        border = border,
        modifier = Modifier.height(40.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun CheckpointRow(cp: Checkpoint, isLast: Boolean) {
    val contentColor = if (cp.taken) MaterialTheme.colorScheme.onSurfaceVariant
                       else MaterialTheme.colorScheme.onSurface

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "${cp.cost}-${cp.number}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = contentColor,
                modifier = Modifier.width(48.dp),
            )
            Text(
                text = cp.name,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (cp.taken) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Взято",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        if (!isLast) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}
