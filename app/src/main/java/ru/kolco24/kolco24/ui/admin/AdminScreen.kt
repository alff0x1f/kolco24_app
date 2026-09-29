package ru.kolco24.kolco24.ui.admin

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.kolco24.kolco24.Kolco24App
import ru.kolco24.kolco24.data.AdminSession
import ru.kolco24.kolco24.data.LoginOutcome
import ru.kolco24.kolco24.data.adminErrorMessage
import ru.kolco24.kolco24.data.combinedLoginOutcome
import ru.kolco24.kolco24.ui.theme.BrandRed
import ru.kolco24.kolco24.ui.theme.OnBrandRed
import ru.kolco24.kolco24.ui.theme.OnTertiary
import ru.kolco24.kolco24.ui.theme.Tertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which server a login targets; `rememberSaveable`-friendly (plain enum). */
private enum class AdminServer { Cloud, Lan }

/**
 * Race-admin overlay (full-screen, hosted via the `showAdmin` flag in `MainActivity`, same overlay
 * pattern as Settings/scan/team-picker). There are two independent sessions — [cloudSession] (cloud
 * site) and [localSession] (LAN race server, which issues its own tokens). With both logged out it
 * renders the login form (logs into cloud, plus LAN while local mode is on); with either logged in it
 * renders the admin home. From home, «Войти» on a server's status row opens the form for just that
 * server (`reLoginTarget`; back or success returns home). Login/logout route through the container's
 * `AdminAuthRepository`s on `applicationScope`; the session flows then flip this overlay reactively.
 * [onClose] dismisses the overlay; [onOpenProvisioning] opens the bulk chip-provisioning pager.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    cloudSession: AdminSession,
    localSession: AdminSession,
    onClose: () -> Unit,
    onOpenProvisioning: () -> Unit = {},
    onOpenMemberProvisioning: () -> Unit = {},
    onOpenCheckChip: () -> Unit = {},
    onOpenCheckMemberChip: () -> Unit = {},
    onOpenJudgeScan: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as Kolco24App).container }
    // Read to recompose on lease changes; isLanActive() also checks expiry against trusted time.
    val lease by container.raceLease.collectAsState()
    val lanActive = lease != null && container.isLanActive()

    var reLoginTarget by rememberSaveable { mutableStateOf<AdminServer?>(null) }
    val anyLoggedIn = cloudSession is AdminSession.LoggedIn || localSession is AdminSession.LoggedIn
    // The re-login form closes when its server's session appears — from this form or from an older,
    // still in-flight attempt — rather than via a success callback a stale attempt could also fire.
    LaunchedEffect(reLoginTarget, cloudSession, localSession) {
        val targetSession = when (reLoginTarget) {
            AdminServer.Cloud -> cloudSession
            AdminServer.Lan -> localSession
            null -> null
        }
        // Also drop it once both sessions are gone: the full form takes over, and a stale target
        // must not resurface as a single-server form after the next login.
        if (targetSession is AdminSession.LoggedIn || !anyLoggedIn) reLoginTarget = null
    }
    val showForm = !anyLoggedIn || reLoginTarget != null

    // Registered after MainActivity's admin BackHandler, so it wins while the re-login form is up.
    BackHandler(enabled = reLoginTarget != null && anyLoggedIn) { reLoginTarget = null }

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
                IconButton(onClick = { if (reLoginTarget != null && anyLoggedIn) reLoginTarget = null else onClose() }) {
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
        if (showForm) {
            val target = reLoginTarget.takeIf { anyLoggedIn }
            LoginForm(
                target = target,
                initialEmail = (cloudSession as? AdminSession.LoggedIn)?.email
                    ?: (localSession as? AdminSession.LoggedIn)?.email
                    ?: "",
            )
        } else {
            AdminHome(
                cloudSession = cloudSession,
                localSession = localSession,
                lanActive = lanActive,
                onLogin = { reLoginTarget = it },
                onOpenProvisioning = onOpenProvisioning,
                onOpenMemberProvisioning = onOpenMemberProvisioning,
                onOpenCheckChip = onOpenCheckChip,
                onOpenCheckMemberChip = onOpenCheckMemberChip,
                onOpenJudgeScan = onOpenJudgeScan,
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
 * Email + password fields and a «Войти» button. [target] `null` = first login: cloud, plus LAN only
 * while local mode is on (the LAN host is cleartext — the password must not go there on a random
 * network). Otherwise logs into just that server. Servers are tried in parallel on `applicationScope`;
 * when none succeeds, [combinedLoginOutcome] picks the error to show (a real answer beats «нет сети»).
 * Success needs no local handling — the session flows flip the overlay to [AdminHome].
 */
