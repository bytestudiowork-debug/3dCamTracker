package com.trackstudio.app.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackstudio.app.playback.PlaybackState
import com.trackstudio.app.ui.theme.AccentPrimary
import com.trackstudio.app.ui.theme.PanelBorder
import com.trackstudio.app.ui.theme.PanelSurfaceRaised
import com.trackstudio.app.ui.theme.TextPrimary
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.util.formatTimecode
import com.trackstudio.app.viewmodel.ViewerUiState
import kotlin.math.roundToInt

@Composable
fun PlaybackPanel(
    state: ViewerUiState,
    compact: Boolean,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onPlayPause: () -> Unit,
    onFirstFrame: () -> Unit,
    onLastFrame: () -> Unit,
    onSeek: (Int) -> Unit,
    onSpeedChanged: (Double) -> Unit,
    onThumbnailsNeeded: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val playback = state.playback
    val lastFrame = (playback.frameCount - 1).coerceAtLeast(0)
    val progress = if (lastFrame == 0) 0f else playback.currentFrame.toFloat() / lastFrame

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 92.dp else 112.dp)
            .clip(RoundedCornerShape(if (isFullscreen) 0.dp else 10.dp))
            .background(Color(0xFF111824))
            .border(1.dp, PanelBorder, RoundedCornerShape(if (isFullscreen) 0.dp else 10.dp))
            .padding(horizontal = if (compact) 8.dp else 14.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(if (compact) 30.dp else 36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(formatTimecode(playback.currentFrame, playback.fps), color = TextPrimary, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            ChromeIconButton(
                icon = Icons.Filled.SkipPrevious,
                contentDescription = "First frame",
                onClick = onFirstFrame,
                enabled = playback.frameCount > 0 && playback.currentFrame > 0,
                tint = TextPrimary,
                buttonSize = 32.dp,
                iconSize = 19.dp
            )
            ChromeIconButton(
                icon = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playback.isPlaying) "Pause" else "Play",
                onClick = onPlayPause,
                enabled = playback.frameCount > 0,
                tint = TextPrimary,
                buttonSize = 38.dp,
                iconSize = 23.dp,
                background = PanelSurfaceRaised
            )
            ChromeIconButton(
                icon = Icons.Filled.SkipNext,
                contentDescription = "Last frame",
                onClick = onLastFrame,
                enabled = playback.frameCount > 0 && playback.currentFrame < lastFrame,
                tint = TextPrimary,
                buttonSize = 32.dp,
                iconSize = 19.dp
            )
            Spacer(Modifier.weight(1f))
            Text(formatTimecode(lastFrame, playback.fps), color = TextSecondary, fontSize = 11.sp)
        }

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF0A1019))
                .pointerInput(playback.frameCount) {
                    detectTapGestures { tap ->
                        if (playback.frameCount > 1 && size.width > 0) {
                            onSeek((tap.x / size.width * lastFrame).roundToInt().coerceIn(0, lastFrame))
                        }
                    }
                }
                .pointerInput(playback.frameCount) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        if (playback.frameCount > 1 && size.width > 0) {
                            onSeek((change.position.x / size.width * lastFrame).roundToInt().coerceIn(0, lastFrame))
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val tickCount = 6
                repeat(tickCount + 1) { i ->
                    val x = w * i / tickCount
                    drawLine(
                        color = Color(0xFF536174),
                        start = androidx.compose.ui.geometry.Offset(x, h * 0.50f),
                        end = androidx.compose.ui.geometry.Offset(x, h * 0.88f),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                drawLine(
                    color = Color(0xFF354255),
                    start = androidx.compose.ui.geometry.Offset(0f, h * 0.88f),
                    end = androidx.compose.ui.geometry.Offset(w, h * 0.88f),
                    strokeWidth = 1.dp.toPx()
                )
                val x = w * progress
                drawLine(
                    color = AccentPrimary,
                    start = androidx.compose.ui.geometry.Offset(x, 0f),
                    end = androidx.compose.ui.geometry.Offset(x, h),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = AccentPrimary,
                    radius = 5.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(x, 4.dp.toPx())
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .padding(horizontal = 3.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                repeat(7) { i ->
                    val frame = (lastFrame.toFloat() * i / 6f).roundToInt()
                    Text(
                        text = formatTimecode(frame, playback.fps),
                        color = TextSecondary,
                        fontSize = 8.sp
                    )
                }
            }
        }
    }
}
