package ru.kolco24.kolco24.ui.teampicker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.kolco24.kolco24.ui.theme.OrangeCta

/**
 * Screen 04 — empty state for the «Команда» tab shown when no team is selected (or the selected team
 * is gone from the server). Charcoal illustration with a dashed orange "to fill" ring and a red glow,
 * a heading, a short explanation and the orange CTA that opens the competition picker.
 * [missing] swaps the copy for the "team disappeared from the server" case (selection is kept).
 */
@Composable
fun TeamEmptyContent(
    onChooseTeam: () -> Unit,
    modifier: Modifier = Modifier,
    missing: Boolean = false,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(18.dp))
            EmptyIllustration()

            Spacer(Modifier.height(20.dp))
            Text(
                text = if (missing) "Команда больше не зарегистрирована" else "Команда не выбрана",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = if (missing) {
                    "Выбранная команда снялась или удалена из списка. Выберите команду заново, чтобы продолжить отмечаться."
                } else {
                    "Отметки на КП засчитываются по NFC-чипам участников. Выберите команду, чтобы отмечаться на дистанции и видеть общий счёт."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            footer()
            Button(
                onClick = onChooseTeam,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(52.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = OrangeCta,
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Выбрать команду", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** Charcoal core circle with a red glow and the team glyph, wrapped in a dashed orange ring. */
@Composable
private fun EmptyIllustration() {
    val ringColor = OrangeCta.copy(alpha = 0.45f)
    Box(
        modifier = Modifier
            .size(132.dp)
            .drawBehind {
                drawCircle(
                    color = ringColor,
                    radius = size.minDimension / 2 - 1.dp.toPx(),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 6.dp.toPx()),
                        ),
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.inverseSurface,
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ),
                    shape = CircleShape,
                )
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFC3011C).copy(alpha = 0.55f),
                                Color(0x00C3011C),
                            ),
                            center = androidx.compose.ui.geometry.Offset(size.width * 0.85f, size.height * 0.15f),
                            radius = size.minDimension * 0.7f,
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.size(50.dp),
            )
        }
    }
}
