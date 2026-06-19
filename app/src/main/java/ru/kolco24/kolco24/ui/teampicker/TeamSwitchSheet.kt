package ru.kolco24.kolco24.ui.teampicker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.kolco24.kolco24.data.db.CategoryEntity
import ru.kolco24.kolco24.data.db.TeamEntity
import ru.kolco24.kolco24.ui.theme.OrangeCta

/**
 * Screen 04d — confirmation [ModalBottomSheet] before switching to a team. Shows the team token,
 * name and category, the roster (member names), and the orange "Выбрать команду" CTA. [onConfirm] commits the
 * choice (host persists it via `selectTeam`); [onDismiss] (handle drag, scrim tap, system back, or
 * "Отмена") closes the sheet without changing the selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamSwitchSheet(
    team: TeamEntity,
    category: CategoryEntity?,
    onConfirm: () -> Unit,
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
            TeamToken(text = teamToken(team), size = 60.dp)

            Spacer(Modifier.height(12.dp))
            Text(
                text = displayTeamName(team),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = peopleLine(category, team.ucount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (team.members.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                team.members.sortedBy { it.numberInTeam }.forEach { member ->
                    Text(
                        text = member.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = OrangeCta,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Выбрать команду",
                    style = MaterialTheme.typography.titleSmall,
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
                    text = "Отмена",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
