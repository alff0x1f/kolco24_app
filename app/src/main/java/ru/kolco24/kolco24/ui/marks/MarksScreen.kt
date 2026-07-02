package ru.kolco24.kolco24.ui.marks

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.marks.photoPaths
import ru.kolco24.kolco24.data.marks.thumbPathOf
import ru.kolco24.kolco24.data.pluralRu
import ru.kolco24.kolco24.data.takenPointCount
import ru.kolco24.kolco24.data.totalScore
import ru.kolco24.kolco24.ui.legend.CheckpointColor
import ru.kolco24.kolco24.ui.legend.parseCheckpointColor
import ru.kolco24.kolco24.ui.theme.OrangeCta
import ru.kolco24.kolco24.ui.theme.RobotoMono
import ru.kolco24.kolco24.ui.theme.Tertiary

private val marksFabListBottomPadding = 128.dp
private val marksFabScrollClearance = 104.dp

data class Mark(
    val number: String,
    val cost: Int,
    val kind: MarkKind,
    val time: String,
    // Full «дата · время» of the take, shown only in the full-screen lightbox (the grid tiles use the
    // compact [time]). Same effective take time as [time], just a wider format. Defaulted so the pure
    // mapper and tests can build a `Mark` without it.
    val dateTime: String = "",
    val color: CheckpointColor? = null,
    // Relative (`marks/<markId>/<uuid>.jpg`) photo paths captured for this take; `filesDir` is resolved
    // at the `AsyncImage` site, never here. Carried on **any** take (an NFC take can also carry photo
    // evidence) so the photo-count badge is driven by [photoCount], independent of the tile [kind].
    val photoPaths: List<String> = emptyList(),
) {
    val photoCount: Int get() = photoPaths.size
}

enum class MarkKind { NFC, PHOTO }

/**
 * Pure mapping of the local take events into display tiles — **one tile per completed event** (a repeat
 * take of the same checkpoint shows as a separate tile). Only `complete` takes are shown: a КП scanned
 * without scanning the whole team (e.g. КП chip only, or a partial collect) leaves a `complete=false`
 * row that is kept in the DB for the future server log but never tiled here, matching the
 * `complete`-only «ВЗЯТО»/«СУММА» metrics. [marks] arrives newest-first (as `observeMarks` delivers);
 * the tiles are returned **oldest-first** so a new take appends to the end of the grid rather than the
 * front. [costOf] resolves a take's **live** checkpoint cost (checkpoint id → current cost) so a tile
 * reflects an organizer's cost edit rather than the stale snapshot on the mark row (defaults to the snapshot).
 * [colorOf] resolves a take's checkpoint color token (checkpoint id → server token) for the tile's
 * whole-tile fill color; it defaults to «no color» so the pure mapping stays testable without a checkpoint
 * map. The tile time is the **trusted** take time (`trustedTakenAt`) when present, falling back to the
 * raw wall `takenAt` for untrusted/legacy rows — so a phone clock reset doesn't shift displayed times.
 * Uses [SimpleDateFormat] (not `java.time`) for minSdk-24/no-desugaring compatibility.
 */
fun marksToTiles(
    marks: List<MarkEntity>,
    costOf: (MarkEntity) -> Int = { it.cost },
    colorOf: (MarkEntity) -> CheckpointColor? = { null },
): List<Mark> {
    val fmt = SimpleDateFormat("HH:mm", Locale.US)
    // «02.07.2026 · 14:32» — the middle dot is quoted so SimpleDateFormat treats it as a literal.
    val fmtDateTime = SimpleDateFormat("dd.MM.yyyy '·' HH:mm", Locale.US)
    // Reverse newest-first → oldest-first so each new take lands at the end of the grid.
    return marks.filter { it.complete }
        .asReversed()
        .map { m ->
            // Prefer the trusted (clock-skew-proof) take time; fall back to raw wall for
            // untrusted/legacy rows where no server time was anchored at take.
            val effectiveTakenAt = m.trustedTakenAt ?: m.takenAt
            Mark(
                number = m.checkpointNumber.toString().padStart(2, '0'),
                cost = costOf(m),
                kind = if (m.method == "photo") MarkKind.PHOTO else MarkKind.NFC,
                time = fmt.format(Date(effectiveTakenAt)),
                dateTime = fmtDateTime.format(Date(effectiveTakenAt)),
                color = colorOf(m),
                photoPaths = photoPaths(m.photoPath),
            )
        }
}

/**
 * One frame in the global lightbox strip: its relative photo [path] plus the take ([mark]) it belongs
 * to — the mark feeds that page's top-left [PhotoKpChip]. `filesDir` is resolved at the render site.
 */
data class LightboxPhoto(val path: String, val mark: Mark)

/**
 * Flatten every take's frames into one ordered strip so the lightbox swipes across **all** photos, not
 * just the tapped take's. Grid order (oldest-first, matching [marksToTiles]/[TileGrid]) so the swipe
 * follows the visual order of the tiles; only takes that actually carry photos contribute frames.
 */
fun lightboxPhotos(tiles: List<Mark>): List<LightboxPhoto> =
    tiles.flatMap { m -> m.photoPaths.map { LightboxPhoto(it, m) } }

/**
 * The photo-take («без чипа») portion of the score awaiting judge review: checkpoint count, their
 * points, and each checkpoint's display [tokens] («стоимость-номер», the tile-token vocabulary;
 * a zero-cost КП is a bare zero-padded number, mirroring [tokenAnnotated]) in grid order (oldest-first).
 */
internal data class PhotoReviewSummary(val count: Int, val points: Int, val tokens: List<String>)

/**
 * Pure summary of the **checkpoints** that need judge review — scored (`complete`) only by photo takes
 * (`method == "photo"`: no КП chip was read, so the photo is the only proof). Checkpoint-level, mirroring
 * the metrics' `distinctBy { checkpointId }` semantics: a repeat photo take of the same КП counts once,
 * and a КП that *also* has a complete NFC take is excluded entirely — the chip already proves the visit
 * (its score comes from the NFC take), so judges have nothing to gate. Likewise an NFC take that merely
 * *attached* photo evidence never counts. Points go through the same live [costOf] the metrics use, so
 * an organizer's cost edit (or a legend reveal — a photo take of a still-locked КП snapshots `cost = 0`
 * and self-corrects on reveal) is reflected. Returns `null` when no checkpoint is photo-only, so the
 * notice disappears entirely rather than rendering a zero state.
 */
