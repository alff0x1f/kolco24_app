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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.data.pluralRu
import ru.kolco24.kolco24.ui.common.RefreshableList
import ru.kolco24.kolco24.ui.theme.CpColorBlue
import ru.kolco24.kolco24.ui.theme.CpColorPurple
import ru.kolco24.kolco24.ui.theme.CpColorRed
import ru.kolco24.kolco24.ui.theme.CpColorYellow
import ru.kolco24.kolco24.ui.theme.OrangeCta
import ru.kolco24.kolco24.ui.theme.Tertiary
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
    totalScore: Int = 0,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
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
            // No team → no race to pull, so the gesture isn't offered on this empty state.
            !hasTeam -> LegendNoTeam(onChooseTeam = onChooseTeam)
            else -> RefreshableList(isRefreshing = isRefreshing, onRefresh = onRefresh) {
                LegendList(checkpoints = checkpoints, takenIds = takenIds, totalScore = totalScore)
            }
        }
    }
}

@Composable
private fun LegendList(checkpoints: List<CheckpointEntity>, takenIds: Set<Int>, totalScore: Int) {
    var showOnlyOpen by rememberSaveable { mutableStateOf(false) }

    val visible = if (showOnlyOpen) checkpoints.filter { it.id !in takenIds } else checkpoints

    val takenCount = checkpoints.count { it.id in takenIds }
    val totalCount = checkpoints.size
    // The numerator sums known costs of taken CPs (taking a locked CP reveals its cost). The
    // denominator [totalScore] is the server's `total_cost` — sum of ALL CP costs, incl. locked
    // ones whose individual cost is hidden — so the bar can't fill to 100% before locked CPs are
    // taken. Locked-unrevealed CPs are surfaced as a «+N закрытых КП» hint via [lockedCount].
    val takenScore = checkpoints.filter { it.id in takenIds }.mapNotNull { it.cost }.sum()
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
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // One baseline-aligned row: score + «/ N баллов» on the left, «N/M КП» pushed right.
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "$takenScore",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp,
                    ),
                    // Mono digits (tabular by construction) keep the score from shifting as it grows.
                    fontFamily = RobotoMono,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "/ $totalScore ${pluralRu(totalScore, "балл", "балла", "баллов")}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = muted,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "$takenCount/$totalCount КП",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    fontFamily = RobotoMono,
                    color = muted,
                    modifier = Modifier.alignByBaseline(),
                )
            }

            Spacer(Modifier.height(9.dp))

            ProgressBar(progress = progress)
        }
    }
}

/**
 * Score progress bar — a 6dp rounded grey track with a two-stop green gradient fill. Custom (not
 * [LinearProgressIndicator]) so the fill can be a horizontal gradient and the height/track color
 * match the spec exactly. [fraction] is `takenScore / totalScore`; at 0 the fill collapses and only
 * the track shows. Both ends stay rounded (3dp radius), per design.
 */
@Composable
private fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val fillGradient = Brush.horizontalGradient(listOf(Color(0xFF1F7A3D), Color(0xFF2FA055)))

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            // Grey trough: rgba(60, 60, 67, 0.10).
            .background(Color(0x1A3C3C43)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(3.dp))
                .background(fillGradient),
        )
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
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val coreBrush = Brush.linearGradient(
        if (isDarkTheme) {
            listOf(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.colorScheme.surfaceContainer,
            )
        } else {
            listOf(Color(0xFF1D242D), Color(0xFF2A333E))
        },
    )
    val glow = if (isDarkTheme) MaterialTheme.colorScheme.primary else Color(0xFFC3011C)
    val contentColor = if (isDarkTheme) MaterialTheme.colorScheme.onSurface else Color.White
    val iconContainerColor =
        if (isDarkTheme) MaterialTheme.colorScheme.surfaceContainerHighest
        else Color.White.copy(alpha = 0.07f)
    val borderColor =
        if (isDarkTheme) MaterialTheme.colorScheme.outlineVariant
        else Color.White.copy(alpha = 0.14f)

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
                            colors = listOf(
                                glow.copy(alpha = if (isDarkTheme) 0.18f else 0.5f),
                                glow.copy(alpha = 0f),
                            ),
                            center = Offset(size.width, 0f),
                            radius = 200f,
                        ),
                        center = Offset(size.width, 0f),
                        radius = 200f,
                    )
                }
                .border(1.dp, borderColor, MaterialTheme.shapes.large)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .drawBehind {
                        drawCircle(color = iconContainerColor)
                    }
                    .border(1.dp, borderColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(28.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Скрыто $lockedCount КП",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.sp,
                    ),
                    color = contentColor,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Стоимость и описания КП появятся позже",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = contentColor.copy(alpha = 0.68f),
                )
            }
        }
    }
}

