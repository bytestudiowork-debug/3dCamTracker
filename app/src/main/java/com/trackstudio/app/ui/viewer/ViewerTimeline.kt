package com.trackstudio.app.ui.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trackstudio.app.ui.theme.TextSecondary

@Composable
fun ViewerTimeline(
    currentFrame: Int,
    frameCount: Int,
    fps: Double,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        if (frameCount > 0) {
            Slider(
                value = currentFrame.toFloat(),
                onValueChange = {
                    onSeek(it.toInt())
                },
                valueRange = 0f..(frameCount - 1).toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Slider(
                value = 0f,
                onValueChange = {},
                enabled = false,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (frameCount > 0) {
                    "${currentFrame + 1}"
                } else {
                    "0"
                },
                color = TextSecondary
            )

            Spacer(
                modifier = Modifier.weight(1f)
            )

            Text(
                text = "${fps.toString().removeSuffix(".0")} FPS",
                color = TextSecondary
            )

            Spacer(
                modifier = Modifier.width(16.dp)
            )

            Text(
                text = frameCount.toString(),
                color = TextSecondary
            )
        }
    }
}