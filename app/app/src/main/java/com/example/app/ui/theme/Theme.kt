package com.example.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = TgPrimary,
    onPrimary = TgOnBackground,
    primaryContainer = TgPrimaryContainer,
    secondary = TgSecondary,
    tertiary = TgSecondary,
    background = TgBackground,
    onBackground = TgOnBackground,
    surface = TgSurface,
    onSurface = TgOnSurface,
    surfaceContainer = TgSurface,
    surfaceContainerLow = TgSurfaceLow,
    surfaceContainerHigh = TgSurfaceLow,
    outline = TgOutline,
    error = TgDanger,
)

private val LightColorScheme = lightColorScheme(
    primary = TgLightPrimary,
    onPrimary = TgLightSurface,
    secondary = TgLightMuted,
    tertiary = TgLightMuted,
    background = TgLightBackground,
    onBackground = TgLightOnSurface,
    surface = TgLightSurface,
    onSurface = TgLightOnSurface,
    surfaceContainer = TgLightSurface,
    surfaceContainerLow = TgLightBackground,
    surfaceContainerHigh = TgLightBackground,
    outline = TgOutline,
    error = TgDanger,
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}