@Composable
private fun CheckpointListCard(checkpoints: List<CheckpointEntity>, takenIds: Set<Int>) {
    val groups = groupCheckpointsByColor(checkpoints)

    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        groups.forEachIndexed { index, group ->
            CheckpointGroupCard(
                group = group,
                takenIds = takenIds,
                shape = checkpointGroupShape(index, groups.size),
            )
            if (index != groups.lastIndex) Spacer(Modifier.height(CheckpointGroupGap))
        }

        Text(
            text = "Слева — стоимость и номер КП. Галочка = отметка засчитана.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Splits the ordered checkpoint list into contiguous runs that share the same color, mirroring the
 * grouped cards in `docs/design/legend.png`: КП of one color form a single card with one continuous
 * left color bar. Grouping is by the **parsed** color, so `""`/unknown tokens all fold into one
 * neutral run (`null == null`) and render exactly like the previous single uncolored card. The input
 * order (number, id — set by [CheckpointDao]) is preserved; a color that recurs in two separate runs
 * stays two cards, which is the intent (КП are laid out in route order, not sorted by color).
 */
internal fun groupCheckpointsByColor(
    checkpoints: List<CheckpointEntity>,
): List<List<CheckpointEntity>> {
    val groups = mutableListOf<MutableList<CheckpointEntity>>()
    for (cp in checkpoints) {
        val color = parseCheckpointColor(cp.color)
        val current = groups.lastOrNull()
        if (current != null && parseCheckpointColor(current.first().color) == color) {
            current.add(cp)
        } else {
            groups.add(mutableListOf(cp))
        }
    }
    return groups
}

/** Outer corner radius — kept at [shapes.large] (16dp) so the КП list block stays as friendly as the
 * hero cards above it. Used only on the list's *outermost* corners (top of the first group, bottom of
 * the last). */
private val CheckpointOuterRadius = 16.dp

/** Inner corner radius — the seams *between* adjacent color groups. Tight so the stack reads as one
 * rounded block (Gmail's grouped-tile look) instead of a column of fat touching pills. */
private val CheckpointInnerRadius = 4.dp

/** Hairline gap between groups — a sliver of background keeps each color run a distinct tile while the
 * graduated radius binds them into one block. Much tighter than a normal card gap on purpose. */
private val CheckpointGroupGap = 2.dp

/**
 * Per-position corner shape for a color group at [index] of [count]: the list's outermost corners
 * (top of the first group, bottom of the last) keep the large [CheckpointOuterRadius]; every internal
 * seam uses the tight [CheckpointInnerRadius]. A lone group ([count] == 1) is both first and last, so
 * all four corners stay large.
 */
private fun checkpointGroupShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) CheckpointOuterRadius else CheckpointInnerRadius
    val bottom = if (index == count - 1) CheckpointOuterRadius else CheckpointInnerRadius
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/**
 * One color group from [groupCheckpointsByColor]: a rounded card (per-position [shape]). The group's
 * discipline color is carried two ways — a **floating rounded capsule** down the left (inset top/
 * bottom so it reads as an intentional marker, not a flush rule) plus a **soft left-to-right color
 * wash** that fades out before the descriptions, a gentle echo of the color-fill tiles on the
 * «Отметки» screen so the two screens share one color language. A neutral group (`null` — `""`/unknown
 * token) gets neither: the 4dp capsule cell stays an empty spacer, so an uncolored group looks like the
 * prior card. Rows keep their 12dp start padding (12 + 4dp cell = 16dp) and the 72dp divider inset, so
 * per-row text alignment is pixel-identical to the pre-grouping layout.
 */
@Composable
private fun CheckpointGroupCard(
    group: List<CheckpointEntity>,
    takenIds: Set<Int>,
    shape: RoundedCornerShape,
) {
    val color = parseCheckpointColor(group.first().color)?.barColor()
    // Read the *applied* surface (respects the manual Light/Dark override) rather than
    // isSystemInDarkTheme(). The two themes need different physics: the light card is pure white
    // (#FFFFFF) on a grey background, so it only "lifts" by being brighter — any wash darkens it and
    // the tile stops standing out, so light gets no wash (the capsule alone carries the color). Dark
    // is the opposite — a glow *adds* light to the dark surface and reads great, so it keeps the wash.
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val washAlpha = if (darkTheme) 0.16f else 0f

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .drawBehind {
                    if (color != null && washAlpha > 0f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(color.copy(alpha = washAlpha), Color.Transparent),
                                startX = 0f,
                                endX = 120.dp.toPx(),
                            ),
                        )
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(4.dp)
                    .fillMaxHeight()
                    .then(
                        if (color != null) {
                            Modifier.clip(RoundedCornerShape(2.dp)).background(color)
                        } else {
                            Modifier
                        },
                    ),
            )
            Column(modifier = Modifier.weight(1f)) {
                group.forEachIndexed { index, cp ->
                    if (cp.locked) {
                        LockedCheckpointRow(cp)
                    } else {
                        OpenCheckpointRow(cp, taken = cp.id in takenIds)
                    }

                    if (index != group.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val backgroundColor = when {
        selected && isDarkTheme -> MaterialTheme.colorScheme.surfaceContainerHighest
        selected -> MaterialTheme.colorScheme.onSurface
        else -> Color.Transparent
    }
    val contentColor = when {
        selected && isDarkTheme -> MaterialTheme.colorScheme.onSurface
        selected -> MaterialTheme.colorScheme.inverseOnSurface
        else -> MaterialTheme.colorScheme.onSurface
    }
    val border = when {
        selected && isDarkTheme -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        selected -> null
        else -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
    }

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
 * rather than in the Android-free `CheckpointColor.kt`. Green/orange reuse existing brand colors
 * ([Tertiary]/[OrangeCta]); the rest come from the `CpColor*` palette. Fixed shades — same in
 * light & dark — purely decorative.
 */
private fun CheckpointColor.barColor(): Color = when (this) {
    CheckpointColor.RED -> CpColorRed
    CheckpointColor.BLUE -> CpColorBlue
    CheckpointColor.GREEN -> Tertiary
    CheckpointColor.YELLOW -> CpColorYellow
    CheckpointColor.ORANGE -> OrangeCta
    CheckpointColor.PURPLE -> CpColorPurple
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
            text = if (cp.cost == 0) {
                cp.number.toString().padStart(2, '0')
            } else {
                "${cp.cost ?: 0}-${cp.number.toString().padStart(2, '0')}"
            },
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
    val firstBarFraction = 0.50f + Math.floorMod(cp.id * 17, 44) / 100f
    val hasSecondBar = Math.floorMod(cp.id * 13, 3) == 0
    val secondBarFraction = 0.28f + Math.floorMod(cp.id * 29, 26) / 100f

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
