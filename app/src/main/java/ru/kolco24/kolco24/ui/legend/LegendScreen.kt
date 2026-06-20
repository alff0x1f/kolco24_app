package ru.kolco24.kolco24.ui.legend

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.ui.theme.CpColorBlue
import ru.kolco24.kolco24.ui.theme.CpColorPurple
import ru.kolco24.kolco24.ui.theme.CpColorRed
import ru.kolco24.kolco24.ui.theme.CpColorYellow
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
 * [checkpoints] doubles as the model (no domain layer). [takenIds] is the set of checkpoint ids the
 * **selected team** has scored, derived from its own marks (see [ru.kolco24.kolco24.data.takenPoints]);
 * "взято" is team-scoped, so it is passed in rather than read off the (race-shared) checkpoint row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegendScreen(
    checkpoints: List<CheckpointEntity>,
    hasTeam: Boolean,
    onChooseTeam: () -> Unit,
    takenIds: Set<Int> = emptySet(),
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
            else -> LegendList(checkpoints = checkpoints, takenIds = takenIds)
        }
    }
}

@Composable
private fun LegendList(checkpoints: List<CheckpointEntity>, takenIds: Set<Int>) {
    var showOnlyOpen by rememberSaveable { mutableStateOf(false) }

    val visible = if (showOnlyOpen) checkpoints.filter { it.id !in takenIds } else checkpoints

    val takenCount = checkpoints.count { it.id in takenIds }
    val totalCount = checkpoints.size
    // cost is nullable now (locked CPs hide it until unlocked); sum only the known costs. Locked-
    // unrevealed CPs are surfaced as a «+N закрытых КП» hint instead of skewing the score.
    val takenScore = checkpoints.filter { it.id in takenIds }.mapNotNull { it.cost }.sum()
    val totalScore = checkpoints.mapNotNull { it.cost }.sum()
    val lockedCount = checkpoints.count { it.locked }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (lockedCount > 0) {
            item("locked-hero") {
                LockedHero(lockedCount = lockedCount)
            }
        }

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
            CheckpointListCard(checkpoints = visible, takenIds = takenIds)
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
        }
    }
}

/**
 * Charcoal hero card surfaced above the legend list when some checkpoints are still locked
 * (`lockedCount > 0`). Adapted from screen A2b's `LockedHero` (`docs/design/prototype/android/
 * screens.jsx`): the «до старта» framing is gone (per-CP encryption replaced the race-level
 * flag), so the eyebrow badge is dropped and the copy reframes to "some CPs are hidden, revealed
 * by scanning". The count lives in the title («Скрыто N КП» — impersonal, so no plural agreement
 * and «КП» is indeclinable). When everything is revealed the card is not rendered at all.
 */
@Composable
private fun LockedHero(lockedCount: Int) {
    val coreBrush = Brush.linearGradient(
        listOf(Color(0xFF1D242D), Color(0xFF2A333E)),
    )
    val glow = Color(0xFFC3011C)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .drawBehind {
                    drawRect(brush = coreBrush)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glow.copy(alpha = 0.5f), glow.copy(alpha = 0f)),
                            center = Offset(size.width, 0f),
                            radius = 200f,
                        ),
                        center = Offset(size.width, 0f),
                        radius = 200f,
                    )
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .drawBehind {
                        drawCircle(color = Color.White.copy(alpha = 0.07f))
                    }
                    .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Скрыто $lockedCount КП",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp,
                    ),
                    color = Color.White,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Стоимость и описания КП появятся позже",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = Color.White.copy(alpha = 0.58f),
                )
            }
        }
    }
}

