package ru.kolco24.kolco24

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import ru.kolco24.kolco24.data.db.TeamEntity
import ru.kolco24.kolco24.ui.legend.LegendScreen
import ru.kolco24.kolco24.ui.marks.MarksScreen
import ru.kolco24.kolco24.ui.scan.ScanScreen
import ru.kolco24.kolco24.ui.team.TeamScreen
import ru.kolco24.kolco24.ui.teampicker.CompPickerScreen
import ru.kolco24.kolco24.ui.teampicker.TeamPickerScreen
import ru.kolco24.kolco24.ui.teampicker.TeamSwitchSheet
import ru.kolco24.kolco24.ui.theme.Kolco24Theme
import ru.kolco24.kolco24.ui.theme.OrangeCta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Kolco24Theme {
                Kolco24AppRoot()
            }
        }
    }
}

/** Step of the team-selection overlay flow, layered over the tab Scaffold (no navigation library). */
private enum class TeamFlowStep { None, CompPicker, TeamPicker }

/** Load state of the currently-selected team's row, so the tab can tell "loading" from "disappeared". */
private sealed interface SelectedTeamState {
    data object None : SelectedTeamState
    data object Loading : SelectedTeamState
    data object Missing : SelectedTeamState
    data class Present(val team: TeamEntity) : SelectedTeamState
}

@Composable
private fun Kolco24AppRoot() {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    var showScan by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val container = remember { (context.applicationContext as Kolco24App).container }
    val raceRepo = container.raceRepository
    val teamRepo = container.teamRepository
    val today = todayIso()

    // Tab «Команда» data: which team is selected, its row, and the categories of its race.
    val races by raceRepo.races.collectAsState(initial = emptyList())
    val selectedTeam by teamRepo.selectedTeam.collectAsState(initial = null)
    val selectedRaceId = selectedTeam?.raceId
    val selectedTeamId = selectedTeam?.teamId

    val teamState by produceState<SelectedTeamState>(SelectedTeamState.Loading, selectedTeamId) {
        val id = selectedTeamId
        if (id == null) {
            value = SelectedTeamState.None
        } else {
            teamRepo.observeTeam(id).collect { team ->
                value = if (team == null) SelectedTeamState.Missing else SelectedTeamState.Present(team)
            }
        }
    }
    val teamForTab = (teamState as? SelectedTeamState.Present)?.team
    val teamMissing = teamState is SelectedTeamState.Missing

    val tabCategories by remember(selectedRaceId) {
        if (selectedRaceId != null) teamRepo.categoriesForRace(selectedRaceId) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val tabCategory = teamForTab?.let { t -> tabCategories.find { it.id == t.categoryId } }

    // Flow overlay state — survives recreation (enum is Serializable; nullable Int saves out of the box).
    var teamFlowStep by rememberSaveable { mutableStateOf(TeamFlowStep.None) }
    var pickerRaceId by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmTeamId by rememberSaveable { mutableStateOf<Int?>(null) }

    // Teams/categories of the race being browsed in the picker (and source for the confirm sheet).
    val pickerTeams by remember(pickerRaceId) {
        pickerRaceId?.let { teamRepo.teamsForRace(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val pickerCategories by remember(pickerRaceId) {
        pickerRaceId?.let { teamRepo.categoriesForRace(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val navItemColors = NavigationBarItemDefaults.colors(
        indicatorColor = Color.Transparent,
        selectedIconColor = OrangeCta,
        selectedTextColor = OrangeCta,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    val activePage = pagerState.targetPage
                    NavigationBarItem(
                        selected = activePage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                        icon = {
                            Icon(
                                if (activePage == 0) Icons.Filled.Flag else Icons.Outlined.Flag,
                                contentDescription = null,
                            )
                        },
                        label = { Text("Отметки") },
                        colors = navItemColors,
                    )
                    NavigationBarItem(
                        selected = activePage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        icon = {
                            Icon(
                                if (activePage == 1) Icons.Filled.Map else Icons.Outlined.Map,
                                contentDescription = null,
                            )
                        },
                        label = { Text("Легенда") },
                        colors = navItemColors,
                    )
                    NavigationBarItem(
                        selected = activePage == 2,
                        onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                        icon = {
                            Icon(
                                if (activePage == 2) Icons.Filled.Groups else Icons.Outlined.Groups,
                                contentDescription = null,
                            )
                        },
                        label = { Text("Команда") },
                        colors = navItemColors,
                    )
                }
            },
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                when (page) {
                    0 -> MarksScreen(
                        onScanClick = { showScan = true },
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    )
                    1 -> LegendScreen(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()))
                    2 -> TeamScreen(
                        team = teamForTab,
                        category = tabCategory,
                        onChooseTeam = { pickerRaceId = selectedRaceId; teamFlowStep = TeamFlowStep.CompPicker },
                        onChangeTeam = { pickerRaceId = selectedRaceId; teamFlowStep = TeamFlowStep.CompPicker },
                        teamMissing = teamMissing,
                        teamLoading = teamState is SelectedTeamState.Loading,
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    )
                }
            }
        }

        // Scan overlay (registered first so the team-flow handler below takes priority when active).
        BackHandler(enabled = showScan) { showScan = false }
        if (showScan) {
            ScanScreen(onClose = { showScan = false }, modifier = Modifier.fillMaxSize())
        }

        // Team-selection flow overlays. Back steps down: sheet (own dismiss) > TeamPicker > CompPicker.
        BackHandler(enabled = teamFlowStep != TeamFlowStep.None && confirmTeamId == null) {
            teamFlowStep = when (teamFlowStep) {
                TeamFlowStep.TeamPicker -> TeamFlowStep.CompPicker
                else -> TeamFlowStep.None
            }
        }

        if (teamFlowStep == TeamFlowStep.CompPicker) {
            CompPickerScreen(
                races = races,
                today = today,
                selectedRaceId = selectedRaceId,
                onBack = { teamFlowStep = TeamFlowStep.None },
                onRaceSelected = { raceId ->
                    pickerRaceId = raceId
                    teamFlowStep = TeamFlowStep.TeamPicker
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        val activePickerRaceId = pickerRaceId
        if (teamFlowStep == TeamFlowStep.TeamPicker && activePickerRaceId != null) {
            TeamPickerScreen(
                raceId = activePickerRaceId,
                race = races.find { it.id == activePickerRaceId },
                teams = pickerTeams,
                categories = pickerCategories,
                selectedTeamId = selectedTeamId,
                onRefresh = teamRepo::refreshTeams,
                onBack = { teamFlowStep = TeamFlowStep.CompPicker },
                onChangeRace = { teamFlowStep = TeamFlowStep.CompPicker },
                onTeamTapped = { confirmTeamId = it },
                modifier = Modifier.fillMaxSize(),
            )

            val confirmTeam = confirmTeamId?.let { id -> pickerTeams.find { it.id == id } }
            if (confirmTeam != null) {
                TeamSwitchSheet(
                    team = confirmTeam,
                    category = pickerCategories.find { it.id == confirmTeam.categoryId },
                    onConfirm = {
                        container.applicationScope.launch {
                            teamRepo.selectTeam(activePickerRaceId, confirmTeam.id)
                        }
                        confirmTeamId = null
                        teamFlowStep = TeamFlowStep.None
                    },
                    onDismiss = { confirmTeamId = null },
                )
            }
        }
    }
}

/** Today as a `YYYY-MM-DD` string (no `java.time` — minSdk 24 without core library desugaring). */
private fun todayIso(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
