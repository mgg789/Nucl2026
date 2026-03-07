package com.peerdone.app.ui.theme

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
    primary = PeerDonePrimary,
    onPrimary = PeerDoneWhite,
    primaryContainer = PeerDonePrimaryContainer,
    onPrimaryContainer = PeerDoneWhite,
    secondary = PeerDonePrimaryVariant,
    onSecondary = PeerDoneWhite,
    tertiary = PeerDoneOnline,
    onTertiary = PeerDoneWhite,
    background = PeerDoneBackground,
    onBackground = PeerDoneWhite,
    surface = PeerDoneSurface,
    onSurface = PeerDoneWhite,
    surfaceVariant = PeerDoneSurfaceLow,
    onSurfaceVariant = PeerDoneGray,
    surfaceContainer = PeerDoneSurface,
    surfaceContainerLow = PeerDoneSurfaceLow,
    surfaceContainerHigh = PeerDoneSurfaceLow,
    outline = PeerDoneGray,
    outlineVariant = PeerDoneDarkGray,
    error = PeerDoneError,
    onError = PeerDoneWhite,
)

private val LightColorScheme = lightColorScheme(
    primary = PeerDonePrimary,
    onPrimary = PeerDoneWhite,
    primaryContainer = PeerDoneSentBubble,
    onPrimaryContainer = PeerDoneTextDark,
    secondary = PeerDonePrimaryVariant,
    onSecondary = PeerDoneWhite,
    tertiary = PeerDoneOnline,
    onTertiary = PeerDoneWhite,
    background = PeerDoneLightGray,
    onBackground = PeerDoneTextDark,
    surface = PeerDoneWhite,
    onSurface = PeerDoneTextDark,
    surfaceVariant = PeerDoneReceivedBubble,
    onSurfaceVariant = PeerDoneTextMuted,
    surfaceContainer = PeerDoneWhite,
    surfaceContainerLow = PeerDoneLightGray,
    surfaceContainerHigh = PeerDoneChatHeader,
    outline = PeerDoneGray,
    outlineVariant = PeerDoneChipInactive,
    error = PeerDoneError,
    onError = PeerDoneWhite,
)

@Composable
fun PeerDoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
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
        typography = PeerDoneTypography,
        content = content
    )
}
