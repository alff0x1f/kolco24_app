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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.kolco24.kolco24.Kolco24App
import ru.kolco24.kolco24.MainActivity
import ru.kolco24.kolco24.data.nfc.readChipCode
import ru.kolco24.kolco24.data.normalizeNfcUid
import ru.kolco24.kolco24.data.pluralRu
import ru.kolco24.kolco24.ui.scan.ScanFeedbackKind
import ru.kolco24.kolco24.ui.theme.OrangeCta
import ru.kolco24.kolco24.ui.theme.RobotoMono
import ru.kolco24.kolco24.ui.theme.Tertiary

/**
 * Compose host of the read-only member-bracelet verification overlay («Проверка браслетов
 * участников»). The admin taps a chip; the host normalizes its UID and matches it against the
 * **already collected** member-tag pool for [raceId], rendering the [MemberChipCheckResult] (see the
 * pure model in `MemberChipCheckModel.kt`). Identity-only and fully offline — the pool is the
 * server-synced `member_tags` table; local member↔chip bindings are per-device and deliberately not
 * consulted. Only when the UID is **not** in the pool does the host read the on-chip code, purely to
 * tell a mis-tapped КП chip apart from an unknown bracelet.
 *
 * Mirrors `CheckChipScreen`'s scaffold verbatim: tap-swallowing `Column`, a `DisposableEffect(raceId)`
 * arming [MainActivity.onTagForVerify] (the two verify overlays are mutually exclusive, so sharing
 * the hook is safe), transient `remember` result state on a composition-scoped
 * [rememberCoroutineScope], null-sentinel Flow initial so scans are ignored until the pool's first
 * Room emission.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckMemberChipScreen(
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
            title = { Text("Проверка браслетов") },
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
            CheckMemberChipHint("Сначала выберите команду в разделе «Команда»")
            return@Column
        }

        // Use null as sentinel: null = first emission not yet received, emptyList = loaded (possibly
        // empty). Prevents classifying scans against stale empty data during the Room delivery window.
        val poolState by remember(raceId) {
            container.memberTagsRepository.observeForRace(raceId)
        }.collectAsState(initial = null)

        val dataReady = poolState != null
        val pool = poolState ?: emptyList()

        var lastResult by remember(raceId) { mutableStateOf<MemberChipCheckResult?>(null) }
        val recent = remember(raceId) { mutableStateListOf<MemberChipCheckResult>() }
        val mutex = remember(raceId) { Mutex() }
        val scope = rememberCoroutineScope()

        // Long-lived hook reads the latest collected pool without re-arming on every recomposition.
        val dataReadyLatest = rememberUpdatedState(dataReady)
        val poolLatest = rememberUpdatedState(pool)

        DisposableEffect(raceId) {
            val host = activity
            // The hook fires on the main thread (mainHandler.post in onTagDiscovered) and the scope
            // is Main-confined, so only readChipCode needs withContext(IO); the state writes after it
            // resumes are already on Main. The Mutex serializes overlapping binder-thread taps.
            host?.onTagForVerify = { tag ->
                scope.launch {
                    mutex.withLock {
                        // Ignore scans until the pool flow has delivered its first emission.
                        if (!dataReadyLatest.value) {
                            container.scanFeedback.neutral()
                            return@withLock
                        }
                        val uid = normalizeNfcUid(tag.id)
                        val memberTag = poolLatest.value.firstOrNull { it.nfcUid == uid }
                        // The code read is a diagnostic for the not-in-pool branch only; a pooled UID
                        // is Ok regardless, so the happy path skips the (slow) NfcA transceive.
                        val hasKpCode = memberTag == null &&
                            withContext(Dispatchers.IO) { readChipCode(tag) } != null
                        val result = classifyMemberChipCheck(uid, memberTag, hasKpCode)
                        container.scanFeedback.play(
                            if (result is MemberChipCheckResult.Ok) ScanFeedbackKind.Success
                            else ScanFeedbackKind.Failure,
                        )
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
        CheckMemberChipHero(
            result = lastResult,
            // The chip scanned just before this one — recent[0] is the current result, recent[1] the
            // previous; their UID diff highlights the digits that changed between the two.
            previousUid = recent.getOrNull(1)?.uid,
            poolSize = pool.size,
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
                    RecentMemberCheckRow(result, previousUid = recent.getOrNull(index + 1)?.uid)
                }
            }
        }
    }
}

/** Centered muted hint for the no-race state. */
@Composable
private fun CheckMemberChipHint(text: String) {
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
 * The hero result card. `null` → a pulsing NFC glyph + «Приложите браслет участника» with the pool
 * size as a subline (a `0` doubles as the "pool not synced" tell); the three [MemberChipCheckResult]
 * variants render their status verbatim.
 */
@Composable
private fun CheckMemberChipHero(
    result: MemberChipCheckResult?,
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
            null -> MemberIdleHero(poolSize)
            is MemberChipCheckResult.Ok -> MemberOkHero(result, previousUid)
            is MemberChipCheckResult.KpChip -> MemberMessageHero(
                color = OrangeCta,
                icon = Icons.Filled.Warning,
                title = "Это чип КП, а не браслет участника",
                uid = result.uid,
                previousUid = previousUid,
            )
            is MemberChipCheckResult.Unknown -> MemberMessageHero(
                color = OrangeCta,
                icon = Icons.Filled.HelpOutline,
                title = "Чип не найден в списке участников этой гонки (другая гонка или устаревший список)",
                uid = result.uid,
                previousUid = previousUid,
            )
        }
    }
}

/** Idle state — pulsing NFC glyph + prompt + pool size (mirrors `CheckChipScreen.IdleHero`). */
@Composable
private fun MemberIdleHero(poolSize: Int) {
    val transition = rememberInfiniteTransition(label = "member-verify-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "member-verify-pulse-alpha",
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
            text = if (poolSize > 0) {
                "В списке $poolSize ${pluralRu(poolSize, "браслет", "браслета", "браслетов")}"
            } else {
                "Список браслетов пуст — обновите данные"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The «Браслет участника» hero: big participant number + green check + uid diagnostics. */
@Composable
private fun MemberOkHero(result: MemberChipCheckResult.Ok, previousUid: String?) {
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
                text = "Браслет участника",
                style = MaterialTheme.typography.titleMedium,
                color = Tertiary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(12.dp))
        UidDiff(uid = result.uid, previousUid = previousUid, fontSize = 18.sp)
    }
}

/** A non-Ok hero: colored status icon, message, and the chip uid. */
@Composable
private fun MemberMessageHero(
    color: Color,
    icon: ImageVector,
    title: String,
    uid: String,
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
        Spacer(Modifier.height(8.dp))
        UidDiff(uid = uid, previousUid = previousUid, fontSize = 18.sp)
    }
}

/** One «Недавние проверки» row: participant label + diff-highlighted uid + status icon. */
@Composable
private fun RecentMemberCheckRow(result: MemberChipCheckResult, previousUid: String?) {
    val label: String
    val icon: ImageVector
    val iconTint: Color
    when (result) {
        is MemberChipCheckResult.Ok -> {
            label = "№${result.number}"
            icon = Icons.Filled.Check
            iconTint = Tertiary
        }
        is MemberChipCheckResult.KpChip -> {
            label = "КП"
            icon = Icons.Filled.Warning
            iconTint = OrangeCta
        }
        is MemberChipCheckResult.Unknown -> {
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
