package com.trackstudio.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TrackStudioColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = TextPrimary,
    primaryContainer = AccentBlueDim,
    onPrimaryContainer = TextPrimary,
    background = BgDeep,
    onBackground = TextPrimary,
    surface = BgPanel,
    onSurface = TextPrimary,
    surfaceVariant = BgCard,
    onSurfaceVariant = TextSecondary,
    outline = DividerCol,
    error = StatusRed,
    onError = TextPrimary
)

@Composable
fun TrackStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TrackStudioColorScheme,
        typography = AppTypography,
        content = content
    )
}
