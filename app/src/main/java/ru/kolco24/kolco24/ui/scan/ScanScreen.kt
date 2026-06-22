package ru.kolco24.kolco24.ui.scan

import android.nfc.Tag
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.kolco24.kolco24.MainActivity
import ru.kolco24.kolco24.ScanInput
import ru.kolco24.kolco24.data.db.TeamMemberItem
import ru.kolco24.kolco24.ui.theme.BrandRed

private const val TIMER_TICK_MS = 250L
private const val SUCCESS_HOLD_MS = 1_000L

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
    onScanTag: suspend (ScanInput, Long) -> ScanEvent,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as? MainActivity
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
    // the opening tap byte-for-byte identical to subsequent taps.
    suspend fun process(input: ScanInput, now: Long) {
        scanMutex.withLock {
            val event = currentOnScanTag(input, now)
            when (event) {
                ScanEvent.UnboundChip -> diagnostic = "Чип не привязан к команде"
                is ScanEvent.BadKp -> diagnostic = event.reason
                else -> {
                    diagnostic = null
                    // If the 20 s window had already elapsed at tap time, the DB side
                    // started a fresh take; discard the expired UI session so reduce
                    // also starts fresh instead of extending the stale one.
                    val effectiveSession = if (session != null &&
                        (now - session!!.lastScanAt) >= SCAN_WINDOW_MS) null else session
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
                // Capture at tag-tap time, before launch/withLock, so that a scan queued behind
                // slow NFC or DB work is dated when the user tapped, not when processing starts.
                val now = System.currentTimeMillis()
                scope.launch { process(ScanInput.Live(tag), now) }
            }
            // Drain the tap that opened this overlay (live idle / cold / warm) once, as the first
            // scan, using its capture-time `now` so the window math matches when the chip was tapped.
            act.pendingScan?.let { scan ->
                act.pendingScan = null
                scope.launch { process(ScanInput.Captured(scan.code, scan.uid), scan.capturedAt) }
            }
        }
        onDispose {
            activity?.onTagForMark = null
            activity?.pendingScan = null
        }
    }

    LaunchedEffect(session?.lastScanAt) {
        val lastScanAt = session?.lastScanAt ?: run {
            remainingMillis = SCAN_WINDOW_MS
            return@LaunchedEffect
        }
        while (session?.lastScanAt == lastScanAt) {
            val remaining = SCAN_WINDOW_MS - (System.currentTimeMillis() - lastScanAt)
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
            finalizeSession()
            currentOnClose()
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
                    canFinish = session?.point != null,
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
            item("hero_timer") {
                if (completed) {
                    HeroSuccessCard()
                } else {
                    HeroTimerCard(
                        seconds = remainingMillis / 1_000f,
                        total = SCAN_WINDOW_MS / 1_000f,
                        remainingScans = remaining,
                        waitingForCheckpoint = session?.point == null,
                    )
                }
            }
            item("cp_waiting") {
                CpWaitingCard(session = session)
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
            item("nfc_banner") {
                NfcBanner(
                    nfcAvailable = nfcAvailable,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
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

@Composable
private fun CpWaitingCard(session: ScanSession?) {
    val hasCheckpoint = session?.point != null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 14.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (hasCheckpoint) {
                CpBadge(number = session?.checkpointNumber, size = 64.dp)
            } else {
                CpBadgeEmpty(size = 64.dp)
            }
            Column {
                Text(
                    text = "Метка КП",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (hasCheckpoint) "КП ${session?.checkpointNumber}" else "КП не отсканирован",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Text(
                    text = if (hasCheckpoint) {
                        "Стоимость: ${session?.cost} баллов"
                    } else {
                        "Поднесите телефон к чипу на КП"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CpBadge(number: Int?, size: Dp) {
    val height = size * 0.86f
    Surface(
        modifier = Modifier.size(width = size, height = height),
        shape = MaterialTheme.shapes.small,
        color = BrandRed,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = number?.toString() ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun CpBadgeEmpty(size: Dp) {
    val height = size * 0.86f
    val stripeHeight = height * 0.11f
    Box(
        modifier = Modifier
            .size(width = size, height = height)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.small)
            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(stripeHeight)
                .align(Alignment.TopStart)
                .background(BrandRed.copy(alpha = 0.78f))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(stripeHeight)
                .align(Alignment.BottomStart)
                .background(BrandRed.copy(alpha = 0.78f))
        )
        Text(
            text = "?",
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.Center),
        )
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

@Composable
private fun HeroSuccessCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 14.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiary,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(Color.White.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Готово!",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "КП и вся команда отсканированы",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun HeroTimerCard(
    seconds: Float,
    total: Float,
    remainingScans: Int,
    waitingForCheckpoint: Boolean,
) {
    val pct = if (total > 0f) (seconds / total).coerceIn(0f, 1f) else 0f
    // The timer ticks every TIMER_TICK_MS, so pct arrives in steps. Tween between steps (linear, one
    // tick long) so the ring sweeps smoothly instead of jumping every 250 ms.
    val animatedPct by animateFloatAsState(
        targetValue = pct,
        animationSpec = tween(durationMillis = TIMER_TICK_MS.toInt(), easing = LinearEasing),
        label = "timerRing",
    )
    val ringColor = if (seconds < 5f) Color(0xFFFFB4AB) else Color(0xFFFFC98A)
    val trackColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.12f)
    val chipWord = when {
        remainingScans % 100 in 11..14 -> "чипов"
        remainingScans % 10 == 1 -> "чип"
        remainingScans % 10 in 2..4 -> "чипа"
        else -> "чипов"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 14.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 4.dp.toPx()
                    val radius = size.width / 2 - strokeWidth / 2
                    drawCircle(
                        color = trackColor,
                        radius = radius,
                        style = Stroke(width = strokeWidth),
                    )
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * animatedPct,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = seconds.toInt().toString(),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 28.sp,
                        ),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                    Text(
                        text = "сек",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.65f),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                    )
                    Text(
                        text = "Сканируйте",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.65f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (waitingForCheckpoint) {
                        "КП и ещё $remainingScans $chipWord"
                    } else {
                        "Осталось $remainingScans $chipWord"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Таймер сбрасывается на ${total.toInt()} с при каждом скане",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.65f),
                )
            }
        }
    }
}

@Composable
private fun NfcBanner(nfcAvailable: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    if (nfcAvailable) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
                    else MaterialTheme.colorScheme.errorContainer,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        if (nfcAvailable) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.error,
                        CircleShape,
                    )
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (nfcAvailable) "NFC активен" else "NFC недоступен",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (nfcAvailable) {
                    "Приложите телефон к КП или чипу команды"
                } else {
                    "Сканирование NFC на этом устройстве недоступно"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