internal fun photoReviewSummary(
    marks: List<MarkEntity>,
    costOf: (MarkEntity) -> Int = { it.cost },
): PhotoReviewSummary? {
    val complete = marks.filter { it.complete }
    val chipVerified = complete.filterNot { it.method == "photo" }.mapTo(HashSet()) { it.checkpointId }
    // [marks] arrives newest-first; reverse to oldest-first so the token list follows the tile grid.
    val photoOnly = complete
        .filter { it.method == "photo" && it.checkpointId !in chipVerified }
        .distinctBy { it.checkpointId }
        .asReversed()
    if (photoOnly.isEmpty()) return null
    return PhotoReviewSummary(
        count = photoOnly.size,
        points = photoOnly.sumOf(costOf),
        tokens = photoOnly.map { m ->
            val cost = costOf(m)
            val number = m.checkpointNumber.toString().padStart(2, '0')
            if (cost > 0) "$cost-$number" else number
        },
    )
}

/**
 * The notice title's parenthesized КП list, capped at [max] tokens — a long photo streak must not
 * balloon the card into a paragraph (the tiles below carry the full picture). Past the cap the tail
 * collapses to an ellipsis: «1-02, 2-03, 5-04, …».
 */
internal fun tokensLabel(tokens: List<String>, max: Int = 3): String =
    if (tokens.size <= max) tokens.joinToString(", ")
    else tokens.take(max).joinToString(", ") + ", …"

/**
 * Pure tokens of the **taken-but-still-hidden** checkpoints — `complete` takes whose checkpoint is
 * still locked in the legend ([lockedIds]), so its cost is unknown client-side and the take contributes
 * 0 to СУММА until reveal (the «сорвали метку» photo take of a locked КП; an NFC take reveals the КП
 * as part of the scan, so it never lands here). Checkpoint-level (`distinctBy { checkpointId }`, like
 * the metrics), oldest-first like the grid. The token is «?-NN» — the `?` sits exactly where the cost
 * digit would in the tile's «стоимость-номер» grammar, saying "points unknown" in one character.
 * Empty list = no notice.
 */
internal fun hiddenTakenTokens(marks: List<MarkEntity>, lockedIds: Set<Int>): List<String> =
    marks.filter { it.complete && it.checkpointId in lockedIds }
        .distinctBy { it.checkpointId }
        .asReversed()
        .map { "?-${it.checkpointNumber.toString().padStart(2, '0')}" }

// Photo-seat fill (the charcoal placeholder behind the КП photo). Fixed shades, single value for
// light & dark, echoing the physical checkpoint markers.
private val PhotoTileTop = Color(0xFF1D242D)
private val PhotoTileBottom = Color(0xFF2A323C)

// Muted whole-tile fill palette for the color-fill grid (screen-scoped — deliberately distinct from
// the bright `CpColor*`/`Tertiary`/`OrangeCta` bar shades in `LegendScreen.kt`/`ProvisioningScreen.kt`,
// which still feed the thin legend/provisioning bars). The six discipline colors are fixed across
// light & dark so a same-color cluster reads identically; only neutral and the grout seam flip with
// the resolved theme, so a colorless tile never glows as a bright blob among the colors.
private val FillRed = Color(0xFFCB4233)
private val FillOrange = Color(0xFFC15A2E)
private val FillBlue = Color(0xFF2F6CAE)
private val FillGreen = Color(0xFF2E9E57)
private val FillYellow = Color(0xFFC99A1E)
private val FillPurple = Color(0xFF7C5AC0)
private val TileInk = Color(0xFF161A1F)          // text on yellow & the light neutral
private val NeutralFillLight = Color(0xFFD6DCE4)
private val NeutralFillDark = Color(0xFF2A323C)
private val NeutralTextDark = Color(0xFFD6DCE4)   // light grey text on the dark neutral

/** A tile's flat fill and the (non-luminance, fixed) text color that reads on it. */
internal data class TileFill(val fill: Color, val text: Color)

/**
 * Pure КП-color → (fill, text) mapping for the color-fill grid. White text on red/orange/blue/green/
 * purple, dark [TileInk] on yellow; a `null` color (no/unknown token) → the theme-aware neutral fill
 * (light grey + ink in light, charcoal + light grey in dark). [darkTheme] is a plain Boolean (no Compose
 * lookup inside) so this stays JVM-unit-testable; the caller resolves the *applied* theme via
 * `isDarkScheme()`, never `isSystemInDarkTheme()`.
 */
