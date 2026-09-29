package ru.kolco24.kolco24.ui.admin

import android.nfc.Tag
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.kolco24.kolco24.Kolco24App
import ru.kolco24.kolco24.MainActivity
import ru.kolco24.kolco24.data.api.PostResult
import ru.kolco24.kolco24.data.nfc.CHIP_TYPE_PARTICIPANT
import ru.kolco24.kolco24.data.nfc.readChipCodes
import ru.kolco24.kolco24.data.nfc.writeChipCode
import ru.kolco24.kolco24.data.normalizeNfcUid
import ru.kolco24.kolco24.data.pluralRu
import ru.kolco24.kolco24.ui.theme.OrangeCta
import ru.kolco24.kolco24.ui.theme.RobotoMono
import ru.kolco24.kolco24.ui.theme.Tertiary

/**
 * Compose host of «Записать браслет участника»: writes the server-issued secret code onto a
 * participant bracelet as a `K24` participant record. Transitions and strings live in the pure
 * `MemberProvisioningModel.kt`; this host owns the pool collection, the NFC hook, and the bind/write
 * side effects.
 *
 * A tap reads the chip (a КП chip is refused), then binds (`POST .../member_tags/bind/`): a UID in the
 * pool (or written this session) with `number = null`, an unknown one — or a `404` on `null` — after
 * the admin types the number. The [Tag] is kept through the bind and the code is written on the same
 * tap; if the bracelet left the field (e.g. while typing the number) the code stays pending until the
 * same bracelet is tapped again.
 *
 * Shares [MainActivity.onTagForProvision] with `ProvisioningScreen` (the two never co-open). Routing
 * matches КП provisioning: LAN server + LAN session while [raceId] is pinned, else cloud; a `401`
 * clears only that session and closes the overlay. State is composition-scoped: closing mid-bind drops
 * the result (the bind is idempotent, the write is header-last).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberProvisioningScreen(
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
            title = { Text("Запись браслетов") },
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
            MemberProvisioningHint("Сначала выберите команду — браслеты привязываются к гонке выбранной команды")
            return@Column
        }

        // null = first Room emission not yet received; taps are ignored until the pool is loaded.
        val poolState by remember(raceId) {
            container.memberTagsRepository.observeForRace(raceId)
        }.collectAsState(initial = null)
        val pool = poolState.orEmpty()

        var state by remember(raceId) { mutableStateOf<MemberProvisionState>(MemberProvisionState.WaitingForChip) }
        var feed by remember(raceId) { mutableStateOf(emptyList<FreshBracelet>()) }
        var nextNumber by remember(raceId) { mutableStateOf<Int?>(null) }
        // The tag of the last tap, kept so a bind that finishes while the bracelet is still in the
        // field (or after the number is typed) writes without a second tap.
        var lastTag by remember(raceId) { mutableStateOf<Tag?>(null) }
        var job by remember(raceId) { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()

        val poolLatest = rememberUpdatedState(poolState)

        // Runs on Main (the scope is Main-confined); only chip I/O and the POST leave it.
        suspend fun write(uid: String, number: Int, codeHex: String, tag: Tag?, retryHint: String) {
            val bytes = parseServerChipCode(codeHex)
            if (bytes == null) {
                state = MemberProvisionState.Failed("Неверный код от сервера")
                container.scanFeedback.failure()
                return
            }
            if (tag == null) {
                state = MemberProvisionState.WaitingForWrite(uid, number, codeHex, MEMBER_WRITE_AGAIN_HINT)
                return
            }
            state = MemberProvisionState.Writing(uid, number)
            val written = withContext(Dispatchers.IO) { writeChipCode(tag, bytes, CHIP_TYPE_PARTICIPANT) }
            val next = memberWriteOutcome(uid, number, codeHex, written, retryHint)
            state = next
            when (next) {
                is MemberProvisionState.Success -> {
                    feed = addFreshBracelet(feed, uid, number)
                    nextNumber = nextMemberNumber(number)
                    lastTag = null
                    container.scanFeedback.success()
                }
                is MemberProvisionState.Failed -> container.scanFeedback.failure()
                // A missed write right after typing the number is expected (the bracelet was lifted).
                else -> if (retryHint == MEMBER_WRITE_RETRY_HINT) container.scanFeedback.failure()
            }
        }

        suspend fun bind(uid: String, number: Int?) {
            state = MemberProvisionState.Binding(uid, number)
            val pinned = container.isRacePinned(raceId)
            val client = if (pinned) container.localApiClient else container.apiClient
            val auth = if (pinned) container.localAdminAuth else container.cloudAdminAuth
            if (auth.token() == null) {
                state = MemberProvisionState.Failed(
                    if (pinned) "Нет входа на LAN-сервер" else "Нет входа на cloud-сервер",
                )
                container.scanFeedback.failure()
                return
            }
            when (val result = client.bindMemberTag(raceId, uid, number)) {
                is PostResult.Success -> write(
                    uid = uid,
                    number = result.data.number,
                    codeHex = result.data.code,
                    tag = lastTag?.takeIf { normalizeNfcUid(it.id) == uid },
                    retryHint = if (number == null) MEMBER_WRITE_RETRY_HINT else MEMBER_WRITE_AGAIN_HINT,
                )
                PostResult.Unauthorized -> {
                    state = MemberProvisionState.WaitingForChip
                    auth.onUnauthorized()
                    onClose()
                }
                else -> {
                    val next = memberBindFailureState(uid, number, result)
                    state = next
                    if (next is MemberProvisionState.Failed) container.scanFeedback.failure()
                }
            }
        }

        fun launchJob(block: suspend () -> Unit) {
            job = scope.launch {
                try {
                    block()
                } finally {
                    // An unexpected early exit must not leave the zone stuck in progress.
                    val current = state
                    if (current is MemberProvisionState.Binding || current is MemberProvisionState.Writing) {
                        state = MemberProvisionState.WaitingForChip
                    }
                    job = null
                }
            }
        }

        DisposableEffect(raceId) {
            val host = activity
            host?.onTagForProvision = onTag@{ tag ->
                val uid = normalizeNfcUid(tag.id)
                // Rediscovery of the retained bracelet (re-presented while typing its number, or during
                // a bind) makes the old handle out of date — keep the fresh one even if the tap is ignored.
                if (lastTag?.let { normalizeNfcUid(it.id) } == uid) lastTag = tag
                if (job != null) return@onTag
                val currentPool = poolLatest.value ?: return@onTag
                when (val route = routeMemberTap(state, uid)) {
                    MemberTapRoute.Ignore -> Unit
                    is MemberTapRoute.Hint -> {
                        state = route.state
                        container.scanFeedback.failure()
                    }
                    is MemberTapRoute.Write -> {
                        lastTag = tag
                        launchJob {
                            write(uid, route.pending.number, route.pending.codeHex, tag, MEMBER_WRITE_RETRY_HINT)
                        }
                    }
                    MemberTapRoute.Classify -> {
                        lastTag = tag
                        launchJob {
                            val codes = withContext(Dispatchers.IO) { readChipCodes(tag) }
                            val known = isKnownBracelet(uid, currentPool, feed)
                            when (val action = classifyMemberTap(uid, codes, known)) {
                                is MemberTapAction.Fail -> {
                                    state = MemberProvisionState.Failed(action.reason)
                                    container.scanFeedback.failure()
                                }
                                is MemberTapAction.AskNumber -> {
                                    state = MemberProvisionState.NeedsNumber(action.uid)
                                    container.scanFeedback.neutral()
                                }
                                is MemberTapAction.BindKnown -> bind(action.uid, null)
                            }
                        }
                    }
                }
            }
            onDispose {
                host?.onTagForProvision = null
                job?.cancel()
            }
        }

        val cancel: () -> Unit = {
            if (job == null) {
                state = MemberProvisionState.WaitingForChip
                lastTag = null
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            MemberProvisionZone(
                state = state,
                poolSize = pool.size,
                nextNumber = nextNumber,
                onConfirmNumber = { number ->
                    val current = state
                    if (job == null && current is MemberProvisionState.NeedsNumber) {
                        launchJob { bind(current.uid, number) }
                    }
                },
                onCancel = cancel,
            )
            if (feed.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Записано: ${feed.size}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    feed.forEach { FreshBraceletToken(it) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Centered muted hint for the no-race state. */
