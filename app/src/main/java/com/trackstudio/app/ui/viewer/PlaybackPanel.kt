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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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

private val SPEED_OPTIONS = listOf(0.25, 0.5, 1.0, 1.5, 2.0)

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
    val hasFrames = playback.frameCount > 0

    PanelCard(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sampleCount =
                when {
                    playback.frameCount <= 24 -> playback.frameCount
                    compact -> 28
                    else -> 40
                }.coerceIn(1, 48)

            LaunchedEffect(sampleCount, playback.frameCount) {
                if (hasFrames) onThumbnailsNeeded(sampleCount)
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (compact) 8.dp else 12.dp,
                        vertical = if (compact) 6.dp else 8.dp
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

                Spacer(Modifier.height(if (compact) 5.dp else 7.dp))

                Timeline(
                    thumbnails = state.thumbnails,
                    frameCount = playback.frameCount,
                    fps = playback.fps,
                    currentFrame = playback.currentFrame,
                    compact = compact,
                    onSeek = onSeek
                )
            }
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
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)
        ) {
            ChromeIconButton(
                icon = Icons.Filled.SkipPrevious,
                contentDescription = "First frame",
                onClick = onFirstFrame,
                enabled = hasFrames && !playback.isFirstFrame,
                tint = TextPrimary,
                buttonSize = if (compact) 36.dp else 40.dp,
                iconSize = if (compact) 20.dp else 22.dp
            )

            ChromeIconButton(
                icon = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playback.isPlaying) "Pause" else "Play",
                onClick = onPlayPause,
                enabled = hasFrames,
                tint = TextPrimary,
                buttonSize = if (compact) 42.dp else 46.dp,
                iconSize = if (compact) 26.dp else 30.dp,
                background = if (playback.isPlaying) PanelSurfaceRaised else Color.Transparent
            )

            ChromeIconButton(
                icon = Icons.Filled.SkipNext,
                contentDescription = "Last frame",
                onClick = onLastFrame,
                enabled = hasFrames && !playback.isLastFrame,
                tint = TextPrimary,
                buttonSize = if (compact) 36.dp else 40.dp,
                iconSize = if (compact) 20.dp else 22.dp
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "F${playback.frameNumber} / F${playback.frameCount}",
                color = TextPrimary,
                fontSize = if (compact) 11.sp else 12.sp
            )

            Text(
                text = "  " +
                    formatTimecode(playback.currentFrame, playback.fps) +
                    " / " +
                    formatTimecode(
                        maxOf(0, playback.frameCount - 1),
                        playback.fps
                    ),
                color = TextSecondary,
                fontSize = if (compact) 10.sp else 11.sp
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = if (compact) 2.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpeedMenu(
                speed = playback.speed,
                compact = compact,
                enabled = hasFrames,
                onSpeedChanged = onSpeedChanged
            )

            FullscreenButton(
                isFullscreen = isFullscreen,
                onToggle = onToggleFullscreen,
                compact = compact
            )
        }
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
                .clip(RoundedCornerShape(8.dp))
                .border(BorderStroke(1.dp, PanelBorder), RoundedCornerShape(8.dp))
                .clickable(enabled = enabled) { expanded = true }
                .padding(
                    horizontal = if (compact) 7.dp else 9.dp,
                    vertical = if (compact) 5.dp else 7.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Speed,
                contentDescription = "Playback speed",
                tint = if (enabled) TextSecondary else TextDisabled,
                modifier = Modifier.size(if (compact) 15.dp else 17.dp)
            )

            Text(
                text = formatSpeed(speed),
                color = if (enabled) TextPrimary else TextDisabled,
                fontSize = if (compact) 10.sp else 11.sp,
                modifier = Modifier.padding(start = 4.dp)
            )

            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
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
        "${speed.toInt()}.0x"
    } else {
        "${speed}x"
    }

@Composable
private fun FullscreenButton(
    isFullscreen: Boolean,
    onToggle: () -> Unit,
    compact: Boolean
) {
    ChromeIconButton(
        icon = if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
        contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
        onClick = onToggle,
        tint = TextSecondary,
        buttonSize = if (compact) 36.dp else 40.dp,
        iconSize = if (compact) 20.dp else 22.dp,
        modifier = Modifier.padding(start = 2.dp)
    )
}

@Composable
private fun Timeline(
    thumbnails: Map<Int, Bitmap>,
    frameCount: Int,
    fps: Double,
    currentFrame: Int,
    compact: Boolean,
    onSeek: (Int) -> Unit
) {
    if (frameCount <= 0) return

    val sampleCount =
        when {
            frameCount <= 24 -> frameCount
            compact -> 28
            else -> 40
        }.coerceIn(1, 48)

    val indices = remember(frameCount, sampleCount) {
        if (sampleCount <= 1 || frameCount == 1) {
            listOf(0)
        } else {
            (0 until sampleCount)
                .map { i ->
                    (i.toLong() * (frameCount - 1) / (sampleCount - 1)).toInt()
                }
                .distinct()
        }
    }

    val currentSegment =
        indices.indexOfLast { it <= currentFrame }.coerceAtLeast(0)

    val listState = rememberLazyListState()

    LaunchedEffect(currentSegment) {
        listState.animateScrollToItem(currentSegment)
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "START  F1",
                color = TextSecondary,
                fontSize = if (compact) 9.sp else 10.sp
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = "FRAME ${currentFrame + 1}",
                color = TextPrimary,
                fontSize = if (compact) 9.sp else 10.sp
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = "END  F${frameCount}",
                color = TextSecondary,
                fontSize = if (compact) 9.sp else 10.sp
            )
        }

        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 72.dp else 84.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ViewerBackground)
                .border(
                    BorderStroke(1.dp, PanelBorder),
                    RoundedCornerShape(8.dp)
                ),
            contentPadding = PaddingValues(
                horizontal = if (compact) 5.dp else 7.dp,
                vertical = 5.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
        ) {
            itemsIndexed(
                items = indices,
                key = { _, frameIndex -> frameIndex }
            ) { itemPosition, frameIndex ->
                val nextIndex = indices.getOrNull(itemPosition + 1) ?: frameCount
                val selected =
                    currentFrame >= frameIndex && currentFrame < nextIndex

                TimelineThumbnail(
                    bitmap = thumbnails[frameIndex],
                    frameIndex = frameIndex,
                    fps = fps,
                    selected = selected,
                    compact = compact,
                    onClick = { onSeek(frameIndex) }
                )
            }
        }
    }
}

@Composable
private fun TimelineThumbnail(
    bitmap: Bitmap?,
    frameIndex: Int,
    fps: Double,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)

    Column(
        modifier = Modifier
            .width(if (compact) 84.dp else 108.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) PanelSurfaceRaised else Color.Transparent)
            .border(
                BorderStroke(
                    if (selected) 2.dp else 1.dp,
                    if (selected) AccentPrimary else PanelBorder
                ),
                shape
            )
            .clickable(onClick = onClick)
            .padding(2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "F${frameIndex + 1}",
                color = if (selected) TextPrimary else TextSecondary,
                fontSize = if (compact) 8.sp else 9.sp
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = formatTimecode(frameIndex, fps),
                color = TextDisabled,
                fontSize = if (compact) 7.sp else 8.sp
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(PanelSurfaceRaised)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Frame ${frameIndex + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