@Composable
private fun LoginForm(target: AdminServer?, initialEmail: String) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as Kolco24App).container }
    val keyboard = LocalSoftwareKeyboardController.current

    var email by remember { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<AdminLoginState>(AdminLoginState.Idle) }
    val submitting = state is AdminLoginState.Submitting

    fun submit() {
        if (email.isBlank() || password.isBlank() || submitting) return
        keyboard?.hide()
        // Lease re-checked at submit time: it may have expired while the form was open.
        val lanActive = container.isLanActive()
        val repos = when (target) {
            AdminServer.Cloud -> listOf(container.cloudAdminAuth)
            AdminServer.Lan -> listOfNotNull(container.localAdminAuth.takeIf { lanActive })
            null -> listOfNotNull(
                container.cloudAdminAuth,
                container.localAdminAuth.takeIf { lanActive },
            )
        }
        if (repos.isEmpty()) {
            state = AdminLoginState.Error("Включите локальный режим гонки")
            return
        }
        state = AdminLoginState.Submitting
        container.applicationScope.launch {
            val outcome = coroutineScope {
                combinedLoginOutcome(repos.map { async { it.login(email.trim(), password) } }.awaitAll())
            }
            withContext(Dispatchers.Main) {
                if (outcome == LoginOutcome.Success) {
                    state = AdminLoginState.Idle
                } else {
                    state = AdminLoginState.Error(adminErrorMessage(outcome))
                }
            }
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = when (target) {
                AdminServer.Cloud -> "Вход на cloud-сервер"
                AdminServer.Lan -> "Вход на LAN-сервер гонки"
                null -> "Вход для администратора гонки"
            },
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
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль",
                    )
                }
            },
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
 * Admin home shown while either session is logged in: a status row per server (email, or «нет входа»
 * with a «Войти» that opens the form for that server — LAN only while local mode is on), the admin
 * action rows, and «Выйти», which calls `logout()` on both servers, each in its own `applicationScope`
 * job so a LAN logout never waits on a cloud timeout (on a logged-out server it only cancels an
 * in-flight login, no request).
 */
