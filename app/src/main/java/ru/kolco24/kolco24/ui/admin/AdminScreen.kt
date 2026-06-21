package ru.kolco24.kolco24.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ru.kolco24.kolco24.Kolco24App
import ru.kolco24.kolco24.data.AdminSession
import ru.kolco24.kolco24.data.LoginOutcome
import ru.kolco24.kolco24.data.adminErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Race-admin overlay (full-screen, hosted via the `showAdmin` flag in `MainActivity`, same overlay
 * pattern as Settings/scan/team-picker). Branches on [session]: [AdminSession.LoggedOut] renders the
 * login form, [AdminSession.LoggedIn] renders the admin home (email + «Привязать чип к КП» +
 * «Выйти»). Login/logout route through `AdminAuthRepository` on `applicationScope`; the session
 * `StateFlow` then flips this overlay between the two branches reactively. [onClose] dismisses the
 * overlay; [onOpenProvisioning] opens the bulk chip-provisioning pager (host flag, Task 12).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    session: AdminSession,
    onClose: () -> Unit,
    onOpenProvisioning: () -> Unit = {},
    onOpenCheckChip: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // Swallow taps so they don't fall through to the screen behind the overlay.
            .pointerInput(Unit) { detectTapGestures {} },
    ) {
        TopAppBar(
            title = { Text("Администратор") },
            navigationIcon = {
                IconButton(onClick = onClose) {
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
        when (session) {
            AdminSession.LoggedOut -> LoginForm()
            is AdminSession.LoggedIn -> AdminHome(
                session = session,
                onOpenProvisioning = onOpenProvisioning,
                onOpenCheckChip = onOpenCheckChip,
            )
        }
    }
}

/** Drives the login form: idle, submitting (spinner), or showing an inline RU error message. */
private sealed interface AdminLoginState {
    data object Idle : AdminLoginState
    data object Submitting : AdminLoginState
    data class Error(val message: String) : AdminLoginState
}

/**
 * Email + password fields and a «Войти» button. On submit calls `AdminAuthRepository.login` on the
 * container's `applicationScope`; a non-success outcome is mapped through [adminErrorMessage] into an
 * inline error. Success needs no local handling — the session flow flips the overlay to [AdminHome].
 */
@Composable
private fun LoginForm() {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as Kolco24App).container }
    val keyboard = LocalSoftwareKeyboardController.current

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<AdminLoginState>(AdminLoginState.Idle) }
    val submitting = state is AdminLoginState.Submitting

    fun submit() {
        if (email.isBlank() || password.isBlank() || submitting) return
        keyboard?.hide()
        state = AdminLoginState.Submitting
        container.applicationScope.launch {
            val outcome = container.adminAuthRepository.login(email.trim(), password)
            withContext(Dispatchers.Main) {
                state = if (outcome == LoginOutcome.Success) {
                    AdminLoginState.Idle
                } else {
                    AdminLoginState.Error(adminErrorMessage(outcome))
                }
            }
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Вход для администратора гонки",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                if (state is AdminLoginState.Error) state = AdminLoginState.Idle
            },
            label = { Text("Email") },
            singleLine = true,
            enabled = !submitting,
            isError = state is AdminLoginState.Error,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                if (state is AdminLoginState.Error) state = AdminLoginState.Idle
            },
            label = { Text("Пароль") },
            singleLine = true,
            enabled = !submitting,
            isError = state is AdminLoginState.Error,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        val errorState = state as? AdminLoginState.Error
        if (errorState != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = errorState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { submit() },
            enabled = !submitting && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Войти")
            }
        }
    }
}

/**
 * Admin home shown while logged in: the admin [AdminSession.email], a «Привязать чип к КП» row that
 * opens the provisioning pager, and a «Выйти» row that calls `logout()` on the container's
 * `applicationScope` (best-effort server revoke + local clear; the session flow then flips back to
 * the login form).
 */
@Composable
private fun AdminHome(
    session: AdminSession.LoggedIn,
    onOpenProvisioning: () -> Unit,
    onOpenCheckChip: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as Kolco24App).container }

    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = "Вы вошли как",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = session.email,
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            AdminActionRow(
                icon = Icons.Filled.Nfc,
                title = "Привязать чип к КП",
                subtitle = "Записать NFC-метки на контрольные пункты",
                onClick = onOpenProvisioning,
            )
        }

        Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            AdminActionRow(
                icon = Icons.AutoMirrored.Filled.FactCheck,
                title = "Проверить чип КП",
                subtitle = "Узнать, к какому КП привязан чип",
                onClick = onOpenCheckChip,
            )
        }

        Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            AdminActionRow(
                icon = Icons.AutoMirrored.Filled.Logout,
                title = "Выйти",
                subtitle = "Завершить сессию администратора",
                onClick = { container.applicationScope.launch { container.adminAuthRepository.logout() } },
            )
        }
    }
}

/** Admin-home action row — charcoal avatar, title + subtitle, chevron; mirrors the Settings rows. */
@Composable
private fun AdminActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.inverseSurface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
