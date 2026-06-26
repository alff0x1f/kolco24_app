package ru.kolco24.kolco24.ui.admin

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Nfc
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.kolco24.kolco24.Kolco24App
import ru.kolco24.kolco24.MainActivity
import ru.kolco24.kolco24.data.crypto.LegendCrypto
import ru.kolco24.kolco24.data.nfc.readChipCode
import ru.kolco24.kolco24.data.normalizeNfcUid
import ru.kolco24.kolco24.data.pluralRu
import ru.kolco24.kolco24.ui.legend.CheckpointColor
import ru.kolco24.kolco24.ui.theme.OrangeCta
import ru.kolco24.kolco24.ui.theme.RobotoMono
import ru.kolco24.kolco24.ui.theme.Tertiary

/**
 * Compose host of the read-only chip-verification overlay («Проверка чипов КП»). The admin taps a
 * chip; the host reads its code over NfcA, derives the `bid`, matches it against the **already
 * collected** legend tags/checkpoints for [raceId], and renders the [ChipCheckResult]. Identity-only
 * and fully offline — no `unlock`/decrypt, no `reveal` side effect, no server round-trip (see the
 * pure model in `ChipCheckModel.kt`).
 *
 * Mirrors `ProvisioningScreen`'s overlay scaffold (tap-swallowing `Column`, `TopAppBar` with a back
 * arrow, a `DisposableEffect(raceId)` that arms/clears [MainActivity.onTagForVerify]) but keeps its
 * result state in transient `remember` rather than app-scoped flows: verification has no in-flight
 * write to survive rotation, so a composition-scoped [rememberCoroutineScope] is used (a late
 * `readChipCode` result can't write stale state into a disposed composition) and losing the last
 * result + recent log on rotation is acceptable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckChipScreen(
    raceId: Int?,
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
            title = { Text("Проверка чипов КП") },
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
            CheckChipHint("Сначала выберите команду в разделе «Команда»")
            return@Column
        }

        // Use null as sentinel: null = first emission not yet received, emptyList = loaded (possibly empty).
        // This prevents processing scans against stale empty data during the brief Room delivery window.
        val tagsState by remember(raceId) {
            container.legendRepository.tagsForRace(raceId)
        }.collectAsState(initial = null)

        val checkpointsState by remember(raceId) {
            container.legendRepository.checkpointsForRace(raceId)
        }.collectAsState(initial = null)

        val dataReady = tagsState != null && checkpointsState != null
        val tags = tagsState ?: emptyList()
        val checkpoints = checkpointsState ?: emptyList()
        val checkpointsById = remember(checkpoints) { checkpoints.associateBy { it.id } }
        val countsByCheckpointId = remember(tags) { tags.groupingBy { it.checkpointId }.eachCount() }

        var lastResult by remember(raceId) { mutableStateOf<ChipCheckResult?>(null) }
        val recent = remember(raceId) { mutableStateListOf<ChipCheckResult>() }
        val mutex = remember(raceId) { Mutex() }
        val scope = rememberCoroutineScope()

        // Long-lived hook reads the latest collected lists without re-arming on every recomposition.
        val dataReadyLatest = rememberUpdatedState(dataReady)
        val tagsLatest = rememberUpdatedState(tags)
        val cpByIdLatest = rememberUpdatedState(checkpointsById)
        val countsLatest = rememberUpdatedState(countsByCheckpointId)

        DisposableEffect(raceId) {
            val host = activity
            // The hook fires on the main thread (mainHandler.post in onTagDiscovered) and the scope
            // is Main-confined, so only readChipCode needs withContext(IO); the state writes after it
            // resumes are already on Main. The Mutex serializes overlapping binder-thread taps.
            host?.onTagForVerify = { tag ->
                scope.launch {
                    mutex.withLock {
                        // Ignore scans until both legend flows have delivered their first emission.
                        if (!dataReadyLatest.value) return@withLock
                        val uid = normalizeNfcUid(tag.id)
                        val code = withContext(Dispatchers.IO) { readChipCode(tag) }
                        val bid = code?.let { LegendCrypto.bid(it) }
                        val tagRow = bid?.let { b -> tagsLatest.value.firstOrNull { it.bid == b } }
                        val cp = tagRow?.let { cpByIdLatest.value[it.checkpointId] }
                        val chipsOnKp = tagRow?.let { countsLatest.value[it.checkpointId] ?: 0 } ?: 0
                        val result = classifyChipCheck(uid, bid, tagRow, cp, chipsOnKp)
                        lastResult = result
                        recent.add(0, result)
                        // removeAt(lastIndex), NOT removeLast(): the stdlib extension trips a NewApi
                        // lint error against JDK 21 SequencedCollection.removeLast on minSdk 24.
                        if (recent.size > 20) recent.removeAt(recent.lastIndex)
                    }
                }
            }
            onDispose { host?.onTagForVerify = null }
        }

        Spacer(Modifier.height(16.dp))
        CheckChipHero(
            result = lastResult,
            // The chip scanned just before this one — recent[0] is the current result, recent[1] the
            // previous; their UID diff highlights the digits that changed between the two.
            previousUid = recent.getOrNull(1)?.uid,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Недавние проверки",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(recent) { index, result ->
                    // recent is newest-first, so the scan before this row is the next (older) item.
                    RecentCheckRow(result, previousUid = recent.getOrNull(index + 1)?.uid)
                }
            }
        }
    }
}

/** Centered muted hint for the no-race state. */
@Composable
private fun CheckChipHint(text: String) {
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

/**
 * The hero result card. `null` → a pulsing NFC glyph + «Приложите чип КП»; the four [ChipCheckResult]
 * variants render their status verbatim (КП number/cost/color band + diagnostics on [ChipCheckResult.Ok];
 * the amber/red messages otherwise).
 */
@Composable
private fun CheckChipHero(
    result: ChipCheckResult?,
    previousUid: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        when (result) {
            null -> IdleHero()
            is ChipCheckResult.Ok -> OkHero(result, previousUid)
            is ChipCheckResult.UnknownChip -> MessageHero(
                color = OrangeCta,
                icon = Icons.Filled.Warning,
                title = "Чип не привязан к КП этой гонки (другая гонка или устаревший список)",
                uid = result.uid,
                previousUid = previousUid,
                diagnostic = "bid ${result.bid}",
            )
            is ChipCheckResult.Inconsistent -> MessageHero(
                color = MaterialTheme.colorScheme.error,
                icon = Icons.Filled.Error,
                title = "КП id=${result.checkpointId} нет в легенде — обновите данные",
                uid = result.uid,
                previousUid = previousUid,
                diagnostic = "bid ${result.bid}",
            )
            is ChipCheckResult.NoCode -> MessageHero(
                color = OrangeCta,
                icon = Icons.Filled.HelpOutline,
                title = "Нет кода КП: пустой чип, браслет участника или ошибка чтения — приложите ещё раз",
                uid = result.uid,
                previousUid = previousUid,
                diagnostic = null,
            )
        }
    }
}

