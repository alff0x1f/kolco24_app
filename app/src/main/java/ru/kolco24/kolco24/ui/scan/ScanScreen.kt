package ru.kolco24.kolco24.ui.scan

import android.nfc.Tag
import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.kolco24.kolco24.MainActivity
import ru.kolco24.kolco24.ScanInput
import ru.kolco24.kolco24.data.db.TeamMemberItem
import ru.kolco24.kolco24.data.time.ClockStatus
import ru.kolco24.kolco24.data.time.TimeSample
import ru.kolco24.kolco24.ui.common.ScanClockBanner
import ru.kolco24.kolco24.ui.theme.BrandRed
import ru.kolco24.kolco24.ui.theme.RobotoMono
import kotlinx.coroutines.flow.MutableStateFlow

private const val TIMER_TICK_MS = 250L
private const val SUCCESS_HOLD_MS = 1_800L

data class ScanChip(
    val chipNumber: Int?,
    val name: String,
    val filled: Boolean,
    val bound: Boolean,
)

@Composable
fun ScanScreen(
    roster: List<TeamMemberItem>,
    chipNumbers: Map<Int, Int>,
    nfcAvailable: Boolean,
    onScanTag: suspend (ScanInput, TimeSample) -> ScanEvent,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as? MainActivity
    // Trusted-clock status drives the in-scan notice: a bright accent on Skewed, a soft "time not
    // verified" plate on NoSync (the only place NoSync is surfaced). Fall back to Ok when there is no
    // activity (preview) so nothing renders.
    val clockStatusFlow = remember(activity) {
        activity?.trustedClock?.status ?: MutableStateFlow<ClockStatus>(ClockStatus.Ok)
    }
    val clockStatus by clockStatusFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val scanMutex = remember { Mutex() }
    val currentOnScanTag by rememberUpdatedState(onScanTag)
    val currentOnClose by rememberUpdatedState(onClose)
    var session by remember { mutableStateOf<ScanSession?>(null) }
    var remainingMillis by remember { mutableLongStateOf(SCAN_WINDOW_MS) }
    var diagnostic by remember { mutableStateOf<String?>(null) }
    // Set true once the КП + full roster have all been scanned; drives the green "Готово!" success
    // beat before the overlay auto-closes. Reset on finalize so a fresh open starts clean.
    var completed by remember { mutableStateOf(false) }

    fun finalizeSession() {
        session = null
        remainingMillis = SCAN_WINDOW_MS
        diagnostic = null
        completed = false
    }

    // Shared scan-processing body for both the live in-overlay hook and the opening-tap drain. Keeps
    // the opening tap byte-for-byte identical to subsequent taps. [sample] is the touch-moment trusted
    // clock snapshot; its monotonic `elapsedMs` is the "now" the window math runs on.
    suspend fun process(input: ScanInput, sample: TimeSample) {
        val now = sample.elapsedMs
        scanMutex.withLock {
            val event = currentOnScanTag(input, sample)
            when (event) {
                ScanEvent.UnboundChip -> diagnostic = "Чип не привязан к команде"
                is ScanEvent.BadKp -> diagnostic = event.reason
                else -> {
                    diagnostic = null
                    // If the 20 s window had already elapsed at tap time, the DB side
                    // started a fresh take; discard the expired UI session so reduce
                    // also starts fresh instead of extending the stale one.
                    val effectiveSession =
                        if (isWindowExpired(session?.lastScanAt, now)) null else session
                    // Let the lastScanAt-keyed LaunchedEffect drive the timer. Don't reset
                    // remainingMillis here: an idempotent re-scan leaves lastScanAt unchanged,
                    // so the ring must keep counting down rather than flash back to full.
                    session = reduce(effectiveSession, event, now)
                }
            }
        }
    }

    DisposableEffect(activity, scope) {
        activity?.let { act ->
            act.onTagForMark = { tag ->
                // Snapshot the trusted clock at tag-tap time, before launch/withLock, so a scan queued
                // behind slow NFC or DB work is dated when the user tapped, not when processing starts.
                // Runs on the main thread (posted from the binder thread); sample() is lock-free.
                val sample = act.trustedClock.sample()
                scope.launch { process(ScanInput.Live(tag), sample) }
            }
        }
        onDispose {
            activity?.onTagForMark = null
            activity?.let { it.pendingScan.value = null }
        }
    }

    // Reactive drain of the captured opening tap. DisposableEffect runs synchronously in the
    // commit phase while LaunchedEffect runs async after commit. When both showScan and captured
    // change in the same Compose frame, the commit-phase drain would see pendingScan == null and
    // miss a scan that the host's LaunchedEffect writes post-commit. A continuous collect catches
    // writes that arrive after DisposableEffect enters.
    LaunchedEffect(activity) {
        val act = activity ?: return@LaunchedEffect
        act.pendingScan.collect { scan ->
            scan ?: return@collect
            act.pendingScan.value = null
            process(ScanInput.Captured(scan.code, scan.uid), scan.sample)
        }
    }

    LaunchedEffect(session?.lastScanAt) {
        val lastScanAt = session?.lastScanAt ?: run {
            remainingMillis = SCAN_WINDOW_MS
            return@LaunchedEffect
        }
        while (session?.lastScanAt == lastScanAt) {
            // Monotonic: lastScanAt is an elapsedRealtime stamp (TimeSample.elapsedMs), so the ring
            // must measure against the same source — wall-clock here would jump if the user reset it.
            val remaining = SCAN_WINDOW_MS - (SystemClock.elapsedRealtime() - lastScanAt)
            remainingMillis = remaining.coerceAtLeast(0L)
            if (remaining <= 0L) {
                // A scan coroutine may be suspended inside scanMutex right at the deadline (e.g.
                // awaiting a DB write after its IO finished). Wait for it to complete; if the scan
                // extended the window (updated session.lastScanAt), skip finalization — the new
                // LaunchedEffect will pick it up.
                var finalized = false
                scanMutex.withLock {
                    if (session?.lastScanAt == lastScanAt) {
                        finalizeSession()
                        finalized = true
                    }
                }
                // Close after releasing the mutex, only when the window actually expired (the scan
                // didn't extend it). A completion auto-close may have already closed the overlay.
                if (finalized) currentOnClose()
                break
            }
            delay(TIMER_TICK_MS)
        }
    }

    // Auto-close on completion: КП identified + all roster members present. Show a brief green
    // "Готово!" beat, then finalize and close. `completed` is set before the delay so a recomposition
    // during the hold can't trigger a second close.
    val allScanned = isComplete(session, roster.size)
    LaunchedEffect(allScanned) {
        if (allScanned && !completed) {
            completed = true
            delay(SUCCESS_HOLD_MS)
            // Re-validate under the mutex after the hold: a КП switch may have arrived during the
            // delay and be mid-processing inside scanMutex (suspended on NFC/Room IO) without having
            // updated `session` yet, so the Compose cancellation of this effect hasn't fired.
            // Mirrors the expiry handler pattern: finalize+close only if still complete.
            var shouldClose = false
            scanMutex.withLock {
                if (isComplete(session, roster.size)) {
                    finalizeSession()
                    shouldClose = true
                } else {
                    completed = false
                }
            }
            if (shouldClose) currentOnClose()
        } else if (!allScanned) {
            // A КП switch during the success hold makes allScanned false and cancels the delay above.
            // Reset completed so the next full-roster scan can trigger the beat again.
            completed = false
        }
    }

    val chips = roster.map { member ->
        val chipNumber = chipNumbers[member.numberInTeam]
        val scanned = (session?.present ?: emptySet()) + (session?.bufferedBeforeKp ?: emptySet())
        ScanChip(
            chipNumber = chipNumber,
            name = member.name,
            filled = member.numberInTeam in scanned,
            bound = chipNumber != null,
        )
    }
    val scanned = chips.count { it.filled }
    val total = chips.size
    val remaining = (total - scanned).coerceAtLeast(0)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.navigationBars.add(WindowInsets(bottom = 32.dp)).asPaddingValues(),
        ) {
            item("top_bar") {
                ScanTopBar(
                    canFinish = session?.checkpointId != null,
                    onClose = {
                        finalizeSession()
                        onClose()
                    },
                    onFinish = {
                        finalizeSession()
                        onClose()
                    },
                )
            }
            if (clockStatus !is ClockStatus.Ok) {
                item("clock_banner") {
                    ScanClockBanner(
                        status = clockStatus,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            item("kp_hero") {
                if (completed) {
                    HeroSuccessCard(session = session)
                } else {
                    CheckpointSheetCard(session = session)
                }
            }
            if (!completed && session != null) {
                item("timer_strip") {
                    ScanTimerStrip(
                        seconds = remainingMillis / 1_000f,
                        total = SCAN_WINDOW_MS / 1_000f,
                        remainingScans = remaining,
                        waitingForCheckpoint = session?.checkpointId == null,
                    )
                }
            }
            item("chip_section") {
                ChipSectionHeader(scanned = scanned, total = total)
            }
            item("chip_grid") {
                ChipGrid(chips = chips)
            }
            item("chip_hint") {
                Text(
                    text = "Сканировать чипы можно в любом порядке",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            diagnostic?.let { message ->
                item("diagnostic") {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (!nfcAvailable) {
                item("nfc_banner") {
                    NfcUnavailableBanner(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanTopBar(canFinish: Boolean, onClose: () -> Unit, onFinish: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Закрыть",
            )
        }
        Text(
            text = "Отметить КП",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        TextButton(onClick = onFinish, enabled = canFinish) {
            Text(
                text = "Готово",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * The hero of the screen — a digital mirror of the laminated paper checkpoint the racer is holding.
 *
 * Once the КП chip is read it shows the same giant black two-digit numeral and contactless-wave mark
 * printed on the physical sheet (`00`, `14`, …), so confirming "I'm marking the right КП" is a glance,
 * not a read. Before the chip is read the same frame becomes the call to action: a pulsing wave and
 * "приложите телефон к метке КП". Fixed min-height so the card doesn't jump between the two states.
 */
@Composable
private fun CheckpointSheetCard(session: ScanSession?) {
    val number = session?.checkpointNumber
    val hasCheckpoint = number != null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        // A hairline edge reads as the laminate pouch around the printed sheet.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 236.dp)
                .padding(20.dp),
        ) {
            // Top row mirrors the sheet furniture: eyebrow on the left, contactless mark on the right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "КП",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ContactlessMark(
                    color = if (hasCheckpoint) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    pulsing = !hasCheckpoint,
                    modifier = Modifier.size(28.dp),
                )
            }

            // The numeral (or the tap prompt) owns the centre; weight(1f) keeps it clear of the rows.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (hasCheckpoint) {
                    // The numeral that matches the paper: two digits, the whole reason this card exists.
                    Text(
                        text = number.toString().padStart(2, '0'),
                        fontFamily = RobotoMono,
                        fontSize = 104.sp,
                        lineHeight = 104.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        text = "Приложите телефон\nк метке КП",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (hasCheckpoint) {
                CostStat(cost = session.cost)
            }
        }
    }
}

/** «18 баллов» — the КП value, secondary to the numeral. The number itself wears the brand red. */
@Composable
private fun CostStat(cost: Int?, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            text = cost?.toString() ?: "—",
            fontFamily = RobotoMono,
            fontSize = 22.sp,
            color = BrandRed,
        )
        Text(
            text = " ${pointsWord(cost)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }
}

/** Russian plural for «балл»: 1 балл · 2–4 балла · 5–20/0 баллов (declension by last digit pair). */
private fun pointsWord(cost: Int?): String {
    val n = cost ?: return "баллов"
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> "баллов"
        mod10 == 1 -> "балл"
        mod10 in 2..4 -> "балла"
        else -> "баллов"
    }
}

/**
 * The contactless ))) glyph printed on every checkpoint sheet, drawn as three concentric arcs off a
 * dot so it reads identical to the paper. [pulsing] runs a gentle waxing of the arcs while waiting for
 * a tap (a quiet "tap here" signal); a resolved КП draws it solid.
 */
@Composable
private fun ContactlessMark(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "waveAlpha",
    )
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        // Origin near the lower-left, arcs sweeping up-right — the contactless convention.
        val origin = Offset(size.width * 0.18f, size.height * 0.82f)
        drawCircle(color = color, radius = 1.8.dp.toPx(), center = origin)
        val arcs = 3
        for (i in 1..arcs) {
            val r = size.minDimension * (0.22f * i)
            val a = if (pulsing) {
                // Outer arcs fade first, so the wave appears to ripple outward.
                (pulse - (i - 1) * 0.22f).coerceIn(0.18f, 1f)
            } else {
                1f
            }
            drawArc(
                color = color.copy(alpha = a),
                startAngle = -65f,
                sweepAngle = 60f,
                useCenter = false,
                topLeft = Offset(origin.x - r, origin.y - r),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = stroke,
            )
        }
    }
}

@Composable
private fun ChipSectionHeader(scanned: Int, total: Int) {
    val allScanned = total > 0 && scanned == total
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Чипы команды",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$scanned / $total",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (allScanned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ChipGrid(chips: List<ScanChip>, modifier: Modifier = Modifier) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val rows = chips.chunked(2)
    val lastRowIdx = rows.lastIndex

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            rows.forEachIndexed { rowIdx, row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    val leftModifier = if (row.size == 2) {
                        Modifier.weight(1f).drawBehind {
                            drawLine(
                                color = dividerColor,
                                start = Offset(size.width, 0f),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    } else {
                        Modifier.weight(1f)
                    }
                    Box(modifier = leftModifier) { ChipSlot(chip = row[0]) }
                    if (row.size == 2) {
                        Box(modifier = Modifier.weight(1f)) { ChipSlot(chip = row[1]) }
                    } else {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
                if (rowIdx != lastRowIdx) {
                    HorizontalDivider(color = dividerColor)
                }
            }
        }
    }
}

@Composable
private fun ChipSlot(chip: ScanChip) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (chip.filled) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(28.dp),
            )
        } else {
            WaitingChipIcon()
        }
        Column(modifier = Modifier.weight(1f)) {
            if (chip.filled) {
                Text(
                    text = chip.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "№${chip.chipNumber ?: "—"}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else if (!chip.bound) {
                Text(
                    text = chip.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Чип не привязан",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                Text(
                    text = chip.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The binding is known before scanning, so show the chip number right away.
                Text(
                    text = "№${chip.chipNumber ?: "—"}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun WaitingChipIcon() {
    val outlineColor = MaterialTheme.colorScheme.outline
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier.size(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.width / 2 - 1.5.dp.toPx()
            drawCircle(
                color = outlineColor,
                radius = radius,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                ),
            )
        }
        Icon(
            imageVector = Icons.Filled.Nfc,
            contentDescription = null,
            tint = onSurfaceVariantColor,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * The completion beat — the very same sheet frame as [CheckpointSheetCard], turned green and stamped.
 * Keeping the footprint identical means the card confirms in place instead of shrinking to a small row
 * at the most satisfying moment; the big checkmark lands exactly where the numeral was (number → tick),
 * and the КП is still named at the bottom so you see which one you just closed.
 */
@Composable
private fun HeroSuccessCard(session: ScanSession?) {
    val number = session?.checkpointNumber
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 236.dp)
                .padding(20.dp),
        ) {
            Text(
                text = "ГОТОВО",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.9f),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CheckStamp(color = Color.White, modifier = Modifier.size(104.dp))
            }
            Text(
                text = if (number != null) {
                    "КП ${number.toString().padStart(2, '0')} · вся команда отмечена"
                } else {
                    "Вся команда отмечена"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

/** A bold hand-stamped checkmark — drawn (not an icon) so its weight matches the heavy КП numeral. */
@Composable
private fun CheckStamp(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.16f, h * 0.54f)
            lineTo(w * 0.42f, h * 0.78f)
            lineTo(w * 0.86f, h * 0.24f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = w * 0.12f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/**
 * Compact countdown for the 20 s sliding window — demoted from a full dark hero so it serves the КП
 * sheet rather than competing with it. It only renders once a scan has started (a window is running),
 * so it never shows a misleading full bar before the first tap. The draining bar runs the bottom edge;
 * it (and the seconds) flush red under 5 s left.
 */
@Composable
private fun ScanTimerStrip(
    seconds: Float,
    total: Float,
    remainingScans: Int,
    waitingForCheckpoint: Boolean,
) {
    val pct = if (total > 0f) (seconds / total).coerceIn(0f, 1f) else 0f
    // The timer ticks every TIMER_TICK_MS, so pct arrives in steps. Tween between steps (linear, one
    // tick long) so the bar drains smoothly instead of jumping every 250 ms.
    val animatedPct by animateFloatAsState(
        targetValue = pct,
        animationSpec = tween(durationMillis = TIMER_TICK_MS.toInt(), easing = LinearEasing),
        label = "timerBar",
    )
    val urgent = seconds < 5f
    val accent = if (urgent) Color(0xFFFFB4AB) else Color(0xFFFFC98A)
    val onDark = MaterialTheme.colorScheme.inverseOnSurface
    val trackColor = onDark.copy(alpha = 0.12f)
    val one = remainingScans % 10 == 1 && remainingScans % 100 != 11
    val chipWord = when {
        remainingScans % 100 in 11..14 -> "чипов"
        one -> "чип"
        remainingScans % 10 in 2..4 -> "чипа"
        else -> "чипов"
    }
    // Verb agrees with the count too: «Остался 1 чип» but «Осталось 2/5 чипа/чипов».
    val verb = if (one) "Остался" else "Осталось"
    val message = when {
        waitingForCheckpoint -> "Приложите метку КП"
        remainingScans == 0 -> "Все чипы отсканированы"
        else -> "$verb $remainingScans $chipWord"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 14.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = seconds.toInt().toString(),
                        fontFamily = RobotoMono,
                        fontSize = 34.sp,
                        color = if (urgent) accent else onDark,
                    )
                    Text(
                        text = " с",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onDark.copy(alpha = 0.65f),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onDark,
                    modifier = Modifier.weight(1f),
                )
            }
            // Draining track pinned to the bottom edge — the at-a-glance "how much time is left".
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(trackColor),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedPct)
                        .height(4.dp)
                        .background(accent),
                )
            }
        }
    }
}

/**
 * Surfaced only when NFC is unavailable — a healthy reader needs no badge (the hero card already
 * tells the user to tap). This is an alert, not a status light.
 */
@Composable
private fun NfcUnavailableBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "NFC недоступен",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Сканирование NFC на этом устройстве недоступно",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
