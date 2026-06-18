package ru.kolco24.kolco24.ui.team

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.kolco24.kolco24.data.db.MemberChipBindingEntity
import ru.kolco24.kolco24.data.db.TeamMemberItem
import ru.kolco24.kolco24.ui.theme.OrangeCta
import ru.kolco24.kolco24.ui.theme.RobotoMono

/** Identifies a single member slot of a team (binding-key — see [MemberChipBindingEntity]). */
data class SlotKey(val teamId: Int, val numberInTeam: Int)

/**
 * Pure decision for the bind flow: given a scanned [uid], the participant [poolNumber] it resolves to
 * in the selected race's `member_tags` pool (`null` = not in pool), the [existing] binding currently
 * holding that uid (from `findByUid`, or `null`), and the [currentSlot] being bound, decide what the
 * sheet should do. Extracted so the branch logic is unit-tested without Compose/NFC.
 */
fun decideBind(
    uid: String,
    poolNumber: Int?,
    existing: MemberChipBindingEntity?,
    currentSlot: SlotKey,
): BindOutcome {
    if (poolNumber == null) return BindOutcome.NotInPool
    if (existing != null) {
        val existingSlot = SlotKey(existing.teamId, existing.numberInTeam)
        return if (existingSlot == currentSlot) {
            BindOutcome.AlreadyOnThisSlot(poolNumber)
        } else {
            BindOutcome.AlreadyBound(existingSlot, poolNumber)
        }
    }
    return BindOutcome.ReadyToBind(poolNumber)
}

/** Outcome of [decideBind]. */
sealed interface BindOutcome {
    /** Scanned uid is not in the race's member_tags pool — refuse, store nothing. */
    object NotInPool : BindOutcome

    /** Uid is already bound to a different [otherSlot]; reassigning would move it (warn + allow). */
    data class AlreadyBound(val otherSlot: SlotKey, val participantNumber: Int) : BindOutcome

    /** Uid is free and in the pool — bind it, resolving to [participantNumber]. */
    data class ReadyToBind(val participantNumber: Int) : BindOutcome

    /** Uid is already bound to exactly this slot — nothing to do; [participantNumber] for display. */
    data class AlreadyOnThisSlot(val participantNumber: Int) : BindOutcome
}

/**
 * UI state the host feeds the [BindChipSheet]. The sheet is otherwise stateless: the host arms NFC,
 * runs [decideBind] on each read, and maps the result here.
 */
sealed interface BindSheetState {
    /** Armed, waiting for a chip to be tapped. */
    object Waiting : BindSheetState

    /** Scanned [uid] is not in the pool. */
    data class NotInPool(val uid: String) : BindSheetState

    /** Scanned [uid] (participant [participantNumber]) is already bound to another member. */
    data class AlreadyBound(val uid: String, val participantNumber: Int) : BindSheetState

    /** Bound [participantNumber] / [uid] to this slot — auto-dismisses. */
    data class Success(val participantNumber: Int, val uid: String) : BindSheetState

    /** Pool hasn't synced yet — a background refresh was triggered; user should rescan shortly. */
    object PoolNotReady : BindSheetState
}

/**
 * Capture sheet for binding an NFC chip to [member]. Mirrors `TeamSwitchSheet` ([ModalBottomSheet]).
 * The host drives [state]: `Waiting` shows the tap prompt (or an NFC-disabled hint with
 * [onOpenNfcSettings] when [nfcDisabled]); `NotInPool` warns; `AlreadyBound` offers «Перепривязать»
 * via [onReassign]; `Success` shows the confirmation (host auto-dismisses). [onDismiss] (handle drag,
 * scrim tap, system back, «Отмена») closes without binding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BindChipSheet(
    member: TeamMemberItem,
    state: BindSheetState,
    nfcDisabled: Boolean,
    onReassign: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "ПРИВЯЗАТЬ ЧИП",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                ),
                fontFamily = RobotoMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = member.name,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))
            when (state) {
                is BindSheetState.Waiting -> WaitingContent(nfcDisabled, onOpenNfcSettings)
                is BindSheetState.PoolNotReady -> PoolNotReadyContent()
                is BindSheetState.NotInPool -> StatusContent(
                    icon = Icons.Filled.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error,
                    title = "Чип не из этого комплекта",
                    detail = state.uid,
                    body = "Этот чип не зарегистрирован для гонки. Привязка не сохранена.",
                )
                is BindSheetState.AlreadyBound -> {
                    StatusContent(
                        icon = Icons.Filled.ErrorOutline,
                        tint = OrangeCta,
                        title = "Чип уже привязан",
                        detail = "№${state.participantNumber} · ${state.uid}",
                        body = "Этот чип закреплён за другим участником. Перепривязать его к «${member.name}»?",
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onReassign,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OrangeCta,
                            contentColor = androidx.compose.ui.graphics.Color.White,
                        ),
                    ) {
                        Text(
                            text = "Перепривязать",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
                is BindSheetState.Success -> StatusContent(
                    icon = Icons.Filled.CheckCircle,
                    tint = MaterialTheme.colorScheme.tertiary,
                    title = "Чип привязан",
                    detail = "№${state.participantNumber} · ${state.uid}",
                    body = null,
                )
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(
                    text = if (state is BindSheetState.Success) "Готово" else "Отмена",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PoolNotReadyContent() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(36.dp),
            color = OrangeCta,
        )
    }
    Spacer(Modifier.height(16.dp))
    Text(
        text = "Загружаем список участников",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Данные ещё не загружены. Поднесите чип снова через несколько секунд.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun WaitingContent(nfcDisabled: Boolean, onOpenNfcSettings: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Nfc,
            contentDescription = null,
            tint = OrangeCta,
            modifier = Modifier.size(36.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
    if (nfcDisabled) {
        Text(
            text = "NFC выключен",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Включите NFC, чтобы считать чип.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onOpenNfcSettings,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = OrangeCta,
                contentColor = androidx.compose.ui.graphics.Color.White,
            ),
        ) {
            Text(
                text = "Открыть настройки NFC",
                style = MaterialTheme.typography.titleSmall,
            )
        }
    } else {
        Text(
            text = "Поднесите чип к телефону",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Браслет участника нужно поднести к задней панели телефона.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    detail: String?,
    body: String?,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(48.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    if (detail != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    if (body != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
