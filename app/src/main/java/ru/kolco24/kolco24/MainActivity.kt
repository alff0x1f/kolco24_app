package ru.kolco24.kolco24

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import ru.kolco24.kolco24.ui.legend.LegendScreen
import ru.kolco24.kolco24.ui.marks.MarksScreen
import ru.kolco24.kolco24.ui.team.TeamScreen
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
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = {
                        Icon(
                            if (pagerState.currentPage == 0) Icons.Filled.Flag else Icons.Outlined.Flag,
                            contentDescription = null,
                        )
                    },
                    label = { Text("Отметки") },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = {
                        Icon(
                            if (pagerState.currentPage == 1) Icons.Filled.Map else Icons.Outlined.Map,
                            contentDescription = null,
                        )
                    },
                    label = { Text("Легенда") },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 2,
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    icon = {
                        Icon(
                            if (pagerState.currentPage == 2) Icons.Filled.Groups else Icons.Outlined.Groups,
                            contentDescription = null,
                        )
                    },
                    label = { Text("Команда") },
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
                0 -> MarksScreen(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()))
                1 -> LegendScreen(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()))
                2 -> TeamScreen(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()))
            }
        }
    }
}
