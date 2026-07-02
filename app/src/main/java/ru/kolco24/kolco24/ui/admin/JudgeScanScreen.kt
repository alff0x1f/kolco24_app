package ru.kolco24.kolco24.ui.admin

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.kolco24.kolco24.Kolco24App
import ru.kolco24.kolco24.MainActivity
import ru.kolco24.kolco24.data.nfc.readChipCode
import ru.kolco24.kolco24.data.normalizeNfcUid
import ru.kolco24.kolco24.data.time.ClockStatus
import ru.kolco24.kolco24.ui.common.ScanClockBanner
import ru.kolco24.kolco24.ui.scan.ScanFeedbackKind
import ru.kolco24.kolco24.ui.theme.OrangeCta
import ru.kolco24.kolco24.ui.theme.RobotoMono
import ru.kolco24.kolco24.ui.theme.Tertiary

/**
 * Compose host of the judge start/finish pik overlay («Отметка старта» / «Отметка финиша»,
 * [eventType] `"start"`/`"finish"`, fixed per opened page). Mirrors `CheckMemberChipScreen`'s
 * scaffold: tap-swallowing `Column`, a `DisposableEffect(raceId)` arming
 * [MainActivity.onTagForJudgeScan], transient `remember` result state on a composition-scoped
 * `rememberCoroutineScope`, null-sentinel pool Flow so scans are ignored until the pool's first Room
 * emission.
 *
 * Unlike `CheckMemberChipScreen` this host **writes**: a [JudgeScanResult.Recorded] pik inserts a row
 * via `JudgeScanRepository.record` on `container.applicationScope` (CLAUDE.md "writes outlive
 * overlays" — the insert must survive the overlay closing mid-write, so it does not run on the
 * composition-scoped `scope`). [raceId] is the currently-active race (the selected team's race, same
 * value `CheckMemberChipScreen` uses) — a judge station scans across all teams of one race, so there
 * is no team dimension.
 *
 * `poolReady` gates both the not-in-pool [readChipCode] transceive and the write: it requires **both**
 * the pool Flow's first emission (`dataReady`, mirrors `CheckMemberChipScreen`'s gate) **and** a
 * confirmed [ru.kolco24.kolco24.data.MemberTagsRepository.hasBeenSynced] marker (`everSynced`) — a
 * synced-but-empty pool must not be confused with a never-synced one. `everSynced == false` (confirmed,
 * not merely unresolved) renders a «Синхронизируйте гонку» plate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JudgeScanScreen(
    raceId: Int?,
    eventType: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as Kolco24App).container }
    val activity = context as? MainActivity

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // Swallow taps so they don't fall through to the screen behind the overlay.
            .pointerInput(Unit) { detectTapGestures {} },
    ) {
        TopAppBar(
            title = { Text(if (eventType == "finish") "Отметка финиша" else "Отметка старта") },
            navigationIcon = {
                IconButton(onClick = onClose) {
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

        if (raceId == null) {
            JudgeScanHint("Сначала выберите команду в разделе «Команда»")
            return@Column
        }

        val clockStatusFlow = remember(activity) {
            activity?.trustedClock?.status ?: MutableStateFlow<ClockStatus>(ClockStatus.Ok)
        }
        val clockStatus by clockStatusFlow.collectAsState()

        var clockText by remember { mutableStateOf("") }
        LaunchedEffect(activity) {
            // Device-default timezone (not UTC): the judge compares this to a local wristwatch.
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            while (true) {
                val sample = activity?.trustedClock?.sample()
                val ms = sample?.trustedMs ?: sample?.wallMs ?: System.currentTimeMillis()
                clockText = fmt.format(Date(ms))
                delay(1000)
            }
        }

        // Null-sentinel: null = first pool emission not yet received, emptyList = loaded (possibly
        // empty). Prevents classifying scans against stale empty data during the Room delivery window.
        val poolState by remember(raceId) {
            container.memberTagsRepository.observeForRace(raceId)
        }.collectAsState(initial = null)
        val dataReady = poolState != null
        val pool = poolState ?: emptyList()

        // Durable sync marker (survives an empty-but-synced pool): null = still checking, false =
        // confirmed never synced, true = confirmed synced. Checked once per opened race.
        var everSynced by remember(raceId) { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(raceId) {
            everSynced = container.memberTagsRepository.hasBeenSynced(
                raceId,
                container.syncCoordinator.sourceFor(raceId),
            )
        }
        val poolReady = dataReady && everSynced == true

        var lastResult by remember(raceId) { mutableStateOf<JudgeScanResult?>(null) }
        val recent = remember(raceId) { mutableStateListOf<JudgeScanResult>() }
        val mutex = remember(raceId) { Mutex() }
        val scope = rememberCoroutineScope()

        // Long-lived hook reads the latest collected pool/readiness without re-arming on every
        // recomposition.
        val poolReadyLatest = rememberUpdatedState(poolReady)
        val poolLatest = rememberUpdatedState(pool)

        DisposableEffect(raceId) {
            val host = activity
            // The hook fires on the main thread (mainHandler.post in onTagDiscovered) and the scope is
            // Main-confined, so only readChipCode needs withContext(IO); the state writes after it
            // resumes are already on Main. The Mutex serializes overlapping binder-thread taps.
            host?.onTagForJudgeScan = { tag ->
                scope.launch {
                    mutex.withLock {
                        // Capture trusted time first (tap-accurate), before any blocking chip I/O.
                        val sample = activity.trustedClock.sample()
                        val uid = normalizeNfcUid(tag.id)
                        val ready = poolReadyLatest.value
                        val memberTag = if (ready) poolLatest.value.firstOrNull { it.nfcUid == uid } else null
                        // The code read is a diagnostic for the not-in-pool branch only, and only once
                        // the pool is ready — avoids a wasted NfcA transceive when it isn't loaded yet.
                        val hasKpCode = ready && memberTag == null &&
                            withContext(Dispatchers.IO) { readChipCode(tag) } != null
                        val result = classifyJudgeScan(uid, memberTag, hasKpCode, ready)
                        if (result is JudgeScanResult.Recorded) {
                            container.applicationScope.launch {
                                container.judgeScanRepository.record(
                                    raceId = raceId,
                                    eventType = eventType,
                                    participantNumber = result.number,
                                    nfcUid = result.uid,
                                    sample = sample,
                                )
                            }
                            container.scanFeedback.play(ScanFeedbackKind.Success)
                        } else {
                            container.scanFeedback.play(ScanFeedbackKind.Failure)
                        }
                        lastResult = result
                        recent.add(0, result)
                        // removeAt(lastIndex), NOT removeLast(): the stdlib extension trips a NewApi
                        // lint error against JDK 21 SequencedCollection.removeLast on minSdk 24.
                        if (recent.size > 20) recent.removeAt(recent.lastIndex)
                    }
                }
            }
            onDispose { host?.onTagForJudgeScan = null }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = clockText,
            fontFamily = RobotoMono,
            fontWeight = FontWeight.Bold,
            fontSize = 40.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (clockStatus !is ClockStatus.Ok) {
            ScanClockBanner(
                status = clockStatus,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (everSynced == false) {
            JudgeScanSyncPlate(modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
        }

        JudgeScanHero(
            result = lastResult,
            previousUid = recent.getOrNull(1)?.uidOrNull(),
            poolSize = pool.size,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Недавние отметки",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(recent) { result -> RecentJudgeScanRow(result) }
            }
        }
    }
}

/** The scanned uid of a [JudgeScanResult], or `null` for [JudgeScanResult.PoolNotReady]. */
private fun JudgeScanResult.uidOrNull(): String? = when (this) {
    is JudgeScanResult.Recorded -> uid
    is JudgeScanResult.UnknownChip -> uid
    JudgeScanResult.KpChip -> null
    JudgeScanResult.PoolNotReady -> null
}

