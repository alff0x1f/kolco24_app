package ru.kolco24.kolco24.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.kolco24.kolco24.data.map.MbtilesMetadata
import ru.kolco24.kolco24.ui.theme.OrangeCta
import java.util.TimeZone

/** The resolved base layer: [source] plus the offline file's [metadata] (read off-main by the host). */
data class MapBase(val source: MapStyleSource, val metadata: MbtilesMetadata?)

private const val OSM_ATTRIBUTION_TEXT = "© OpenStreetMap contributors"

/**
 * «Карта» tab: the team's GPS track and taken КП over the race's offline MBTiles base (or online OSM).
 * Stateless apart from the selected-pin card.
 *
 * - [hasTeam] `false` → «Выберите команду» empty state (no map at all).
 * - [isActive] (`pagerState.settledPage == 2`) gates the [TrackMapView] — the MapView (and its GPS
 *   LocationComponent) never lives off-screen or during a tab animation through this page.
 * - [availability]/[base] `null` → still resolving (team/race/metadata) → plain background.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    hasTeam: Boolean,
    onChooseTeam: () -> Unit,
    isActive: Boolean,
    availability: MapAvailability?,
    base: MapBase?,
    trackGeoJson: String,
    pins: List<MapPin>,
    pinsGeoJson: String,
    locationPermitted: Boolean,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Карта") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        if (!hasTeam) {
            MapNoTeam(onChooseTeam = onChooseTeam)
            return@Column
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            if (!isActive || availability == null || base == null) return@Box

            var selectedPinId by rememberSaveable { mutableStateOf<Int?>(null) }
            val pinNumbers = remember(pins) { pins.mapTo(HashSet()) { it.number } }
            // A pin that vanished (team switch, mark deleted) hides its card.
            val selectedPin = selectedPinId?.let { id -> pins.firstOrNull { it.checkpointId == id } }

            TrackMapView(
                styleSource = base.source,
                metadata = base.metadata,
                trackGeoJson = trackGeoJson,
                pinsGeoJson = pinsGeoJson,
                pinNumbers = pinNumbers,
                locationPermitted = locationPermitted,
                onPinClick = { selectedPinId = it },
                modifier = Modifier.fillMaxSize(),
            )

            if (availability == MapAvailability.NoMapForRace) {
                NoMapBanner(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selectedPin != null) {
                    PinCard(caption = pinCaption(selectedPin, TimeZone.getDefault()))
                }
                when (availability) {
                    MapAvailability.NotDownloaded -> DownloadCard(enabled = true, onDownload = onDownload)
                    MapAvailability.BusyOtherRace -> DownloadCard(enabled = false, onDownload = onDownload)
                    is MapAvailability.Downloading ->
                        DownloadingCard(progress = availability.progress, onCancel = onCancelDownload)
                    MapAvailability.NoMapForRace, MapAvailability.Ready -> Unit
                }
                // Visible attribution whenever the online OSM base is on screen (not just MapLibre's (i)).
                if (base.source is MapStyleSource.Online) {
                    Text(
                        text = OSM_ATTRIBUTION_TEXT,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF333333),
                        modifier = Modifier
                            .align(Alignment.End)
                            .background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NoMapBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Оффлайн-карта для этой гонки недоступна",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PinCard(caption: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Place, contentDescription = null, tint = OrangeCta, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DownloadCard(enabled: Boolean, onDownload: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Скачать карту гонки",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (enabled) {
                    "Скачайте до старта — карта будет работать без интернета"
                } else {
                    "Идёт скачивание карты другой гонки"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDownload,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(containerColor = OrangeCta, contentColor = Color.White),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Скачать", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun DownloadingCard(progress: Float?, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Скачивание карты…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (progress != null) {
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCancel) { Text("Отмена") }
            }
            Spacer(Modifier.height(4.dp))
            val indicatorModifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp, bottom = 8.dp)
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = indicatorModifier, color = OrangeCta)
            } else {
                LinearProgressIndicator(modifier = indicatorModifier, color = OrangeCta)
            }
        }
    }
}

@Composable
private fun MapNoTeam(onChooseTeam: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                tint = OrangeCta,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Выберите команду",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Карта с треком и взятыми КП привязана к команде. Выберите соревнование и команду — карта появится здесь.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onChooseTeam,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(containerColor = OrangeCta, contentColor = Color.White),
            ) {
                Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Выбрать команду", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
