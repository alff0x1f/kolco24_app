package ru.kolco24.kolco24

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import ru.kolco24.kolco24.data.RefreshResult
import ru.kolco24.kolco24.data.UnlockOutcome
import ru.kolco24.kolco24.data.nfc.ChipWriteResult
import ru.kolco24.kolco24.data.nfc.chipCodeFromHex
import ru.kolco24.kolco24.data.nfc.chipCodeFromNdef
import ru.kolco24.kolco24.data.nfc.chipCodeHex
import ru.kolco24.kolco24.data.nfc.newChipCode
import ru.kolco24.kolco24.data.nfc.readChipCode
import ru.kolco24.kolco24.data.nfc.writeChipCode
import ru.kolco24.kolco24.data.nfc.writeChipCodeNdef
import ru.kolco24.kolco24.data.db.TeamEntity
import ru.kolco24.kolco24.data.normalizeNfcUid
import ru.kolco24.kolco24.data.takenPoints
import ru.kolco24.kolco24.data.todayIso
import ru.kolco24.kolco24.ui.legend.LegendScreen
import ru.kolco24.kolco24.ui.marks.MarksScreen
import ru.kolco24.kolco24.ui.scan.SCAN_WINDOW_MS
import ru.kolco24.kolco24.ui.scan.ScanScreen
import ru.kolco24.kolco24.ui.scan.ScanEvent
import ru.kolco24.kolco24.ui.scan.classifyTag
import ru.kolco24.kolco24.ui.settings.SettingsScreen
import ru.kolco24.kolco24.ui.settings.WriteChipDialog
import ru.kolco24.kolco24.ui.settings.WriteChipState
import ru.kolco24.kolco24.ui.team.BindChipSheet
import ru.kolco24.kolco24.ui.team.BindOutcome
import ru.kolco24.kolco24.ui.team.BindSheetState
import ru.kolco24.kolco24.ui.team.SlotKey
import ru.kolco24.kolco24.ui.team.decideBind
import ru.kolco24.kolco24.ui.team.TeamScreen
import ru.kolco24.kolco24.ui.teampicker.CompPickerScreen
import ru.kolco24.kolco24.ui.teampicker.TeamPickerScreen
import ru.kolco24.kolco24.ui.teampicker.TeamSwitchSheet
import ru.kolco24.kolco24.ui.admin.AdminScreen
import ru.kolco24.kolco24.ui.admin.CheckChipScreen
import ru.kolco24.kolco24.ui.admin.ProvisioningScreen
import ru.kolco24.kolco24.ui.theme.Kolco24Theme
import ru.kolco24.kolco24.ui.theme.OrangeCta
import ru.kolco24.kolco24.ui.theme.ThemeMode
import ru.kolco24.kolco24.ui.theme.isDark

