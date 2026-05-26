package ru.kolco24.kolco24.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
