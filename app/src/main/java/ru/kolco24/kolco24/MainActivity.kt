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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import ru.kolco24.kolco24.data.db.TeamEntity
import ru.kolco24.kolco24.data.todayIso
import ru.kolco24.kolco24.ui.legend.LegendScreen
import ru.kolco24.kolco24.ui.marks.MarksScreen
import ru.kolco24.kolco24.ui.scan.ScanScreen
import ru.kolco24.kolco24.ui.settings.SettingsScreen
import ru.kolco24.kolco24.ui.team.TeamScreen
import ru.kolco24.kolco24.ui.teampicker.CompPickerScreen
import ru.kolco24.kolco24.ui.teampicker.TeamPickerScreen
import ru.kolco24.kolco24.ui.teampicker.TeamSwitchSheet
import ru.kolco24.kolco24.ui.theme.Kolco24Theme
import ru.kolco24.kolco24.ui.theme.OrangeCta

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

private data class PickerTeamsState(
    val raceId: Int? = null,
    val teams: List<TeamEntity> = emptyList(),
    val loaded: Boolean = false,
)

@Composable
private fun Kolco24AppRoot() {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    var showScan by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val container = remember { (context.applicationContext as Kolco24App).container }
    val raceRepo = container.raceRepository
    val teamRepo = container.teamRepository
    val legendRepo = container.legendRepository
    val today = todayIso()

    // Tab «Команда» data: which team is selected, its row, and the categories of its race.
    val races by raceRepo.races.collectAsState(initial = emptyList())
    val selectedTeam by teamRepo.selectedTeam.collectAsState(initial = null)
    val selectedRaceId = selectedTeam?.raceId
    val selectedTeamId = selectedTeam?.teamId

    val teamState by produceState<SelectedTeamState>(SelectedTeamState.Loading) {
        teamRepo.selectedTeam.collectLatest { selection ->
            val teamId = selection?.teamId
            if (teamId == null) {
                value = SelectedTeamState.None
            } else {
                value = SelectedTeamState.Loading
                teamRepo.observeTeam(teamId).collect { team ->
                    value = if (team == null) SelectedTeamState.Missing else SelectedTeamState.Present(team)
                }
            }
        }
    }
    val teamForTab = (teamState as? SelectedTeamState.Present)?.team
    val teamMissing = teamState is SelectedTeamState.Missing

    val tabCategories by remember(selectedRaceId) {
        if (selectedRaceId != null) teamRepo.categoriesForRace(selectedRaceId) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val tabCategory = teamForTab?.let { t -> tabCategories.find { it.id == t.categoryId } }

    // Tab «Легенда» data: the selected team's race checkpoints (offline-readable from Room).
    val legendCheckpoints by remember(selectedRaceId) {
        selectedRaceId?.let { legendRepo.checkpointsForRace(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    // Flow overlay state — survives recreation (enum is Serializable; nullable Int saves out of the box).
    var teamFlowStep by rememberSaveable { mutableStateOf(TeamFlowStep.None) }
    var pickerRaceId by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmTeamId by rememberSaveable { mutableStateOf<Int?>(null) }

    // Teams/categories of the race being browsed in the picker (and source for the confirm sheet).
    val pickerTeamsState by produceState(PickerTeamsState(), pickerRaceId) {
        val raceId = pickerRaceId
        if (raceId == null) {
            value = PickerTeamsState()
        } else {
            value = PickerTeamsState(raceId = raceId)
            teamRepo.teamsForRace(raceId).collect { teams ->
                value = PickerTeamsState(raceId = raceId, teams = teams, loaded = true)
            }
        }
    }
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
                        onScanClick = { teamFlowStep = TeamFlowStep.None; confirmTeamId = null; showSettings = false; showScan = true },
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    )
                    1 -> LegendScreen(
                        checkpoints = legendCheckpoints,
                        hasTeam = teamState !is SelectedTeamState.None,
                        onChooseTeam = { pickerRaceId = null; teamFlowStep = TeamFlowStep.CompPicker },
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    )
                    2 -> TeamScreen(
                        team = teamForTab,
                        category = tabCategory,
                        onChooseTeam = { pickerRaceId = selectedRaceId; teamFlowStep = TeamFlowStep.CompPicker },
                        onOpenSettings = { showSettings = true },
                        teamMissing = teamMissing,
                        teamLoading = teamState is SelectedTeamState.Loading,
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    )
                }
            }
        }

        // Scan overlay. Settings and team-flow handlers are registered after this one, so without the
        // !showScan guards on both of them they would win (Compose gives priority to the last registered
        // enabled BackHandler). Their guards ensure scan's back press is never masked when co-active.
        BackHandler(enabled = showScan) { showScan = false; showSettings = false }
        if (showScan) {
            ScanScreen(onClose = { showScan = false; showSettings = false }, modifier = Modifier.fillMaxSize())
        }

        // Settings overlay — sits beneath the picker/scan overlays (rendered before them, so they draw
        // on top when both are active). Its BackHandler only fires when nothing else is layered above it.
        BackHandler(
            enabled = showSettings && teamFlowStep == TeamFlowStep.None && confirmTeamId == null && !showScan,
        ) { showSettings = false }
        if (showSettings && teamFlowStep == TeamFlowStep.None && confirmTeamId == null && !showScan) {
            SettingsScreen(
                onBack = { showSettings = false },
                onChangeTeam = {
                    showSettings = false
                    confirmTeamId = null
                    pickerRaceId = selectedRaceId
                    teamFlowStep = TeamFlowStep.CompPicker
                },
                onResetTeam = if (BuildConfig.DEBUG) {
                    {
                        // applicationScope (not composition scope) so the delete outlives the
                        // closing overlay — same reasoning as selectTeam below.
                        container.applicationScope.launch { teamRepo.clearSelectedTeam() }
                        showSettings = false
                    }
                } else {
                    null
                },
                onClearDatabase = if (BuildConfig.DEBUG) {
                    {
                        container.applicationScope.launch { container.clearDatabase() }
                        showSettings = false
                    }
                } else {
                    null
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Team-selection flow overlays. Back steps down: sheet (own dismiss) > TeamPicker > CompPicker.
        // Guard !showScan so scan overlay's BackHandler is never masked by the team-flow handler
        // (can co-exist after process-death restore when both states are independently non-default).
        BackHandler(enabled = teamFlowStep != TeamFlowStep.None && confirmTeamId == null && !showScan) {
            teamFlowStep = when (teamFlowStep) {
                TeamFlowStep.TeamPicker -> TeamFlowStep.CompPicker
                else -> {
                    // Also clear showSettings so that a process-death restore of both
                    // showSettings=true + teamFlowStep!=None doesn't resurface Settings
                    // after the user backs all the way out of the picker.
                    showSettings = false
                    TeamFlowStep.None
                }
            }
        }

        if (teamFlowStep == TeamFlowStep.CompPicker) {
            CompPickerScreen(
                races = races,
                today = today,
                selectedRaceId = selectedRaceId,
                onBack = { showSettings = false; teamFlowStep = TeamFlowStep.None },
                onRaceSelected = { raceId ->
                    // Warm Room ahead of the screen transition so the team list is ready when the
                    // picker opens. Use applicationScope so it outlives the closing comp picker.
                    // A duplicate GET with TeamPickerScreen's own onRefresh is accepted (idempotent).
                    container.applicationScope.launch { teamRepo.refreshTeams(raceId) }
                    container.applicationScope.launch { legendRepo.refreshLegend(raceId) }
                    confirmTeamId = null
                    pickerRaceId = raceId
                    teamFlowStep = TeamFlowStep.TeamPicker
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        val activePickerRaceId = pickerRaceId
        if (teamFlowStep == TeamFlowStep.TeamPicker && activePickerRaceId != null) {
            val activePickerTeams = remember(activePickerRaceId, pickerTeamsState) {
                if (pickerTeamsState.raceId == activePickerRaceId) {
                    pickerTeamsState.teams.filter { it.raceId == activePickerRaceId }
                } else {
                    emptyList()
                }
            }
            val activePickerTeamsLoaded =
                pickerTeamsState.raceId == activePickerRaceId && pickerTeamsState.loaded
            val activePickerCategories = remember(activePickerRaceId, pickerCategories) {
                pickerCategories.filter { it.raceId == activePickerRaceId }
            }

            TeamPickerScreen(
                raceId = activePickerRaceId,
                race = races.find { it.id == activePickerRaceId },
                teams = activePickerTeams,
                teamsLoaded = activePickerTeamsLoaded,
                categories = activePickerCategories,
                selectedTeamId = selectedTeamId,
                onRefresh = teamRepo::refreshTeams,
                onBack = { confirmTeamId = null; teamFlowStep = TeamFlowStep.CompPicker },
                onChangeRace = { confirmTeamId = null; teamFlowStep = TeamFlowStep.CompPicker },
                onTeamTapped = { confirmTeamId = it },
                modifier = Modifier.fillMaxSize(),
            )

            val confirmTeam = confirmTeamId?.let { id -> activePickerTeams.find { it.id == id } }
            if (confirmTeam != null) {
                TeamSwitchSheet(
                    team = confirmTeam,
                    category = activePickerCategories.find { it.id == confirmTeam.categoryId },
                    onConfirm = {
                        container.applicationScope.launch {
                            teamRepo.selectTeam(activePickerRaceId, confirmTeam.id)
                        }
                        confirmTeamId = null
                        showSettings = false
                        teamFlowStep = TeamFlowStep.None
                    },
                    onDismiss = { confirmTeamId = null },
                )
            }
        }
    }
}