@Composable
private fun CheckpointListCard(checkpoints: List<CheckpointEntity>, takenIds: Set<Int>) {
    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                checkpoints.forEachIndexed { index, cp ->
                    CheckpointRow(
                        cp = cp,
                        taken = cp.id in takenIds,
                        isLast = index == checkpoints.size - 1,
                    )
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

/**
 * Compose mapping from the pure [CheckpointColor] enum to its bar color. Kept here (a Compose file)
 * rather than in the Android-free `CheckpointColor.kt`. Green/orange reuse existing theme brand
 * colors; the rest come from the `CpColor*` palette. Same shade in light & dark — purely decorative.
 */
private fun CheckpointColor.barColor(tertiary: Color): Color = when (this) {
    CheckpointColor.RED -> CpColorRed
    CheckpointColor.BLUE -> CpColorBlue
    CheckpointColor.GREEN -> tertiary
    CheckpointColor.YELLOW -> CpColorYellow
    CheckpointColor.ORANGE -> OrangeCta
    CheckpointColor.PURPLE -> CpColorPurple
}

@Composable
private fun CheckpointRow(cp: CheckpointEntity, taken: Boolean, isLast: Boolean) {
    // `color` is race-scoped public data (present on open AND locked rows), so the bar shows even
    // before reveal. Neutral (`null` = `""`/unknown token) → transparent → the row looks as today.
    // The 4dp bar is a fixed gutter in every row so spacing is identical across colored/uncolored
    // rows; the inner rows drop 4dp of leading padding to keep text alignment pixel-identical.
    val barColor = parseCheckpointColor(cp.color)?.barColor(MaterialTheme.colorScheme.tertiary)
        ?: Color.Transparent

    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(barColor),
        )
        Column {
            // A locked CP keeps its plaintext on the server until an NFC scan reveals it, so the row
            // is masked: a lock chip + the bare КП number, with the description shown as skeleton bars
            // (screen A2b in docs/design — see `LockedLegendRow`) instead of any real text.
            if (cp.locked) {
                LockedCheckpointRow(cp)
            } else {
                OpenCheckpointRow(cp, taken = taken)
            }

            if (!isLast) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun OpenCheckpointRow(cp: CheckpointEntity, taken: Boolean) {
    val contentColor =
        if (taken) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // start padding is 16-4 = 12dp; the 4dp leading color bar makes up the difference so
            // text alignment is pixel-identical to the pre-color layout.
            .padding(start = 12.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "${cp.cost ?: 0}-${cp.number.toString().padStart(2, '0')}",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
            ),
            fontFamily = RobotoMono,
            color = contentColor,
            modifier = Modifier.widthIn(min = 48.dp),
        )
        Text(
            text = cp.description.orEmpty(),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.5.sp,
                fontWeight = if (taken) FontWeight.Normal else FontWeight.Medium,
                letterSpacing = 0.sp,
            ),
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (taken) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Взято",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun LockedCheckpointRow(cp: CheckpointEntity) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val skeleton = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    // No description to show, so the placeholder bars stand in for it. Widths are derived from the
    // CP id — stable across recompositions, but varied row-to-row so the masked list reads like real
    // text rather than a column of identical bars (the design varies them by the hidden name length).
    val firstBarFraction = 0.50f + ((cp.id * 17) % 44) / 100f
    val hasSecondBar = (cp.id * 13) % 3 == 0
    val secondBarFraction = 0.28f + ((cp.id * 29) % 26) / 100f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // start padding is 16-4 = 12dp; the 4dp leading color bar makes up the difference so
            // text alignment is pixel-identical to the pre-color layout.
            .padding(start = 12.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.widthIn(min = 48.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        RoundedCornerShape(5.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier.size(10.dp),
                )
            }
            Text(
                text = cp.number.toString().padStart(2, '0'),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.3.sp,
                ),
                fontFamily = RobotoMono,
                color = muted,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(firstBarFraction)
                    .height(9.dp)
                    .background(skeleton, RoundedCornerShape(5.dp)),
            )
            if (hasSecondBar) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(secondBarFraction)
                        .height(9.dp)
                        .background(skeleton, RoundedCornerShape(5.dp)),
                )
            }
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
