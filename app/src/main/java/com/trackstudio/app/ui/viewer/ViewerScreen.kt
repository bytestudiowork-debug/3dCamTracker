package com.trackstudio.app.ui.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trackstudio.app.ui.theme.AccentError
import com.trackstudio.app.ui.theme.AccentPrimary
import com.trackstudio.app.ui.theme.AccentSuccess
import com.trackstudio.app.ui.theme.PanelBackground
import com.trackstudio.app.ui.theme.PanelBorder
import com.trackstudio.app.ui.theme.TextPrimary
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.ui.theme.ViewerBackground
import com.trackstudio.app.tracking.TrackingVerificationSegment
import com.trackstudio.app.viewmodel.ViewerViewModel

@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    frameUris: List<Uri>,
    fps: Double,
    projectName: String,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    var timelinePoster by remember(projectName, frameUris) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(frameUris, fps) {
        viewModel.loadSequence(frameUris, fps)
    }

    LaunchedEffect(state.currentBitmap) {
        if (timelinePoster == null && state.currentBitmap != null) {
            timelinePoster = state.currentBitmap
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var zoom by remember { mutableFloatStateOf(1f) }
    var fullscreen by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(true) }
    var showVerificationOverlay by remember { mutableStateOf(true) }
    var showTrackPointsOverlay by remember { mutableStateOf(false) }
    var activeToolPopup by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(PanelBackground)
    ) {
        val windowWidth = maxWidth
        val windowHeight = maxHeight
        val compactHeight = windowHeight < 460.dp
        val inlinePanel = windowWidth >= 640.dp
        // Keep the inspector useful without taking space away from the footage.
        val panelWidth = (windowWidth * 0.23f).coerceIn(196.dp, 320.dp)
        val playbackHeight = (windowHeight * 0.14f).coerceIn(76.dp, 128.dp)
        val drawerWidth = minOf(320.dp, windowWidth * 0.88f)
        val gap = if (compactHeight) 8.dp else 12.dp

        BackHandler {
            when {
                panelOpen && !inlinePanel -> panelOpen = false
                fullscreen -> fullscreen = false
                else -> onBack()
            }
        }

        Column(Modifier.fillMaxSize()) {
            if (!fullscreen) {
                WorkspaceTopBar(
                    projectName = projectName,
                    compact = compactHeight,
                    showTitle = windowWidth >= 600.dp,
                    showPanelToggle = true,
                    onTogglePanel = { panelOpen = !panelOpen },
                    onCloseProject = onBack
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = if (fullscreen) 0.dp else gap,
                            vertical = if (fullscreen) 0.dp else gap
                        ),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    ViewerPanel(
                        bitmap = state.currentBitmap,
                        trackingPoints = state.trackingPointsByFrame[state.playback.currentFrame].orEmpty(),
                        verificationSegments = state.trackingVerificationByFrame[state.playback.currentFrame].orEmpty(),
                        showVerificationOverlay = showVerificationOverlay,
                        showTrackPointsOverlay = showTrackPointsOverlay,
                        isLoading = state.isLoadingFrame,
                        errorMessage = state.errorMessage,
                        zoom = zoom,
                        onZoomChanged = { zoom = it },
                        compact = compactHeight,
                        fullscreen = fullscreen,
                        onToggleFullscreen = { fullscreen = !fullscreen },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )

                    if (inlinePanel && panelOpen && !fullscreen) {
                        WorkspaceRightPanel(
                            projectName = projectName,
                            fps = state.playback.fps,
                            frameCount = state.playback.frameCount,
                            isAutoTracking = state.isAutoTracking,
                            trackingProgress = state.trackingProgress,
                            trackingError = state.trackingError,
                            trackingSummary = state.trackingSummary,
                            onAutoTrack = viewModel::startAutoTrack,
                            onCancelAutoTrack = viewModel::cancelAutoTrack,
                            onOpen3DTest = { activeToolPopup = "3D Test" },
                            onOpenTrackerTools = { activeToolPopup = "Tracker Tools" },
                            modifier = Modifier
                                .width(panelWidth)
                                .fillMaxHeight()
                        )
                    }
                }

                if (panelOpen && !inlinePanel && !fullscreen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x99000000))
                            .pointerInput(Unit) {
                                detectTapGestures { panelOpen = false }
                            }
                    )

                    WorkspaceRightPanel(
                        projectName = projectName,
                        fps = state.playback.fps,
                        frameCount = state.playback.frameCount,
                        isAutoTracking = state.isAutoTracking,
                        trackingProgress = state.trackingProgress,
                        trackingError = state.trackingError,
                        trackingSummary = state.trackingSummary,
                        onAutoTrack = viewModel::startAutoTrack,
                        onCancelAutoTrack = viewModel::cancelAutoTrack,
                        onOpen3DTest = { activeToolPopup = "3D Test" },
                        onOpenTrackerTools = { activeToolPopup = "Tracker Tools" },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(drawerWidth)
                            .fillMaxHeight()
                            .background(PanelBackground)
                            .padding(8.dp)
                    )
                }
            }

            PlaybackPanel(
                state = state,
                poster = timelinePoster ?: state.currentBitmap,
                compact = compactHeight,
                isFullscreen = fullscreen,
                panelHeight = playbackHeight,
                onToggleFullscreen = { fullscreen = !fullscreen },
                onPlayPause = viewModel::playPause,
                onFirstFrame = viewModel::firstFrame,
                onLastFrame = viewModel::lastFrame,
                onSeek = viewModel::seekTo,
                onSpeedChanged = viewModel::setPlaybackSpeed,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(if (fullscreen) 0.dp else gap)
            )
        }

        if (activeToolPopup != null) {
            TrackingToolPopup(
                title = activeToolPopup!!,
                currentFrame = state.playback.currentFrame + 1,
                frameCount = state.playback.frameCount,
                trackPointCount = state.trackingPointsByFrame[state.playback.currentFrame].orEmpty().size,
                verificationLineCount = state.trackingVerificationByFrame[state.playback.currentFrame].orEmpty().size,
                showCube = showVerificationOverlay,
                showTracks = showTrackPointsOverlay,
                onCubeChanged = { showVerificationOverlay = it },
                onTracksChanged = { showTrackPointsOverlay = it },
                onDismiss = { activeToolPopup = null }
            )
        }
    }
}

