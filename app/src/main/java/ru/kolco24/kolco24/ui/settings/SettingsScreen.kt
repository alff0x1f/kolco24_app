package ru.kolco24.kolco24.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Settings (Настройки) overlay — a full-screen `Column` rendered over the «Команда» tab via a
 * `rememberSaveable` flag in `MainActivity` (same overlay pattern as the scan/team-picker flows).
 * Stateless: [onBack] dismisses it, [onChangeTeam] hands off to the comp-picker flow. Its single
 * functional entry is the relocated «Сменить команду» row (moved out of `TeamScreen`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChangeTeam: () -> Unit,
    onResetTeam: (() -> Unit)? = null,
    onClearDatabase: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface).pointerInput(Unit) { detectTapGestures {} }) {
        TopAppBar(
            title = { Text("Настройки") },
            navigationIcon = {
                IconButton(onClick = onBack) {
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

        Text(
            text = "Команда",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            ChangeTeamRow(onClick = onChangeTeam)
        }

        // Debug-only: caller passes non-null callbacks only in debug builds (see MainActivity).
        if (onResetTeam != null || onClearDatabase != null) {
            Text(
                text = "Отладка",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column {
                    if (onResetTeam != null) {
                        DebugRow(
                            icon = Icons.Filled.RestartAlt,
                            title = "Сбросить команду",
                            subtitle = "Debug: вернуться к выбору команды",
                            onClick = onResetTeam,
                        )
                    }
                    if (onClearDatabase != null) {
                        DebugRow(
                            icon = Icons.Filled.DeleteSweep,
                            title = "Очистить базу данных",
                            subtitle = "Debug: удалить гонки, команды, легенду и ETag",
                            onClick = onClearDatabase,
                        )
                    }
                }
            }
        }
    }
}

/** Debug-only action row — red avatar, title + subtitle, chevron. */
@Composable
private fun DebugRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Relocated «Сменить команду» row — charcoal swap icon, mirrors the old `SwitchTeamRow`. */
@Composable
private fun ChangeTeamRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.inverseSurface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.SwapHoriz,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Сменить команду",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Выбрать другую команду соревнования",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
