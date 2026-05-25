package ru.kolco24.kolco24

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import ru.kolco24.kolco24.ui.legend.LegendScreen
import ru.kolco24.kolco24.ui.theme.Kolco24Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Kolco24Theme {
                Kolco24App()
            }
        }
    }
}

@Composable
private fun Kolco24App() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            if (selectedTab == 0) Icons.Filled.Flag else Icons.Outlined.Flag,
                            contentDescription = null,
                        )
                    },
                    label = { Text("Отметки") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            if (selectedTab == 1) Icons.Filled.Map else Icons.Outlined.Map,
                            contentDescription = null,
                        )
                    },
                    label = { Text("Легенда") },
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            if (selectedTab == 2) Icons.Filled.Groups else Icons.Outlined.Groups,
                            contentDescription = null,
                        )
                    },
                    label = { Text("Команда") },
                )
            }
        },
    ) { innerPadding ->
        when (selectedTab) {
            0 -> PlaceholderScreen(
                title = "Отметки",
                modifier = Modifier.padding(innerPadding),
            )
            1 -> LegendScreen(
                modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
            )
            2 -> PlaceholderScreen(
                title = "Команда",
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
