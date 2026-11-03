package com.trackstudio.app.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackstudio.app.playback.PlaybackState
import com.trackstudio.app.ui.theme.AccentPrimary
import com.trackstudio.app.ui.theme.PanelBorder
import com.trackstudio.app.ui.theme.PanelSurfaceRaised
import com.trackstudio.app.ui.theme.TextDisabled
import com.trackstudio.app.ui.theme.TextPrimary
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.ui.theme.ViewerBackground
import com.trackstudio.app.util.formatTimecode
import com.trackstudio.app.viewmodel.ViewerUiState
import kotlin.math.roundToInt

private val SPEED_OPTIONS = listOf(0.25, 0.5, 1.0, 1.5, 2.0)

@Composable
fun PlaybackPanel(
    state: ViewerUiState,
    poster: Bitmap?,
    compact: Boolean,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onPlayPause: () -> Unit,
    onFirstFrame: () -> Unit,
    onLastFrame: () -> Unit,
    onSeek: (Int) -> Unit,
    onSpeedChanged: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val playback = state.playback
    val hasFrames = playback.frameCount > 0

    PanelCard(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 104.dp else 124.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (compact) 8.dp else 12.dp,
                    vertical = if (compact) 4.dp else 6.dp
                )
        ) {
            PlaybackHeader(
                playback = playback,
                compact = compact,
                hasFrames = hasFrames,
                isFullscreen = isFullscreen,
                onPlayPause = onPlayPause,
                onFirstFrame = onFirstFrame,
                onLastFrame = onLastFrame,
                onSpeedChanged = onSpeedChanged,
                onToggleFullscreen = onToggleFullscreen
            )

            TimelineStrip(
                poster = poster,
                frameCount = playback.frameCount,
                currentFrame = playback.currentFrame,
                fps = playback.fps,
                compact = compact,
                onSeek = onSeek
            )
        }
    }
}

@Composable
private fun PlaybackHeader(
    playback: PlaybackState,
    compact: Boolean,
    hasFrames: Boolean,
    isFullscreen: Boolean,
    onPlayPause: () -> Unit,
    onFirstFrame: () -> Unit,
    onLastFrame: () -> Unit,
    onSpeedChanged: (Double) -> Unit,
    onToggleFullscreen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 32.dp else 36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "F" + playback.frameNumber + " / F" + playback.frameCount,
            color = TextPrimary,
            fontSize = if (compact) 9.sp else 10.sp
        )

        Spacer(Modifier.width(6.dp))

        Text(
            text = formatTimecode(playback.currentFrame, playback.fps) +
                " / " +
                formatTimecode(maxOf(0, playback.frameCount - 1), playback.fps),
            color = TextSecondary,
            fontSize = if (compact) 9.sp else 10.sp
        )

        Spacer(Modifier.weight(1f))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            ChromeIconButton(
                icon = Icons.Filled.SkipPrevious,
                contentDescription = "First frame",
                onClick = onFirstFrame,
                enabled = hasFrames && !playback.isFirstFrame,
                tint = TextPrimary,
                buttonSize = if (compact) 30.dp else 34.dp,
                iconSize = if (compact) 17.dp else 19.dp
            )

            ChromeIconButton(
                icon = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playback.isPlaying) "Pause" else "Play",
                onClick = onPlayPause,
                enabled = hasFrames,
                tint = TextPrimary,
                buttonSize = if (compact) 32.dp else 36.dp,
                iconSize = if (compact) 20.dp else 23.dp,
                background = if (playback.isPlaying) PanelSurfaceRaised else Color.Transparent
            )

            ChromeIconButton(
                icon = Icons.Filled.SkipNext,
                contentDescription = "Last frame",
                onClick = onLastFrame,
                enabled = hasFrames && !playback.isLastFrame,
                tint = TextPrimary,
                buttonSize = if (compact) 30.dp else 34.dp,
                iconSize = if (compact) 17.dp else 19.dp
            )
        }

        Spacer(Modifier.weight(1f))

        ChromeIconButton(
            icon = if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
            contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
            onClick = onToggleFullscreen,
            enabled = hasFrames,
            tint = TextSecondary,
            buttonSize = if (compact) 30.dp else 34.dp,
            iconSize = if (compact) 18.dp else 20.dp,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}