@Composable
private fun MemberProvisioningHint(text: String) {
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

/** The status card: one body per [MemberProvisionState]. */
@Composable
private fun MemberProvisionZone(
    state: MemberProvisionState,
    poolSize: Int,
    nextNumber: Int?,
    onConfirmNumber: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                MemberProvisionState.WaitingForChip -> {
                    PulsingNfcGlyph()
                    Spacer(Modifier.height(12.dp))
                    ZoneTitle("Приложите браслет участника", MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    ZoneSubline(
                        if (poolSize > 0) {
                            "В списке $poolSize ${pluralRu(poolSize, "браслет", "браслета", "браслетов")}"
                        } else {
                            "Список браслетов пуст — номер введёте вручную"
                        },
                    )
                }

                is MemberProvisionState.NeedsNumber -> NumberEntry(
                    uid = state.uid,
                    nextNumber = nextNumber,
                    onConfirm = onConfirmNumber,
                    onCancel = onCancel,
                )

                is MemberProvisionState.Binding -> {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(12.dp))
                    ZoneTitle("Привязываем…", MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    ZoneSubline(state.number?.let { "№$it · ${chipTokenLabel(state.uid)}" } ?: chipTokenLabel(state.uid))
                }

                is MemberProvisionState.Writing -> {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(12.dp))
                    ZoneTitle("Записываем браслет…", MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    ZoneSubline("№${state.number} · не убирайте браслет")
                }

                is MemberProvisionState.WaitingForWrite -> {
                    BigNumber(state.number)
                    PulsingNfcGlyph()
                    Spacer(Modifier.height(12.dp))
                    ZoneTitle(state.hint, OrangeCta)
                    Spacer(Modifier.height(4.dp))
                    ZoneSubline("Браслет ${chipTokenLabel(state.uid)}")
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onCancel) { Text("Отмена") }
                }

                is MemberProvisionState.Success -> {
                    BigNumber(state.number)
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
                        ZoneTitle("Код записан", Tertiary)
                    }
                    Spacer(Modifier.height(4.dp))
                    ZoneSubline("Приложите следующий браслет")
                }

                is MemberProvisionState.Failed -> {
                    ZoneTitle(state.reason, MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    ZoneSubline("Приложите браслет")
                }
            }
        }
    }
}

