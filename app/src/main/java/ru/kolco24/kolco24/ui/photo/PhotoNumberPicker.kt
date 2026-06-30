package ru.kolco24.kolco24.ui.photo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.kolco24.kolco24.data.db.CheckpointEntity
import ru.kolco24.kolco24.data.marks.filterCheckpointsByQuery
import ru.kolco24.kolco24.data.marks.resolvePhotoCheckpoint
import ru.kolco24.kolco24.ui.theme.RobotoMono

/**
 * Lightweight КП-number picker overlay for the photo-mark `AskNumber` path (no NFC take within the
 * 3-minute auto-attach window). Follows the overlay convention: a `rememberSaveable` host flag renders
 * this after the `Scaffold`, dismissed via [BackHandler].
 *
 * The user types a КП number (numeric field) which **filters** the synced [legend] live; tapping a row
 * — or submitting an exact number via the IME action — raises [onCheckpointSelected] so the host can open
 * [PhotoCaptureScreen] with an `AskNumber` target built from the chosen checkpoint. A number absent from
 * the legend yields an inline error and **no mark** (mirrors v1's "warning if not in legend"). Locked КП
 * (`locked = true`, `cost = null`) are listed and selectable on purpose — that is the core "метку сорвали"
 * scenario (the code was never read, so the КП stays unrevealed). No data is written here; the row is
 * created later by the camera commit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoNumberPicker(
    legend: List<CheckpointEntity>,
    onCheckpointSelected: (CheckpointEntity) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var notFound by rememberSaveable { mutableStateOf(false) }

    val filtered = remember(legend, query) { filterCheckpointsByQuery(legend, query) }

    BackHandler { onBack() }

    fun submit() {
        val number = query.trim().toIntOrNull() ?: run { notFound = true; return }
        val cp = resolvePhotoCheckpoint(number, legend)
        if (cp == null) notFound = true else onCheckpointSelected(cp)
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Номер КП") },
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
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TextField(
                value = query,
                onValueChange = {
                    // Digits only — the field is the КП number; live filter, clear stale error.
                    query = it.filter(Char::isDigit)
                    notFound = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Введите номер КП") },
                singleLine = true,
                isError = notFound,
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )

            if (notFound) {
                Text(
                    text = "КП с таким номером нет в легенде",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            ) {
                if (filtered.isEmpty()) {
                    item("empty") {
                        Text(
                            text = "Ничего не найдено",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(filtered, key = { it.id }) { cp ->
                        CheckpointPickRow(cp = cp, onClick = { onCheckpointSelected(cp) })
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckpointPickRow(cp: CheckpointEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (cp.locked) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        RoundedCornerShape(5.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Text(
            text = if (cp.cost != null && cp.cost != 0) {
                "${cp.cost}-${cp.number.toString().padStart(2, '0')}"
            } else {
                cp.number.toString().padStart(2, '0')
            },
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            fontFamily = RobotoMono,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(min = 48.dp),
        )
        Text(
            text = cp.description.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
