package ru.kolco24.kolco24.ui.admin

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Nfc
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kolco24.kolco24.BuildConfig
import ru.kolco24.kolco24.Kolco24App
import ru.kolco24.kolco24.MainActivity
import ru.kolco24.kolco24.data.api.PostResult
import ru.kolco24.kolco24.data.nfc.ChipWriteResult
import ru.kolco24.kolco24.data.nfc.chipCodeFromHex
import ru.kolco24.kolco24.data.nfc.writeChipCodeNdef
import ru.kolco24.kolco24.data.normalizeNfcUid
import ru.kolco24.kolco24.ui.legend.CheckpointColor
import ru.kolco24.kolco24.ui.legend.parseCheckpointColor
import ru.kolco24.kolco24.ui.theme.CpColorBlue
import ru.kolco24.kolco24.ui.theme.CpColorPurple
import ru.kolco24.kolco24.ui.theme.CpColorRed
import ru.kolco24.kolco24.ui.theme.CpColorYellow
import ru.kolco24.kolco24.ui.theme.OrangeCta
import ru.kolco24.kolco24.ui.theme.RobotoMono
import ru.kolco24.kolco24.ui.theme.Tertiary

/**
 * Compose surface of the chip-provisioning flow. The stateful [ProvisioningScreen] host owns the
 * `HorizontalPager` over the race's checkpoints, arms the NFC bind/write side effects, and feeds the
 * four signature stateless pieces (rail, hero КП card, chip rack, scan zone). The pure model +
 * decisions live in `ProvisioningModel.kt`. The leaf composables are intentionally stateless: the host
 * passes the settled КП, the bound counts, and the live [ProvisionState].
 *
 * The `CheckpointColor → Color` mapping ([barColor]) lives here (a Compose file), mirroring the same
 * private mapping in `LegendScreen.kt` — fixed saturated shades, identical in light and dark.
 */

