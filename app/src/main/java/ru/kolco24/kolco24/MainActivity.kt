package ru.kolco24.kolco24

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import ru.kolco24.kolco24.ui.legend.LegendScreen
import ru.kolco24.kolco24.ui.marks.MarksScreen
import ru.kolco24.kolco24.ui.scan.ScanScreen
import ru.kolco24.kolco24.ui.team.TeamScreen
import ru.kolco24.kolco24.ui.theme.Kolco24Theme
import ru.kolco24.kolco24.ui.theme.OrangeCta

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
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    var showScan by rememberSaveable { mutableStateOf(false) }

    val navItemColors = NavigationBarItemDefaults.colors(
        indicatorColor = Color.Transparent,
        selectedIconColor = OrangeCta,
        selectedTextColor = OrangeCta,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    val activePage = pagerState.targetPage
                    NavigationBarItem(
                        selected = activePage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                        icon = {
                            Icon(
                                if (activePage == 0) Icons.Filled.Flag else Icons.Outlined.Flag,
                                contentDescription = null,
                            )
                        },
                        label = { Text("Отметки") },
                        colors = navItemColors,
                    )
                    NavigationBarItem(
                        selected = activePage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        icon = {
                            Icon(
                                if (activePage == 1) Icons.Filled.Map else Icons.Outlined.Map,
                                contentDescription = null,
                            )
                        },
                        label = { Text("Легенда") },
                        colors = navItemColors,
                    )
                    NavigationBarItem(
                        selected = activePage == 2,
                        onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                        icon = {
                            Icon(
                                if (activePage == 2) Icons.Filled.Groups else Icons.Outlined.Groups,
                                contentDescription = null,
                            )
                        },
                        label = { Text("Команда") },
                        colors = navItemColors,
                    )
                }
            },
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                when (page) {
                    0 -> MarksScreen(
                        onScanClick = { showScan = true },
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                    )
                    1 -> LegendScreen(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()))
                    2 -> TeamScreen(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()))
                }
            }
        }
        if (showScan) {
            BackHandler { showScan = false }
            ScanScreen(onClose = { showScan = false }, modifier = Modifier.fillMaxSize())
        }
    }
}
