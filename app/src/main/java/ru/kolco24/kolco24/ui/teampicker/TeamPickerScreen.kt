package ru.kolco24.kolco24.ui.teampicker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.kolco24.kolco24.data.RefreshResult
import ru.kolco24.kolco24.data.db.CategoryEntity
import ru.kolco24.kolco24.data.db.RaceEntity
import ru.kolco24.kolco24.data.db.TeamEntity
import ru.kolco24.kolco24.ui.theme.RobotoMono

/** Lowercase 3-letter Russian month abbreviations, indexed by month number 1..12. */
private val MONTH_ABBR_LOWER = listOf(
    "янв", "фев", "мар", "апр", "май", "июн",
    "июл", "авг", "сен", "окт", "ноя", "дек",
)

/** Outcome of the in-screen `refreshTeams` call, used to pick the loading/error UI. */
private enum class PickerLoad { Loading, Loaded, Offline, HttpError, Forbidden }

/**
 * Screen 04c — team picker (step 2 of choosing a team). Shows the registered teams of one race
 * (cached from Room, refreshed on entry) with name/number search and a competition-context card.
 * Tapping a team raises [onTeamTapped] so the host can show the confirmation sheet ([TeamSwitchSheet]).
 *
 * Cached rows render immediately; [onRefresh] runs in the composition scope (a read-only refresh,
 * safe to cancel on exit). The empty/error states follow the plan: a full placeholder + retry when
 * the cache is empty and offline, a snackbar when a stale cache is shown, "обновите приложение" on
 * `Forbidden`, and "никто не зарегистрирован" on an empty successful list. Filtering is the
 * unit-tested [filterTeams].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamPickerScreen(
    raceId: Int,
    race: RaceEntity?,
    teams: List<TeamEntity>,
    categories: List<CategoryEntity>,
    selectedTeamId: Int?,
    onRefresh: suspend (Int) -> RefreshResult,
    onBack: () -> Unit,
    onChangeRace: () -> Unit,
    onTeamTapped: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable(raceId) { mutableStateOf("") }
    var retryKey by rememberSaveable(raceId) { mutableIntStateOf(0) }
    var load by rememberSaveable(raceId) { mutableStateOf(PickerLoad.Loading) }
    val snackbarHostState = remember(raceId) { SnackbarHostState() }

    LaunchedEffect(raceId, retryKey) {
        load = PickerLoad.Loading
        load = when (onRefresh(raceId)) {
            RefreshResult.Updated, RefreshResult.NotModified -> PickerLoad.Loaded
            RefreshResult.Offline -> PickerLoad.Offline
            RefreshResult.Forbidden -> PickerLoad.Forbidden
            is RefreshResult.HttpError -> PickerLoad.HttpError
        }
    }

    // Stale cache: show what we have and warn via snackbar instead of blocking the list.
    // Guard with a flag so repeated failures (e.g. failed retry) don't re-show the snackbar.
    val staleCache = teams.isNotEmpty() && (load == PickerLoad.Offline || load == PickerLoad.HttpError)
    var staleCacheSnackbarShown by rememberSaveable(raceId) { mutableStateOf(false) }
    LaunchedEffect(staleCache) {
        if (staleCache && !staleCacheSnackbarShown) {
            staleCacheSnackbarShown = true
            snackbarHostState.showSnackbar("Нет сети, показан сохранённый список")
        }
    }
    val forbiddenWithCache = teams.isNotEmpty() && load == PickerLoad.Forbidden
    var forbiddenSnackbarShown by rememberSaveable(raceId) { mutableStateOf(false) }
    LaunchedEffect(forbiddenWithCache) {
        if (forbiddenWithCache && !forbiddenSnackbarShown) {
            forbiddenSnackbarShown = true
            snackbarHostState.showSnackbar("Требуется обновление приложения")
        }
    }

    val categoryById = remember(categories) { categories.associateBy { it.id } }
    val filtered = remember(teams, query) { filterTeams(teams, query) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(if (selectedTeamId != null) "Сменить команду" else "Выбор команды") },
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            load == PickerLoad.Forbidden && teams.isEmpty() ->
                PickerPlaceholder(
                    modifier = Modifier.padding(padding),
                    title = "Обновите приложение",
                    message = "Текущая версия больше не поддерживается сервером.",
                )

            teams.isEmpty() && (load == PickerLoad.Offline || load == PickerLoad.HttpError) ->
                PickerPlaceholder(
                    modifier = Modifier.padding(padding),
                    title = "Не удалось загрузить команды",
                    message = "Проверьте соединение и попробуйте ещё раз.",
                    retryLabel = "Повторить",
                    onRetry = { retryKey++ },
                )

            teams.isEmpty() && load == PickerLoad.Loading ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item("comp") {
                    CompContextCard(race = race, onChangeRace = onChangeRace)
                }

                if (teams.isEmpty()) {
                    item("empty") {
                        Text(
                            text = "Пока никто не зарегистрирован",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    item("search") {
                        TeamSearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    item("header") {
                        Text(
                            text = "Зарегистрированные · ${teams.size}",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item("teams") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            if (filtered.isEmpty()) {
                                Text(
                                    text = "Ничего не найдено",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Column {
                                    filtered.forEachIndexed { index, team ->
                                        TeamPickRow(
                                            team = team,
                                            category = categoryById[team.categoryId],
                                            isCurrent = team.id == selectedTeamId,
                                            isLast = index == filtered.lastIndex,
                                            onClick = { onTeamTapped(team.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item("hint") {
                        Text(
                            text = "Выбор определяет, чьи NFC-чипы засчитываются на КП.",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Competition context card with race name, date · place and a text "Изменить" action. */