@Composable
private fun ViewerPanel(
    bitmap: Bitmap?,
    trackingPoints: List<com.trackstudio.app.tracking.CameraFeatureObservation>,
    verificationSegments: List<TrackingVerificationSegment>,
    showVerificationOverlay: Boolean,
    showTrackPointsOverlay: Boolean,
    onToggleVerificationOverlay: () -> Unit,
    onToggleTrackPointsOverlay: () -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    zoom: Float,
    onZoomChanged: (Float) -> Unit,
    compact: Boolean,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape =
        if (fullscreen) RoundedCornerShape(0.dp)
        else RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .border(
                BorderStroke(
                    if (fullscreen) 0.dp else 1.dp,
                    PanelBorder
                ),
                shape
            )
    ) {
        ViewerCanvas(
            bitmap = bitmap,
            trackingPoints = if (showTrackPointsOverlay) trackingPoints else emptyList(),
            trackingVerificationSegments = if (showVerificationOverlay) verificationSegments else emptyList(),
            isLoading = isLoading,
            requestedZoom = zoom,
            onZoomChanged = onZoomChanged,
            backgroundColor = ViewerBackground,
            showZoomLabel = false,
            modifier = Modifier.fillMaxSize()
        )

        if (verificationSegments.isNotEmpty() || trackingPoints.isNotEmpty()) {
            OverlayChip(
                modifier = Modifier.align(Alignment.TopStart).padding(if (compact) 8.dp else 10.dp),
                compact = compact
            ) {
                Text(
                    text = "3D TEST",
                    color = if (showVerificationOverlay) AccentPrimary else TextSecondary,
                    fontSize = if (compact) 10.sp else 11.sp,
                    modifier = Modifier.clickable(onClick = onToggleVerificationOverlay)
                )
                Text(
                    text = "TRACKS",
                    color = if (showTrackPointsOverlay) Color(0xFF00E5FF) else TextSecondary,
                    fontSize = if (compact) 10.sp else 11.sp,
                    modifier = Modifier.clickable(onClick = onToggleTrackPointsOverlay)
                )
            }
        }

        if (!fullscreen) {
            OverlayChip(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(if (compact) 8.dp else 10.dp),
                compact = compact,
                onClick = onToggleFullscreen
            ) {
                Icon(
                    imageVector = Icons.Outlined.Fullscreen,
                    contentDescription = "Fullscreen",
                    tint = TextPrimary,
                    modifier = Modifier.size(if (compact) 18.dp else 22.dp)
                )
            }
        } else {
            OverlayChip(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(if (compact) 8.dp else 12.dp),
                compact = compact,
                onClick = onToggleFullscreen
            ) {
                Icon(
                    imageVector = Icons.Outlined.FullscreenExit,
                    contentDescription = "Exit fullscreen",
                    tint = TextPrimary,
                    modifier = Modifier.size(if (compact) 18.dp else 22.dp)
                )
            }
        }

        if (showTrackPointsOverlay && trackingPoints.isNotEmpty()) {
            Text(
                text = "TRACK POINTS · ${trackingPoints.size}",
                color = Color(0xFF00E5FF),
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC0B1220))
                    .padding(horizontal = 9.dp, vertical = 6.dp)
            )
        }

        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC0B1220))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun AxisLegend(
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    OverlayChip(
        modifier = modifier,
        compact = compact
    ) {
        AxisLabel("+X", AccentError, compact)
        AxisLabel("+Y", AccentSuccess, compact)
        AxisLabel("+Z", AccentPrimary, compact)
    }
}

@Composable
private fun OverlayChip(
    modifier: Modifier = Modifier,
    compact: Boolean,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = modifier
            .clip(shape)
            .background(Color(0xCC0B1220))
            .border(BorderStroke(1.dp, PanelBorder), shape)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier
            )
            .padding(
                horizontal = if (compact) 8.dp else 12.dp,
                vertical = if (compact) 5.dp else 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun AxisLabel(
    text: String,
    color: Color,
    compact: Boolean
) {
    Text(
        text = text,
        color = color,
        fontSize = if (compact) 11.sp else 12.sp
    )
}
