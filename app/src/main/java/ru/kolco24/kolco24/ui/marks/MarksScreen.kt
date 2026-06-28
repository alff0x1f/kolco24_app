package ru.kolco24.kolco24.ui.marks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.kolco24.kolco24.data.db.MarkEntity
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
)

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
    modifier: Modifier = Modifier,
) {
    val takenKp = takenPointCount(marks)
    // Score off the live checkpoint cost (joined by checkpoint id), falling back to the mark's snapshot
    // for a checkpoint dropped from the legend — so СУММА tracks the «Легенда» score after an organizer
    // cost edit rather than the stale value baked into the mark row.
    val costOf: (MarkEntity) -> Int = { checkpointCosts[it.checkpointId] ?: it.cost }
    val takenScore = totalScore(marks, costOf)
    val tiles = marksToTiles(marks, costOf) { parseCheckpointColor(checkpointColors[it.checkpointId] ?: "") }

    Column(modifier = modifier.fillMaxSize()) {
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
                        TileGrid(marks = tiles)
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
            // The «Фото» fallback FAB is hidden until photo marking is implemented.
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
        ScorecardGhostRow(leadGlyph = content.glyph)

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
 * The empty-state signature: a preview of the color-fill grid this screen fills in. The first slot is a
 * solid card carrying the state's lead glyph (a neutral stand-in for a real [ColorTile]'s color fill +
 * frame); the trailing slots are hairline-dashed placeholders that fade out, reading as "marks land here,
 * one КП at a time". Purely decorative — no state, no motion.
 */
@Composable
private fun ScorecardGhostRow(leadGlyph: ImageVector, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostTile(active = true, glyph = leadGlyph)
        GhostTile(active = false, alpha = 0.55f)
        GhostTile(active = false, alpha = 0.32f)
        GhostTile(active = false, alpha = 0.18f)
    }
}

@Composable
private fun GhostTile(
    active: Boolean,
    glyph: ImageVector? = null,
    alpha: Float = 1f,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = TileShape,
        color = if (active) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
        modifier = Modifier
            .size(54.dp)
            .then(
                if (active) {
                    Modifier.drawBehind {
                        // Solid hairline frame, framing the ghost stand-in for a real grid tile.
                        drawRoundRect(
                            color = outline,
                            cornerRadius = CornerRadius(10.dp.toPx()),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                } else {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = outline.copy(alpha = alpha),
                            cornerRadius = CornerRadius(10.dp.toPx()),
                            style = Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                                ),
                            ),
                        )
                    }
                },
            ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (active) {
                // Neutral top stripe stand-in for the КП color fill (no КП yet → no color).
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(outline),
                )
            }
            if (glyph != null) {
                Icon(
                    imageVector = glyph,
                    contentDescription = null,
                    tint = OrangeCta,
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
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
private fun TileGrid(marks: List<Mark>, modifier: Modifier = Modifier) {
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
                        ColorTile(mark = mark)
                    }
                }
                // Empty spacers (showing the screen background) keep the last row's grid regular to the
                // edge without tinting the leftover cells.
                repeat(4 - rowMarks.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

private val TileShape = RoundedCornerShape(10.dp)

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
private fun ColorTile(mark: Mark) {
    val tf = tileFill(mark.color, isDarkScheme())
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(tf.fill),
    ) {
        when (mark.kind) {
            MarkKind.NFC -> NfcTileBody(mark, tf.text)
            MarkKind.PHOTO -> PhotoTileBody(mark)
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
 * The `<стоимость>-<номер>` token with the hyphen dimmed to 50% alpha (the digits inherit the caller's
 * text color), so the cost and number read as two values rather than one run on the color fill.
 */
private fun tokenAnnotated(mark: Mark, textColor: Color): AnnotatedString = buildAnnotatedString {
    append("${mark.cost}")
    withStyle(SpanStyle(color = textColor.copy(alpha = 0.5f))) { append("-") }
    append(mark.number)
}

/**
 * A photo take's tile: the captured photo fills the whole square. The `<стоимость>-<номер>` token sits
 * **bottom-leading** and the take time **bottom-end**, both inside a bottom scrim (transparent → ~60%
 * black) so they read as a legible caption — *not* centered — once a real photo replaces the charcoal
 * placeholder (a centered token would float over the bright middle of a photo with no scrim behind it).
 */
@Composable
private fun PhotoTileBody(mark: Mark) {
    // TODO(photo): fill with mark.photoPath image once photo marking ships (add Coil dependency)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(PhotoTileTop, PhotoTileBottom))),
    ) {
        // Bottom scrim so the caption stays legible over real imagery (the placeholder is dark already,
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
            text = tokenAnnotated(mark, Color.White),
            color = Color.White,
            maxLines = 1,
            style = TextStyle(
                fontFamily = RobotoMono,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                letterSpacing = (-0.4).sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 6.dp, start = 8.dp),
        )
        Text(
            text = mark.time,
            fontFamily = RobotoMono,
            fontWeight = FontWeight.Medium,
            fontSize = 10.5.sp,
            color = Color.White.copy(alpha = 0.82f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 6.dp, end = 8.dp),
        )
    }
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
