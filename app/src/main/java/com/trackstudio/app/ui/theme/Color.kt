package com.trackstudio.app.ui.theme

import androidx.compose.ui.graphics.Color

// Unified TrackStudio dark editor palette.
val BgDeep = Color(0xFF0A0E14)
val BgPanel = Color(0xFF11161F)
val BgCard = Color(0xFF1A212C)
val BgViewport = Color(0xFF0F141B)

val AccentBlue = Color(0xFF2F7BFF)
val AccentBlueDim = Color(0xFF1E4FA8)
val HandleColor = Color(0xFF3E8BFF)

val TextPrimary = Color(0xFFE6EAF2)
val TextSecondary = Color(0xFF8A94A6)
val TextMuted = Color(0xFF5C6675)

val StatusGreen = Color(0xFF2ECC71)
val StatusRed = Color(0xFFE74C3C)
val StatusBlue = Color(0xFF3498DB)

val DividerCol = Color(0xFF232B38)

// Compatibility aliases for existing UI code.
val PanelBackground = BgDeep
val PanelSurface = BgPanel
val PanelSurfaceRaised = BgCard
val PanelBorder = DividerCol
val PanelBorderFocused = AccentBlue
val TextDisabled = TextMuted
val AccentPrimary = AccentBlue
val AccentPrimaryDim = AccentBlueDim
val AccentWarning = Color(0xFFE0A93E)
val AccentError = StatusRed