/** Idle state — pulsing NFC glyph + prompt (mirrors `ProvisioningScreen.ScanZone`). */
@Composable
private fun IdleHero() {
    val transition = rememberInfiniteTransition(label = "verify-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "verify-pulse-alpha",
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
            text = "Приложите чип КП",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The «Привязан корректно» hero: color band + КП number + cost + green check + diagnostics. */
@Composable
private fun OkHero(result: ChipCheckResult.Ok, previousUid: String?) {
    val band = result.color?.barColor() ?: Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(8.dp)
                .fillMaxHeight()
                .background(band),
        )
        Column(
            modifier = Modifier.weight(1f).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "КП",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = result.number.toString().padStart(2, '0'),
                fontFamily = RobotoMono,
                fontWeight = FontWeight.Bold,
                fontSize = 96.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = result.cost?.let { "$it ${pluralRu(it, "балл", "балла", "баллов")}" } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    text = "Привязан корректно",
                    style = MaterialTheme.typography.titleMedium,
                    color = Tertiary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            UidDiff(uid = result.uid, previousUid = previousUid, fontSize = 18.sp)
            val others = (result.chipsOnKp - 1).coerceAtLeast(0)
            if (others > 0) {
                Text(
                    text = "На этом КП ещё $others чип(ов)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${result.bid} · ${result.checkMethod}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A non-Ok hero: colored status icon, message, the chip uid, and an optional diagnostic line. */
@Composable
private fun MessageHero(
    color: Color,
    icon: ImageVector,
    title: String,
    uid: String,
    previousUid: String?,
    diagnostic: String?,
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
        Spacer(Modifier.height(8.dp))
        UidDiff(uid = uid, previousUid = previousUid, fontSize = 18.sp)
        if (diagnostic != null) {
            Text(
                text = diagnostic,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The chip UID in monospace, grouped into byte pairs (`1D C7 60 63 03 10 80`), with the nibbles that
 * changed since [previousUid] lit up in [OrangeCta] and the shared boilerplate dimmed — so the digits
 * that differ between two chips are the only thing the eye lands on. With no [previousUid] (the first
 * chip of the session, or a rescan of the same chip) the uid renders plain at full emphasis.
 */
@Composable
private fun UidDiff(
    uid: String,
    previousUid: String?,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dimColor = baseColor.copy(alpha = 0.5f)
    val text = remember(uid, previousUid, baseColor) {
        val changed = changedNibbles(uid, previousUid)
        // A previous uid exists but nothing changed (rescanned the same chip) → still dim, not plain.
        val hasBaseline = !previousUid.isNullOrEmpty()
        buildAnnotatedString {
            uid.forEachIndexed { i, c ->
                if (i > 0 && i % 2 == 0) append(' ') // group into bytes for at-a-glance comparison
                val isChanged = i in changed
                withStyle(
                    SpanStyle(
                        color = when {
                            isChanged -> OrangeCta
                            hasBaseline -> dimColor
                            else -> baseColor
                        },
                        fontWeight = if (isChanged) FontWeight.Bold else FontWeight.Normal,
                        background = if (isChanged) OrangeCta.copy(alpha = 0.14f) else Color.Transparent,
                    ),
                ) { append(c) }
            }
        }
    }
    Text(
        text = text,
        fontFamily = RobotoMono,
        fontSize = fontSize,
        modifier = modifier,
    )
}

/** One «Недавние проверки» row: color dot + КП label + diff-highlighted uid + status icon. */
@Composable
private fun RecentCheckRow(result: ChipCheckResult, previousUid: String?) {
    val neutral = MaterialTheme.colorScheme.outlineVariant
    val dot: Color
    val label: String
    val icon: ImageVector
    val iconTint: Color
    when (result) {
        is ChipCheckResult.Ok -> {
            dot = result.color?.barColor() ?: neutral
            label = "КП ${result.number.toString().padStart(2, '0')}"
            icon = Icons.Filled.Check
            iconTint = Tertiary
        }
        is ChipCheckResult.UnknownChip -> {
            dot = neutral
            label = "—"
            icon = Icons.Filled.Warning
            iconTint = OrangeCta
        }
        is ChipCheckResult.Inconsistent -> {
            dot = neutral
            label = "—"
            icon = Icons.Filled.Error
            iconTint = MaterialTheme.colorScheme.error
        }
        is ChipCheckResult.NoCode -> {
            dot = neutral
            label = "—"
            icon = Icons.Filled.HelpOutline
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
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
            Text(
                text = label,
                fontFamily = RobotoMono,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            UidDiff(uid = result.uid, previousUid = previousUid, fontSize = 13.sp)
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
