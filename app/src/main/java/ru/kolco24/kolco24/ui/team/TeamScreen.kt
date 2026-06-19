package ru.kolco24.kolco24.ui.team

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ru.kolco24.kolco24.data.db.CategoryEntity
import ru.kolco24.kolco24.data.db.MemberChipBindingEntity
import ru.kolco24.kolco24.data.db.TeamEntity
import ru.kolco24.kolco24.data.db.TeamMemberItem
import ru.kolco24.kolco24.ui.teampicker.TeamEmptyContent
import ru.kolco24.kolco24.ui.teampicker.displayTeamName
import ru.kolco24.kolco24.ui.teampicker.peopleLine
import ru.kolco24.kolco24.ui.theme.OrangeCta

/**
 * Screen 04 — the «Команда» tab. With no selected [team] it shows [TeamEmptyContent] (onboarding,
 * or the "team disappeared" notice when [teamMissing]); otherwise it renders the selected team's
 * hero card and roster. State is hoisted: the host collects the selection and passes it in.
 *
 * Each member slot carries an optional local NFC chip [bindings] entry (keyed by `numberInTeam`):
 * bound members render their participant number + uid and a long-press on the row requests an unbind
 * (the host confirms via a dialog — a plain tap does nothing, to avoid accidental deletes); unbound
 * members show «Чип не привязан» + a «Привязать» button (enabled only when [nfcAvailable]). The hero card's
 * «N / total с чипом» counter is driven by `members.count { bindings.containsKey(it.numberInTeam) }`
 * (counts only current roster members with bindings, so stale entries for removed members are ignored).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(
    team: TeamEntity?,
    category: CategoryEntity?,
    onChooseTeam: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    teamMissing: Boolean = false,
    teamLoading: Boolean = false,
    bindings: Map<Int, MemberChipBindingEntity> = emptyMap(),
    onBindMember: (TeamMemberItem) -> Unit = {},
    onUnbindMember: (TeamMemberItem) -> Unit = {},
    nfcAvailable: Boolean = false,
) {
    if (team == null) {
        if (!teamLoading) {
            Column(modifier = modifier.fillMaxSize()) {
                TeamTopBar()
                TeamEmptyContent(
                    onChooseTeam = onChooseTeam,
                    missing = teamMissing,
                    footer = { MiscSection(onOpenSettings) },
                )
            }
        }
        return
    }

    val members = team.members

    Column(modifier = modifier.fillMaxSize()) {
        TeamTopBar()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item("hero") {
                TeamHeroCard(
                    team = team,
                    category = category,
                    totalCount = team.ucount,
                    boundCount = members.count { bindings.containsKey(it.numberInTeam) },
                )
            }
            item("members") {
                SectionCard(
                    title = "Состав · ${members.size}",
                    supporting = "Привяжите NFC-чип каждому участнику до старта — без него отметки не засчитаются.",
                ) {
                    members.forEachIndexed { index, member ->
                        MemberRow(
                            member = member,
                            binding = bindings[member.numberInTeam],
                            isLast = index == members.lastIndex,
                            nfcAvailable = nfcAvailable,
                            onBind = { onBindMember(member) },
                            onUnbind = { onUnbindMember(member) },
                        )
                    }
                }
            }
            item("misc") {
                MiscSection(onOpenSettings = onOpenSettings)
            }
        }
    }
}

@Composable
private fun MiscSection(onOpenSettings: () -> Unit) {
    SectionCard(title = "Прочее") {
        MiscRow(icon = Icons.Filled.Settings, label = "Настройки", subtitle = "Сменить команду", isLast = true, onClick = onOpenSettings)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamTopBar() {
    TopAppBar(
        title = { Text("Команда") },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun TeamHeroCard(team: TeamEntity, category: CategoryEntity?, totalCount: Int, boundCount: Int) {
    val allBound = totalCount > 0 && boundCount >= totalCount
    val number = team.startNumber?.takeIf { it.isNotBlank() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (number != null) {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
                Text(
                    text = displayTeamName(team),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            Text(
                text = peopleLine(category, totalCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.70f),
            )

            Spacer(Modifier.height(14.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = Color.White.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(
                        modifier = Modifier.size(6.dp).background(
                            if (allBound) MaterialTheme.colorScheme.tertiaryContainer else Color(0xFFFFD7A2),
                            CircleShape,
                        )
                    )
                    Text(
                        text = "$boundCount / $totalCount с чипом",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }

            if (!allBound && totalCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = chipNotBoundText(totalCount - boundCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.65f),
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    supporting: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 18.dp)) {
        Text(
            text = title,
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
            Column { content() }
        }
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemberRow(
    member: TeamMemberItem,
    binding: MemberChipBindingEntity?,
    isLast: Boolean,
    nfcAvailable: Boolean,
    onBind: () -> Unit,
    onUnbind: () -> Unit,
) {
    val bound = binding != null
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (bound) Modifier.combinedClickable(onClick = {}, onLongClick = onUnbind) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MonogramAvatar()

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (binding != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "№${binding.participantNumber}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Box(modifier = Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        Text(
                            text = "Чип не привязан",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (!bound) {
                OutlinedButton(
                    onClick = onBind,
                    enabled = nfcAvailable,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.Nfc, contentDescription = null, modifier = Modifier.size(18.dp), tint = OrangeCta)
                    Spacer(Modifier.width(6.dp))
                    Text("Привязать", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (!isLast) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 70.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun MonogramAvatar() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PersonAdd,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun MiscRow(icon: ImageVector, label: String, subtitle: String, isLast: Boolean, onClick: (() -> Unit)? = null) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!isLast) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 70.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** Russian declension for "N чипов/чипа/чип не привязан/не привязаны". */
private fun chipNotBoundText(n: Int): String {
    val rem100 = n % 100
    val rem10 = n % 10
    return when {
        rem100 in 11..19 -> "$n чипов не привязаны"
        rem10 == 1 -> "$n чип не привязан"
        rem10 in 2..4 -> "$n чипа не привязаны"
        else -> "$n чипов не привязаны"
    }
}
