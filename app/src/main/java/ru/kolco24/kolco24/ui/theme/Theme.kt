package ru.kolco24.kolco24.ui.theme

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView

// Nav-bar scrim colours used on API 26-28 where transparency is not supported.
// These match the defaults that enableEdgeToEdge() uses when called without arguments.
private val LightNavScrim = android.graphics.Color.argb(0xe6, 0xff, 0xff, 0xff)
private val DarkNavScrim  = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

private val LightColorScheme = lightColorScheme(
    primary              = BrandRed,
    onPrimary            = OnBrandRed,
    primaryContainer     = BrandRedContainer,
    onPrimaryContainer   = OnBrandRedContainer,
    secondary            = Secondary,
    onSecondary          = OnSecondary,
    secondaryContainer   = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary             = Tertiary,
    onTertiary           = OnTertiary,
    tertiaryContainer    = TertiaryContainer,
    onTertiaryContainer  = OnTertiaryContainer,
    background               = SurfaceLight,
    surfaceTint              = Color.Transparent,
    surface                  = SurfaceLight,
    onSurface                = OnSurfaceLight,
    onSurfaceVariant         = OnSurfaceVariantLight,
    surfaceContainerLowest   = SurfaceContainerLowest,
    surfaceContainerLow      = SurfaceContainerLow,
    surfaceContainer         = SurfaceContainerDefault,
    surfaceContainerHigh     = SurfaceContainerHigh,
    surfaceContainerHighest  = SurfaceContainerHighest,
    outline                  = OutlineLight,
    outlineVariant           = OutlineVariantLight,
    inverseSurface           = InverseSurface,
    inverseOnSurface         = InverseOnSurface,
    inversePrimary           = InversePrimary,
    error                    = ErrorLight,
    onError                  = OnErrorLight,
    errorContainer           = ErrorContainer,
    onErrorContainer         = OnErrorContainer,
)

private val DarkColorScheme = darkColorScheme(
    primary              = BrandRedDark,
    onPrimary            = OnBrandRedDark,
    primaryContainer     = BrandRedContainerDark,
    onPrimaryContainer   = OnBrandRedContainerDark,
    tertiary             = TertiaryDark,
    onTertiary           = OnTertiaryDark,
    tertiaryContainer    = TertiaryContainerDark,
    onTertiaryContainer  = OnTertiaryContainerDark,
    surface              = SurfaceDark,
    onSurface            = OnSurfaceDark,
    onSurfaceVariant     = OnSurfaceVariantDark,
    inverseSurface       = InverseSurfaceDark,
    inverseOnSurface     = InverseOnSurfaceDark,
)

@Composable
fun Kolco24Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Re-apply the full SystemBarStyle on every theme change so that on API 26-28
            // the nav-bar scrim colour stays in sync with the resolved app theme rather than
            // remaining OS-mode-based from the enableEdgeToEdge() call in onCreate.
            // auto() preserves gesture-navigation transparency on API 29+ while still
            // supplying the correct scrim on API 26-28.
            (view.context as? ComponentActivity)?.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                    detectDarkMode = { darkTheme },
                ),
                navigationBarStyle = SystemBarStyle.auto(
                    LightNavScrim,
                    DarkNavScrim,
                    detectDarkMode = { darkTheme },
                ),
            )
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
