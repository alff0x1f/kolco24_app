package ru.kolco24.kolco24.ui.common

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ru.kolco24.kolco24.data.RefreshResult
import ru.kolco24.kolco24.ui.theme.OrangeCta

/**
 * Twitter-style pull-to-refresh around a scrollable list body (a `LazyColumn`). Place it **inside**
 * a screen, around the list only — not the `TopAppBar` — so the indicator falls under the fixed bar.
 *
 * Standardizes the indicator once (brand [OrangeCta] arrow on a `surfaceContainer` puck) so every tab
 * that adopts the gesture (Легенда, Команда, …) looks identical without copy-pasting indicator config.
 *
 * [isRefreshing] is owned by the host (toggled around the suspending refresh); success is silent (the
 * Room flow updates the list), failures surface via a snackbar mapped by [refreshErrorMessage].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshableList(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                color = OrangeCta,
            )
        },
        content = { content() },
    )
}

/**
 * Pure mapping of a pull-to-refresh outcome to the snackbar message (RU), or `null` when nothing should
 * be shown. Success ([RefreshResult.Updated]/[RefreshResult.NotModified]) is silent — the list updates
 * itself via Room — so only the failure branches return a message.
 */
fun refreshErrorMessage(result: RefreshResult): String? = when (result) {
    RefreshResult.Updated, RefreshResult.NotModified -> null
    RefreshResult.Offline -> "Нет сети — не удалось обновить"
    RefreshResult.Forbidden -> "Доступ запрещён"
    is RefreshResult.HttpError -> "Ошибка сервера (${result.code})"
}