internal fun tileFill(color: CheckpointColor?, darkTheme: Boolean): TileFill = when (color) {
    CheckpointColor.RED -> TileFill(FillRed, Color.White)
    CheckpointColor.ORANGE -> TileFill(FillOrange, Color.White)
    CheckpointColor.BLUE -> TileFill(FillBlue, Color.White)
    CheckpointColor.GREEN -> TileFill(FillGreen, Color.White)
    CheckpointColor.YELLOW -> TileFill(FillYellow, TileInk)
    CheckpointColor.PURPLE -> TileFill(FillPurple, Color.White)
    null -> if (darkTheme) TileFill(NeutralFillDark, NeutralTextDark)
    else TileFill(NeutralFillLight, TileInk)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarksScreen(
    marks: List<MarkEntity> = emptyList(),
    checkpointColors: Map<Int, String> = emptyMap(),
    checkpointCosts: Map<Int, Int> = emptyMap(),
    // Checkpoints still locked in the legend — feeds the hidden-КП notice (points unknown until reveal).
    lockedCheckpointIds: Set<Int> = emptySet(),
    totalKp: Int = 0,
    totalCost: Int = 0,
    nfcAvailable: Boolean = true,
    nfcDisabled: Boolean = false,
    hasTeam: Boolean = false,
    // Room hasn't emitted marks/bindings for the resolved team yet: suppress the empty state so a
    // cold start doesn't flash a false «Привяжите чипы» for a few frames before the tiles land.
    loading: Boolean = false,
    memberCount: Int = 0,
    boundCount: Int = 0,
    trackRecording: Boolean = false,
    locationGranted: Boolean = true,
    onChooseTeam: () -> Unit = {},
    onBindChips: () -> Unit = {},
    onOpenNfcSettings: () -> Unit = {},
    onStartTrack: () -> Unit = {},
    onRequestLocation: () -> Unit = {},
    onPhotoClick: () -> Unit = {},
    // A photo tile was tapped: the host opens the full-screen lightbox (hoisted to MainActivity's overlay
    // stack so it covers the bottom navigation bar). The tapped take's own frame paths are handed up; the
    // host anchors the global photo strip on the first of them.
    onOpenPhotoLightbox: (List<String>) -> Unit = {},
    // Checkpoint-take celebration hand-off from the host (fires only on the NFC completion auto-close
    // path): scroll to bottom + last-tile pop-in + coin sound.
    celebration: Boolean = false,
    onCelebrationDone: () -> Unit = {},
    onCoinSound: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Score off the live checkpoint cost (joined by checkpoint id), falling back to the mark's snapshot
    // for a checkpoint dropped from the legend — so СУММА tracks the «Легенда» score after an organizer
    // cost edit rather than the stale value baked into the mark row.
    val costOf: (MarkEntity) -> Int = { checkpointCosts[it.checkpointId] ?: it.cost }
    // «ВЗЯТО» counts only scoring (cost > 0) checkpoints — a locked take by photo has cost=null →
    // costOf snapshot 0, so it self-corrects once the legend is revealed (see LegendScreen.isScoring).
    val takenKp = takenPointCount(marks, costOf)
    val takenScore = totalScore(marks, costOf)
    val photoReview = photoReviewSummary(marks, costOf)
    val hiddenTaken = hiddenTakenTokens(marks, lockedCheckpointIds)
    val tiles = marksToTiles(marks, costOf) { parseCheckpointColor(checkpointColors[it.checkpointId] ?: "") }

    val listState = rememberLazyListState()
    var celebratingLast by remember { mutableStateOf(false) }
    val celebrationScale = remember { Animatable(1f) }
    val celebrationAlpha = remember { Animatable(1f) }
    val fabScrollClearancePx = with(LocalDensity.current) { marksFabScrollClearance.toPx() }

    LaunchedEffect(celebration) {
        if (!celebration) return@LaunchedEffect
        if (tiles.isEmpty()) {
            onCelebrationDone()
            return@LaunchedEffect
        }
        // `onCelebrationDone` must fire even if this effect is cancelled mid-animation (e.g. the user
        // taps another bottom-nav tab) — otherwise the host's one-shot flag stays stuck true and the
        // whole celebration replays next time this screen recomposes.
        try {
            // Render the last tile at scale 0 before scrolling so it never flashes at full size while
            // still off-screen.
            celebratingLast = true
            celebrationScale.snapTo(0f)
            celebrationAlpha.snapTo(0f)
            // On a fresh composition (e.g. this page wasn't pre-composed by the pager) the LazyColumn
            // hasn't laid out yet and `totalItemsCount` reads 0 — await the first real measurement
            // instead of racing it (tiles is non-empty here, so a measurement is guaranteed to land).
            val totalItemsCount = snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            val lastItemIndex = totalItemsCount - 1
            val safeViewportEnd = listState.layoutInfo.viewportEndOffset - fabScrollClearancePx.toInt()
            // Jump straight to the bottom INSTANTLY (no scroll animation) so the screen opens already at
            // the last tile. `scrollToItem` top-aligns the one tall `tile_grid` item; a second instant
            // `scrollBy` then lifts its bottom edge up to the FAB-safe line so the freshly-popped tile
            // clears the photo FAB. Both moves are non-animated, so there is no visible scroll.
            listState.scrollToItem(lastItemIndex)
            val lastItemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastItemIndex }
            if (lastItemInfo != null) {
                val delta = (lastItemInfo.offset + lastItemInfo.size - safeViewportEnd)
                    .coerceAtLeast(0)
                if (delta > 0) listState.scrollBy(delta.toFloat())
            }
            // The coin sound IS the pop — fire both together.
            onCoinSound()
            // Join both animations before dropping the graphicsLayer below — the scale spring often
            // settles before the fixed-duration alpha tween, and celebratingLast=false strips the
            // graphicsLayer outright (not just the animated values), which would otherwise truncate
            // an in-flight fade into an abrupt pop to full opacity.
            coroutineScope {
                launch { celebrationAlpha.animateTo(1f, tween(durationMillis = 250)) }
                celebrationScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
            celebratingLast = false
        } finally {
            celebratingLast = false
            onCelebrationDone()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Отметки") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = marksFabListBottomPadding),
            ) {
                item("metrics") {
                    // «ДО КВ» has no real source yet — placeholder until control-time lands.
                    MetricsCard(
                        takenKp = takenKp,
                        totalKp = totalKp,
                        takenScore = takenScore,
                        totalCost = totalCost,
                        timeToKv = "—",
                    )
                }
                if (photoReview != null) {
                    item("photo_review") {
                        PhotoReviewNotice(
                            summary = photoReview,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                        )
                    }
                }
                if (hiddenTaken.isNotEmpty()) {
                    item("hidden_taken") {
                        HiddenKpNotice(
                            tokens = hiddenTaken,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                        )
                    }
                }
                if (tiles.isEmpty()) {
                    // While loading, render neither branch — a blank beat instead of a false empty
                    // state (no skeleton: the window is a few frames, a skeleton would itself flash).
                    if (!loading) item("empty") {
                        MarksEmpty(
                            hasTeam = hasTeam,
                            nfcAvailable = nfcAvailable,
                            nfcDisabled = nfcDisabled,
                            memberCount = memberCount,
                            boundCount = boundCount,
                            trackRecording = trackRecording,
                            locationGranted = locationGranted,
                            onChooseTeam = onChooseTeam,
                            onBindChips = onBindChips,
                            onOpenNfcSettings = onOpenNfcSettings,
                            onStartTrack = onStartTrack,
                            onRequestLocation = onRequestLocation,
                            modifier = Modifier.padding(top = 40.dp),
                        )
                    }
                } else {
                    item("tile_grid") {
                        TileGrid(
                            marks = tiles,
                            onPhotoTileClick = onOpenPhotoLightbox,
                            celebrateLastTile = celebratingLast,
                            celebrationScale = celebrationScale.value,
                            celebrationAlpha = celebrationAlpha.value,
                        )
                    }
                    // The empty state folds the NFC notice into its own message, so only surface the
                    // standalone banner once there are tiles to sit above.
                    if (!nfcAvailable) {
                        item("nfc_banner") {
                            NfcUnavailableBanner(
                                disabled = nfcDisabled,
                                onOpenNfcSettings = onOpenNfcSettings,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }

            // No «Отметить КП» button — the scan overlay opens automatically when a КП chip is tapped.
            // The «Фото» fallback FAB is the single FAB: a photo take is the fallback for a КП that
            // can't be NFC-read (no NFC, ripped marker, unreadable chip). Its onClick routes through the
            // host (decidePhotoTarget — auto-attach vs ask-number, no team → picker).
            FloatingActionButton(
                onClick = onPhotoClick,
                containerColor = OrangeCta,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = "Сфотографировать КП")
            }
        }
    }
}

/**
 * The full-screen, view-only photo lightbox as a host-rendered overlay. The tile grid lives inside the
 * tab pager (below the Scaffold's bottom navigation bar), so the lightbox is hoisted to MainActivity's
 * overlay stack — rendered after the `Scaffold` — to cover the whole window, bottom bar included (matching
 * the project's other full-screen overlays). It rebuilds the global photo strip from the same inputs
 * [MarksScreen] uses (so the mapping stays a small duplicated join, not a shared coupling), anchors the
 * initial page on [anchorPath], and closes via [onDismiss]. If the strip empties out from under an open
 * lightbox (the underlying marks changed), it self-dismisses.
 */
@Composable
internal fun PhotoLightboxOverlay(
    marks: List<MarkEntity>,
    checkpointColors: Map<Int, String>,
    checkpointCosts: Map<Int, Int>,
    anchorPath: String,
    onDismiss: () -> Unit,
) {
    val costOf: (MarkEntity) -> Int = { checkpointCosts[it.checkpointId] ?: it.cost }
    val tiles = marksToTiles(marks, costOf) { parseCheckpointColor(checkpointColors[it.checkpointId] ?: "") }
    val photos = lightboxPhotos(tiles)
    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val initialPage = photos.indexOfFirst { it.path == anchorPath }.coerceAtLeast(0)
    PhotoLightbox(photos = photos, initialPage = initialPage, onDismiss = onDismiss)
}

/**
 * The empty Отметки state, framed as the next step toward the first take rather than a flat "nothing
 * here". The signature is a [ScorecardGhostRow] — a preview of the tile grid that will fill in — and the
 * headline/body/CTA are chosen for where the team actually is in the flow, in workflow order:
 *  1. no team selected → choose one;
 *  2. NFC switched off → a CTA into system NFC settings (the toggle is the one thing in the user's way);
 *  3. NFC absent (no hardware) → the photo fallback (folds in what used to be a separate floating banner);
 *  4. chips not all bound → bind them (a take only scores once **every** member is present, so an
 *     unbound roster can never produce a tile — this is the prerequisite the user most needs surfaced);
 *  5. ready → tap a КП, plus a [TrackNudge] pre-start reminder to start the GPS track (the one thing a
 *     team can actually do at the start line, and the easiest to forget).
 *
 * [nfcDisabled] (NFC present but switched off) is checked before [nfcAvailable] so the disabled branch
 * with its «Включить NFC» CTA wins; `!nfcAvailable && !nfcDisabled` is then unambiguously no-hardware.
 *
 * In the ready state, when [locationGranted] is false a [LocationNudge] is shown above the track reminder:
 * a КП take stamps a one-shot GPS coordinate (anti-cheat proof the team was physically there), so the
 * pre-start checklist asks for location permission while the team can still grant it calmly.
 */
@Composable
private fun MarksEmpty(
    hasTeam: Boolean,
    nfcAvailable: Boolean,
    nfcDisabled: Boolean,
    memberCount: Int,
    boundCount: Int,
    trackRecording: Boolean,
    locationGranted: Boolean,
    onChooseTeam: () -> Unit,
    onBindChips: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    onStartTrack: () -> Unit,
    onRequestLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val needsBinding = memberCount > 0 && boundCount < memberCount

    // (lead glyph, headline, body, optional CTA). `trackNudge` appends the pre-start track reminder —
    // only the ready state sets it, so the nudge never competes with a branch that has its own CTA.
    data class EmptyContent(
        val glyph: ImageVector,
        val headline: String,
        val body: String,
        val ctaLabel: String? = null,
        val onCta: (() -> Unit)? = null,
        val trackNudge: Boolean = false,
    )

    val content = when {
        !hasTeam -> EmptyContent(
            glyph = Icons.Filled.Groups,
            headline = "Отметок пока нет",
            body = "Выберите соревнование и команду — отметки появятся здесь.",
            ctaLabel = "Выбрать команду",
            onCta = onChooseTeam,
        )
        nfcDisabled -> EmptyContent(
            glyph = Icons.Filled.Nfc,
            headline = "NFC выключен",
            body = "Включите NFC, чтобы отмечать КП прикосновением.",
            ctaLabel = "Включить NFC",
            onCta = onOpenNfcSettings,
        )
        !nfcAvailable -> EmptyContent(
            glyph = Icons.Filled.CameraAlt,
            headline = "NFC недоступен",
            body = "Отметить КП по NFC на этом устройстве не получится. Отмечайте КП через «Фото».",
        )
        needsBinding -> EmptyContent(
            glyph = Icons.Filled.AddLink,
            headline = "Привяжите чипы участникам",
            body = "Отметка засчитывается, только когда отмечены все участники команды. " +
                "Сейчас с чипом $boundCount из $memberCount.",
            ctaLabel = "Привязать чипы",
            onCta = onBindChips,
        )
        else -> EmptyContent(
            glyph = Icons.Filled.Nfc,
            headline = "Здесь появятся отметки",
            body = "Приложите телефон к метке КП — отметка добавится сюда.",
            trackNudge = true,
        )
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GhostTileRow(leadGlyph = content.glyph)

        Spacer(Modifier.height(22.dp))
        Text(
            text = content.headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = content.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (content.ctaLabel != null && content.onCta != null) {
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = content.onCta,
                modifier = Modifier.height(48.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = OrangeCta,
                    contentColor = Color.White,
                ),
                contentPadding = PaddingValues(horizontal = 22.dp),
            ) {
                Icon(content.glyph, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(content.ctaLabel, style = MaterialTheme.typography.titleSmall)
            }
        }

        // Anti-cheat coordinate nudge: only in the ready state (alongside the track reminder), and only
        // while permission is missing — once granted there is nothing to ask for. Shown above the track
        // nudge so the «can we even stamp the take?» prerequisite leads the optional «start track» step.
        if (content.trackNudge && !locationGranted) {
            Spacer(Modifier.height(28.dp))
            LocationNudge(onRequest = onRequestLocation)
        }

        if (content.trackNudge) {
            Spacer(Modifier.height(28.dp))
            TrackNudge(recording = trackRecording, onStart = onStartTrack)
        }
    }
}

/**
 * Pre-start location-permission reminder shown under the ready empty state when foreground location is
 * not yet granted. A КП take stamps a one-shot GPS coordinate as anti-cheat proof the team was at the
 * checkpoint; without permission the take still lands, just without that proof. Tapping the card asks for
 * the permission (or routes to app settings on a permanent denial — the host decides). Mirrors the
 * [TrackNudge] card vocabulary so it reads as part of the same pre-start checklist.
 */
@Composable
private fun LocationNudge(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onRequest)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(OrangeCta, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Разрешите геолокацию",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Координата на КП подтверждает, что вы были на точке",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Pre-start GPS-track reminder shown under the ready empty state. Before the gun the one thing a team can
 * actually do on this screen is start their track — and it is the easiest step to forget — so the reminder
 * sits here as a single tappable card. The orange play badge is the same «start track» vocabulary as the
 * Команда-tab `TrackCard`, so it reads as the same feature rather than a new control. Once recording, the
 * card gives way to a quiet success line so a team that already started is acknowledged, not nagged.
 */
@Composable
private fun TrackNudge(recording: Boolean, onStart: () -> Unit, modifier: Modifier = Modifier) {
    if (recording) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(20.dp).background(Tertiary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = "Трек записывается",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        return
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onStart)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(OrangeCta, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Не забудьте трек",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Включите GPS-запись пути перед стартом",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The empty-state signature: a small centered preview of the grid this screen fills in — four 54dp
 * **flat squares** (0dp radius, matching the populated `TileGrid`'s flat color-fill tiles, not the old
 * rounded card). The first is the **next slot**: a solid neutral square (the real null-color [tileFill],
 * so an empty slot and a colorless real take share a shade) carrying the state's lead glyph where a real
 * tile's `<стоимость>-<номер>` token would sit. The trailing three are dashed square outlines that fade
 * out, reading «marks land here, one КП at a time». The dashes use the readable `onSurfaceVariant` (not
 * the near-invisible `outlineVariant`) so the stroke shows on the light surface too. Decorative — no
 * state, no motion.
 */
@Composable
private fun GhostTileRow(leadGlyph: ImageVector, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostTile(active = true, glyph = leadGlyph)
        GhostTile(active = false, alpha = 0.65f)
        GhostTile(active = false, alpha = 0.45f)
        GhostTile(active = false, alpha = 0.30f)
    }
}

@Composable
private fun GhostTile(active: Boolean, glyph: ImageVector? = null, alpha: Float = 1f) {
    val fill = tileFill(null, isDarkScheme()).fill
    val dash = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(54.dp)
            .then(
                if (active) {
                    // The next slot: a solid flat square, the same shade a colorless real tile renders.
                    Modifier.background(fill)
                } else {
                    // An upcoming slot: a dashed square outline (sharp corners to match the flat tiles).
                    Modifier.drawBehind {
                        val s = 1.dp.toPx()
                        drawRect(
                            color = dash.copy(alpha = alpha),
                            topLeft = Offset(s / 2, s / 2),
                            size = Size(size.width - s, size.height - s),
                            style = Stroke(
                                width = s,
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                                ),
                            ),
                        )
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph != null) {
            Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = OrangeCta,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * The judge-review notice under the metrics card, shown only while at least one photo take exists (an
 * alert, not a status light — no photo takes, no card). Explains that the photo portion of СУММА is
 * provisional: a take without a chip scores only after judges verify the photos. The title names the
 * checkpoints under review by their tile tokens — «3 КП по фото (1-02, 2-03, 5-04) · 8 баллов» — so the
 * team can match the notice against the grid without hunting for camera chips. Styled as a **warning**
 * in the app's one alert palette (`errorContainer`/`onErrorContainer`, the [ClockWarningBanner]/
 * `NfcUnavailableBanner` vocabulary — deliberately no third amber hue): these points are at stake until
 * the judges rule, which is exactly what that palette flags elsewhere. The lead badge stays the tile
 * grid's flat square, but in the `error` color with a white [CameraAlt][Icons.Filled.CameraAlt] glyph —
 * warning weight, photo vocabulary. Not clickable — the card asks for nothing. When the photo points sum
 * to 0 (all photo КП still locked in the legend, `cost = null → 0`) the «· 0 баллов» part is dropped
 * rather than showing a misleading zero; the figure self-corrects on reveal.
 */
@Composable
private fun PhotoReviewNotice(summary: PhotoReviewSummary, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.CameraAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                val points = summary.points
                val tokens = tokensLabel(summary.tokens)
                Text(
                    text = if (points > 0) {
                        "${summary.count} КП по фото ($tokens) · $points ${pluralRu(points, "балл", "балла", "баллов")}"
                    } else {
                        "${summary.count} КП по фото ($tokens)"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "Баллы засчитают после проверки судьями",
                    style = MaterialTheme.typography.bodySmall,
                    // The container's own on-color at reduced alpha keeps the secondary line quieter
                    // than the title while staying in the warning palette (no grey on red-tinted ground).
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/**
 * The taken-but-hidden warning under the metrics (below [PhotoReviewNotice] when both show): the team
 * scored a КП that is still locked in the legend, so its cost is unknown and СУММА is understating —
 * «2 скрытых КП (?-04, ?-07) / Баллы засчитают после раскрытия КП». Same warning anatomy and palette
 * as the photo card («duplicate, don't couple»), told apart by the [Lock][Icons.Filled.Lock] badge —
 * the legend's own locked-row glyph. A hidden КП taken by photo shows in **both** cards (the photo one
 * gates the points on judges, this one explains why they read as 0 today); both resolve on reveal, when
 * the checkpoint leaves [hiddenTakenTokens]' locked set and its live cost lands in СУММА.
 */
@Composable
private fun HiddenKpNotice(tokens: List<String>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                val count = tokens.size
                Text(
                    text = "$count ${pluralRu(count, "скрытое", "скрытых", "скрытых")} КП " +
                        "(${tokensLabel(tokens)})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "Баллы засчитают после раскрытия КП",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun MetricsCard(
    takenKp: Int,
    totalKp: Int,
    takenScore: Int,
    totalCost: Int,
    timeToKv: String,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Show the «/total» only once the legend has loaded (total > 0), so a cold start
            // doesn't flash a «8/0».
            MetricItem(
                label = "ВЗЯТО",
                value = "$takenKp",
                total = totalKp.takeIf { it > 0 }?.toString(),
                unit = "КП",
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(
                modifier = Modifier.height(36.dp).padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            MetricItem(
                label = "СУММА",
                value = "$takenScore",
                total = totalCost.takeIf { it > 0 }?.toString(),
                unit = "бал.",
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(
                modifier = Modifier.height(36.dp).padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            MetricItem(label = "ДО КВ", value = timeToKv, isWarn = true, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * One metric column: the caption sits **above** the value (per the Отметки design), and an optional
 * «/total» denominator (e.g. «8/15 КП») mirrors the Легенда's score progress so взято/сумма read as
 * fractions of the race total rather than bare counts. [total] is null until the legend has loaded.
 */
@Composable
private fun MetricItem(
    label: String,
    value: String,
    total: String? = null,
    unit: String? = null,
    isWarn: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
                fontFamily = if (isWarn) RobotoMono else null,
                color = if (isWarn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (total != null) {
                Text(
                    text = "/$total",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            if (unit != null) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                )
            }
        }
    }
}

/**
 * The color-fill grid: an **edge-to-edge** 4-column field of flat [ColorTile]s separated by 1dp seams.
 * The 1dp vertical/horizontal gaps (and the trailing empty cells of an incomplete last row) show the
 * **normal app background** color through — so a partly-filled row blends into the screen rather than
 * appearing as a darker grey band, and the seams read as the ordinary background between tiles, not a
 * separate grout shade. No horizontal padding — the grid sits flush to the screen edge (the metrics card
 * above keeps its own inset).
 */
@Composable
private fun TileGrid(
    marks: List<Mark>,
    onPhotoTileClick: (List<String>) -> Unit,
    // Checkpoint-take celebration pop-in: [celebrateLastTile] gates the effect to the single flat
    // index `marks.lastIndex` (never by `Mark` equality — two identical takes are `==`-equal), so
    // zero cost is paid by every other tile.
    celebrateLastTile: Boolean = false,
    celebrationScale: Float = 1f,
    celebrationAlpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val rows = marks.chunked(4)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        rows.forEachIndexed { rowIndex, rowMarks ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                rowMarks.forEachIndexed { colIndex, mark ->
                    val flatIndex = rowIndex * 4 + colIndex
                    val isCelebrating = celebrateLastTile && flatIndex == marks.lastIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (isCelebrating) {
                                    Modifier.graphicsLayer {
                                        scaleX = celebrationScale
                                        scaleY = celebrationScale
                                        alpha = celebrationAlpha
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        ColorTile(mark = mark, onPhotoTileClick = onPhotoTileClick)
                    }
                }
                // Empty spacers (showing the screen background) keep the last row's grid regular to the
                // edge without tinting the leftover cells.
                repeat(4 - rowMarks.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/** The *resolved* theme (respecting the app's manual Light/Dark override), NOT `isSystemInDarkTheme()`. */
@Composable
private fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * One taken checkpoint as a flat color-fill tile: the КП discipline color fills the whole square
 * (0dp radius, no border/elevation) so a same-color cluster reads as one tiled region. The
 * `<стоимость>-<номер>` token sits centered (NFC takes) or as a caption over the photo scrim. The
 * fill + readable text are resolved by the pure [tileFill] against the [isDarkScheme] result.
 */
@Composable
private fun ColorTile(mark: Mark, onPhotoTileClick: (List<String>) -> Unit) {
    val tf = tileFill(mark.color, isDarkScheme())
    val hasPhotos = mark.photoCount > 0
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(tf.fill)
            // Only a tile that actually carries photos is tappable (opens the lightbox); a plain NFC
            // tile keeps its current inert behaviour.
            .then(if (hasPhotos) Modifier.clickable { onPhotoTileClick(mark.photoPaths) } else Modifier),
    ) {
        // A take that carries photos shows its first frame as the tile background regardless of how it
        // was marked — a PHOTO take, or an NFC take that also captured evidence. Only a plain NFC take
        // with no photos keeps the flat color-fill token body. The top-right camera chip stays exclusive
        // to PHOTO-kind takes (see [PhotoTileBody.showCameraChip]) so an NFC-with-photos tile is still
        // told apart from a pure photo take.
        if (hasPhotos) {
            PhotoTileBody(mark, tf.fill, showCameraChip = mark.kind == MarkKind.PHOTO)
        } else {
            NfcTileBody(mark, tf.text)
        }
        // The «+N» extra-photo badge. The first frame IS the tile background, so it's never counted —
        // only the *hidden* remainder shows (2 photos → «+1», N → «+(N-1)»); a single-photo tile shows
        // nothing. Mirrors the take-time label (bare mono digits, no chip) at the bottom-LEADING corner;
        // over the photo scrim it stays dimmed white.
        if (mark.photoCount > 1) {
            PhotoCountBadge(
                extra = mark.photoCount - 1,
                color = Color.White.copy(alpha = 0.82f),
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 6.dp, start = 8.dp),
            )
        }
    }
}

/** Extra-photo corner label — «+N» hidden-frame count, styled like the take time (no chip). */
@Composable
private fun PhotoCountBadge(extra: Int, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = "+$extra",
        color = color,
        fontFamily = RobotoMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        modifier = modifier,
    )
}

/**
 * Full-screen, view-only photo lightbox: a [HorizontalPager] over **all** takes' captured frames
 * ([photos], grid order), resolved from `filesDir` at render time (each [LightboxPhoto] carries only a
 * relative path plus its owning take). Opens at [initialPage] (the tapped take's first frame). A global
 * «k/N» counter rides the top when there is more than one frame; the top-left [PhotoKpChip] is resolved
 * **per page** from that frame's own take, so swiping across takes updates the КП token. A tap anywhere
 * or back dismisses. **Swipe-down-to-dismiss** (the phone gallery's own gesture): dragging vertically
 * translates the current page and fades the black backdrop toward the screen behind it; releasing past
 * [dismissThresholdDp] closes, otherwise it springs back to center. Phase 1 has no edit/delete here.
 */
@Composable
private fun PhotoLightbox(photos: List<LightboxPhoto>, initialPage: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { photos.size })
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { dismissThresholdDp.toPx() }
    BackHandler(onBack = onDismiss)

    // Hide the system navigation bar for an immersive full-screen photo (the app is otherwise edge-to-edge
    // with the bars visible). Scoped to the lightbox: hidden on open, restored on dismiss via onDispose. A
    // swipe from the bottom edge brings it back transiently (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE), so the
    // gesture pill / back controls stay reachable.
    DisposableEffect(Unit) {
        val controller = context.findActivity()?.window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.navigationBars()) }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Fades the backdrop (revealing the screen underneath) as the drag approaches the
                // dismiss threshold — a preview of the close, not a hard cut.
                alpha = 1f - (offsetY.value.absoluteValue / dismissThresholdPx).coerceIn(0f, 1f) * 0.7f
            }
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = offsetY.value }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offsetY.value.absoluteValue > dismissThresholdPx) onDismiss()
                                else offsetY.animateTo(0f)
                            }
                        },
                        onDragCancel = { scope.launch { offsetY.animateTo(0f) } },
                    ) { change, dragAmount ->
                        change.consume()
                        scope.launch { offsetY.snapTo(offsetY.value + dragAmount) }
                    }
                },
        ) { page ->
            val photo = photos[page]
            LightboxPage(file = File(context.filesDir, photo.path), mark = photo.mark)
        }
        if (photos.size > 1) {
            Text(
                text = "${pagerState.currentPage + 1}/${photos.size}",
                color = Color.White,
                fontFamily = RobotoMono,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
            )
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 8.dp),
        ) {
            // Share the frame currently in view. The photo already exists at filesDir/marks/<id>/<uuid>.jpg
            // (immutable downscaled JPEG), so we hand it straight to the chooser via FileProvider — no copy.
            // FLAG_GRANT_READ_URI_PERMISSION grants only this one URI to the receiver; exposing the marks/
            // subtree in file_paths.xml doesn't let any app enumerate the folder. The system chooser's
            // «Сохранить в Фото» target covers the save-to-gallery motivation too.
            IconButton(
                onClick = { sharePhoto(context, photos[pagerState.currentPage].path) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = "Поделиться", tint = Color.White)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
            }
        }
    }
}

/**
 * Hand a single photo frame to the system share-sheet. The file lives in private internal storage
 * (`filesDir/marks/<markId>/<uuid>.jpg`) and is exposed read-only per-URI via the existing FileProvider
 * (authority `${packageName}.fileprovider`, `marks/` declared in `res/xml/file_paths.xml`). Guards a
 * missing file (orphan sweep / manual wipe) and a chooser with zero targets, both with an RU Toast.
 */
private fun sharePhoto(context: android.content.Context, relPath: String) {
    val file = File(context.filesDir, relPath)
    if (!file.exists()) {
        Toast.makeText(context, "Файл фото не найден", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, "Поделиться фото"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Нет приложений для отправки", Toast.LENGTH_SHORT).show()
    }
}

/** Vertical drag distance past which releasing the lightbox closes it instead of springing back. */
private val dismissThresholdDp = 120.dp

/** Unwrap a (possibly wrapped) Compose [Context] to its hosting [Activity], or `null` if there is none. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * One lightbox page: the photo is drawn with [ContentScale.Fit], which letterboxes rather than filling
 * the page whenever the frame's aspect ratio doesn't match the screen's — so the [PhotoKpChip] is pinned
 * to the *photo's own* rendered top-left corner, not the page's, by first sizing an inner [Box] to the
 * photo's actual displayed (post-letterbox) dimensions and aligning the chip within that box. Before the
 * frame's intrinsic size is known (first composition / load in flight) the inner box falls back to filling
 * the page so nothing crashes or flashes at 0-size; the chip lands correctly on the very next frame once
 * the size resolves. [mark] is the frame's own take (resolved per-page by the caller) — its КП token/color
 * feed the top-left [PhotoKpChip].
 */
@Composable
private fun LightboxPage(file: File, mark: Mark, modifier: Modifier = Modifier) {
    val painter = rememberAsyncImagePainter(model = file)
    val intrinsic = painter.intrinsicSize
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val photoSizeModifier = if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) {
            val scale = minOf(
                constraints.maxWidth / intrinsic.width,
                constraints.maxHeight / intrinsic.height,
            )
            with(LocalDensity.current) {
                Modifier.size(
                    width = (intrinsic.width * scale).toDp(),
                    height = (intrinsic.height * scale).toDp(),
                )
            }
        } else {
            Modifier.fillMaxSize()
        }
        Box(modifier = photoSizeModifier) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            PhotoKpChip(
                mark = mark,
                color = tileFill(mark.color, isDarkScheme()).fill,
                modifier = Modifier.align(Alignment.TopStart),
                // Larger than the thumbnail's chip so it reads proportionally on the full-screen photo.
                scale = 1.7f,
            )
        }
        // The take's «дата · время», echoing the tile's mono time caption but scaled up and pinned to the
        // black margin at the very bottom of the page — outside the photo (the КП chip owns the photo
        // itself). Aligned to the page, not the letterboxed photo box, so it sits in the black band below
        // a portrait frame; it rides the vertical dismiss drag with the rest of the page.
        Text(
            text = mark.dateTime,
            color = Color.White.copy(alpha = 0.85f),
            fontFamily = RobotoMono,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            letterSpacing = 0.3.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 40.dp),
        )
    }
}

@Composable
private fun NfcTileBody(mark: Mark, textColor: Color) {
    // Per-element placement (not a shared inset) so the «стоимость-номер» token centers on the WHOLE
    // tile while the take time hugs the bottom-right like a chat-message timestamp.
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = tokenAnnotated(mark, textColor),
            color = textColor,
            maxLines = 1,
            // includeFontPadding=false + centered/trimmed line height so the digits sit optically
            // centered — without it the font's top padding makes the token look pushed down.
            style = TextStyle(
                fontFamily = RobotoMono,
                fontWeight = FontWeight.Bold,
                fontSize = 23.sp,
                lineHeight = 23.sp,
                letterSpacing = (-0.8).sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 6.dp),
        )
        Text(
            text = mark.time,
            fontFamily = RobotoMono,
            fontWeight = FontWeight.Medium,
            fontSize = 10.5.sp,
            color = textColor.copy(alpha = 0.78f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 6.dp, end = 8.dp),
        )
    }
}

/**
 * The `<стоимость>-<номер>` token with the hyphen dimmed ([hyphenAlpha], the digits inherit the caller's
 * text color), so the cost and number read as two values rather than one run on the color fill.
 * A zero-cost КП (transit/test zone) shows only the bare number — no cost prefix, no hyphen.
 */
private fun tokenAnnotated(
    mark: Mark,
    textColor: Color,
    hyphenAlpha: Float = 0.5f,
): AnnotatedString = buildAnnotatedString {
    if (mark.cost == 0) {
        // Zero-cost КП (transit/test zone): show only the number, no cost prefix.
        append(mark.number)
    } else {
        append("${mark.cost}")
        withStyle(SpanStyle(color = textColor.copy(alpha = hyphenAlpha))) { append("-") }
        append(mark.number)
    }
}

/**
 * A photo take's tile: the captured photo fills the whole square edge-to-edge — the КП discipline color
 * no longer washes over it or frames it. Tiles are separated purely by the grid grout; the discipline
 * color survives in the corner chips ([PhotoKpChip] top-left carrying the `<стоимость>-<номер>` token,
 * plus the top-right camera glyph on photo takes). The take time stays **bottom-end** inside the bottom
 * scrim (transparent → ~60% black) so it reads as a legible caption over bright imagery. Shared by PHOTO
 * takes and NFC takes that carry photos; [showCameraChip] gates the top-right glyph so only a genuine
 * photo take flags it.
 */
@Composable
private fun PhotoTileBody(mark: Mark, stageColor: Color, showCameraChip: Boolean = true) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(PhotoTileTop, PhotoTileBottom))),
    ) {
        // The first captured frame fills the tile; the charcoal gradient shows through until it loads
        // (and stays as the seat if the file is missing). `filesDir` is resolved here — the pure mapper
        // carries only relative paths.
        mark.photoPaths.firstOrNull()?.let { rel ->
            // Prefer the small `<uuid>.thumb.jpg` written at capture (a fraction of the full frame's
            // decode cost with ~100 tiles on screen); frames from before thumbs existed fall back to
            // the full frame — Coil still downsamples that decode to the tile size. One cheap stat
            // per tile, cached by `remember` across recompositions.
            val model = remember(rel) {
                val thumb = File(context.filesDir, thumbPathOf(rel))
                if (thumb.exists()) thumb else File(context.filesDir, rel)
            }
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Bottom scrim so the take time stays legible over real imagery (the placeholder is dark already,
        // but a photo's lower edge can be bright).
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))),
                ),
        )
        Text(
            text = mark.time,
            fontFamily = RobotoMono,
            fontWeight = FontWeight.Medium,
            fontSize = 10.5.sp,
            color = Color.White.copy(alpha = 0.82f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 6.dp, end = 8.dp),
        )
        PhotoKpChip(mark = mark, color = stageColor, modifier = Modifier.align(Alignment.TopStart))
        if (showCameraChip) {
            PhotoCameraChip(color = stageColor, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

/**
 * The camera glyph pinned to a photo tile's top-RIGHT corner, mirroring [PhotoKpChip]'s backing: solid
 * discipline [color], only the inner floating corner (bottom-start) rounded to 9dp so the two flush edges
 * stay square and the top-trailing corner coincides with the tile's. Marks a photo (not NFC) take at a glance.
 */
@Composable
private fun PhotoCameraChip(color: Color, modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.CameraAlt,
        contentDescription = null,
        tint = Color.White,
        modifier = modifier
            .background(color, RoundedCornerShape(bottomStart = 9.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .size(15.dp),
    )
}

/**
 * The КП-code anchor pinned to a photo tile/lightbox's top-left corner: solid discipline [color] (the
 * same shade as the tile's perimeter border), only the corner floating inside the tile (bottom-end)
 * rounded to 9dp — the two edges flush with the tile's own edges stay square, so the top-leading corner
 * coincides exactly with the tile's corner. White, bold, mono, tight tracking per spec.
 */
@Composable
private fun PhotoKpChip(mark: Mark, color: Color, modifier: Modifier = Modifier, scale: Float = 1f) {
    // [scale] grows the whole chip in proportion (font + tracking + padding + corner) so the fullscreen
    // lightbox can render it larger than the thumbnail tile without the token looking lost on the photo.
    Text(
        text = tokenAnnotated(mark, Color.White, hyphenAlpha = 0.55f),
        color = Color.White,
        maxLines = 1,
        style = TextStyle(
            fontFamily = RobotoMono,
            fontWeight = FontWeight.Bold,
            fontSize = 14.5.sp * scale,
            letterSpacing = (-0.6).sp * scale,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        modifier = modifier
            .background(color, RoundedCornerShape(bottomEnd = 9.dp * scale))
            .padding(horizontal = 7.dp * scale, vertical = 4.dp * scale),
    )
}

/**
 * Surfaced only when NFC is unavailable — a healthy reader needs no badge. This is an alert,
 * not a status light.
 */
@Composable
private fun NfcUnavailableBanner(
    disabled: Boolean,
    onOpenNfcSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // When NFC is merely switched off the whole banner is a shortcut into system settings; with no NFC
    // hardware there is nothing to act on, so it stays a static notice.
    val rowModifier = if (disabled) {
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpenNfcSettings)
    } else {
        modifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier,
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
                text = if (disabled) "NFC выключен" else "NFC недоступен",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (disabled) "Нажмите, чтобы включить"
                    else "Сканирование NFC на этом устройстве недоступно",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
