package com.trackstudio.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Dark-only, deliberately. This is a professional editing tool, not a
// consumer app that should follow the system light/dark toggle - Resolve,
// Nuke, Premiere all default to (and mostly only ship) a dark UI for the
// same reason: color/luminance judgments on footage are easiest against a
// neutral dark surround.
private val TrackStudioColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = PanelBackground,
    background = PanelBackground,
    onBackground = TextPrimary,
    surface = PanelSurface,
    onSurface = TextPrimary,
    surfaceVariant = PanelSurfaceRaised,
    onSurfaceVariant = TextSecondary,
    outline = PanelBorder,
    error = AccentError
)

@Composable
fun TrackStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TrackStudioColorScheme,
        typography = AppTypography,
        content = content
    )
}
