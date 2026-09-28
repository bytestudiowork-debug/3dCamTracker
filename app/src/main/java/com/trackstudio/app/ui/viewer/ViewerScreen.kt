package com.trackstudio.app.ui.viewer

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trackstudio.app.ui.theme.AccentBlue
import com.trackstudio.app.ui.theme.BgCard
import com.trackstudio.app.ui.theme.BgDeep
import com.trackstudio.app.ui.theme.BgPanel
import com.trackstudio.app.ui.theme.BgViewport
import com.trackstudio.app.ui.theme.DividerCol
import com.trackstudio.app.ui.theme.TextMuted
import com.trackstudio.app.ui.theme.TextPrimary
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.viewmodel.ViewerViewModel

@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    frameUris: List<Uri>,
    fps: Double,
    projectName: String = "Untitled Project",
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var selectedZoom by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(frameUris, fps) {
        viewModel.loadSequence(uris = frameUris, fps = fps)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
    ) {
        EditorTopBar(projectName = projectName, onBack = onBack)

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            EditorLeftTools()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(BgViewport, RoundedCornerShape(6.dp))
                        .border(1.dp, DividerCol, RoundedCornerShape(6.dp))
                ) {
                    ViewerCanvas(
                        bitmap = state.currentBitmap,
                        isLoading = state.isLoadingFrame,
                        requestedZoom = selectedZoom,
                        onZoomChanged = { selectedZoom = it },
                        modifier = Modifier.fillMaxSize()
                    )

                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        ZoomControl("FIT", selectedZoom == 1f) { selectedZoom = 1f }
                        ZoomControl("100%", selectedZoom == 1f) { selectedZoom = 1f }
                        ZoomControl("200%", selectedZoom == 2f) { selectedZoom = 2f }
                        ZoomControl("400%", selectedZoom == 4f) { selectedZoom = 4f }
                    }
                }

                Spacer(Modifier.height(8.dp))

                EditorPlayback(
                    isPlaying = state.playback.isPlaying,
                    currentFrame = state.playback.currentFrame,
                    frameCount = state.playback.frameCount,
                    onFirstFrame = viewModel::firstFrame,
                    onPreviousFrame = viewModel::previousFrame,
                    onPlayPause = viewModel::playPause,
                    onNextFrame = viewModel::nextFrame,
                    onLastFrame = viewModel::lastFrame,
                    fps = state.playback.fps,
                    onSeek = viewModel::seekTo
                )
            }

            EditorRightTools()
        }
    }

    state.errorMessage?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(4.dp)
        )
    }
}

@Composable
private fun EditorTopBar(
    projectName: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(BgPanel)
            .border(1.dp, DividerCol),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(14.dp))
        Text("TRACKSTUDIO", color = AccentBlue)
        Spacer(Modifier.width(18.dp))
        Text("/", color = TextMuted)
        Spacer(Modifier.width(12.dp))
        Text(projectName.ifBlank { "Untitled Project" }, color = TextPrimary)

        Spacer(Modifier.weight(1f))

        IconButton(onClick = {}) {
            Icon(Icons.Default.Undo, "Undo", tint = TextSecondary)
        }
        IconButton(onClick = {}) {
            Icon(Icons.Default.Redo, "Redo", tint = TextSecondary)
        }

        TextButton(onClick = {}) {
            Icon(Icons.Default.Settings, "Settings", tint = TextSecondary)
            Spacer(Modifier.width(4.dp))
            Text("SETTINGS", color = TextSecondary)
        }

        TextButton(onClick = {}) {
            Text("EXPORT", color = TextSecondary)
        }

        IconButton(onClick = {}) {
            Icon(Icons.Default.MoreVert, "More options", tint = TextPrimary)
        }

        IconButton(onClick = onBack) {
            Icon(Icons.Default.Close, "Close project", tint = TextSecondary)
        }

        Spacer(Modifier.width(6.dp))
    }
}

@Composable
private fun EditorLeftTools() {
    val tools = listOf(
        "⌂" to "Home",
        "◎" to "Track",
        "≡" to "Adjust",
        "⬡" to "3D",
        "▣" to "Camera",
        "⁘" to "Points",
        "✎" to "Mask",
        "∿" to "Stabilize",
        "▤" to "2D / 3D",
        "▦" to "Grid",
        "⌖" to "Axes",
        "?" to "Help"
    )

    Column(
        modifier = Modifier
            .width(70.dp)
            .fillMaxHeight()
            .background(BgPanel)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        tools.forEachIndexed { index, (icon, label) ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(38.dp)
                        .background(
                            if (index == 1) AccentBlue else Color.Transparent,
                            RoundedCornerShape(6.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(icon, color = if (index == 1) Color.White else TextSecondary)
                }
                Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun EditorRightTools() {
    Column(
        modifier = Modifier
            .width(250.dp)
            .fillMaxHeight()
            .background(BgPanel)
            .padding(10.dp)
    ) {
        Text("TOOLS", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        PlaceholderToolSection("Tracking")
        PlaceholderToolSection("3D Reconstruction")
        PlaceholderToolSection("Track Points")
        PlaceholderToolSection("Masks")
        PlaceholderToolSection("Camera")
        PlaceholderToolSection("Display")
        Spacer(Modifier.weight(1f))
        Text(
            "Tool controls will be added here.",
            color = TextMuted,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun PlaceholderToolSection(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(BgCard, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = TextSecondary, modifier = Modifier.weight(1f))
        Text("›", color = TextMuted)
    }
}

@Composable
private fun EditorPlayback(
    isPlaying: Boolean,
    currentFrame: Int,
    frameCount: Int,
    onFirstFrame: () -> Unit,
    onPreviousFrame: () -> Unit,
    onPlayPause: () -> Unit,
    onNextFrame: () -> Unit,
    onLastFrame: () -> Unit,
    fps: Double,
    onSeek: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgPanel, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        ViewerControls(
            isPlaying = isPlaying,
            currentFrame = currentFrame,
            frameCount = frameCount,
            onFirstFrame = onFirstFrame,
            onPreviousFrame = onPreviousFrame,
            onPlayPause = onPlayPause,
            onNextFrame = onNextFrame,
            onLastFrame = onLastFrame,
            modifier = Modifier.fillMaxWidth()
        )
        ViewerTimeline(
            currentFrame = currentFrame,
            frameCount = frameCount,
            fps = fps,
            onSeek = onSeek,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ZoomControl(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(text, color = if (selected) AccentBlue else TextSecondary)
    }
}