/**
 * Stateful host of the bulk chip-provisioning pager. Pages over the [raceId] race's checkpoints (one
 * КП per page); NFC is armed throughout via [MainActivity.onTagForProvision]. A chip tapped while the
 * pager is **settled** (not mid-swipe) binds to the current КП (`POST .../tags/`) and the
 * server-returned hex `code` is written onto it ([writeChipCodeNdef]) so the app can recognise the КП
 * offline. The rail/hero/rack are pre-seeded from cached [TagEntity] counts and this session's fresh
 * writes. [raceId] is the selected team's race — `null` shows a hint. [onClose] dismisses the overlay.
 *
 * No client-side type filter: the server is authoritative on which checkpoints are bindable, so a tap
 * on a non-bindable КП surfaces inline (404/400) in the scan zone rather than being hidden from the pager.
 * A `401` clears the admin session ([AdminAuthRepository.onUnauthorized]) and closes the overlay,
 * dropping back to the login form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvisioningScreen(
    raceId: Int?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as Kolco24App).container }
    val activity = context as? MainActivity
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // Swallow taps so they don't fall through to the screen behind the overlay.
            .pointerInput(Unit) { detectTapGestures {} },
    ) {
        TopAppBar(
            title = { Text("Привязка чипов") },
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
            ProvisioningHint("Сначала выберите команду — чипы привязываются к гонке выбранной команды")
            return@Column
        }

        val checkpoints by remember(raceId) {
            container.legendRepository.checkpointsForRace(raceId)
        }.collectAsState(initial = emptyList())
        // collectAsState does not reset on key change — filter stale rows from the prior race.
        val cps = checkpoints.filter { it.raceId == raceId }

        val tags by remember(raceId) {
            container.legendRepository.tagsForRace(raceId)
        }.collectAsState(initial = emptyList())
        // Cached per-КП «уже привязано» counts, pre-seeded from the legend's tag rows.
        val cachedCounts = remember(tags, raceId) { tags.filter { it.raceId == raceId }.groupingBy { it.point }.eachCount() }

        // uids written this session, keyed by checkpoint.id; set-semantics (dedupe on add).
        val freshTokens = remember(raceId) { mutableStateMapOf<Int, List<String>>() }

        if (cps.isEmpty()) {
            ProvisioningHint("Нет контрольных пунктов для этой гонки")
            return@Column
        }

        val pagerState = rememberPagerState(pageCount = { cps.size })
        var provisionState by remember { mutableStateOf<ProvisionState>(ProvisionState.WaitingForChip) }

        // Reset the scan zone whenever the pager settles on a different КП.
        LaunchedEffect(pagerState.currentPage) {
            provisionState = ProvisionState.WaitingForChip
        }
        // Light haptic stamp on a successful write.
        LaunchedEffect(provisionState) {
            if (provisionState is ProvisionState.Success) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }

        // Long-lived hook reads the latest checkpoints without re-arming on every recomposition.
        val cpsState = rememberUpdatedState(cps)
        DisposableEffect(raceId) {
            val host = activity
            host?.onTagForProvision = onTag@{ tag ->
                // Bind only to the settled КП; ignore mid-swipe taps and re-taps while already busy.
                if (pagerState.isScrollInProgress) return@onTag
                val current = provisionState
                if (current is ProvisionState.Binding || current == ProvisionState.Writing) return@onTag
                val cp = cpsState.value.getOrNull(pagerState.settledPage) ?: return@onTag
                val uid = normalizeNfcUid(tag.id)
                provisionState = ProvisionState.Binding(uid)
                // applicationScope survives overlay dismissal so the NFC write completes even if
                // the user navigates away after the server bind but before the chip write finishes.
                container.applicationScope.launch {
                    when (val result = container.apiClient.bindTag(raceId, cp.id, uid)) {
                        is PostResult.Success -> {
                            withContext(Dispatchers.Main) { provisionState = ProvisionState.Writing }
                            val bytes = try {
                                chipCodeFromHex(result.data.code)
                            } catch (_: IllegalArgumentException) {
                                withContext(Dispatchers.Main) {
                                    provisionState = ProvisionState.Failed("Неверный код от сервера")
                                }
                                return@launch
                            }
                            val written = withContext(Dispatchers.IO) {
                                writeChipCodeNdef(tag, bytes, BuildConfig.APPLICATION_ID)
                            }
                            withContext(Dispatchers.Main) {
                                if (written == ChipWriteResult.Success) {
                                    // A re-tap of an already-written chip is idempotent (200 + same code).
                                    val existing = freshTokens[cp.id].orEmpty()
                                    if (uid !in existing) freshTokens[cp.id] = existing + uid
                                    provisionState = ProvisionState.Success(result.data.number)
                                } else {
                                    provisionState =
                                        ProvisionState.Failed("Не удалось записать, приложите снова")
                                }
                            }
                        }
                        // 401: token revoked/expired server-side — clear the session and drop to login.
                        PostResult.Unauthorized -> {
                            container.adminAuthRepository.onUnauthorized()
                            withContext(Dispatchers.Main) { onClose() }
                        }
                        else -> withContext(Dispatchers.Main) {
                            provisionState = ProvisionState.Failed(provisionErrorMessage(result))
                        }
                    }
                }
            }
            onDispose { host?.onTagForProvision = null }
        }

        // Rail coverage = cached bound chips + this session's fresh writes.
        val boundCounts = cps.associate { cp ->
            cp.id to ((cachedCounts[cp.id] ?: 0) + (freshTokens[cp.id]?.size ?: 0))
        }

        Spacer(Modifier.height(12.dp))
        ProvisioningRail(
            ticks = railTicks(cps, boundCounts, pagerState.currentPage),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(20.dp))
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp),
        ) { page ->
            val cp = cps[page]
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                HeroCheckpointCard(
                    number = cp.number,
                    cost = cp.cost,
                    color = parseCheckpointColor(cp.color),
                )
                Spacer(Modifier.height(16.dp))
                ChipRack(
                    preSeededCount = cachedCounts[cp.id] ?: 0,
                    freshTokens = freshTokens[cp.id].orEmpty(),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        ScanZone(state = provisionState, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/** Centered muted hint for the no-race / no-checkpoints states. */
