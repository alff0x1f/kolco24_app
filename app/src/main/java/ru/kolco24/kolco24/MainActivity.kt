package ru.kolco24.kolco24

import android.Manifest
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import ru.kolco24.kolco24.data.RefreshResult
import ru.kolco24.kolco24.ui.common.ClockWarningBanner
import ru.kolco24.kolco24.ui.common.refreshErrorMessage
import ru.kolco24.kolco24.data.UnlockOutcome
import ru.kolco24.kolco24.data.nfc.chipModelFromVersion
import ru.kolco24.kolco24.data.nfc.readChipCode
import ru.kolco24.kolco24.data.nfc.readChipVersion
import ru.kolco24.kolco24.data.db.TeamEntity
import ru.kolco24.kolco24.data.normalizeNfcUid
import ru.kolco24.kolco24.data.takenPoints
import ru.kolco24.kolco24.data.time.ClockStatus
import ru.kolco24.kolco24.data.time.TimeSample
import ru.kolco24.kolco24.data.time.TrustedClock
import ru.kolco24.kolco24.data.todayIso
import ru.kolco24.kolco24.data.track.TrackProfile
import ru.kolco24.kolco24.data.track.TrackState
import ru.kolco24.kolco24.data.track.filterPoints
import ru.kolco24.kolco24.data.track.trackLengthMeters
import ru.kolco24.kolco24.ui.legend.LegendScreen
import ru.kolco24.kolco24.ui.marks.MarksScreen
import ru.kolco24.kolco24.ui.scan.SCAN_WINDOW_MS
import ru.kolco24.kolco24.ui.scan.ScanScreen
import ru.kolco24.kolco24.ui.scan.ScanEvent
import ru.kolco24.kolco24.ui.scan.classifyTag
import ru.kolco24.kolco24.ui.scan.isWindowExpired
import ru.kolco24.kolco24.ui.settings.SettingsScreen
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

    /**
     * Trusted clock used to stamp scan takes with monotonic + trusted time. Snapshotted via
     * [TrustedClock.sample] at the moment of a tap — on the binder thread (idle opening tap in
     * [onTagDiscovered]) and on the main thread (live tap in `ScanScreen`'s `onTagForMark`); both are
     * safe because `sample()` is a lock-free `AtomicReference` read.
     */
    val trustedClock: TrustedClock by lazy { (applicationContext as Kolco24App).container.trustedClock }

    /** Sink for the next scanned UID; the bind sheet registers/clears it via a DisposableEffect. */
    @Volatile var onTagScanned: ((String) -> Unit)? = null

    /**
     * Sink for the next raw [Tag] when the debug «Инфо о чипе» flow is active. Placed **first** in the
     * [onTagDiscovered] priority ladder. A distinct hook keeps each `DisposableEffect` owning exactly
     * one hook (CLAUDE.md convention). Reads GET_VERSION off the raw Tag; no write.
     */
    @Volatile var onTagForChipInfo: ((Tag) -> Unit)? = null

    /**
     * Sink for the next raw [Tag] when the admin chip-provisioning pager is active. When set it
     * yields to [onTagForChipInfo] (debug-only) and takes priority over [onTagForMark]/[onTagScanned]
     * — provisioning needs the full Tag to write the server-returned `code` onto the chip. A distinct
     * hook keeps each `DisposableEffect` owning exactly one hook.
     */
    @Volatile var onTagForProvision: ((Tag) -> Unit)? = null

    /**
     * Sink for the next raw [Tag] when the admin chip-verification overlay is active
     * (CheckChipScreen). When set it yields to [onTagForProvision] but takes
     * priority over [onTagForMark]/[onTagScanned] — verification reads the chip's code off the raw
     * Tag to resolve which КП it is bound to. Read-only: no writes, no server, no admin token.
     * Provisioning and verify never co-open, so the relative order with [onTagForProvision] is
     * cosmetic, but a distinct hook keeps each `DisposableEffect` owning exactly one hook.
     */
    @Volatile var onTagForVerify: ((Tag) -> Unit)? = null

    /**
     * Sink for the next raw [Tag] when the «Отметить КП» scan flow is active (ScanScreen). When set
     * it takes priority over [onTagScanned] in [onTagDiscovered] — marking needs the full Tag to both
     * read the CP chip's code and fall back to the member uid.
     */
    @Volatile var onTagForMark: ((Tag) -> Unit)? = null

    /**
     * Entry point for opening the «Отметить КП» overlay from the live idle foreground tap
     * ([onTagDiscovered]). A host collector decides what to do based on the selected-team state.
     * Thread-safe: set from the binder thread and the main thread.
     */
    val nfcLaunchScan = MutableStateFlow<CapturedScan?>(null)

    /**
     * Bound member-bracelet uids for the selected team, mirrored here so the binder-thread idle path
     * can recognize a bracelet (open the overlay) without touching Compose state. A coarse open-gate
     * only — the authoritative roster-filtered `scanBindings` still governs scoring.
     */
    @Volatile var boundUidsSnapshot: Set<String> = emptySet()

    /**
     * The opening tap handed off to [ScanScreen]. Set by the host collector; observed reactively by
     * [ScanScreen] via a [kotlinx.coroutines.flow.StateFlow] collect so that a scan buffered after
     * the overlay's [DisposableEffect] enter phase is still delivered. Cleared by the overlay's
     * collector after processing.
     */
    val pendingScan = MutableStateFlow<CapturedScan?>(null)

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
            val trackProfile by container.trackProfilePreference.profile.collectAsState()
            Kolco24Theme(darkTheme = mode.isDark(isSystemInDarkTheme())) {
                Kolco24AppRoot(
                    themeMode = mode,
                    onThemeModeChange = { container.themePreference.setMode(it) },
                    economyMode = trackProfile == TrackProfile.Economy,
                    onEconomyModeChange = {
                        container.trackProfilePreference.setProfile(
                            if (it) TrackProfile.Economy else TrackProfile.Precise,
                        )
                    },
                )
            }
        }
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
     * Reader-mode callback (binder thread). Priority: an armed chip-info flow gets the raw [Tag]; an
     * armed provisioning flow (admin pager) gets the raw [Tag]; an armed verify flow
     * (CheckChipScreen) gets the raw [Tag]; an armed mark flow (ScanScreen) gets
     * the raw [Tag]; an armed scan flow (bind sheet) gets the
     * normalized UID; otherwise — idle foreground — we read the tag's raw code and surface our own
     * code chips so the host can open the «Отметить КП» overlay.
     * Tag I/O runs here on the binder thread.
     */
    override fun onTagDiscovered(tag: Tag) {
        val chipInfoHook = onTagForChipInfo
        if (chipInfoHook != null) {
            mainHandler.post { chipInfoHook(tag) }
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
        // Idle foreground: recognize our own chips and stash the read so the host can open the
        // «Отметить КП» overlay (a КП chip has a parseable code; a bound member bracelet matches the
        // selected team's uid snapshot). The (code, uid) read here is reused downstream — no second
        // chip read. An unrecognized tag is dropped silently.
        val uid = normalizeNfcUid(tag.id)
        // Snapshot the trusted clock before the blocking NFC read so the sample reflects actual tap
        // time, not post-I/O time. The overlay may drain slightly later; resampling at drain time
        // would corrupt both window-expiry math and the stored take time.
        val sample = trustedClock.sample()
        val code = readChipCode(tag)
        if (code == null && uid !in boundUidsSnapshot) return
        // Publish the already-read (code, uid) — no second chip read. If onTagForMark was armed
        // while readChipCode was blocking (the overlay opened concurrently), the dispatcher sees
        // showScan=true and routes to pendingScan; the ScanScreen's continuous collect drain
        // processes it as ScanInput.Captured.
        nfcLaunchScan.value = CapturedScan(code, uid, sample)
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
 * One captured chip scan that opens / feeds the «Отметить КП» overlay. The live idle foreground tap
 * ([MainActivity.onTagDiscovered]) reads a `(code, uid)` pair so no raw [Tag] replay is ever needed
 * downstream.
 *
 * - [code] is the КП chip's code (`null` ⇒ a bound team-member bracelet).
 * - [uid] is the normalized tag UID.
 * - [sample] is the trusted-clock snapshot taken on the binder thread at tap time; it carries the
 *   monotonic `elapsedMs` threaded into the scan processor's window math and the trusted/wall/boot
 *   fields persisted with the take (the overlay can drain it slightly later, so resampling at drain
 *   time would corrupt both the window-expiry math and the stored take time).
 *
 * Identity equality is used as a [kotlinx.coroutines.flow.StateFlow] payload (set/cleared by
 * reference); override `equals`/`hashCode` only if value equality is ever required.
 */
class CapturedScan(
    val code: ByteArray?,
    val uid: String,
    val sample: TimeSample,
)

/**
 * Source of one scan fed to the «Отметить КП» processor. [Live] is an in-overlay subsequent tap whose
 * code/uid are read off the live tag; [Captured] is the opening tap (live-idle binder path) whose
 * `(code, uid)` were already read — no second chip read.
 */
sealed interface ScanInput {
    data class Live(val tag: Tag) : ScanInput
    data class Captured(val code: ByteArray?, val uid: String) : ScanInput
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
 *   starts a new take), keeping the persisted rows in step with the UI's timer-driven finalize. It is
 *   a **monotonic** `elapsedRealtime` ms (from [TimeSample.elapsedMs]); `null` means "no scan yet" —
 *   a nullable sentinel because `0L` is a legal monotonic reading right after a reboot.
 */
private class ScanTakeState {
    var markId: String? = null
    var point: Int? = null
    var expectedCount: Int = 0
    val buffer = mutableSetOf<Int>()
    val present = mutableSetOf<Int>()
    var lastScanAt: Long? = null
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
    economyMode: Boolean,
    onEconomyModeChange: (Boolean) -> Unit,
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
    // Debug «Инфо о чипе»: GET_VERSION model label awaiting display, or null when no read is pending.
    var chipInfoModel by rememberSaveable { mutableStateOf<String?>(null) }
    // Set true while a chip-info read is armed (drives the DisposableEffect that arms onTagForChipInfo).
    var chipInfoArmed by rememberSaveable { mutableStateOf(false) }
    // Pull-to-refresh spinners — one per server-synced tab (both pages stay composed under the pager,
    // so a refresh on one can be in flight while the other is shown). Snackbar surfaces failures only.
    var legendRefreshing by remember { mutableStateOf(false) }
    var teamRefreshing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    val container = remember { (context.applicationContext as Kolco24App).container }
    val raceRepo = container.raceRepository
    val teamRepo = container.teamRepository
    val legendRepo = container.legendRepository
    val memberTagsRepo = container.memberTagsRepository
    val bindingRepo = container.memberChipBindingRepository
    val markRepo = container.markRepository
    val trackRepo = container.trackRepository
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
    // Trusted-clock status: drives the global skew banner (under each tab's TopAppBar) and the scan
    // notice. A local 5 s tick recomputes it so a wall-clock change with no network event still surfaces
    // within ~5 s; equal values are deduped by the StateFlow, so no spurious recompositions.
    val clockStatus by container.trustedClock.status.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            container.trustedClock.recomputeStatus()
        }
    }
    // GPS-track recording state (written by TrackRecordingService, read here for the «Команда» card).
    val trackState by container.trackRecordingState.collectAsState()
    val selectedTeam by teamRepo.selectedTeam.collectAsState(initial = null)
    val selectedRaceId = selectedTeam?.raceId
    val selectedTeamId = selectedTeam?.teamId

    // Pull-to-refresh: toggle the tab's spinner around a suspending refresh, then surface failures only
    // (success is silent — the Room flow updates the list). Composition scope: a foreground gesture,
    // and the repos write data-then-ETag as separate transactions, so a cancelled refresh self-heals.
    fun pullRefresh(setSpinner: (Boolean) -> Unit, refresh: suspend (Int) -> RefreshResult) {
        val raceId = selectedRaceId ?: return
        setSpinner(true)
        scope.launch {
            val result = refresh(raceId)
            setSpinner(false)
            refreshErrorMessage(result)?.let { snackbarHostState.showSnackbar(it) }
        }
    }

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

    // GPS-track points for the selected team. Mirror the safeMarks guard: collectAsState keeps the
    // prior team's value across a key change until the new flow emits, so filter on selectedTeamId.
    val track by remember(selectedTeamId) {
        val tid = selectedTeamId
        val rid = selectedRaceId
        if (tid != null && rid != null) trackRepo.observeTrack(tid, rid) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val safeTrack = if (selectedTeamId != null) track.filter { it.teamId == selectedTeamId } else emptyList()
    // Length/time metrics use the accuracy-filtered, capture-ordered points (raw count stays full).
    val trackUsable = remember(safeTrack) { filterPoints(safeTrack).sortedBy { it.elapsedRealtimeAt } }
    val trackLength = remember(trackUsable) { trackLengthMeters(trackUsable) }
    val trackFirstTime = remember(trackUsable) { trackUsable.firstOrNull()?.let { formatPointTime(it.trustedMs ?: it.wallMs) } }
    val trackLastTime = remember(trackUsable) { trackUsable.lastOrNull()?.let { formatPointTime(it.trustedMs ?: it.wallMs) } }
    // Degraded accuracy = network is available but GPS is not enabled (no chip or toggle off) — the
    // track will be coarse but recording is still allowed (the engine falls back to network).
    // Read once at composition; runtime GPS toggling is not tracked (no recomposition trigger needed).
    val locationManager = remember { context.getSystemService(LocationManager::class.java) }
    val degradedAccuracy = remember(locationManager) {
        locationManager != null &&
            !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

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
    // Live per-checkpoint cost (point id → current cost), so «Отметки» СУММА/tiles score off the latest
    // legend value rather than the cost snapshotted onto the mark row at take time (which goes stale if
    // the organizer edits a КП cost afterwards). Locked CPs (null cost) are omitted; the mark snapshot
    // fills in for any point missing from the map.
    val checkpointCosts = remember(safeCheckpoints) {
        safeCheckpoints.mapNotNull { cp -> cp.cost?.let { cp.id to it } }.toMap()
    }

    // Flow overlay state — survives recreation (enum is Serializable; nullable Int saves out of the box).
    var teamFlowStep by rememberSaveable { mutableStateOf(TeamFlowStep.None) }
    var pickerRaceId by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmTeamId by rememberSaveable { mutableStateOf<Int?>(null) }

    // Bind-chip overlay: which member slot (numberInTeam) is being bound, or null when the sheet is closed.
    var bindSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    // Unbind confirmation: which member slot (numberInTeam) is pending unbind, or null when no dialog.
    var unbindSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    // Track-recording UI state: confirmation dialogs for clearing the track, a disabled-location
    // notice, and a permanently-denied-permission notice (deep-links to settings).
    var showClearTrackDialog by rememberSaveable { mutableStateOf(false) }
    var showLocationDisabledDialog by rememberSaveable { mutableStateOf(false) }
    var showLocationDeniedDialog by rememberSaveable { mutableStateOf(false) }
    // Tracks whether we have already launched a location permission request at least once this
    // session. shouldShowRequestPermissionRationale returns false both before any request has
    // been made (first ever ask) AND after a permanent denial — using it alone would show the
    // "go to settings" dialog on the very first denial. Guard: only treat a no-rationale result
    // as permanent when we know a prior request was already attempted.
    var hasRequestedLocation by rememberSaveable { mutableStateOf(false) }
    // Clear both slots on team change so a stale slot from a previous team cannot accidentally
    // re-open the sheet/dialog for an unrelated member on the newly selected team. A team switch while
    // recording also stops the service — the running track belongs to the team we are leaving.
    LaunchedEffect(selectedTeamId) {
        bindSlot = null; unbindSlot = null; showAdmin = false; showProvisioning = false; showCheckChip = false
        showClearTrackDialog = false; showLocationDisabledDialog = false; showLocationDeniedDialog = false
        // Only stop recording when a different team is selected. Guard against selectedTeamId == null,
        // which occurs transiently during activity recreation (collectAsState initial = null) before Room
        // emits the persisted value — stopping on null would kill an active recording on every rotation.
        val recording = container.trackRecordingState.value as? TrackState.Recording
        if (recording != null && selectedTeamId != null && recording.teamId != selectedTeamId) {
            TrackRecordingService.stop(context)
        }
    }

    // Start recording once permission is (re)confirmed. The launcher requests fine location (+ POST
    // notifications on 13+); a grant starts the foreground service — Task 7's service re-checks
    // permission on entry as a TOCTOU guard. If no location provider is enabled we still start (fixes
    // flow once the toggle is on) but surface a deep-link; a permanent denial routes to app settings.
    val trackPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        // Snapshot before updating so the permanent-denial guard can distinguish
        // "first ever denial" (alreadyRequested == false) from a subsequent denial.
        val alreadyRequested = hasRequestedLocation
        hasRequestedLocation = true
        if (locationGranted) {
            val raceId = selectedRaceId
            val teamId = selectedTeamId
            if (raceId != null && teamId != null) {
                val anyEnabled = locationManager?.let {
                    it.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                        it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                } ?: false
                if (!anyEnabled) showLocationDisabledDialog = true
                TrackRecordingService.start(context, raceId, teamId)
            }
        } else {
            // Permanent denial: no rationale for either permission AND we have already requested
            // at least once. Without the alreadyRequested guard, shouldShowRequestPermissionRationale
            // returning false on the very first denial would incorrectly route the user to settings.
            val permanent = alreadyRequested && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION) &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (permanent) showLocationDeniedDialog = true
        }
    }
    val onStartTrack: () -> Unit = {
        val perms = buildList {
            // Request both fine and coarse together so Android 12+ shows the "Approximate location"
            // option in the system dialog; fine-only requests may be silently ignored on some OEMs.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        trackPermissionLauncher.launch(perms.toTypedArray())
    }

    // Mirror the roster-filtered bound uids onto the Activity so the binder-thread idle path can
    // recognize a bound bracelet (open the overlay) without touching Compose state. A coarse open-gate
    // only: it may briefly lag a team switch, so a stale bracelet could open an overlay that then reads
    // UnboundChip — the authoritative scanBindings still governs scoring.
    LaunchedEffect(scanBindings) { activity?.boundUidsSnapshot = scanBindings.keys }

    // The captured opening tap (live idle foreground tap), published by the Activity. A bare
    // collectAsState gives the value; the dispatch itself is a keyed LaunchedEffect so the side
    // effects never run during composition and a Loading → Present transition re-fires the deferral.
    val captured by (activity?.nfcLaunchScan ?: remember { MutableStateFlow<CapturedScan?>(null) })
        .collectAsState()
    LaunchedEffect(captured, teamState) {
        val scan = captured ?: return@LaunchedEffect
        val act = activity ?: return@LaunchedEffect
        // Some other overlay is up: dropping the tap (rather than yanking the user out) keeps the
        // current flow intact. The live-idle binder path can't even reach here while a bind/provision/
        // verify hook is armed, but Settings/confirm states still can.
        val busy = showScan || teamFlowStep != TeamFlowStep.None || confirmTeamId != null ||
            showSettings || showAdmin || showProvisioning || showCheckChip || bindSlot != null ||
            unbindSlot != null
        if (busy) {
            // Narrow race: showScan is true but onTagForMark is not yet armed (DisposableEffect
            // hasn't run). The idle path fires and publishes a live scan here instead of routing to
            // the hook. Buffer it so the overlay drains it on arm. Safe because the idle path is only
            // reachable when onTagForMark is null — once the hook is armed, the binder thread routes
            // to it directly and never reaches the idle publish.
            if (showScan && act.pendingScan.value == null) act.pendingScan.value = scan
            act.nfcLaunchScan.value = null
            return@LaunchedEffect
        }
        when (teamState) {
            is SelectedTeamState.Present -> {
                // Same overlay resets as onScanClick, then hand the captured tap to ScanScreen to drain.
                teamFlowStep = TeamFlowStep.None; confirmTeamId = null; showSettings = false
                showAdmin = false; showProvisioning = false; showCheckChip = false
                bindSlot = null; unbindSlot = null; chipInfoArmed = false; chipInfoModel = null
                showClearTrackDialog = false; showLocationDisabledDialog = false; showLocationDeniedDialog = false
                act.pendingScan.value = scan
                showScan = true
                act.nfcLaunchScan.value = null
            }
            SelectedTeamState.None, SelectedTeamState.Missing -> {
                // No team to score against — route to team selection (mirrors the FAB's no-team path).
                pickerRaceId = selectedRaceId
                teamFlowStep = TeamFlowStep.CompPicker
                act.nfcLaunchScan.value = null
            }
            SelectedTeamState.Loading -> {
                // Wait: keep the captured scan so the Loading → Present transition re-runs this effect.
                // The original cold-launch race (tap arriving before Room emits the team) is gone with
                // the NDEF launch path, but the branch stays as a defensive guard for a live-idle tap
                // landing mid team-switch.
            }
        }
    }

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
            snackbarHost = { SnackbarHost(snackbarHostState) },
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
          Column(modifier = Modifier.fillMaxSize()) {
            // Global skew nag above all tabs. The per-tab TopAppBar reserves the status-bar inset, so
            // give the banner statusBarsPadding too; it renders nothing unless Skewed (no regression in
            // the normal case — empty composable, zero height).
            // When the banner IS showing we consume the status-bar inset for the pager subtree so the
            // per-tab TopAppBars don't add a duplicate status-bar gap below the banner.
            ClockWarningBanner(
                status = clockStatus,
                modifier = Modifier.statusBarsPadding(),
            )
            val pagerModifier = if (clockStatus is ClockStatus.Skewed)
                Modifier.fillMaxSize().weight(1f).consumeWindowInsets(WindowInsets.statusBars)
            else
                Modifier.fillMaxSize().weight(1f)
            HorizontalPager(
                state = pagerState,
                modifier = pagerModifier,
                beyondViewportPageCount = 1,
            ) { page ->
                when (page) {
                    0 -> MarksScreen(
                        marks = safeMarks,
                        checkpointColors = checkpointColors,
                        checkpointCosts = checkpointCosts,
                        totalKp = safeCheckpoints.size,
                        totalCost = legendTotalCost,
                        nfcAvailable = nfcActiveForScan,
                        nfcDisabled = nfcState == NfcState.Disabled,
                        hasTeam = teamState !is SelectedTeamState.None,
                        memberCount = teamForTab?.members?.size ?: 0,
                        boundCount = teamForTab?.members?.count { bindings.containsKey(it.numberInTeam) } ?: 0,
                        trackRecording = (trackState as? TrackState.Recording)?.teamId == selectedTeamId,
                        onChooseTeam = { pickerRaceId = selectedRaceId; teamFlowStep = TeamFlowStep.CompPicker },
                        onBindChips = { scope.launch { pagerState.animateScrollToPage(2) } },
                        onOpenNfcSettings = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                        onStartTrack = onStartTrack,
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    )
                    1 -> LegendScreen(
                        checkpoints = safeCheckpoints,
                        hasTeam = teamState !is SelectedTeamState.None,
                        onChooseTeam = { pickerRaceId = null; teamFlowStep = TeamFlowStep.CompPicker },
                        takenIds = takenIds,
                        totalScore = legendTotalCost,
                        isRefreshing = legendRefreshing,
                        onRefresh = { pullRefresh({ legendRefreshing = it }, legendRepo::refreshLegend) },
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
                        isRefreshing = teamRefreshing,
                        onRefresh = { pullRefresh({ teamRefreshing = it }, teamRepo::refreshTeams) },
                        trackState = if ((trackState as? TrackState.Recording)?.teamId == selectedTeamId) trackState else TrackState.Idle,
                        trackPointCount = safeTrack.size,
                        trackLengthMeters = trackLength,
                        trackDegradedAccuracy = degradedAccuracy,
                        trackFirstPointTime = trackFirstTime,
                        trackLastPointTime = trackLastTime,
                        onStartTrack = onStartTrack,
                        onStopTrack = { TrackRecordingService.stop(context) },
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    )
                }
            }
          }
        }

        // Scan overlay. Settings and team-flow handlers are registered after this one, so without the
        // !showScan guards on both of them they would win (Compose gives priority to the last registered
        // enabled BackHandler). Their guards ensure scan's back press is never masked when co-active.
        BackHandler(enabled = showScan) { showScan = false; showSettings = false; showAdmin = false; showProvisioning = false; showCheckChip = false }
        if (showScan) {
            // Fresh DB-side take bookkeeping per opened overlay (parallels ScanScreen's UI session).
            val scanTake = remember { ScanTakeState() }
            ScanScreen(
                roster = scanRoster,
                chipNumbers = scanChipNumbers,
                nfcAvailable = nfcActiveForScan,
                onScanTag = onScanTag@{ input, sample ->
                    // Monotonic "now" for the sliding window; immune to wall-clock changes. The full
                    // sample (trusted/wall/boot) is persisted with the take below.
                    val now = sample.elapsedMs
                    val raceId = selectedRaceId
                    val teamId = selectedTeamId
                    // raceId/teamId come from the selection pointer, which survives the team row going
                    // missing; an empty roster means there is nothing to score, so refuse rather than
                    // open a take with expectedCount = 0 (which can never complete and orphans a row).
                    if (raceId == null || teamId == null || scanRoster.isEmpty()) {
                        return@onScanTag ScanEvent.BadKp("команда не выбрана")
                    }
                    // A Live in-overlay tap reads the chip now (readChipCode is blocking NfcA I/O); a
                    // Captured opening tap already carries the code/uid read at capture time.
                    val code: ByteArray?
                    val uid: String
                    when (input) {
                        is ScanInput.Live -> {
                            uid = normalizeNfcUid(input.tag.id)
                            code = withContext(Dispatchers.IO) { readChipCode(input.tag) }
                        }
                        is ScanInput.Captured -> {
                            uid = input.uid
                            code = input.code
                        }
                    }
                    // unlock is a suspend DAO+crypto path.
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
                    val expired = isWindowExpired(scanTake.lastScanAt, now)
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
                                        // The touch-moment sample: monotonic window + trusted/wall/boot
                                        // fields, captured before scope.launch so slow NFC/Room work
                                        // can't stale the take time.
                                        sample = sample,
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
                                        // The touch-moment sample (monotonic window + trusted/wall/boot).
                                        sample = sample,
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
                onClose = { showScan = false; showSettings = false; showAdmin = false; showProvisioning = false; showCheckChip = false },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Settings overlay — sits beneath the picker/scan overlays (rendered before them, so they draw
        // on top when both are active). Its BackHandler only fires when nothing else is layered above it.
        BackHandler(
            enabled = showSettings && teamFlowStep == TeamFlowStep.None && confirmTeamId == null && !showScan,
        ) { showSettings = false; chipInfoArmed = false; chipInfoModel = null }
        if (showSettings && teamFlowStep == TeamFlowStep.None && confirmTeamId == null && !showScan) {
            SettingsScreen(
                onBack = { showSettings = false; chipInfoArmed = false; chipInfoModel = null },
                onChangeTeam = {
                    showSettings = false
                    chipInfoArmed = false
                    chipInfoModel = null
                    confirmTeamId = null
                    pickerRaceId = selectedRaceId
                    teamFlowStep = TeamFlowStep.CompPicker
                },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                economyMode = economyMode,
                onEconomyModeChange = onEconomyModeChange,
                trackPointCount = safeTrack.size,
                // Clearing is allowed only when a track exists and is NOT recording for this team
                // (same Recording-for-this-team check the TeamScreen TrackCard uses). The confirm
                // dialog's own `is TrackState.Idle` guard is a second safety net.
                trackClearEnabled = safeTrack.isNotEmpty() &&
                    (trackState as? TrackState.Recording)?.teamId != selectedTeamId,
                onClearTrack = { showClearTrackDialog = true },
                session = adminSession,
                // Opening admin closes Settings so the two overlays never co-render (Admin draws above).
                onOpenAdmin = { showSettings = false; chipInfoArmed = false; chipInfoModel = null; showAdmin = true },
                onResetTeam = if (BuildConfig.DEBUG) {
                    {
                        // applicationScope (not composition scope) so the delete outlives the
                        // closing overlay — same reasoning as selectTeam below.
                        container.applicationScope.launch { teamRepo.clearSelectedTeam() }
                        showSettings = false; chipInfoArmed = false; chipInfoModel = null
                    }
                } else {
                    null
                },
                onClearDatabase = if (BuildConfig.DEBUG) {
                    {
                        container.applicationScope.launch { container.clearDatabase() }
                        showSettings = false; chipInfoArmed = false; chipInfoModel = null
                    }
                } else {
                    null
                },
                onReadChipInfo = if (BuildConfig.DEBUG) {
                    { chipInfoModel = null; chipInfoArmed = true }
                } else {
                    null
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Debug «Инфо о чипе» — reads the chip's GET_VERSION model and shows it in a dialog. Floats
        // above the open Settings overlay (the row that opens it lives there); arms onTagForChipInfo
        // while waiting for a tap, then displays chipModelFromVersion(...). DEBUG-only (the row that
        // arms it is gated on a DEBUG-only callback in SettingsScreen).
        if ((chipInfoArmed || chipInfoModel != null) && showSettings) {
            DisposableEffect(chipInfoArmed) {
                val host = activity
                var pendingJob: Job? = null
                var disposed = false
                if (chipInfoArmed) {
                    host?.onTagForChipInfo = { tag ->
                        // Guard against: (1) a mainHandler.post that was already queued when onDispose
                        // fired (stale-post race); (2) a second tap arriving while the first job is
                        // still running (duplicate-launch — only the first tap wins).
                        if (!disposed && pendingJob == null) {
                            pendingJob = scope.launch {
                                val resp = withContext(Dispatchers.IO) { readChipVersion(tag) }
                                if (!disposed) {
                                    chipInfoModel = resp?.let { chipModelFromVersion(it) } ?: "неизвестно"
                                    chipInfoArmed = false
                                }
                            }
                        }
                    }
                }
                onDispose { disposed = true; host?.onTagForChipInfo = null; pendingJob?.cancel() }
            }
            AlertDialog(
                onDismissRequest = { chipInfoArmed = false; chipInfoModel = null },
                title = { Text("Инфо о чипе") },
                text = {
                    Text(
                        if (chipInfoModel != null) "Модель: $chipInfoModel"
                        else "Приложите чип к телефону",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { chipInfoArmed = false; chipInfoModel = null }) {
                        Text("Закрыть")
                    }
                },
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

        // Confirm wiping the team's track. Only reachable from Idle (the card hides «Очистить» while
        // recording); the guard re-checks state so a wipe can never race an in-flight insert.
        if (showClearTrackDialog) {
            val clearTeamId = selectedTeamId
            val clearRaceId = selectedRaceId
            AlertDialog(
                onDismissRequest = { showClearTrackDialog = false },
                title = { Text("Очистить трек?") },
                text = { Text("Все записанные точки этой команды будут удалены без возможности восстановления.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (clearTeamId != null && clearRaceId != null && container.trackRecordingState.value is TrackState.Idle) {
                                // applicationScope so the delete outlives the closing dialog (consistent with unbind).
                                container.applicationScope.launch { trackRepo.deleteForTeam(clearTeamId, clearRaceId) }
                            }
                            showClearTrackDialog = false
                        },
                    ) {
                        Text("Очистить", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearTrackDialog = false }) { Text("Отмена") }
                },
            )
        }

        // Location services are off (no enabled provider): recording started anyway, fixes will flow
        // once the user enables the toggle. Offer a deep-link to the location settings.
        if (showLocationDisabledDialog) {
            AlertDialog(
                onDismissRequest = { showLocationDisabledDialog = false },
                title = { Text("Геолокация выключена") },
                text = { Text("Запись начата, но точки появятся только после включения геолокации в настройках.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            showLocationDisabledDialog = false
                        },
                    ) { Text("Настройки") }
                },
                dismissButton = {
                    TextButton(onClick = { showLocationDisabledDialog = false }) { Text("Закрыть") }
                },
            )
        }

        // Permission permanently denied: only the app-details settings screen can re-grant it.
        if (showLocationDeniedDialog) {
            AlertDialog(
                onDismissRequest = { showLocationDeniedDialog = false },
                title = { Text("Нужен доступ к геолокации") },
                text = { Text("Разрешите доступ к местоположению в настройках приложения, чтобы записывать трек.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                            showLocationDeniedDialog = false
                        },
                    ) { Text("Настройки") }
                },
                dismissButton = {
                    TextButton(onClick = { showLocationDeniedDialog = false }) { Text("Закрыть") }
                },
            )
        }
    }
}

/** Format a point's epoch-ms (`trustedMs ?: wallMs`) as a local `HH:mm` label for the track metrics. */
private fun formatPointTime(epochMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(epochMs))
