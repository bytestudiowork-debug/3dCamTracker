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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import com.trackstudio.app.playback.PlaybackState
import com.trackstudio.app.ui.theme.AccentError
import com.trackstudio.app.ui.theme.AccentPrimary
import com.trackstudio.app.ui.theme.AccentSuccess
import com.trackstudio.app.ui.theme.PanelBackground
import com.trackstudio.app.ui.theme.PanelBorder
import com.trackstudio.app.ui.theme.TextPrimary
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.ui.theme.ViewerBackground
import com.trackstudio.app.util.formatTimecode
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

    LaunchedEffect(frameUris, fps) {
        viewModel.loadSequence(frameUris, fps)
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
    var sidebarSelection by remember { mutableStateOf("Home") }
    var fullscreen by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }

    BackHandler {
        when {
            panelOpen -> panelOpen = false
            fullscreen -> fullscreen = false
            else -> onBack()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(PanelBackground)
    ) {
        val windowWidth = maxWidth
        val windowHeight = maxHeight
        val compactHeight = windowHeight < 460.dp
        val sidebarMode =
            if (windowWidth >= 1000.dp) SidebarMode.FULL else SidebarMode.RAIL
        val inlinePanel = windowWidth >= 840.dp
        val panelWidth = if (windowWidth >= 1100.dp) 320.dp else 280.dp
        val gap = if (compactHeight) 8.dp else 12.dp

        Column(Modifier.fillMaxSize()) {
            if (!fullscreen) {
                WorkspaceTopBar(
                    projectName = projectName,
                    compact = compactHeight,
                    showTitle = windowWidth >= 720.dp,
                    showPanelToggle = !inlinePanel,
                    onTogglePanel = { panelOpen = !panelOpen },
                    onCloseProject = onBack
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (!fullscreen) {
                    WorkspaceSidebar(
                        mode = sidebarMode,
                        selected = sidebarSelection,
                        onSelect = { sidebarSelection = it }
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(if (fullscreen) 0.dp else gap),
                    verticalArrangement =
                        if (fullscreen) Arrangement.Top
                        else Arrangement.spacedBy(gap)
                ) {
                    ViewerPanel(
                        bitmap = state.currentBitmap,
                        isLoading = state.isLoadingFrame,
                        errorMessage = state.errorMessage,
                        playback = state.playback,
                        zoom = zoom,
                        onZoomChanged = { zoom = it },
                        compact = compactHeight,
                        fullscreen = fullscreen,
                        onToggleFullscreen = { fullscreen = !fullscreen },
                        onPlayPause = viewModel::playPause,
                        onSeek = viewModel::seekTo,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )

                    if (!fullscreen) {
                        PlaybackPanel(
                            state = state,
                            compact = compactHeight,
                            isFullscreen = false,
                            onToggleFullscreen = { fullscreen = true },
                            onPlayPause = viewModel::playPause,
                            onFirstFrame = viewModel::firstFrame,
                            onLastFrame = viewModel::lastFrame,
                            onSeek = viewModel::seekTo,
                            onSpeedChanged = viewModel::setPlaybackSpeed,
                            onThumbnailsNeeded = viewModel::ensureThumbnails
                        )
                    }
                }

                if (inlinePanel && !fullscreen) {
                    WorkspaceRightPanel(
                        modifier = Modifier
                            .width(panelWidth)
                            .fillMaxHeight()
                            .padding(
                                top = gap,
                                end = gap,
                                bottom = gap
                            )
                    )
                }
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
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(280.dp)
                    .fillMaxHeight()
                    .background(PanelBackground)
                    .padding(10.dp)
            )
        }
    }
}

@Composable
private fun ViewerPanel(
    bitmap: Bitmap?,
    isLoading: Boolean,
    errorMessage: String?,
    playback: PlaybackState,
    zoom: Float,
    onZoomChanged: (Float) -> Unit,
    compact: Boolean,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Int) -> Unit,
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
            isLoading = isLoading,
            requestedZoom = zoom,
            onZoomChanged = onZoomChanged,
            backgroundColor = ViewerBackground,
            showZoomLabel = false,
            modifier = Modifier.fillMaxSize()
        )

        OverlayChip(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(if (compact) 8.dp else 10.dp),
            compact = compact
        ) {
            Icon(
                imageVector = Icons.Outlined.ViewInAr,
                contentDescription = null,
                tint = AccentPrimary,
                modifier = Modifier.size(if (compact) 16.dp else 18.dp)
            )
            Text(
                text = "3D View",
                color = TextPrimary,
                fontSize = if (compact) 12.sp else 13.sp,
                modifier = Modifier.padding(start = 6.dp)
            )
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
                    modifier = Modifier.size(if (compact) 16.dp else 18.dp)
                )
                Text(
                    text = "Fullscreen",
                    color = TextPrimary,
                    fontSize = if (compact) 12.sp else 13.sp,
                    modifier = Modifier.padding(start = 6.dp)
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
                    modifier = Modifier.size(if (compact) 18.dp else 20.dp)
                )
                Text(
                    text = "Exit",
                    color = TextPrimary,
                    fontSize = if (compact) 12.sp else 13.sp,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }

        AxisLegend(
            compact = compact,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(if (compact) 8.dp else 10.dp)
        )

        if (fullscreen && playback.frameCount > 0) {
            FullscreenPlayback(
                playback = playback,
                compact = compact,
                onPlayPause = onPlayPause,
                onSeek = onSeek,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (compact) 8.dp else 18.dp,
                        vertical = if (compact) 8.dp else 14.dp
                    )
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
private fun FullscreenPlayback(
    playback: PlaybackState,
    compact: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val maxFrame = (playback.frameCount - 1).coerceAtLeast(1)
    val progress = playback.currentFrame.toFloat() / maxFrame.toFloat()

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE60B1220))
            .border(
                BorderStroke(1.dp, PanelBorder),
                RoundedCornerShape(10.dp)
            )
            .padding(
                horizontal = if (compact) 8.dp else 12.dp,
                vertical = if (compact) 4.dp else 6.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChromeIconButton(
            icon = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (playback.isPlaying) "Pause" else "Play",
            onClick = onPlayPause,
            enabled = playback.frameCount > 0,
            tint = TextPrimary,
            buttonSize = if (compact) 34.dp else 40.dp,
            iconSize = if (compact) 20.dp else 24.dp
        )

        Text(
            text = formatTimecode(playback.currentFrame, playback.fps),
            color = TextPrimary,
            fontSize = if (compact) 9.sp else 10.sp,
            modifier = Modifier.padding(horizontal = 6.dp)
        )

        Slider(
            value = progress,
            onValueChange = { value ->
                onSeek((value * maxFrame).toInt().coerceIn(0, maxFrame))
            },
            modifier = Modifier.weight(1f),
            valueRange = 0f..1f
        )

        Text(
            text = formatTimecode(maxFrame, playback.fps),
            color = TextSecondary,
            fontSize = if (compact) 9.sp else 10.sp,
            modifier = Modifier.padding(start = 6.dp)
        )
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