@Composable
private fun SpeedMenu(
    speed: Double,
    compact: Boolean,
    enabled: Boolean,
    onSpeedChanged: (Double) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .border(BorderStroke(1.dp, PanelBorder), RoundedCornerShape(7.dp))
                .clickable(enabled = enabled) { expanded = true }
                .padding(
                    horizontal = if (compact) 5.dp else 7.dp,
                    vertical = 3.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Speed,
                contentDescription = "Playback speed",
                tint = if (enabled) TextSecondary else TextDisabled,
                modifier = Modifier.size(if (compact) 13.dp else 15.dp)
            )
            Text(
                text = formatSpeed(speed),
                color = if (enabled) TextPrimary else TextDisabled,
                fontSize = if (compact) 9.sp else 10.sp,
                modifier = Modifier.padding(start = 3.dp)
            )
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(14.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SPEED_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(formatSpeed(option)) },
                    onClick = {
                        expanded = false
                        onSpeedChanged(option)
                    }
                )
            }
        }
    }
}

private fun formatSpeed(speed: Double): String =
    if (speed == speed.toInt().toDouble()) {
        speed.toInt().toString() + ".0x"
    } else {
        speed.toString() + "x"
    }

@Composable
private fun TimelineStrip(
    poster: Bitmap?,
    frameCount: Int,
    currentFrame: Int,
    fps: Double,
    compact: Boolean,
    onSeek: (Int) -> Unit
) {
    if (frameCount <= 0) return

    val lastFrame = (frameCount - 1).coerceAtLeast(0)
    val progress = if (lastFrame > 0) {
        (currentFrame.toFloat() / lastFrame).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTimecode(0, fps),
                color = TextSecondary,
                fontSize = if (compact) 8.sp else 9.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatTimecode(currentFrame, fps),
                color = TextPrimary,
                fontSize = if (compact) 8.sp else 9.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatTimecode(frameCount - 1, fps),
                color = TextSecondary,
                fontSize = if (compact) 8.sp else 9.sp
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 36.dp else 42.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(ViewerBackground)
                .border(
                    BorderStroke(1.dp, PanelBorder),
                    RoundedCornerShape(5.dp)
                )
                .pointerInput(frameCount) {
                    detectTapGestures { position ->
                        if (size.width > 0) {
                            onSeek(
                                (position.x / size.width * lastFrame)
                                    .roundToInt()
                                    .coerceIn(0, lastFrame)
                            )
                        }
                    }
                }
                .pointerInput(frameCount) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        if (size.width > 0) {
                            onSeek(
                                (change.position.x / size.width * lastFrame)
                                    .roundToInt()
                                    .coerceIn(0, lastFrame)
                            )
                        }
                    }
                }
        ) {
            if (poster != null) {
                Image(
                    bitmap = poster.asImageBitmap(),
                    contentDescription = "Footage preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                )
            }

            Canvas(Modifier.fillMaxSize()) {
                val baseY = size.height * 0.68f
                val tickStep = size.width / 10f

                for (tick in 0..10) {
                    val x = tickStep * tick
                    drawLine(
                        color = Color.White.copy(alpha = 0.32f),
                        start = Offset(x, baseY),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                val playheadX = progress * size.width
                drawLine(
                    color = AccentPrimary,
                    start = Offset(playheadX, 0f),
                    end = Offset(playheadX, size.height),
                    strokeWidth = 2.dp.toPx()
                )
                drawCircle(
                    color = AccentPrimary,
                    radius = 4.dp.toPx(),
                    center = Offset(playheadX, 3.dp.toPx())
                )
            }
        }
    }
}
