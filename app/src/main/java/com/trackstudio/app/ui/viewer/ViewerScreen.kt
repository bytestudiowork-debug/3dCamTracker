package com.trackstudio.app.ui.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CropFree
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
import com.trackstudio.app.viewmodel.ViewerViewModel

/**
 * The tracking workspace: top bar, tool sidebar, large viewer with
 * playback below it, and a tool panel on the right.
 *
 * The layout adapts to the window size (landscape only):
 *  - wide tablets:   labelled sidebar + inline right panel + filmstrip
 *  - mid-size:       icon rail + inline right panel
 *  - phones:         icon rail, right panel slides over the viewer from
 *                    a top-bar button, single-row playback bar
 * so the viewer always gets as much of the screen as possible.
 */
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
        viewModel.loadSequence(
            uris = frameUris,
            fps = fps
        )
    }

    // Stop playback when the app leaves the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.pause()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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

        Column(modifier = Modifier.fillMaxSize()) {

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
                        .padding(gap),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {

                    ViewerPanel(
                        bitmap = state.currentBitmap,
                        isLoading = state.isLoadingFrame,
                        errorMessage = state.errorMessage,
                        zoom = zoom,
                        onZoomChanged = { zoom = it },
                        onFit = { zoom = 1f },
                        compact = compactHeight,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )

                    PlaybackPanel(
                        state = state,
                        compact = compactHeight,
                        isFullscreen = fullscreen,
                        onToggleFullscreen = { fullscreen = !fullscreen },
                        onPlayPause = viewModel::playPause,
                        onPreviousFrame = viewModel::previousFrame,
                        onNextFrame = viewModel::nextFrame,
                        onSeek = viewModel::seekTo,
                        onThumbnailsNeeded = viewModel::ensureThumbnails
                    )
                }

                if (inlinePanel && !fullscreen) {
                    WorkspaceRightPanel(
                        modifier = Modifier
                            .width(panelWidth)
                            .fillMaxHeight()
                            .padding(top = gap, end = gap, bottom = gap)
                    )
                }
            }
        }

        // Narrow screens: the tool panel slides over the viewer.
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

// ---------------------------------------------------------------------------
// Viewer with overlays
// ---------------------------------------------------------------------------

@Composable
private fun ViewerPanel(
    bitmap: android.graphics.Bitmap?,
    isLoading: Boolean,
    errorMessage: String?,
    zoom: Float,
    onZoomChanged: (Float) -> Unit,
    onFit: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    var activeTool by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .clip(shape)
            .border(BorderStroke(1.dp, PanelBorder), shape)
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

        // "3D View" badge (placeholder for the future 2D/3D switch).
        OverlayChip(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
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

        // Fit-to-window (resets zoom and pan).
        OverlayChip(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
            compact = compact,
            onClick = onFit
        ) {
            Icon(
                imageVector = Icons.Outlined.CropFree,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(if (compact) 16.dp else 18.dp)
            )

            Text(
                text = "Fit",
                color = TextPrimary,
                fontSize = if (compact) 12.sp else 13.sp,
                modifier = Modifier.padding(start = 6.dp)
            )
        }

        // Vertical tool strip (placeholders, except the last one).
        ToolStrip(
            activeTool = activeTool,
            onToolSelected = { activeTool = it },
            isFullscreen = isFullscreen,
            onToggleFullscreen = onToggleFullscreen,
            compact = compact,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp)
        )

        // Axis legend.
        OverlayChip(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
            compact = compact
        ) {
            AxisLabel("+X", AccentError, compact)
            AxisLabel("+Y", AccentSuccess, compact)
            AxisLabel("+Z", AccentPrimary, compact)
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