@Composable
private fun CompContextCard(race: RaceEntity?, onChangeRace: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0xFF1D242D), MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Flag,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = race?.name ?: "—",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (race != null) {
                    Text(
                        text = "${shortDate(race.date)} · ${race.place}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            TextButton(onClick = onChangeRace) {
                Text(
                    text = "Изменить",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TeamSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("Название или номер команды") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun TeamPickRow(
    team: TeamEntity,
    category: CategoryEntity?,
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
            TeamToken(text = teamToken(team))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = displayTeamName(team),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isCurrent) {
                        CurrentTeamBadge()
                    }
                }
                Text(
                    text = categoryLine(category, team.ucount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!isLast) {
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(start = 68.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun CurrentTeamBadge() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
    ) {
        Text(
            text = "ТЕКУЩАЯ",
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

/** Full-screen placeholder for the blocking error states (no cache to fall back on). */
@Composable
private fun PickerPlaceholder(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (retryLabel != null && onRetry != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onRetry) {
                Text(retryLabel)
            }
        }
    }
}

/** Grey squircle token with the monospace start number (or monogram). Shared with [TeamSwitchSheet]. */
@Composable
internal fun TeamToken(text: String, size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFFE2E6EB), Color(0xFFC5CCD5)),
                ),
                shape = MaterialTheme.shapes.medium,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = RobotoMono,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.35f).sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** "Категория X · N чел." line for a team row; `чел.` form. */
internal fun categoryLine(category: CategoryEntity?, ucount: Int): String {
    val cat = category?.shortName?.takeIf { it.isNotBlank() } ?: category?.name?.takeIf { it.isNotBlank() }
    return if (cat != null) "Категория $cat · $ucount чел." else "$ucount чел."
}

/** "10 окт" short date from a `YYYY-MM-DD` string (string slicing — no `java.time`). */
private fun shortDate(date: String): String {
    val parts = date.split("-")
    val month = parts.getOrNull(1)?.toIntOrNull()
    val day = parts.getOrNull(2)?.trimStart('0')?.ifEmpty { "0" } ?: return date
    val abbr = month?.let { MONTH_ABBR_LOWER.getOrNull(it - 1) } ?: return date
    return "$day $abbr"
}
