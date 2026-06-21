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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kolco24.kolco24.BuildConfig
import ru.kolco24.kolco24.Kolco24App
import ru.kolco24.kolco24.MainActivity
import ru.kolco24.kolco24.data.api.PostResult
import ru.kolco24.kolco24.data.nfc.CHIP_CODE_BYTES
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

        // App-scoped so the map survives activity rotation: the bind+write job holds a reference to
        // the AppContainer flow, not the composition, so a rotation mid-write still updates the rack.
        val freshTokensMap by container.provisioningFreshTokens.collectAsState()
        // Application-scoped lock: survives overlay close/reopen and activity rotation so a
        // composition reset can't open a second concurrent job while the original is still running.
        val isBusy = container.provisioningLock
        // Collected before pagerState so their snapshot values can be used for initialPage without
        // a direct .value read (which lint flags as StateFlowValueCalledInComposition).
        val provisionState by container.provisioningState.collectAsState()
        val activePageIdx by container.provisioningActivePage.collectAsState()

        if (cps.isEmpty()) {
            ProvisioningHint("Нет контрольных пунктов для этой гонки")
            return@Column
        }

        // Restore the pager to the active checkpoint on reopen after a close-during-job.
        val startPage = if (provisionState is ProvisionState.WaitingForChip) {
            0
        } else {
            activePageIdx.coerceIn(0, cps.size - 1)
        }
        val pagerState = rememberPagerState(initialPage = startPage, pageCount = { cps.size })

        // Reset the scan zone whenever the pager settles on a different КП — but not while a
        // bind/write job is in flight, and not when a terminal state (Success/Failed) belongs to
        // the active page (so closing/reopening or rotating mid-job doesn't clobber the result).
        LaunchedEffect(pagerState.settledPage) {
            // Read the StateFlow directly so the guard reflects the latest value even if the
            // Compose snapshot (collectAsState) hasn't propagated yet — an NFC tap arriving just
            // after page settlement sets Binding on the binder thread; reading the snapshot could
            // still see WaitingForChip and overwrite Binding before the frame propagates the update.
            val current = container.provisioningState.value
            if (current !is ProvisionState.Binding && current !is ProvisionState.Writing) {
                val isTerminal = current is ProvisionState.Success || current is ProvisionState.Failed
                val isActivePage = pagerState.settledPage == container.provisioningActivePage.value
                if (!isTerminal || !isActivePage) {
                    container.provisioningState.value = ProvisionState.WaitingForChip
                }
            }
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
            val previousActiveRaceId = container.provisioningActiveRaceId.value
            if (previousActiveRaceId == null || previousActiveRaceId == raceId) {
                // Same race (or no prior session): re-opening means cleanup is no longer needed.
                container.provisioningPendingCleanup.set(false)
            } else {
                // Different race: reset stale state from race A immediately so it doesn't bleed
                // into this provisioning session.
                container.provisioningState.value = ProvisionState.WaitingForChip
                container.provisioningFreshTokens.value = emptyMap()
                container.provisioningActivePage.value = 0
                if (!isBusy.get()) {
                    // No job running; the pendingCleanup flag is stale from the previous onDispose.
                    container.provisioningPendingCleanup.set(false)
                }
                // else: old job is still in flight; leave pendingCleanup=true so its finally
                // block finishes cleanup (resetting to the same safe initial values we set above).
            }
            container.provisioningActiveRaceId.value = raceId
            val host = activity
            host?.onTagForProvision = onTag@{ tag ->
                // Bind only to the settled КП; ignore mid-swipe taps and concurrent jobs.
                if (pagerState.isScrollInProgress) return@onTag
                if (!isBusy.compareAndSet(false, true)) return@onTag
                val cp = cpsState.value.getOrNull(pagerState.settledPage) ?: run {
                    isBusy.set(false)
                    return@onTag
                }
                val uid = normalizeNfcUid(tag.id)
                // Record which page and race are active before starting: lets close/reopen and
                // rotation restore the pager to the correct checkpoint (see rememberPagerState
                // initialPage), and lets cross-race opens detect stale state (see DisposableEffect).
                container.provisioningActivePage.value = pagerState.settledPage
                container.provisioningActiveRaceId.value = raceId
                // Update the app-scoped flow directly — safe from any thread (MutableStateFlow
                // is thread-safe), and the flow outlives this composition so state is preserved
                // across activity rotation and overlay close/reopen.
                container.provisioningState.value = ProvisionState.Binding(uid)
                // applicationScope survives overlay dismissal so the NFC write completes even if
                // the user navigates away after the server bind but before the chip write finishes.
                container.applicationScope.launch {
                    try {
                        when (val result = container.apiClient.bindTag(raceId, cp.id, uid)) {
                            is PostResult.Success -> {
                                container.provisioningState.value = ProvisionState.Writing
                                val hexCode = result.data.code
                                val bytes = if (hexCode.length != CHIP_CODE_BYTES * 2) {
                                    null
                                } else {
                                    try {
                                        chipCodeFromHex(hexCode)
                                    } catch (_: IllegalArgumentException) {
                                        null
                                    }
                                }
                                if (bytes == null) {
                                    container.provisioningState.value =
                                        ProvisionState.Failed("Неверный код от сервера")
                                    return@launch
                                }
                                val written = withContext(Dispatchers.IO) {
                                    writeChipCodeNdef(tag, bytes, BuildConfig.APPLICATION_ID)
                                }
                                if (written == ChipWriteResult.Success) {
                                    // provisioningFreshTokens is a MutableStateFlow — thread-safe,
                                    // no withContext needed. isBusy prevents concurrent writes so
                                    // the read-then-write is safe. A re-tap is idempotent (uid guard).
                                    val prev = container.provisioningFreshTokens.value
                                    val existing = prev[cp.id].orEmpty()
                                    if (uid !in existing) {
                                        container.provisioningFreshTokens.value =
                                            prev + (cp.id to (existing + uid))
                                    }
                                    container.provisioningState.value =
                                        ProvisionState.Success(result.data.number)
                                } else {
                                    container.provisioningState.value =
                                        ProvisionState.Failed("Не удалось записать, приложите снова")
                                }
                            }
                            // 401: token revoked/expired server-side — clear the session and drop to login.
                            PostResult.Unauthorized -> {
                                container.adminAuthRepository.onUnauthorized()
                                withContext(Dispatchers.Main) { onClose() }
                            }
                            else -> {
                                container.provisioningState.value =
                                    ProvisionState.Failed(provisionErrorMessage(result))
                            }
                        }
                    } finally {
                        // Safety reset: if an unexpected early-exit left the state in-progress,
                        // clear it so no new job sees a stale Binding/Writing.
                        val current = container.provisioningState.value
                        if (current is ProvisionState.Binding || current is ProvisionState.Writing) {
                            container.provisioningState.value = ProvisionState.WaitingForChip
                        }
                        // Release the lock BEFORE checking pendingCleanup: onDispose (main thread)
                        // may be racing with this block (IO thread). By releasing first, we ensure
                        // that if onDispose already set the flag and then read isBusy=true, it defers
                        // to us; if onDispose fires after our release and sees isBusy=false, it takes
                        // ownership via compareAndSet without us double-cleaning.
                        isBusy.set(false)
                        // Deferred cleanup: the overlay was closed (not rotated) while this job was
                        // running. compareAndSet guarantees exactly one of (finally, onDispose)
                        // performs the reset, even when they race at the isBusy boundary.
                        if (container.provisioningPendingCleanup.compareAndSet(true, false)) {
                            // Guard: only reset state if we're still the active race. A Race B
                            // session may have opened between our onDispose and this finally block,
                            // already resetting state and arming its own hooks — clobbering its
                            // freshTokens or activeRaceId here would corrupt Race B's session.
                            val activeRace = container.provisioningActiveRaceId.value
                            if (activeRace == null || activeRace == raceId) {
                                container.provisioningState.value = ProvisionState.WaitingForChip
                                container.provisioningFreshTokens.value = emptyMap()
                                container.provisioningActivePage.value = 0
                                container.provisioningActiveRaceId.value = null
                            }
                        }
                    }
                }
            }
            onDispose {
                host?.onTagForProvision = null
                // Skip cleanup during rotation (isChangingConfigurations = true): the composition
                // restarts but the admin is still provisioning, so state should survive the rotation.
                if (host?.isChangingConfigurations != true) {
                    // Set the flag BEFORE reading isBusy so that a job releasing its lock
                    // concurrently will see the flag in its finally block and perform cleanup.
                    container.provisioningPendingCleanup.set(true)
                    if (!isBusy.get()) {
                        // No job is running (or it already finished and released isBusy). Take
                        // ownership of cleanup via compareAndSet so we don't double-clean if the
                        // job's finally raced between our set and this isBusy read.
                        if (container.provisioningPendingCleanup.compareAndSet(true, false)) {
                            container.provisioningState.value = ProvisionState.WaitingForChip
                            container.provisioningFreshTokens.value = emptyMap()
                            container.provisioningActivePage.value = 0
                            container.provisioningActiveRaceId.value = null
                        }
                    }
                    // else: job is running; its finally block will see the flag and clean up.
                }
            }
        }

        // Rail coverage: take the max of cached counts and fresh-session counts to avoid
        // double-counting when a legend refresh mid-session adds the just-written tags to cachedCounts.
        val boundCounts = cps.associate { cp ->
            cp.id to maxOf(cachedCounts[cp.id] ?: 0, freshTokensMap[cp.id]?.size ?: 0)
        }

        Spacer(Modifier.height(12.dp))
        ProvisioningRail(
            ticks = railTicks(cps, boundCounts, pagerState.currentPage),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(20.dp))
        HorizontalPager(
            state = pagerState,
            // Disable swiping while a bind/write job is in progress: prevents the settled-page
            // reset from firing mid-job (which would show WaitingForChip while the chip is still
            // being written) and removes the risk of premature chip removal.
            userScrollEnabled = provisionState !is ProvisionState.Binding &&
                provisionState !is ProvisionState.Writing,
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
                    // Subtract fresh-session count from cached count: after a mid-session legend
                    // refresh the server delivers the just-written tags into Room, growing
                    // cachedCounts to include them — without this subtraction the rack would
                    // show "Уже привязано: N+K" AND K fresh pills, double-counting K.
                    preSeededCount = maxOf(
                        0,
                        (cachedCounts[cp.id] ?: 0) - (freshTokensMap[cp.id]?.size ?: 0),
                    ),
                    freshTokens = freshTokensMap[cp.id].orEmpty(),
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
