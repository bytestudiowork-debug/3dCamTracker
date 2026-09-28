package com.trackstudio.app.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackstudio.app.ui.theme.AccentPrimary
import com.trackstudio.app.ui.theme.PanelBorder
import com.trackstudio.app.ui.theme.TextPrimary
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.ui.theme.ViewerBackground
import com.trackstudio.app.util.formatTimecode
import com.trackstudio.app.viewmodel.ViewerUiState
import kotlin.math.roundToInt

private val SPEED_OPTIONS = listOf(0.25f, 0.5f, 1f, 2f)

/**
 * Transport controls, scrubber and (on roomy screens) a filmstrip.
 *
 * [compact] collapses everything into a single slim row for short
 * screens such as phones held in landscape.
 */
@Composable
fun PlaybackPanel(
    state: ViewerUiState,
    compact: Boolean,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onPlayPause: () -> Unit,
    onPreviousFrame: () -> Unit,
    onNextFrame: () -> Unit,
    onSeek: (Int) -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onThumbnailsNeeded: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val playback = state.playback
    val hasFrames = playback.frameCount > 0

    val progress =
        if (playback.frameCount > 1) {
            playback.currentFrame.toFloat() / (playback.frameCount - 1)
        } else {
            0f
        }

    val seekFromFraction: (Float) -> Unit = { fraction ->
        if (playback.frameCount > 0) {
            onSeek((fraction * (playback.frameCount - 1)).roundToInt())
        }
    }

    PanelCard(modifier = modifier.fillMaxWidth()) {

        if (compact) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Transport(
                    isPlaying = playback.isPlaying,
                    hasFrames = hasFrames,
                    canStepBack = playback.currentFrame > 0,
                    canStepForward = !playback.isLastFrame,
                    onPlayPause = onPlayPause,
                    onPrevious = onPreviousFrame,
                    onNext = onNextFrame
                )

                TimeText(state)

                ThinSlider(
                    value = progress,
                    onValueChange = seekFromFraction,
                    enabled = hasFrames,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )

                SpeedSelector(state.playbackSpeed, onSpeedSelected)

                FullscreenButton(isFullscreen, onToggleFullscreen)
            }

        } else {

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {

                // Capture the BoxWithConstraints width explicitly so nested
                // RowScope/ColumnScope receivers cannot shadow maxWidth.
                val availableWidth = maxWidth
                val thumbnailCount =
                    ((availableWidth - 24.dp - 52.dp) / 100.dp)
                        .toInt()
                        .coerceIn(3, 12)

                LaunchedEffect(thumbnailCount, playback.frameCount) {
                    if (playback.frameCount > 0) {
                        onThumbnailsNeeded(thumbnailCount)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "Playback",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Transport(
                            isPlaying = playback.isPlaying,
                            hasFrames = hasFrames,
                            canStepBack = playback.currentFrame > 0,
                            canStepForward = !playback.isLastFrame,
                            onPlayPause = onPlayPause,
                            onPrevious = onPreviousFrame,
                            onNext = onNextFrame
                        )

                        TimeText(state)

                        if (playback.frameCount > 0 && availableWidth > 560.dp) {
                            Text(
                                text = "Frame ${playback.frameNumber} / ${playback.frameCount}",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 14.dp)
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        SpeedSelector(state.playbackSpeed, onSpeedSelected)

                        FullscreenButton(isFullscreen, onToggleFullscreen)
                    }

                    ThinSlider(
                        value = progress,
                        onValueChange = seekFromFraction,
                        enabled = hasFrames,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(6.dp))

                    Filmstrip(
                        thumbnails = state.thumbnails,
                        frameCount = playback.frameCount,
                        fps = playback.fps,
                        currentFrame = playback.currentFrame,
                        thumbnailCount = thumbnailCount,
                        onSeek = onSeek
                    )
                }
            }
        }
    }
}

@Composable
private fun Transport(
    isPlaying: Boolean,
    hasFrames: Boolean,
    canStepBack: Boolean,
    canStepForward: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {

        ChromeIconButton(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = "Previous frame",
            onClick = onPrevious,
            enabled = hasFrames && canStepBack,
            tint = TextPrimary
        )

        ChromeIconButton(
            icon =
                if (isPlaying) Icons.Filled.Pause
                else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            onClick = onPlayPause,
            enabled = hasFrames,
            tint = TextPrimary,
            buttonSize = 44.dp,
            iconSize = 30.dp
        )

        ChromeIconButton(
            icon = Icons.Filled.SkipNext,
            contentDescription = "Next frame",
            onClick = onNext,
            enabled = hasFrames && canStepForward,
            tint = TextPrimary
        )
    }
}

@Composable
private fun TimeText(state: ViewerUiState) {
    val playback = state.playback

    val current = formatTimecode(playback.currentFrame, playback.fps)
    val total = formatTimecode(playback.frameCount, playback.fps)

    Text(
        text = "$current / $total",
        color = TextPrimary,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier.padding(start = 10.dp)
    )
}

@Composable
private fun SpeedSelector(
    speed: Float,
    onSpeedSelected: (Float) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(BorderStroke(1.dp, PanelBorder), RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${speed}x",
                color = TextPrimary,
                fontSize = 13.sp
            )

            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = "Playback speed",
                tint = TextSecondary,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(18.dp)
            )
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false }
        ) {
            SPEED_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option}x") },
                    onClick = {
                        open = false
                        onSpeedSelected(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun FullscreenButton(
    isFullscreen: Boolean,
    onToggle: () -> Unit
) {
    ChromeIconButton(
        icon =
            if (isFullscreen) Icons.Outlined.FullscreenExit
            else Icons.Outlined.Fullscreen,
        contentDescription = "Toggle fullscreen viewer",
        onClick = onToggle,
        tint = TextSecondary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

// ---------------------------------------------------------------------------
// Filmstrip
// ---------------------------------------------------------------------------

@Composable
private fun Filmstrip(
    thumbnails: Map<Int, Bitmap>,
    frameCount: Int,
    fps: Double,
    currentFrame: Int,
    thumbnailCount: Int,
    onSeek: (Int) -> Unit
) {
    if (frameCount <= 0) {
        return
    }

    // Must match ViewerViewModel.ensureThumbnails so the images line up.
    val indices =
        remember(frameCount, thumbnailCount) {
            if (thumbnailCount <= 1 || frameCount == 1) {
                listOf(0)
            } else {
                (0 until thumbnailCount)
                    .map { i ->
                        (i.toLong() * (frameCount - 1) / (thumbnailCount - 1)).toInt()
                    }
                    .distinct()
            }
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {

        Box(
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(44f / 30f)
                .clip(RoundedCornerShape(6.dp))
                .background(ViewerBackground),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Movie,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        indices.forEachIndexed { position, frameIndex ->

            val nextIndex = indices.getOrNull(position + 1) ?: Int.MAX_VALUE
            val selected = currentFrame >= frameIndex && currentFrame < nextIndex

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val shape = RoundedCornerShape(6.dp)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(shape)
                        .background(ViewerBackground)
                        .border(
                            BorderStroke(
                                if (selected) 2.dp else 1.dp,
                                if (selected) AccentPrimary else PanelBorder
                            ),
                            shape
                        )
                        .clickable { onSeek(frameIndex) }
                ) {
                    val bitmap = thumbnails[frameIndex]

                    if (bitmap != null) {
                        ThumbnailImage(bitmap)
                    }
                }

                Text(
                    text = formatTimecode(frameIndex, fps),
                    color = if (selected) TextPrimary else TextSecondary,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun ThumbnailImage(bitmap: Bitmap) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
    )
}
