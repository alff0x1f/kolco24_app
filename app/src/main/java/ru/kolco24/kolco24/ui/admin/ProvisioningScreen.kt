package ru.kolco24.kolco24.ui.admin

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.kolco24.kolco24.ui.legend.CheckpointColor
import ru.kolco24.kolco24.ui.theme.CpColorBlue
import ru.kolco24.kolco24.ui.theme.CpColorPurple
import ru.kolco24.kolco24.ui.theme.CpColorRed
import ru.kolco24.kolco24.ui.theme.CpColorYellow
import ru.kolco24.kolco24.ui.theme.OrangeCta
import ru.kolco24.kolco24.ui.theme.RobotoMono
import ru.kolco24.kolco24.ui.theme.Tertiary

/**
 * Compose surface of the chip-provisioning flow — the four signature pieces (rail, hero КП card, chip
 * rack, scan zone). The pure model + decisions live in `ProvisioningModel.kt`; the pager that drives
 * these and the NFC bind/write side effects are wired in Task 12. These composables are intentionally
 * stateless: the host passes the settled КП, the bound counts, and the live [ProvisionState].
 *
 * The `CheckpointColor → Color` mapping ([barColor]) lives here (a Compose file), mirroring the same
 * private mapping in `LegendScreen.kt` — fixed saturated shades, identical in light and dark.
 */

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
            val tint = tick.color?.barColor() ?: MaterialTheme.colorScheme.primary
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