/** Centered muted hint for the no-race state. */
@Composable
private fun JudgeScanHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** «Синхронизируйте гонку» plate shown when the race's member-tag pool has never synced. */
@Composable
private fun JudgeScanSyncPlate(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Sync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "Синхронизируйте гонку — список участников ещё не загружен",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * The hero result card. `null` → a pulsing NFC glyph + «Приложите браслет участника» with the pool
 * size as a subline; the four [JudgeScanResult] variants render their status verbatim.
 */
@Composable
private fun JudgeScanHero(
    result: JudgeScanResult?,
    previousUid: String?,
    poolSize: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        when (result) {
            null -> JudgeScanIdleHero(poolSize)
            is JudgeScanResult.Recorded -> JudgeScanOkHero(result, previousUid)
            is JudgeScanResult.KpChip -> JudgeScanMessageHero(
                color = OrangeCta,
                icon = Icons.Filled.Warning,
                title = "Это чип КП, а не браслет участника",
                uid = null,
                previousUid = previousUid,
            )
            is JudgeScanResult.UnknownChip -> JudgeScanMessageHero(
                color = OrangeCta,
                icon = Icons.Filled.HelpOutline,
                title = "Чип не найден в списке участников этой гонки",
                uid = result.uid,
                previousUid = previousUid,
            )
            is JudgeScanResult.PoolNotReady -> JudgeScanMessageHero(
                color = OrangeCta,
                icon = Icons.Filled.Sync,
                title = "Список участников ещё не загружен",
                uid = null,
                previousUid = previousUid,
            )
        }
    }
}