@Composable
private fun ProvisioningHint(text: String) {
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

/** Fixed `CheckpointColor → Color` palette (same in light & dark); green/orange reuse brand tokens. */
internal fun CheckpointColor.barColor(): Color = when (this) {
    CheckpointColor.RED -> CpColorRed
    CheckpointColor.BLUE -> CpColorBlue
    CheckpointColor.GREEN -> Tertiary
    CheckpointColor.YELLOW -> CpColorYellow
    CheckpointColor.ORANGE -> OrangeCta
    CheckpointColor.PURPLE -> CpColorPurple
}

/**
 * The provisioning rail: one segmented tick per race checkpoint (pager order). A filled tick is tinted
 * by its [RailTick.color] (or a neutral track when `null`/unbound); the settled [RailTick.current] tick
 * is enlarged. Purely decorative coverage indicator.
 */
@Composable
fun ProvisioningRail(ticks: List<RailTick>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val neutral = MaterialTheme.colorScheme.outlineVariant
        ticks.forEach { tick ->
            val tint = tick.color?.barColor() ?: neutral
            val color = if (tick.filled) tint else neutral
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(if (tick.current) 10.dp else 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

/**
 * The hero КП card — a huge [RobotoMono] checkpoint [number] (~96sp) over a color band, with the
 * [cost] beneath. [color] tints the band (transparent when `null`). The headline of the page.
 */
@Composable
fun HeroCheckpointCard(
    number: Int,
    cost: Int?,
    color: CheckpointColor?,
    modifier: Modifier = Modifier,
) {
    val band = color?.barColor() ?: Color.Transparent
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(160.dp)
                    .background(band),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "КП",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = number.toString().padStart(2, '0'),
                    fontFamily = RobotoMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 96.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = cost?.let { "$it баллов" } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The chip rack: the КП's pre-seeded «уже привязано» count (cached [TagEntity] rows) plus the chips
 * freshly written this session (each labelled by its uid tail via [chipTokenLabel] with a check).
 */
@Composable
fun ChipRack(
    preSeededCount: Int,
    freshTokens: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (preSeededCount > 0) {
            Text(
                text = "Уже привязано: $preSeededCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (freshTokens.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                freshTokens.forEach { uid -> FreshChipToken(uid) }
            }
        }
    }
}

/** One freshly-written chip token — green pill with a check and the uid tail. */
@Composable
private fun FreshChipToken(uid: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Tertiary.copy(alpha = 0.15f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Tertiary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = chipTokenLabel(uid),
                fontFamily = RobotoMono,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The scan zone — the live status line under the КП. In [ProvisionState.WaitingForChip] it pulses an
 * NFC glyph + «Приложите чип к телефону»; the working states show progress text; [ProvisionState.Success]
 * scales in (haptic stamped by the host) and [ProvisionState.Failed] shows the RU error in `error`.
 *
 * [reducedMotion] suppresses the pulse + scale-in (instant) when the user disabled animations.
 */
@Composable
fun ScanZone(
    state: ProvisionState,
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val pulse = if (reducedMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "scan-pulse")
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "scan-pulse-alpha",
        ).value
    }
    // Scales from 0.8 → 1 when the state settles on Success; instant (snapped to 1) under reduced motion.
    val successScale by animateFloatAsState(
        targetValue = if (state is ProvisionState.Success) 1f else 0.8f,
        animationSpec = if (reducedMotion) tween(0) else tween(220),
        label = "success-scale",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            ProvisionState.WaitingForChip -> {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .alpha(pulse)
                        .background(OrangeCta.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Nfc,
                        contentDescription = null,
                        tint = OrangeCta,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                ScanText("Приложите чип к телефону", MaterialTheme.colorScheme.onSurfaceVariant)
            }

            is ProvisionState.Binding ->
                ScanText("Привязываем…", MaterialTheme.colorScheme.onSurfaceVariant)

            ProvisionState.Writing ->
                ScanText("Записываем чип…", MaterialTheme.colorScheme.onSurfaceVariant)

            is ProvisionState.Success ->
                Box(modifier = Modifier.scale(successScale)) {
                    ScanText("Готово · №${state.number}", Tertiary)
                }

            is ProvisionState.Failed ->
                ScanText(state.reason, MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ScanText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        textAlign = TextAlign.Center,
    )
}