/** Number field (prefilled with the auto-incremented [nextNumber]) + «Привязать» / «Отмена». */
@Composable
private fun NumberEntry(
    uid: String,
    nextNumber: Int?,
    onConfirm: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(uid) { mutableStateOf(nextNumber?.toString().orEmpty()) }
    val parsed = parseMemberNumber(text)
    ZoneTitle("Браслета нет в списке", MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(4.dp))
    ZoneSubline("Введите номер участника · ${chipTokenLabel(uid)}")
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Номер участника") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { parsed?.let(onConfirm) }),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCancel) { Text("Отмена") }
        Button(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text("Привязать") }
    }
}

@Composable
private fun PulsingNfcGlyph() {
    val transition = rememberInfiniteTransition(label = "member-provision-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "member-provision-pulse-alpha",
    )
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
}

@Composable
private fun BigNumber(number: Int) {
    Text(
        text = "№$number",
        fontFamily = RobotoMono,
        fontWeight = FontWeight.Bold,
        fontSize = 72.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ZoneTitle(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ZoneSubline(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** One bracelet written this session — green pill «№101 · A1B2». */
@Composable
private fun FreshBraceletToken(bracelet: FreshBracelet) {
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
                text = "№${bracelet.number} · ${chipTokenLabel(bracelet.uid)}",
                fontFamily = RobotoMono,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
