package ru.kolco24.kolco24.ui.legend

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.ui.theme.OrangeCta
import ru.kolco24.kolco24.ui.theme.RobotoMono

/**
 * Screen 02 — the «Легенда» tab. Stateless: it renders one of two states from the data passed in.
 * - [hasTeam] `false` → **02c LegendNoTeam** (no team/race selected yet) with a CTA to pick a team;
 * - otherwise → the **02** checkpoint list backed by [checkpoints] (real data from Room).
 *
 * The legend is now **always** served (per-CP encryption replaced the race-level `is_legend_visible`
 * flag), so there is no "before start" state: locked CPs arrive in [checkpoints] with `cost == null`
 * and render as masked rows ([CheckpointRow]) until an NFC scan reveals them.
 *
 * [checkpoints] doubles as the model (no domain layer); [CheckpointEntity.taken] is always `false`
 * this iteration (marks not built yet) but the score card, filter and dim/strike row styling stay so
 * the future marks feature flips data, not UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegendScreen(
    checkpoints: List<CheckpointEntity>,
    hasTeam: Boolean,
    onChooseTeam: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Легенда") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        when {
            !hasTeam -> LegendNoTeam(onChooseTeam = onChooseTeam)
            else -> LegendList(checkpoints = checkpoints)
        }
    }
}

@Composable
private fun LegendList(checkpoints: List<CheckpointEntity>) {
    var showOnlyOpen by rememberSaveable { mutableStateOf(false) }

    val visible = if (showOnlyOpen) checkpoints.filter { !it.taken } else checkpoints

    val takenCount = checkpoints.count { it.taken }
    val totalCount = checkpoints.size
    // cost is nullable now (locked CPs hide it until unlocked); sum only the known costs. Locked-
    // unrevealed CPs are surfaced as a «+N закрытых КП» hint instead of skewing the score.
    val takenScore = checkpoints.filter { it.taken }.mapNotNull { it.cost }.sum()
    val totalScore = checkpoints.mapNotNull { it.cost }.sum()
    val lockedCount = checkpoints.count { it.cost == null }

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
                lockedCount = lockedCount,
            )
        }

        item("chips") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 14.dp),
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

@Composable
private fun ScoreCard(
    takenScore: Int,
    totalScore: Int,
    takenCount: Int,
    totalCount: Int,
    lockedCount: Int,
) {
    val progress = if (totalScore > 0) takenScore.toFloat() / totalScore else 0f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp),
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
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                        ),
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

            if (lockedCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "+$lockedCount закрытых КП",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CheckpointListCard(checkpoints: List<CheckpointEntity>) {
    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
    val contentColor = if (selected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface
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
private fun CheckpointRow(cp: CheckpointEntity, isLast: Boolean) {
    // A locked CP arrives with `cost == null` (and no description) — the plaintext stays on the
    // server until an NFC scan unlocks it, so the row is masked instead of showing a real label.
    val locked = cp.cost == null
    val contentColor = when {
        locked || cp.taken -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (locked) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.widthIn(min = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "?-${cp.number.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                        ),
                        fontFamily = RobotoMono,
                        color = contentColor,
                    )
                }
            } else {
                Text(
                    text = "${cp.cost}-${cp.number.toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.sp,
                    ),
                    fontFamily = RobotoMono,
                    color = contentColor,
                    modifier = Modifier.widthIn(min = 48.dp),
                )
            }
            Text(
                text = if (locked) "Откроется на КП" else cp.description.orEmpty(),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.5.sp,
                    fontWeight = if (locked || cp.taken) FontWeight.Normal else FontWeight.Medium,
                    letterSpacing = 0.sp,
                ),
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (cp.taken && !locked) {
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

/**
 * 02c — no team (and therefore no race) selected: the legend is tied to a race, so there is nothing
 * to show. Map-glyph illustration in the rhyme of the empty «Команда» state, plus the CTA that opens
 * the team-selection flow via [onChooseTeam].
 */
@Composable
private fun LegendNoTeam(onChooseTeam: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(18.dp))
            MapIllustration()

            Spacer(Modifier.height(20.dp))
            Text(
                text = "Легенда пока недоступна",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Список КП привязан к соревнованию. Выберите соревнование и команду — легенда появится здесь.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onChooseTeam,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = OrangeCta,
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Выбрать команду", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** Charcoal map core circle wrapped in a dashed orange "to fill" ring (rhymes with the empty team state). */
@Composable
private fun MapIllustration() {
    val ringColor = OrangeCta.copy(alpha = 0.45f)
    val coreBrush = Brush.linearGradient(
        listOf(Color(0xFF1D242D), Color(0xFF2A333E)),
    )
    Box(
        modifier = Modifier
            .size(132.dp)
            .drawBehind {
                drawCircle(
                    color = ringColor,
                    radius = size.minDimension / 2 - 1.dp.toPx(),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 6.dp.toPx()),
                        ),
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .drawBehind { drawRect(brush = coreBrush) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Map,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(50.dp),
            )
        }
    }
}
