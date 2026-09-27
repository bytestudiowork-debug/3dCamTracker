package com.trackstudio.app.ui.viewer

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trackstudio.app.ui.common.DesktopButton
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.viewmodel.ViewerViewModel

@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    frameUris: List<Uri>,
    fps: Double,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    var selectedZoom by remember {
        mutableFloatStateOf(1f)
    }

    LaunchedEffect(
        frameUris,
        fps
    ) {
        viewModel.loadSequence(
            uris = frameUris,
            fps = fps
        )
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
    ) {

        /*
         * Viewer toolbar.
         */
        Row(
            modifier =
                Modifier.fillMaxWidth(),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            DesktopButton(
                text = "← Back",
                onClick = onBack
            )

            Spacer(
                modifier =
                    Modifier.width(16.dp)
            )

            Text(
                text = "Viewer",
                color = TextSecondary
            )

            Spacer(
                modifier =
                    Modifier.weight(1f)
            )

            ZoomControls(
                selectedZoom =
                    selectedZoom,

                onZoomSelected = {
                    selectedZoom = it
                }
            )
        }

        Spacer(
            modifier =
                Modifier.height(12.dp)
        )

        /*
         * Image viewer.
         */
        ViewerCanvas(
            bitmap =
                state.currentBitmap,

            isLoading =
                state.isLoadingFrame,

            requestedZoom =
                selectedZoom,

            onZoomChanged = {
                selectedZoom = it
            },

            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
        )

        Spacer(
            modifier =
                Modifier.height(12.dp)
        )

        /*
         * Playback buttons.
         */
        ViewerControls(
            isPlaying =
                state.playback.isPlaying,

            currentFrame =
                state.playback.currentFrame,

            frameCount =
                state.playback.frameCount,

            onFirstFrame =
                viewModel::firstFrame,

            onPreviousFrame =
                viewModel::previousFrame,

            onPlayPause =
                viewModel::playPause,

            onNextFrame =
                viewModel::nextFrame,

            onLastFrame =
                viewModel::lastFrame,

            modifier =
                Modifier.fillMaxWidth()
        )

        Spacer(
            modifier =
                Modifier.height(4.dp)
        )

        /*
         * Timeline.
         */
        ViewerTimeline(
            currentFrame =
                state.playback.currentFrame,

            frameCount =
                state.playback.frameCount,

            fps =
                state.playback.fps,

            onSeek =
                viewModel::seekTo,

            modifier =
                Modifier.fillMaxWidth()
        )

        state.errorMessage?.let { error ->

            Text(
                text = error,

                color =
                    androidx.compose.material3
                        .MaterialTheme
                        .colorScheme
                        .error,

                modifier =
                    Modifier.padding(
                        horizontal = 8.dp,
                        vertical = 4.dp
                    )
            )
        }
    }
}

@Composable
private fun ZoomControls(
    selectedZoom: Float,
    onZoomSelected: (Float) -> Unit
) {
    Row(
        horizontalArrangement =
            Arrangement.spacedBy(6.dp),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        ZoomButton(
            text = "Fit",
            selected =
                selectedZoom == 1f,
            onClick = {
                onZoomSelected(1f)
            }
        )

        ZoomButton(
            text = "100%",
            selected =
                selectedZoom == 1f,
            onClick = {
                onZoomSelected(1f)
            }
        )

        ZoomButton(
            text = "200%",
            selected =
                selectedZoom == 2f,
            onClick = {
                onZoomSelected(2f)
            }
        )

        ZoomButton(
            text = "400%",
            selected =
                selectedZoom == 4f,
            onClick = {
                onZoomSelected(4f)
            }
        )
    }
}

@Composable
private fun ZoomButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    DesktopButton(
        text = text,
        onClick = onClick,
        emphasized = selected,
        modifier =
            Modifier.width(64.dp)
    )
}