@Composable
private fun AdminHome(
    cloudSession: AdminSession,
    localSession: AdminSession,
    lanActive: Boolean,
    onLogin: (AdminServer) -> Unit,
    onOpenProvisioning: () -> Unit,
    onOpenMemberProvisioning: () -> Unit,
    onOpenCheckChip: () -> Unit,
    onOpenCheckMemberChip: () -> Unit,
    onOpenJudgeScan: (String) -> Unit,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as Kolco24App).container }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        AdminSectionHeader("Вход")
        AdminCard {
            ServerStatusRow(
                label = "Cloud",
                session = cloudSession,
                loggedOutText = "нет входа",
                onLogin = { onLogin(AdminServer.Cloud) },
            )
            AdminRowDivider(startIndent = 16.dp)
            ServerStatusRow(
                label = "LAN",
                session = localSession,
                loggedOutText = if (lanActive) "нет входа" else "включите локальный режим гонки",
                onLogin = if (lanActive) ({ onLogin(AdminServer.Lan) }) else null,
            )
        }

        AdminSectionHeader("Чипы КП")
        AdminCard {
            AdminActionRow(
                icon = Icons.Filled.Nfc,
                title = "Привязать чип к КП",
                subtitle = "Запись кода на чип",
                onClick = onOpenProvisioning,
            )
            AdminRowDivider()
            AdminActionRow(
                icon = Icons.AutoMirrored.Filled.FactCheck,
                title = "Проверить чип КП",
                subtitle = "Узнать, к какому КП привязан чип",
                onClick = onOpenCheckChip,
            )
        }

        AdminSectionHeader("Браслеты участников")
        AdminCard {
            AdminActionRow(
                icon = Icons.Filled.Watch,
                title = "Записать браслет",
                subtitle = "Запись кода на браслет",
                onClick = onOpenMemberProvisioning,
            )
            AdminRowDivider()
            AdminActionRow(
                icon = Icons.Filled.PersonSearch,
                title = "Проверить браслет",
                subtitle = "Узнать, чей это браслет",
                onClick = onOpenCheckMemberChip,
            )
        }

        AdminSectionHeader("Судейские отметки")
        AdminCard {
            AdminActionRow(
                icon = Icons.Filled.Flag,
                title = "Отметка старта",
                subtitle = "Пикать браслеты участников на старте",
                onClick = { onOpenJudgeScan("start") },
                tileColor = Tertiary,
                tileContentColor = OnTertiary,
            )
            AdminRowDivider()
            AdminActionRow(
                icon = Icons.Filled.SportsScore,
                title = "Отметка финиша",
                subtitle = "Пикать браслеты участников на финише",
                onClick = { onOpenJudgeScan("finish") },
                tileColor = BrandRed,
                tileContentColor = OnBrandRed,
            )
        }

        Spacer(Modifier.height(24.dp))
        AdminCard {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        listOf(container.cloudAdminAuth, container.localAdminAuth)
                            .forEach { repo -> container.applicationScope.launch { repo.logout() } }
                    }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Выйти",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Muted group label above an [AdminCard] (iOS inset-grouped section header). */
@Composable
private fun AdminSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** One rounded card holding a group of rows. */
@Composable
private fun AdminCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column { content() }
    }
}

/** Hairline between rows of one card; the default indent aligns it with the row text, past the tile. */
@Composable
private fun AdminRowDivider(startIndent: Dp = 66.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startIndent),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * One server's session line: status dot (green = logged in), «Cloud · email», or «Cloud · нет входа»
 * with an optional «Войти».
 */
@Composable
private fun ServerStatusRow(
    label: String,
    session: AdminSession,
    loggedOutText: String,
    onLogin: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (session is AdminSession.LoggedIn) Tertiary else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = (session as? AdminSession.LoggedIn)?.email ?: loggedOutText,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (session is AdminSession.LoggedIn) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (session is AdminSession.LoggedOut && onLogin != null) {
            TextButton(onClick = onLogin) { Text("Войти") }
        }
    }
}

/**
 * Admin-home action row — icon tile, title + subtitle, chevron. The tile defaults to the neutral
 * charcoal (light) / subtle elevated grey (dark) of the Settings avatars — see
 * [neutralAvatarContainerColor]; judge rows tint it green/red like iOS.
 */
@Composable
private fun AdminActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tileColor: Color = neutralAvatarContainerColor(),
    tileContentColor: Color = neutralAvatarContentColor(),
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
                .size(36.dp)
                .background(tileColor, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tileContentColor,
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

// Copied from SettingsScreen (duplicate, don't couple) — resolve the *applied* theme so the neutral
// avatar stays charcoal-on-light / subtle-grey-on-dark instead of inverting to a bright circle.
@Composable
private fun neutralAvatarContainerColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.inverseSurface
    }

@Composable
private fun neutralAvatarContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.inverseOnSurface
    }
