package ru.kolco24.kolco24.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ru.kolco24.kolco24.ui.theme.OrangeCta

/** UI state the host feeds [WriteChipDialog]; the host arms NFC and drives the transitions. */
sealed interface WriteChipState {
    /** Armed, waiting for a chip to be tapped. */
    data object Waiting : WriteChipState

    /** Write in progress (off-main-thread MifareUltralight I/O). */
    data object Writing : WriteChipState

    /** Code written to the chip. */
    data object Success : WriteChipState

    /** Tapped tag is not a MifareUltralight. */
    data object Unsupported : WriteChipState

    /** Write failed mid-flight; [message] is the underlying I/O error. */
    data class Failed(val message: String) : WriteChipState
}

/**
 * Debug-only dialog that writes a generated [codeHex] (uuid) to a MifareUltralight tag. Stateless:
 * the host arms NFC, runs the write on each tap, and maps the result into [state]. [onReset]
 * generates a fresh code and returns to [WriteChipState.Waiting] (write another / retry);
 * [onDismiss] closes the dialog.
 */
@Composable
fun WriteChipDialog(
    codeHex: String,
    state: WriteChipState,
    nfcDisabled: Boolean,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Записать code на чип") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "code (uuid):",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = codeHex,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(20.dp))
                when (state) {
                    is WriteChipState.Waiting ->
                        if (nfcDisabled) {
                            StatusLine(
                                icon = Icons.Filled.Nfc,
                                tint = MaterialTheme.colorScheme.error,
                                text = "NFC выключен. Включите NFC и поднесите чип.",
                            )
                        } else {
                            StatusLine(
                                icon = Icons.Filled.Nfc,
                                tint = OrangeCta,
                                text = "Поднесите чип MifareUltralight к телефону.",
                            )
                        }
                    is WriteChipState.Writing ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = OrangeCta)
                            Spacer(Modifier.size(12.dp))
                            Text("Запись…", style = MaterialTheme.typography.bodyMedium)
                        }
                    is WriteChipState.Success ->
                        StatusLine(
                            icon = Icons.Filled.CheckCircle,
                            tint = MaterialTheme.colorScheme.tertiary,
                            text = "Записано на чип.",
                        )
                    is WriteChipState.Unsupported ->
                        StatusLine(
                            icon = Icons.Filled.ErrorOutline,
                            tint = MaterialTheme.colorScheme.error,
                            text = "Метка не поддерживается (нет NfcA).",
                        )
                    is WriteChipState.Failed ->
                        StatusLine(
                            icon = Icons.Filled.ErrorOutline,
                            tint = MaterialTheme.colorScheme.error,
                            text = "Не удалось записать: ${state.message}",
                        )
                }
            }
        },
        confirmButton = {
            when (state) {
                is WriteChipState.Success ->
                    TextButton(onClick = onReset) { Text("Записать ещё") }
                is WriteChipState.Failed, is WriteChipState.Unsupported ->
                    TextButton(onClick = onReset) { Text("Повторить") }
                else -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (state is WriteChipState.Success) "Готово" else "Закрыть")
            }
        },
    )
}

@Composable
private fun StatusLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
