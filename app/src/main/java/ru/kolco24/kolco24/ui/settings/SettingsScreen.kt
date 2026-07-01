package ru.kolco24.kolco24.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import android.widget.Toast
import ru.kolco24.kolco24.data.AdminSession
import ru.kolco24.kolco24.data.track.pointsLabel
import ru.kolco24.kolco24.ui.theme.ThemeMode

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
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    economyMode: Boolean = false,
    onEconomyModeChange: (Boolean) -> Unit = {},
    trackPointCount: Int = 0,
    trackClearEnabled: Boolean = false,
    onClearTrack: () -> Unit = {},
    session: AdminSession,
    onOpenAdmin: () -> Unit,
    onResetTeam: (() -> Unit)? = null,
    onClearDatabase: (() -> Unit)? = null,
    onReadChipInfo: (() -> Unit)? = null,
    versionName: String = "",
    versionCode: Int = 0,
    debugInitiallyVisible: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    // The «Отладка» section is shown immediately in debug builds; in release it stays hidden until
    // the version row is tapped 10× (a hidden field-debug gate). Per-session: resets when the
    // overlay leaves composition.
    var debugUnlocked by remember { mutableStateOf(debugInitiallyVisible) }
    var versionTaps by remember { mutableIntStateOf(0) }
    // Destructive debug actions go through a confirmation dialog; null = none pending.
    var debugConfirm by remember { mutableStateOf<DebugConfirmKind?>(null) }
    val context = LocalContext.current
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

        // Content scrolls under the pinned TopAppBar so tall section lists (incl. «О приложении»
        // and the unlocked «Отладка» card) are reachable on short screens.
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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

        Text(
            text = "Внешний вид",
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
            ThemeRow(currentMode = themeMode, onClick = { showThemeDialog = true })
        }

        Text(
            text = "Запись трека",
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
                EconomyModeRow(checked = economyMode, onCheckedChange = onEconomyModeChange)
                ClearTrackRow(
                    pointCount = trackPointCount,
                    enabled = trackClearEnabled,
                    onClick = onClearTrack,
                )
            }
        }

        Text(
            text = "Администратор",
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
            AdminRow(session = session, onClick = onOpenAdmin)
        }

        // Hidden until unlocked: debug actions are always wired by MainActivity now, but the section
        // only renders in debug builds or after the 10-tap version unlock (debugUnlocked).
        if (debugUnlocked && (onResetTeam != null || onClearDatabase != null || onReadChipInfo != null)) {
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
                            onClick = { debugConfirm = DebugConfirmKind.ResetTeam },
                        )
                    }
                    if (onClearDatabase != null) {
                        DebugRow(
                            icon = Icons.Filled.DeleteSweep,
                            title = "Очистить базу данных",
                            subtitle = "Debug: удалить гонки, команды, легенду и ETag",
                            onClick = { debugConfirm = DebugConfirmKind.ClearDatabase },
                        )
                    }
                    if (onReadChipInfo != null) {
                        DebugRow(
                            icon = Icons.Filled.Info,
                            title = "Инфо о чипе",
                            subtitle = "Debug: модель метки (GET_VERSION)",
                            onClick = onReadChipInfo,
                        )
                    }
                }
            }
        }

        Text(
            text = "О приложении",
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
            VersionRow(
                versionName = versionName,
                versionCode = versionCode,
                onTap = {
                    if (!debugUnlocked) {
                        versionTaps++
                        if (versionTaps >= 10) {
                            debugUnlocked = true
                            Toast.makeText(context, "Меню отладки включено", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showThemeDialog) {
        ThemeDialog(
            currentMode = themeMode,
            onSelect = {
                onThemeModeChange(it)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
    }

    debugConfirm?.let { kind ->
        val title = when (kind) {
            DebugConfirmKind.ResetTeam -> "Сбросить команду?"
            DebugConfirmKind.ClearDatabase -> "Очистить базу данных?"
        }
        val message = when (kind) {
            DebugConfirmKind.ResetTeam -> "Текущая команда будет сброшена — придётся выбрать её заново."
            DebugConfirmKind.ClearDatabase ->
                "Все локальные данные (гонки, команды, легенда, ETag) будут удалены и загружены заново."
        }
        val confirmLabel = when (kind) {
            DebugConfirmKind.ResetTeam -> "Сбросить"
            DebugConfirmKind.ClearDatabase -> "Очистить"
        }
        val onConfirm = when (kind) {
            DebugConfirmKind.ResetTeam -> onResetTeam
            DebugConfirmKind.ClearDatabase -> onClearDatabase
        }
        AlertDialog(
            onDismissRequest = { debugConfirm = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    debugConfirm = null
                    onConfirm?.invoke()
                }) {
                    Text(confirmLabel, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { debugConfirm = null }) { Text("Отмена") }
            },
        )
    }
}

/** Which destructive debug action is awaiting confirmation in [SettingsScreen]. */
private enum class DebugConfirmKind { ResetTeam, ClearDatabase }

/** Human-readable Russian label for a [ThemeMode] (used in the subtitle + dialog rows). */
private fun themeModeLabel(mode: ThemeMode): String =
    when (mode) {
        ThemeMode.SYSTEM -> "Системная"
        ThemeMode.LIGHT -> "Светлая"
        ThemeMode.DARK -> "Тёмная"
    }

@Composable
private fun neutralAvatarContainerColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.inverseSurface
    }

@Composable
private fun neutralAvatarContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.inverseOnSurface
    }

/**
 * «Версия» row — info avatar, «versionName (versionCode)» subtitle; mirrors [ThemeRow] styling but
 * with no trailing chevron (it's informational). Tapping forwards to [onTap], which the host uses as
 * the 10-tap «Отладка» unlock gate.
 */
@Composable
private fun VersionRow(versionName: String, versionCode: Int, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(neutralAvatarContainerColor(), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = neutralAvatarContentColor(),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Версия",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "$versionName ($versionCode)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** «Тема» row — palette avatar, current-mode subtitle, chevron; mirrors [ChangeTeamRow] styling. */
@Composable
private fun ThemeRow(currentMode: ThemeMode, onClick: () -> Unit) {
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
                .background(neutralAvatarContainerColor(), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Palette,
                contentDescription = null,
                tint = neutralAvatarContentColor(),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Тема",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = themeModeLabel(currentMode),
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

/**
 * «Экономия батареи» row — battery-saver avatar, state-dependent subtitle, trailing [Switch];
 * mirrors [ThemeRow]/[ChangeTeamRow] styling but with a Switch instead of a chevron. Tapping the
 * whole row toggles too (forwards to [onCheckedChange]).
 */
@Composable
private fun EconomyModeRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(neutralAvatarContainerColor(), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.BatterySaver,
                contentDescription = null,
                tint = neutralAvatarContentColor(),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Экономия батареи",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (checked) "Координата раз в 3 мин" else "Точная запись, 15 с",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * «Очистить трек» row — red delete avatar, «N точек» subtitle, no trailing chevron (a terminal
 * destructive action, not navigation). Relocated here from `TrackCard` so a wipe is two taps deeper
 * than the frequently-visited «Команда» tab. [enabled] is false when there is nothing to clear
 * (`pointCount == 0`) or a track is recording for this team — the host owns that policy; tapping the
 * enabled row forwards to [onClick] (the host confirms via an `AlertDialog`).
 */
@Composable
private fun ClearTrackRow(pointCount: Int, enabled: Boolean, onClick: () -> Unit) {
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val avatarColor =
        if (enabled) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(avatarColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.DeleteOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = if (enabled) 1f else 0.38f),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Очистить трек",
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
            Text(
                text = pointsLabel(pointCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
            )
        }
    }
}

/** Theme picker — `AlertDialog` with three radio rows (Системная/Светлая/Тёмная). */
@Composable
private fun ThemeDialog(
    currentMode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Тема") },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = mode == currentMode,
                                role = Role.RadioButton,
                                onClick = { onSelect(mode) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(
                            selected = mode == currentMode,
                            onClick = null,
                        )
                        Text(
                            text = themeModeLabel(mode),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
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

/**
 * «Администратор» row — admin-panel avatar, subtitle = «Войти» when [AdminSession.LoggedOut] else the
 * logged-in admin email; tap → [onClick] (opens the admin overlay). Mirrors [ChangeTeamRow] styling.
 */
@Composable
private fun AdminRow(session: AdminSession, onClick: () -> Unit) {
    val subtitle = when (session) {
        AdminSession.LoggedOut -> "Войти"
        is AdminSession.LoggedIn -> session.email
    }
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
                .background(neutralAvatarContainerColor(), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.AdminPanelSettings,
                contentDescription = null,
                tint = neutralAvatarContentColor(),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Администратор",
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
                .background(neutralAvatarContainerColor(), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.SwapHoriz,
                contentDescription = null,
                tint = neutralAvatarContentColor(),
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
