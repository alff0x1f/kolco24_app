package ru.kolco24.kolco24.ui.marks

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch
import ru.kolco24.kolco24.data.db.MarkEntity
import ru.kolco24.kolco24.data.marks.photoPaths
import ru.kolco24.kolco24.data.takenPointCount
import ru.kolco24.kolco24.data.totalScore
import ru.kolco24.kolco24.ui.legend.CheckpointColor
import ru.kolco24.kolco24.ui.legend.parseCheckpointColor
import ru.kolco24.kolco24.ui.theme.OrangeCta
import ru.kolco24.kolco24.ui.theme.RobotoMono
import ru.kolco24.kolco24.ui.theme.Tertiary

data class Mark(
    val number: String,
    val cost: Int,
    val kind: MarkKind,
    val time: String,
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
    // Reverse newest-first → oldest-first so each new take lands at the end of the grid.
    return marks.filter { it.complete }
        .asReversed()
        .map { m ->
            Mark(
                number = m.checkpointNumber.toString().padStart(2, '0'),
                cost = costOf(m),
                kind = if (m.method == "photo") MarkKind.PHOTO else MarkKind.NFC,
                // Prefer the trusted (clock-skew-proof) take time; fall back to raw wall for
                // untrusted/legacy rows where no server time was anchored at take.
                time = fmt.format(Date(m.trustedTakenAt ?: m.takenAt)),
                color = colorOf(m),
                photoPaths = photoPaths(m.photoPath),
            )
        }
}

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
    totalKp: Int = 0,
    totalCost: Int = 0,
    nfcAvailable: Boolean = true,
    nfcDisabled: Boolean = false,
    hasTeam: Boolean = false,
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
    val tiles = marksToTiles(marks, costOf) { parseCheckpointColor(checkpointColors[it.checkpointId] ?: "") }

    // A tapped photo tile opens the view-only lightbox; the paths drive the pager. Local screen state
    // (not a `MainActivity` overlay flag) — Phase 1 view-only, dismissed by tap/back.
    var lightboxPaths by rememberSaveable(
        stateSaver = listSaver(save = { it }, restore = { it }),
    ) { mutableStateOf(emptyList<String>()) }

    Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Отметки") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp),
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
                if (tiles.isEmpty()) {
                    item("empty") {
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
                            onPhotoTileClick = { paths -> lightboxPaths = paths },
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

        // Full-screen view-only lightbox, drawn over the whole screen (incl. the TopAppBar) so the
        // photos own the frame; tap or back dismisses it.
        if (lightboxPaths.isNotEmpty()) {
            // The tile grid always opens the lightbox with one mark's own photo list, so a structural
            // match against the just-recomputed [tiles] finds the same mark (for its КП chip) without
            // threading extra saveable state through the click callback.
            PhotoLightbox(
                mark = tiles.firstOrNull { it.photoPaths == lightboxPaths },
                paths = lightboxPaths,
                onDismiss = { lightboxPaths = emptyList() },
            )
        }
    }
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
    modifier: Modifier = Modifier,
) {
    val rows = marks.chunked(4)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        rows.forEach { rowMarks ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                rowMarks.forEach { mark ->
                    Box(modifier = Modifier.weight(1f)) {
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
 * Full-screen, view-only photo lightbox: a [HorizontalPager] over the take's N captured frames, resolved
 * from `filesDir` at render time (the [Mark] carries only relative paths). A «k/N» counter rides the top
 * when there is more than one frame; a tap anywhere or back dismisses. [mark] (looked up by the caller
 * from the same [Mark] the tile grid tapped) feeds the top-left [PhotoKpChip] — `null` only if the
 * underlying data changed out from under an already-open lightbox, in which case the chip is simply
 * skipped. **Swipe-down-to-dismiss** (the phone gallery's own gesture): dragging vertically translates the
 * current page and fades the black backdrop toward the screen behind it; releasing past
 * [dismissThresholdDp] closes, otherwise it springs back to center. Phase 1 has no edit/delete here.
 */
@Composable
private fun PhotoLightbox(mark: Mark?, paths: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { paths.size })
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { dismissThresholdDp.toPx() }
    BackHandler(onBack = onDismiss)
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
            LightboxPage(file = File(context.filesDir, paths[page]), mark = mark)
        }
        if (paths.size > 1) {
            Text(
                text = "${pagerState.currentPage + 1}/${paths.size}",
                color = Color.White,
                fontFamily = RobotoMono,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
            )
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 8.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
        }
    }
}

/** Vertical drag distance past which releasing the lightbox closes it instead of springing back. */
private val dismissThresholdDp = 120.dp

/**
 * One lightbox page: the photo is drawn with [ContentScale.Fit], which letterboxes rather than filling
 * the page whenever the frame's aspect ratio doesn't match the screen's — so the [PhotoKpChip] is pinned
 * to the *photo's own* rendered top-left corner, not the page's, by first sizing an inner [Box] to the
 * photo's actual displayed (post-letterbox) dimensions and aligning the chip within that box. Before the
 * frame's intrinsic size is known (first composition / load in flight) the inner box falls back to filling
 * the page so nothing crashes or flashes at 0-size; the chip lands correctly on the very next frame once
 * the size resolves.
 */
@Composable
private fun LightboxPage(file: File, mark: Mark?, modifier: Modifier = Modifier) {
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
            if (mark != null) {
                PhotoKpChip(
                    mark = mark,
                    color = tileFill(mark.color, isDarkScheme()).fill,
                    modifier = Modifier.align(Alignment.TopStart),
                    // Larger than the thumbnail's chip so it reads proportionally on the full-screen photo.
                    scale = 1.7f,
                )
            }
        }
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
            AsyncImage(
                model = File(context.filesDir, rel),
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
