package com.trackstudio.app.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trackstudio.app.ui.common.DesktopButton

@Composable
fun ViewerControls(
    isPlaying: Boolean,
    currentFrame: Int,
    frameCount: Int,
    onFirstFrame: () -> Unit,
    onPreviousFrame: () -> Unit,
    onPlayPause: () -> Unit,
    onNextFrame: () -> Unit,
    onLastFrame: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(
            horizontal = 8.dp
        ),
        horizontalArrangement =
            Arrangement.spacedBy(8.dp),
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        DesktopButton(
            text = "|<",
            onClick = onFirstFrame,
            modifier = Modifier.width(58.dp),
            enabled = frameCount > 0
        )

        DesktopButton(
            text = "<",
            onClick = onPreviousFrame,
            modifier = Modifier.width(58.dp),
            enabled = currentFrame > 0
        )

        DesktopButton(
            text = if (isPlaying) {
                "Pause"
            } else {
                "Play"
            },
            onClick = onPlayPause,
            modifier = Modifier.width(90.dp),
            enabled = frameCount > 0,
            emphasized = true
        )

        DesktopButton(
            text = ">",
            onClick = onNextFrame,
            modifier = Modifier.width(58.dp),
            enabled =
                currentFrame <
                    frameCount - 1
        )

        DesktopButton(
            text = ">|",
            onClick = onLastFrame,
            modifier = Modifier.width(58.dp),
            enabled = frameCount > 0
        )

        Text(
            text = if (frameCount > 0) {
                "Frame ${currentFrame + 1} / $frameCount"
            } else {
                "No frames"
            },
            style =
                MaterialTheme.typography.bodyMedium,
            modifier =
                Modifier.padding(start = 12.dp)
        )
    }
}