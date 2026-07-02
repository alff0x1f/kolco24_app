package ru.kolco24.kolco24.ui.teampicker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.kolco24.kolco24.data.db.RaceEntity
import ru.kolco24.kolco24.ui.common.RefreshableList
import ru.kolco24.kolco24.ui.theme.RobotoMono

/** Amber month label inside the charcoal calendar token (matches the mock's `AMBER`). */
private val TokenAmber = Color(0xFFF2B36B)

/** Russian 3-letter uppercase month abbreviations, indexed by month number 1..12. */
private val MONTH_ABBR = listOf(
    "ЯНВ", "ФЕВ", "МАР", "АПР", "МАЙ", "ИЮН",
    "ИЮЛ", "АВГ", "СЕН", "ОКТ", "НОЯ", "ДЕК",
)

/**
 * Screen 04b — race picker (step 1 of choosing a team). Shows the races from Room split into
 * current / archive tabs; tapping a row opens its team list via [onRaceSelected]. Pure UI —
 * the split and status rules live in [TeamPickerLogic] and are unit-tested there.
 *
 * Races are refreshed on open ([onRefresh] in a one-shot [LaunchedEffect]) and via pull-to-refresh,
 * so a picker opened with an empty/stale cache (e.g. the app cold-started offline) self-heals once
 * connectivity returns — without restarting the app. [refreshRaces] is no-arg and works with no team
 * selected, so this is wired in the host directly rather than through the raceId-bound `pullRefresh`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompPickerScreen(
    races: List<RaceEntity>,
    today: String,
    selectedRaceId: Int?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onRaceSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showArchive by rememberSaveable { mutableStateOf(false) }

    // Auto-refresh once when the picker opens (the screen is composed fresh each open, behind an `if`
    // in the host). refreshRaces() is ETag-guarded — a no-op 304 when nothing changed — so this is cheap.
    LaunchedEffect(Unit) { onRefresh() }

    val split = splitRaces(races, today)
    val list = if (showArchive) split.archive else split.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        TopAppBar(
            title = { Text("Соревнование") },
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

        RefreshableList(isRefreshing = isRefreshing, onRefresh = onRefresh) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item("chips") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .padding(top = 6.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CompFilterChip(
                            selected = !showArchive,
                            onClick = { showArchive = false },
                            label = "Актуальные · ${split.current.size}",
                        )
                        CompFilterChip(
                            selected = showArchive,
                            onClick = { showArchive = true },
                            label = "Архив · ${split.archive.size}",
                        )
                    }
                }

                item("list") {
                    CompListCard(
                        races = list,
                        today = today,
                        selectedRaceId = selectedRaceId,
                        onRaceSelected = onRaceSelected,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    // Selected fill is a charcoal pill in light theme; onSurface would invert to a glaring near-white
    // in dark, so use the neutral elevated grey there instead (mirrors the admin/settings avatars).
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val backgroundColor = when {
        !selected -> Color.Transparent
        darkTheme -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.onSurface
    }
    val contentColor = when {
        !selected -> MaterialTheme.colorScheme.onSurface
        darkTheme -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.inverseOnSurface
    }
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
private fun CompListCard(
    races: List<RaceEntity>,
    today: String,
    selectedRaceId: Int?,
    onRaceSelected: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            if (races.isEmpty()) {
                Text(
                    text = "Здесь пока пусто",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    races.forEachIndexed { index, race ->
                        CompRow(
                            race = race,
                            today = today,
                            isCurrent = race.id == selectedRaceId,
                            isLast = index == races.lastIndex,
                            onClick = { onRaceSelected(race.id) },
                        )
                    }
                }
            }
        }

        Text(
            text = "Выберите соревнование — откроется список его команд.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompRow(
    race: RaceEntity,
    today: String,
    isCurrent: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DateToken(date = race.date)

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = race.name,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isCurrent) {
                        CurrentBadge()
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    StatusPill(pill = raceStatusPill(race, today))
                    Text(
                        text = race.place,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!isLast) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 72.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** Charcoal calendar token: amber month abbreviation over the day, both monospace. */
@Composable
private fun DateToken(date: String) {
    val (month, day) = monthDay(date)
    // Resolve the *applied* theme (not isSystemInDarkTheme) so a manual override matches — mirrors MarksScreen.
    // The light-theme charcoal blends into the dark surface, so lift it to an elevated cool grey in dark.
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val tokenColors = if (darkTheme) {
        listOf(Color(0xFF39414B), Color(0xFF2A313A))
    } else {
        listOf(Color(0xFF1D242D), Color(0xFF2A323C))
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(
                brush = Brush.linearGradient(colors = tokenColors),
                shape = MaterialTheme.shapes.medium,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = month,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.5.sp,
                    letterSpacing = 0.8.sp,
                ),
                fontFamily = RobotoMono,
                color = TokenAmber,
            )
            Text(
                text = day,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp,
                    letterSpacing = 0.sp,
                ),
                fontFamily = RobotoMono,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun StatusPill(pill: RaceStatusPill) {
    val isRegistration = pill == RaceStatusPill.Registration
    val fg = if (isRegistration) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    val bg = if (isRegistration) {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
    }
    Surface(shape = MaterialTheme.shapes.small, color = bg) {
        Text(
            text = pill.label.uppercase(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
            ),
            fontFamily = RobotoMono,
            color = fg,
        )
    }
}

@Composable
private fun CurrentBadge() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
    ) {
        Text(
            text = "ТЕКУЩЕЕ",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
            ),
            fontFamily = RobotoMono,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

/**
 * Splits a `YYYY-MM-DD` date into a Russian month abbreviation and the day number. Done by string
 * slicing (no `java.time` — minSdk 24 without desugaring). Malformed input falls back gracefully.
 */
private fun monthDay(date: String): Pair<String, String> {
    val parts = date.split("-")
    val month = parts.getOrNull(1)?.toIntOrNull()
    val day = parts.getOrNull(2)?.trimStart('0')?.ifEmpty { "0" } ?: ""
    val abbr = month?.let { MONTH_ABBR.getOrNull(it - 1) } ?: ""
    return abbr to day
}