/** Whether the device can currently read NFC tags, readable by composables for the bind UI. */
enum class NfcState { NoHardware, Disabled, Available }

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    /** Lazily resolved once; null when the device has no NFC hardware. */
    private val nfcAdapter: NfcAdapter? by lazy {
        getSystemService(android.nfc.NfcManager::class.java)?.defaultAdapter
    }

    /** Main-thread handler so tag reads (delivered on a binder thread) hop to the UI thread. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Sink for the next scanned UID; the bind sheet registers/clears it via a DisposableEffect. */
    @Volatile var onTagScanned: ((String) -> Unit)? = null

    /**
     * Sink for the next raw [Tag] when a write flow is active (debug chip writer). When set it takes
     * priority over [onTagScanned] in [onTagDiscovered] — writing needs the full Tag, not just the uid.
     */
    @Volatile var onTagForWrite: ((Tag) -> Unit)? = null

    /**
     * Sink for the next raw [Tag] when the admin chip-provisioning pager is active. When set it
     * yields only to [onTagForWrite] (the debug writer) and takes priority over [onTagForMark]/
     * [onTagScanned] — provisioning needs the full Tag to write the server-returned `code` onto the
     * chip. A distinct hook (rather than reusing [onTagForWrite]) keeps each `DisposableEffect`
     * owning exactly one hook; provisioning and the debug writer are never armed simultaneously.
     */
    @Volatile var onTagForProvision: ((Tag) -> Unit)? = null

    /**
     * Sink for the next raw [Tag] when the admin chip-verification overlay is active
     * (CheckChipScreen). When set it yields to [onTagForWrite]/[onTagForProvision] but takes
     * priority over [onTagForMark]/[onTagScanned] — verification reads the chip's code off the raw
     * Tag to resolve which КП it is bound to. Read-only: no writes, no server, no admin token.
     * Provisioning and verify never co-open, so the relative order with [onTagForProvision] is
     * cosmetic, but a distinct hook keeps each `DisposableEffect` owning exactly one hook.
     */
    @Volatile var onTagForVerify: ((Tag) -> Unit)? = null

    /**
     * Sink for the next raw [Tag] when the «Отметить КП» scan flow is active (ScanScreen). When set
     * it takes priority over [onTagScanned] but yields to [onTagForWrite] in [onTagDiscovered] —
     * marking needs the full Tag to both read the CP chip's code and fall back to the member uid.
     */
    @Volatile var onTagForMark: ((Tag) -> Unit)? = null

    /** Recomputed on every resume; composables observe it to render the bind affordances. */
    var nfcState by mutableStateOf(NfcState.NoHardware)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set the real window background to match the persisted theme before the first Compose
        // frame is painted. This eliminates any unstyled-surface flash between activity creation
        // and the first Compose draw. The preview/starting window is disabled via
        // windowDisablePreview in themes.xml, so no OS-mode-based colour flash occurs when
        // the stored theme differs from the system dark mode.
        val storedMode = (applicationContext as Kolco24App).container.themePreference.mode.value
        val systemNight = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        window.decorView.setBackgroundColor(
            if (storedMode.isDark(systemNight))
                android.graphics.Color.parseColor("#201A19")  // SurfaceDark
            else
                android.graphics.Color.parseColor("#EEF0F3")  // SurfaceLight
        )
        // Apply the resolved theme style immediately so that on API 26-28 the nav-bar
        // scrim colour matches the app theme rather than the OS mode. auto() preserves
        // gesture-navigation transparency on API 29+ while still supplying the correct
        // scrim on API 26-28.
        val resolvedDark = storedMode.isDark(systemNight)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                detectDarkMode = { resolvedDark },
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.argb(0xe6, 0xff, 0xff, 0xff),
                android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b),
                detectDarkMode = { resolvedDark },
            ),
        )
        setContent {
            // Single subscription point for the persisted theme preference: collect once here,
            // apply via Kolco24Theme, and thread the mode + setter down as params (no second
            // collectAsState deeper in the tree).
            val container = remember { (applicationContext as Kolco24App).container }
            val mode by container.themePreference.mode.collectAsState()
            Kolco24Theme(darkTheme = mode.isDark(isSystemInDarkTheme())) {
                Kolco24AppRoot(
                    themeMode = mode,
                    onThemeModeChange = { container.themePreference.setMode(it) },
                )
            }
        }
        // Cold/background launch from an NFC tap: the launching intent already carries the tag's
        // NDEF data (no second scan needed). Skip on recreation — the intent is stale by then.
        if (savedInstanceState == null) handleNfcIntent(intent)
    }

    /** singleTop: a tap while the activity is already resumed re-delivers the intent here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    /**
     * Parse a chip [code] out of an NFC launch intent. Only `NDEF_DISCOVERED` is handled (our
     * manifest filter is scoped to the `kolco24.ru:cp` external type). The NDEF message is
     * captured by the OS at scan time, so the code is available even if the tag was already lifted.
     */
    private fun handleNfcIntent(intent: Intent) {
        if (intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return
        val messages = ndefMessagesOf(intent) ?: return
        val code = chipCodeFromNdef(messages) ?: return
        // Temporary visible consumer — the real read path (legend unlock) replaces this Toast.
        Toast.makeText(this, "Чип: ${chipCodeHex(code)}", Toast.LENGTH_LONG).show()
    }

    /** Version-safe read of `EXTRA_NDEF_MESSAGES`; null when absent or empty. */
    private fun ndefMessagesOf(intent: Intent): Array<NdefMessage>? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                ?.filterIsInstance<NdefMessage>()
                ?.toTypedArray()
        }
        return raw?.takeIf { it.isNotEmpty() }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        nfcState = when {
            adapter == null -> NfcState.NoHardware
            !adapter.isEnabled -> NfcState.Disabled
            else -> NfcState.Available
        }
        if (nfcState == NfcState.Available) {
            adapter!!.enableReaderMode(this, this, READER_FLAGS, null)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    /**
     * Reader-mode callback (binder thread). Priority: an armed write flow gets the raw [Tag]; an
     * armed provisioning flow (admin pager) gets the raw [Tag]; an armed verify flow
     * (CheckChipScreen) gets the raw [Tag]; an armed mark flow (ScanScreen) gets
     * the raw [Tag]; an armed scan flow (bind sheet) gets the
     * normalized UID; otherwise — idle foreground — we read the tag's NDEF and surface our own code
     * chips, mirroring the cold-launch intent path (which reader mode would otherwise suppress).
     * Tag I/O runs here on the binder thread.
     */
    override fun onTagDiscovered(tag: Tag) {
        val writeHook = onTagForWrite
        if (writeHook != null) {
            mainHandler.post { writeHook(tag) }
            return
        }
        val provisionHook = onTagForProvision
        if (provisionHook != null) {
            mainHandler.post { provisionHook(tag) }
            return
        }
        val verifyHook = onTagForVerify
        if (verifyHook != null) {
            mainHandler.post { verifyHook(tag) }
            return
        }
        val markHook = onTagForMark
        if (markHook != null) {
            mainHandler.post { markHook(tag) }
            return
        }
        val scanHook = onTagScanned
        if (scanHook != null) {
            val uid = normalizeNfcUid(tag.id)
            mainHandler.post { scanHook(uid) }
            return
        }
        val code = readChipCode(tag) ?: return
        mainHandler.post {
            Toast.makeText(this, "Чип: ${chipCodeHex(code)}", Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }
}

/**
 * Bookkeeping for the DB side of one «Отметить КП» session, mirroring [ScanScreen]'s UI session so
 * `onScanTag` can persist takes. A fresh instance is `remember`-ed each time the scan overlay opens.
 *
 * - [markId]/[point]/[expectedCount] describe the open take row (set on the КП scan).
 * - [buffer] holds member slots scanned **before** the КП chip; it is drained into [MarkRepository.startKpTake].
 * - [present] mirrors the slots already credited to the open take so a re-scan of an already-counted
 *   member is idempotent and does **not** refresh [lastScanAt] (mirrors [reduce]).
 * - [lastScanAt] lazily detects window expiry (a scan more than [SCAN_WINDOW_MS] after the previous one
 *   starts a new take), keeping the persisted rows in step with the UI's timer-driven finalize.
 */
private class ScanTakeState {
    var markId: String? = null
    var point: Int? = null
    var expectedCount: Int = 0
    val buffer = mutableSetOf<Int>()
    val present = mutableSetOf<Int>()
    var lastScanAt: Long = 0L
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
private fun Kolco24AppRoot(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    var showScan by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAdmin by rememberSaveable { mutableStateOf(false) }
    // Admin chip-provisioning pager (sub-overlay opened from the admin home, drawn above AdminScreen).
    var showProvisioning by rememberSaveable { mutableStateOf(false) }
    // Admin chip-check overlay (read-only verify, sub-overlay opened from the admin home, drawn above AdminScreen).
    var showCheckChip by rememberSaveable { mutableStateOf(false) }
    // Debug chip writer: hex of the code being written (uuid), or null when the dialog is closed.
    var chipWriterCode by rememberSaveable { mutableStateOf<String?>(null) }
    // false = raw bytes to pages 4–7; true = NDEF message + AAR (tag stays NDEF, auto-opens the app).
    var chipWriterNdef by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val container = remember { (context.applicationContext as Kolco24App).container }
    val raceRepo = container.raceRepository
    val teamRepo = container.teamRepository
    val legendRepo = container.legendRepository
    val memberTagsRepo = container.memberTagsRepository
    val bindingRepo = container.memberChipBindingRepository
    val markRepo = container.markRepository
    val today = todayIso()

    // NFC capability, recomputed on every resume by MainActivity; drives the bind affordances.
    val activity = context as? MainActivity
    val nfcState = activity?.nfcState ?: NfcState.NoHardware
    // Enable bind affordances whenever NFC hardware is present (even if currently disabled).
    // The bind sheet shows an NFC-settings deep-link when nfcState == Disabled; disabling the
    // button on NoHardware only (not Disabled) makes that recovery UI reachable.
    val nfcAvailable = nfcState != NfcState.NoHardware
    // For scan-facing UI (banners that say "NFC активен"): reader mode is only active when
    // NFC is fully Available, so the banner must reflect the actual scanning capability.
    val nfcActiveForScan = nfcState == NfcState.Available

    // Tab «Команда» data: which team is selected, its row, and the categories of its race.
    val races by raceRepo.races.collectAsState(initial = emptyList())
    // Reactive race-admin session (source of truth for the admin overlay + the Settings «Администратор» row).
    val adminSession by container.adminAuthRepository.session.collectAsState()
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
    // Guard: collectAsState does not reset on key change — filter stale checkpoints from the
    // prior race during the brief window before the new flow emits (mirrors the safeMarks guard).
    val safeCheckpoints = if (selectedRaceId != null) legendCheckpoints.filter { it.raceId == selectedRaceId } else emptyList()

    // Legend progress denominator: sum of ALL CP costs (open + locked), from the server's
    // `total_cost` (locked CPs hide their individual cost, so it can't be summed client-side).
    val legendTotalCost by remember(selectedRaceId) {
        selectedRaceId?.let { legendRepo.totalCostForRace(it) } ?: flowOf(0)
    }.collectAsState(initial = 0)

    // Local NFC chip bindings for the selected team, keyed by member slot (numberInTeam).
    val bindingsList by remember(selectedTeamId) {
        selectedTeamId?.let { bindingRepo.observeForTeam(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val bindings = remember(bindingsList) { bindingsList.associateBy { it.numberInTeam } }

    // Local take events for the selected team, newest first (consumed by «Отметки» and «Легенда»).
    val marks by remember(selectedTeamId) {
        selectedTeamId?.let { markRepo.observeMarks(it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    // Guard: collectAsState does not reset on key change — filter stale marks from the prior team
    // during the brief window before the new flow emits (mirrors the scanRoster/scanBindings guard).
    val safeMarks = if (selectedTeamId != null) marks.filter { it.teamId == selectedTeamId } else emptyList()
    // "Взято" is team-scoped: derive it from THIS team's complete marks, never off the race-shared
    // checkpoint row — otherwise switching teams within a race would show the prior team's progress.
    val takenIds = remember(safeMarks) { takenPoints(safeMarks) }

    // Scan-overlay inputs: the roster, the uid→slot binding map, and a CP-id index for unlock resolve.
    // Guard: collectAsState does not reset its value when the flow key changes (the mutableStateOf is
    // only seeded with `initial` on first composition). During the brief window after a team switch
    // where bindingsList / teamForTab are still the previous team's data, filter on the current
    // selectedTeamId so stale chips from team A are not credited to team B's mark.
    val scanRoster = if (teamForTab?.id == selectedTeamId) teamForTab?.members ?: emptyList() else emptyList()
    // Only the current roster's slots may count toward a take. A binding left over from a member who
    // was since removed from the roster (its numberInTeam no longer present) is excluded, so a stale
    // chip reads as UnboundChip instead of silently substituting for a real, un-scanned participant.
    val rosterSlots = remember(scanRoster) { scanRoster.mapTo(HashSet()) { it.numberInTeam } }
    val scanBindings = remember(bindingsList, rosterSlots, selectedTeamId) {
        bindingsList
            .filter { it.teamId == selectedTeamId && it.numberInTeam in rosterSlots }
            .associate { it.nfcUid to it.numberInTeam }
    }
    // Display-only map for the scan overlay: the member's slot → its chip number (participantNumber),
    // same filter as scanBindings. The screen shows «№N» instead of the raw nfcUid.
    val scanChipNumbers = remember(bindingsList, rosterSlots, selectedTeamId) {
        bindingsList
            .filter { it.teamId == selectedTeamId && it.numberInTeam in rosterSlots }
            .associate { it.numberInTeam to it.participantNumber }
    }
    val checkpointsById = remember(safeCheckpoints) { safeCheckpoints.associateBy { it.id } }
    // Per-checkpoint color token (point id → server color), so «Отметки» tiles can paint the same
    // leading color bar the Легенда rows use. Race-scoped public data, joined off the mark's point.
    val checkpointColors = remember(safeCheckpoints) { safeCheckpoints.associate { it.id to it.color } }

    // Flow overlay state — survives recreation (enum is Serializable; nullable Int saves out of the box).
    var teamFlowStep by rememberSaveable { mutableStateOf(TeamFlowStep.None) }
    var pickerRaceId by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmTeamId by rememberSaveable { mutableStateOf<Int?>(null) }

    // Bind-chip overlay: which member slot (numberInTeam) is being bound, or null when the sheet is closed.
    var bindSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    // Unbind confirmation: which member slot (numberInTeam) is pending unbind, or null when no dialog.
    var unbindSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    // Clear both slots on team change so a stale slot from a previous team cannot accidentally
    // re-open the sheet/dialog for an unrelated member on the newly selected team.
    LaunchedEffect(selectedTeamId) { bindSlot = null; unbindSlot = null; showAdmin = false; showProvisioning = false; showCheckChip = false }

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
                        marks = safeMarks,
                        checkpointColors = checkpointColors,
                        totalKp = safeCheckpoints.size,
                        totalCost = legendTotalCost,
                        nfcAvailable = nfcActiveForScan,
                        onScanClick = {
                            // Scanning needs a resolved team with a roster. With no team (or a
                            // selection whose team row has gone missing) there is nothing to score
                            // against — route to team selection instead of opening an empty scan that
                            // would only error on the first tap and could log an orphan take.
                            if (teamForTab != null) {
                                teamFlowStep = TeamFlowStep.None; confirmTeamId = null; showSettings = false
                                showAdmin = false; showProvisioning = false; showCheckChip = false
                                bindSlot = null; unbindSlot = null; chipWriterCode = null; showScan = true
                            } else {
                                pickerRaceId = selectedRaceId; teamFlowStep = TeamFlowStep.CompPicker
                            }
                        },
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    )
                    1 -> LegendScreen(
                        checkpoints = safeCheckpoints,
                        hasTeam = teamState !is SelectedTeamState.None,
                        onChooseTeam = { pickerRaceId = null; teamFlowStep = TeamFlowStep.CompPicker },
                        takenIds = takenIds,
                        totalScore = legendTotalCost,
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    )
                    2 -> TeamScreen(
                        team = teamForTab,
                        category = tabCategory,
                        onChooseTeam = { pickerRaceId = selectedRaceId; teamFlowStep = TeamFlowStep.CompPicker },
                        onOpenSettings = { showSettings = true },
                        teamMissing = teamMissing,
                        teamLoading = teamState is SelectedTeamState.Loading,
                        bindings = bindings,
                        onBindMember = { member -> bindSlot = member.numberInTeam },
                        // Only request confirmation here; the actual Room delete happens after the
                        // user confirms in the AlertDialog below (guards against accidental unbinds).
                        onUnbindMember = { member -> unbindSlot = member.numberInTeam },
                        nfcAvailable = nfcAvailable,
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    )
                }
            }
        }

        // Scan overlay. Settings and team-flow handlers are registered after this one, so without the
        // !showScan guards on both of them they would win (Compose gives priority to the last registered
        // enabled BackHandler). Their guards ensure scan's back press is never masked when co-active.
        BackHandler(enabled = showScan) { showScan = false; showSettings = false; showAdmin = false; showProvisioning = false; showCheckChip = false }
        if (showScan) {
            // Fresh DB-side take bookkeeping per opened overlay (parallels ScanScreen's UI session).
            val scanTake = remember(showScan) { ScanTakeState() }
            ScanScreen(
                roster = scanRoster,
                chipNumbers = scanChipNumbers,
                nfcAvailable = nfcActiveForScan,
                onScanTag = onScanTag@{ tag, now ->
                    val raceId = selectedRaceId
                    val teamId = selectedTeamId
                    // raceId/teamId come from the selection pointer, which survives the team row going
                    // missing; an empty roster means there is nothing to score, so refuse rather than
                    // open a take with expectedCount = 0 (which can never complete and orphans a row).
                    if (raceId == null || teamId == null || scanRoster.isEmpty()) {
                        return@onScanTag ScanEvent.BadKp("команда не выбрана")
                    }
                    val uid = normalizeNfcUid(tag.id)
                    // readChipCode is blocking NfcA I/O; unlock is a suspend DAO+crypto path.
                    val code = withContext(Dispatchers.IO) { readChipCode(tag) }
                    val unlock = if (code != null) legendRepo.unlock(raceId, code) else null
                    // For Revealed, checkpointsById is stale (recomposition hasn't fired) so we always
                    // re-read from the DAO. For IdentityOnly we also re-read: if the legend hasn't
                    // emitted its first batch yet (cold start), checkpointsById is still empty and
                    // the DAO snapshot is the only source that has the CP's cost.
                    val localCheckpointsById =
                        if (unlock is UnlockOutcome.Revealed || unlock is UnlockOutcome.IdentityOnly) {
                            legendRepo.checkpointsSnapshot(raceId).associateBy { it.id }
                        } else {
                            checkpointsById
                        }
                    val event = classifyTag(code, uid, unlock, scanBindings, localCheckpointsById)
                    val expired = scanTake.lastScanAt != 0L &&
                        (now - scanTake.lastScanAt) >= SCAN_WINDOW_MS
                    when (event) {
                        is ScanEvent.Kp -> {
                            // A new KP, an expired window, or a switch of CP starts a fresh take row;
                            // re-scanning the same KP within the window only re-stamps the window.
                            if (expired || scanTake.markId == null || scanTake.point != event.point) {
                                // An expired window means the pre-KP buffer belongs to a dead session;
                                // discard it so stale members are not credited to the new take.
                                if (expired) scanTake.buffer.clear()
                                val buffered = scanTake.buffer.toSet()
                                val rosterSize = scanRoster.size
                                // applicationScope.async: the write survives the overlay closing, yet
                                // await() still hands the id back for the in-session addMember chain.
                                val id = container.applicationScope.async {
                                    markRepo.startKpTake(
                                        raceId = raceId,
                                        teamId = teamId,
                                        point = event.point,
                                        number = event.number,
                                        cost = event.cost,
                                        cpUid = event.cpUid,
                                        cpCode = event.cpCode,
                                        expectedCount = rosterSize,
                                        bufferedMembers = buffered,
                                        now = now,
                                    )
                                }.await()
                                scanTake.markId = id
                                scanTake.point = event.point
                                scanTake.expectedCount = rosterSize
                                // The buffered members were drained into the take's present-set.
                                scanTake.present.clear()
                                scanTake.present.addAll(buffered)
                                scanTake.buffer.clear()
                            }
                            scanTake.lastScanAt = now
                        }
                        is ScanEvent.Member -> {
                            if (expired) {
                                scanTake.markId = null
                                scanTake.point = null
                                scanTake.buffer.clear()
                                scanTake.present.clear()
                            }
                            val markId = scanTake.markId
                            val point = scanTake.point
                            if (markId == null || point == null) {
                                // No KP yet: hold the member until the КП chip lands. A re-tap of an
                                // already-buffered member is idempotent and must not refresh the window.
                                if (!scanTake.buffer.add(event.numberInTeam)) return@onScanTag event
                            } else {
                                // A re-tap of an already-credited member is idempotent and must not
                                // refresh the window (else one person could keep the take alive alone).
                                if (!scanTake.present.add(event.numberInTeam)) return@onScanTag event
                                container.applicationScope.async {
                                    markRepo.addMember(
                                        markId = markId,
                                        point = point,
                                        numberInTeam = event.numberInTeam,
                                        expectedCount = scanTake.expectedCount,
                                        now = now,
                                    )
                                }.await()
                            }
                            scanTake.lastScanAt = now
                        }
                        // Diagnostics never open a take or advance the window.
                        ScanEvent.UnboundChip, is ScanEvent.BadKp -> Unit
                    }
                    event
                },
                onClose = { showScan = false; showSettings = false },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Settings overlay — sits beneath the picker/scan overlays (rendered before them, so they draw
        // on top when both are active). Its BackHandler only fires when nothing else is layered above it.
        BackHandler(
            enabled = showSettings && teamFlowStep == TeamFlowStep.None && confirmTeamId == null && !showScan,
        ) { showSettings = false; chipWriterCode = null }
        if (showSettings && teamFlowStep == TeamFlowStep.None && confirmTeamId == null && !showScan) {
            SettingsScreen(
                onBack = { showSettings = false; chipWriterCode = null },
                onChangeTeam = {
                    showSettings = false
                    chipWriterCode = null
                    confirmTeamId = null
                    pickerRaceId = selectedRaceId
                    teamFlowStep = TeamFlowStep.CompPicker
                },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                session = adminSession,
                // Opening admin closes Settings so the two overlays never co-render (Admin draws above).
                onOpenAdmin = { showSettings = false; showAdmin = true },
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
                onWriteChip = if (BuildConfig.DEBUG) {
                    { chipWriterNdef = false; chipWriterCode = chipCodeHex(newChipCode()) }
                } else {
                    null
                },
                onWriteChipNdef = if (BuildConfig.DEBUG) {
                    { chipWriterNdef = true; chipWriterCode = chipCodeHex(newChipCode()) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Debug chip writer dialog — floats above the open Settings overlay. Renders only while Settings
        // is open (the row that opens it lives there); arms onTagForWrite for the duration.
        val activeChipCode = chipWriterCode
        if (activeChipCode != null && showSettings) {
            var writeState by remember(activeChipCode) { mutableStateOf<WriteChipState>(WriteChipState.Waiting) }
            DisposableEffect(activeChipCode) {
                val host = activity
                host?.onTagForWrite = { tag ->
                    // Only act on a fresh tap; ignore re-presentations during/after a write.
                    if (writeState is WriteChipState.Waiting) {
                        writeState = WriteChipState.Writing
                        scope.launch {
                            val bytes = chipCodeFromHex(activeChipCode)
                            val result = withContext(Dispatchers.IO) {
                                if (chipWriterNdef) {
                                    writeChipCodeNdef(tag, bytes, BuildConfig.APPLICATION_ID)
                                } else {
                                    writeChipCode(tag, bytes)
                                }
                            }
                            writeState = when (result) {
                                ChipWriteResult.Success -> WriteChipState.Success
                                ChipWriteResult.Unsupported -> WriteChipState.Unsupported
                                is ChipWriteResult.Failed -> WriteChipState.Failed(result.message)
                            }
                        }
                    }
                }
                onDispose { host?.onTagForWrite = null }
            }
            WriteChipDialog(
                codeHex = activeChipCode,
                state = writeState,
                nfcDisabled = nfcState != NfcState.Available,
                onReset = { chipWriterCode = chipCodeHex(newChipCode()) },
                onDismiss = { chipWriterCode = null },
            )
        }

        // Admin overlay — sits beneath the picker overlays (rendered before them, so a picker launched
        // from inside admin draws on top). Opened from the Settings «Администратор» row; its BackHandler
        // only fires when nothing else is layered above it.
        BackHandler(
            enabled = showAdmin && !showProvisioning && !showCheckChip && !showScan && teamFlowStep == TeamFlowStep.None && confirmTeamId == null,
        ) { showAdmin = false }
        if (showAdmin) {
            AdminScreen(
                session = adminSession,
                onClose = { showAdmin = false; showProvisioning = false; showCheckChip = false },
                onOpenProvisioning = { showCheckChip = false; showProvisioning = true },
                onOpenCheckChip = { showProvisioning = false; showCheckChip = true },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Provisioning pager — opened from the admin home, drawn above AdminScreen. Its BackHandler is
        // registered after admin's (and admin's is guarded with !showProvisioning) so it wins the back
        // press when both overlays are stacked. raceId is the selected team's race (null → hint screen).
        BackHandler(
            enabled = showProvisioning && !showCheckChip && !showScan && teamFlowStep == TeamFlowStep.None && confirmTeamId == null,
        ) { showProvisioning = false }
        if (showProvisioning && !showCheckChip && !showScan) {
            ProvisioningScreen(
                raceId = selectedRaceId,
                onClose = { showProvisioning = false },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Chip-check overlay — read-only verify, opened from the admin home, drawn above AdminScreen.
        // Its BackHandler is registered after admin's (and admin's is guarded with !showCheckChip) so it
        // wins the back press when both overlays are stacked. raceId is the selected team's race.
        BackHandler(
            enabled = showCheckChip && !showScan && teamFlowStep == TeamFlowStep.None && confirmTeamId == null,
        ) { showCheckChip = false }
        if (showCheckChip && !showScan) {
            CheckChipScreen(
                raceId = selectedRaceId,
                onClose = { showCheckChip = false },
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

        // Bind-chip overlay (ModalBottomSheet). Shown when a member slot is selected and resolves to a
        // member of the current team; NFC is armed app-wide, so the sheet only needs to route each read.
        // Back press is handled by the sheet's own onDismissRequest plus this guarded BackHandler.
        val activeBindSlot = bindSlot
        val activeTeamId = selectedTeamId
        val activeRaceId = selectedRaceId
        val bindMember = if (activeBindSlot != null) {
            teamForTab?.members?.find { it.numberInTeam == activeBindSlot }
        } else {
            null
        }
        BackHandler(
            enabled = bindSlot != null && !showScan && !showSettings && !showAdmin && !showProvisioning && !showCheckChip && teamFlowStep == TeamFlowStep.None && confirmTeamId == null,
        ) { bindSlot = null }
        // Keyed by race; caches within this composition whether the pool is known-synced, so repeated
        // offline opens within a session skip the DB lookup. Durable state (across activity recreation
        // and startup warm-ups) is tracked in sync_meta via memberTagsRepo.hasBeenSynced().
        val hasSyncedPool = remember(activeRaceId) { booleanArrayOf(false) }
        if (activeBindSlot != null && bindMember != null && activeTeamId != null && activeRaceId != null && !showSettings && !showAdmin && !showProvisioning && !showCheckChip) {
            val currentSlot = SlotKey(activeTeamId, activeBindSlot)
            // Reset per opened slot; survives recomposition while the same slot stays open.
            var sheetState by remember(activeBindSlot) { mutableStateOf<BindSheetState>(BindSheetState.Waiting) }

            // Arm/clear the NFC read hook for exactly this open sheet. onDispose covers every exit path
            // (dismiss, BackHandler, success auto-dismiss, team switch, recomposition).
            val scanMutex = remember(activeBindSlot) { Mutex() }
            DisposableEffect(activeBindSlot) {
                val host = activity
                host?.onTagScanned = { uid ->
                    // Suspending DAO lookups run off the main thread internally; launch on the UI scope so
                    // state writes land on the main thread. Binding writes use the same scope — they are
                    // fast Room inserts that finish well before the 900ms auto-dismiss window.
                    scope.launch {
                        // Ignore stray scans during the 900ms auto-dismiss animation after a successful bind.
                        if (sheetState is BindSheetState.Success) return@launch
                        // Serialize concurrent scan events (e.g. rapid re-presentation of the same chip):
                        // skip any scan that arrives while one is already being processed.
                        if (!scanMutex.tryLock()) return@launch
                        try {
                            // Fetch the full pool so we can distinguish "pool not yet synced" (empty table)
                            // from "uid genuinely absent from the pool" (non-empty table, uid missing).
                            // Using findByUid alone would conflate these two cases.
                            var pool = memberTagsRepo.observeForRace(activeRaceId).first()
                            if (pool.isEmpty() && !hasSyncedPool[0]) {
                                // Pool is empty and not yet confirmed in this composition: check the
                                // durable sync_meta record first (covers activity recreation and cases
                                // where the startup warm-up synced an empty pool before the sheet opened).
                                if (memberTagsRepo.hasBeenSynced(activeRaceId)) {
                                    // Pool is known-synced but empty; cache the flag and fall through so
                                    // the scan produces NotInPool rather than PoolNotReady.
                                    hasSyncedPool[0] = true
                                } else {
                                    // No prior sync record: attempt an inline refresh. If the server is
                                    // unreachable stay in PoolNotReady; on success mark synced and re-read
                                    // (a still-empty pool will produce NotInPool on this and all subsequent
                                    // scans).
                                    sheetState = BindSheetState.PoolNotReady
                                    val refreshResult = memberTagsRepo.refreshMemberTags(activeRaceId)
                                    if (refreshResult == RefreshResult.Offline ||
                                        refreshResult is RefreshResult.HttpError ||
                                        refreshResult == RefreshResult.Forbidden
                                    ) {
                                        sheetState = BindSheetState.Waiting
                                        return@launch
                                    }
                                    hasSyncedPool[0] = true
                                    pool = memberTagsRepo.observeForRace(activeRaceId).first()
                                }
                            }
                            val poolNumber = pool.find { it.nfcUid == uid }?.number
                            val existing = bindingRepo.findByUid(uid)
                            when (val outcome = decideBind(uid, poolNumber, existing, currentSlot)) {
                                BindOutcome.NotInPool -> sheetState = BindSheetState.NotInPool(uid)
                                is BindOutcome.AlreadyOnThisSlot -> {
                                    // Refresh the stored participantNumber from the authoritative pool
                                    // in case it changed since the original bind.
                                    try {
                                        bindingRepo.bind(activeTeamId, activeBindSlot, uid, outcome.participantNumber)
                                        sheetState = BindSheetState.Success(outcome.participantNumber)
                                    } catch (_: Exception) {
                                        sheetState = BindSheetState.Waiting
                                    }
                                }
                                is BindOutcome.AlreadyBound -> {
                                    sheetState = BindSheetState.AlreadyBound(uid, outcome.participantNumber)
                                }
                                is BindOutcome.ReadyToBind -> {
                                    try {
                                        bindingRepo.bind(activeTeamId, activeBindSlot, uid, outcome.participantNumber)
                                        sheetState = BindSheetState.Success(outcome.participantNumber)
                                    } catch (_: Exception) {
                                        sheetState = BindSheetState.Waiting
                                    }
                                }
                            }
                        } finally {
                            scanMutex.unlock()
                        }
                    }
                }
                onDispose { host?.onTagScanned = null }
            }

            // Auto-dismiss shortly after a successful bind so the confirmation is visible briefly.
            LaunchedEffect(sheetState) {
                if (sheetState is BindSheetState.Success) {
                    delay(900)
                    bindSlot = null
                }
            }

            BindChipSheet(
                member = bindMember,
                state = sheetState,
                nfcDisabled = nfcState != NfcState.Available,
                onReassign = {
                    val s = sheetState as? BindSheetState.AlreadyBound ?: return@BindChipSheet
                    scope.launch {
                        if (!scanMutex.tryLock()) return@launch
                        try {
                            bindingRepo.bind(activeTeamId, activeBindSlot, s.uid, s.participantNumber)
                            sheetState = BindSheetState.Success(s.participantNumber)
                        } catch (_: Exception) {
                            sheetState = BindSheetState.AlreadyBound(s.uid, s.participantNumber)
                        } finally {
                            scanMutex.unlock()
                        }
                    }
                },
                onOpenNfcSettings = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                onDismiss = { bindSlot = null },
            )
        }

        // Unbind confirmation. A long-press on a bound member sets unbindSlot (no write yet); this
        // dialog performs the Room delete only on explicit confirmation, so an accidental tap on a
        // bound member can never destroy a binding.
        val activeUnbindSlot = unbindSlot
        val unbindMember = if (activeUnbindSlot != null) {
            teamForTab?.members?.find { it.numberInTeam == activeUnbindSlot }
        } else {
            null
        }
        val unbindBinding = activeUnbindSlot?.let { bindings[it] }
        BackHandler(
            enabled = unbindSlot != null && !showScan && !showSettings && !showAdmin && !showProvisioning && !showCheckChip && teamFlowStep == TeamFlowStep.None && confirmTeamId == null,
        ) { unbindSlot = null }
        if (activeUnbindSlot != null && unbindMember != null && unbindBinding != null && selectedTeamId != null && !showSettings && !showAdmin && !showProvisioning && !showCheckChip) {
            val teamId = selectedTeamId
            AlertDialog(
                onDismissRequest = { unbindSlot = null },
                title = { Text("Отвязать чип?") },
                text = {
                    Column {
                        Text(unbindMember.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "№${unbindBinding.participantNumber} · ${unbindBinding.nfcUid}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Чип можно будет привязать заново.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            // applicationScope so the delete outlives the closing dialog (consistent with selectTeam).
                            container.applicationScope.launch { bindingRepo.unbind(teamId, activeUnbindSlot) }
                            unbindSlot = null
                        },
                    ) {
                        Text("Отвязать", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { unbindSlot = null }) { Text("Отмена") }
                },
            )
        }
    }
}