/** Idle state — pulsing NFC glyph + prompt + pool size (mirrors `CheckMemberChipScreen`'s idle hero). */
@Composable
private fun JudgeScanIdleHero(poolSize: Int) {
    val transition = rememberInfiniteTransition(label = "judge-scan-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "judge-scan-pulse-alpha",
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .alpha(pulse)
                .background(OrangeCta.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Nfc,
                contentDescription = null,
                tint = OrangeCta,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Приложите браслет участника",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (poolSize > 0) "В списке $poolSize участников" else "Список участников пуст",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The «Записано» hero: big participant number + green check. */
@Composable
private fun JudgeScanOkHero(result: JudgeScanResult.Recorded, previousUid: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Участник",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "№${result.number}",
            fontFamily = RobotoMono,
            fontWeight = FontWeight.Bold,
            fontSize = 72.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Tertiary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "Записано",
                style = MaterialTheme.typography.titleMedium,
                color = Tertiary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** A non-recorded hero: colored status icon + message, and the chip uid when known. */
@Composable
private fun JudgeScanMessageHero(
    color: Color,
    icon: ImageVector,
    title: String,
    uid: String?,
    previousUid: String?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(color.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (uid != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = uid,
                fontFamily = RobotoMono,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One «Недавние отметки» row: participant label + status icon. */
@Composable
private fun RecentJudgeScanRow(result: JudgeScanResult) {
    val label: String
    val icon: ImageVector
    val iconTint: Color
    when (result) {
        is JudgeScanResult.Recorded -> {
            label = "№${result.number}"
            icon = Icons.Filled.Check
            iconTint = Tertiary
        }
        is JudgeScanResult.KpChip -> {
            label = "КП"
            icon = Icons.Filled.Warning
            iconTint = OrangeCta
        }
        is JudgeScanResult.UnknownChip -> {
            label = "—"
            icon = Icons.Filled.HelpOutline
            iconTint = OrangeCta
        }
        is JudgeScanResult.PoolNotReady -> {
            label = "—"
            icon = Icons.Filled.Sync
            iconTint = OrangeCta
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = label,
                fontFamily = RobotoMono,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
