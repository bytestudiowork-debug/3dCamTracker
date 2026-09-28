package com.trackstudio.app.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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

    PanelCard(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 82.dp else 94.dp)
    ) {
        val sampleCount = when {
            playback.frameCount <= 12 -> playback.frameCount
            compact -> 16
            else -> 24
        }.coerceIn(1, 24)

        LaunchedEffect(sampleCount, playback.frameCount) {
            if (hasFrames) onThumbnailsNeeded(sampleCount)
        }

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
                onPlayPause = onPlayPause,
                onFirstFrame = onFirstFrame,
                onLastFrame = onLastFrame,
                onSpeedChanged = onSpeedChanged,
                onToggleFullscreen = onToggleFullscreen
            )

            TimelineStrip(
                thumbnails = state.thumbnails,
                frameCount = playback.frameCount,
                currentFrame = playback.currentFrame,
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

        SpeedMenu(
            speed = playback.speed,
            compact = compact,
            enabled = hasFrames,
            onSpeedChanged = onSpeedChanged
        )

        ChromeIconButton(
            icon = Icons.Outlined.Fullscreen,
            contentDescription = "Fullscreen",
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
    thumbnails: Map<Int, Bitmap>,
    frameCount: Int,
    currentFrame: Int,
    compact: Boolean,
    onSeek: (Int) -> Unit
) {
    if (frameCount <= 0) return

    val sampleCount = when {
        frameCount <= 12 -> frameCount
        compact -> 16
        else -> 24
    }.coerceIn(1, 24)

    val indices = remember(frameCount, sampleCount) {
        if (sampleCount == 1) {
            listOf(0)
        } else {
            (0 until sampleCount)
                .map { i ->
                    (i.toLong() * (frameCount - 1) / (sampleCount - 1)).toInt()
                }
                .distinct()
        }
    }

    val segment = currentSegment(indices, currentFrame)
    val listState = rememberLazyListState()

    LaunchedEffect(segment) {
        if (indices.isNotEmpty()) {
            listState.animateScrollToItem(segment.coerceIn(0, indices.lastIndex))
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "00:00",
                color = TextSecondary,
                fontSize = if (compact) 8.sp else 9.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "F" + (currentFrame + 1),
                color = TextPrimary,
                fontSize = if (compact) 8.sp else 9.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "F" + frameCount,
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
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                itemsIndexed(
                    items = indices,
                    key = { _, frameIndex -> frameIndex }
                ) { itemPosition, frameIndex ->
                    val nextFrame =
                        indices.getOrNull(itemPosition + 1) ?: frameCount
                    val isCurrent = currentFrame in frameIndex until nextFrame

                    TimelineSegment(
                        bitmap = thumbnails[frameIndex],
                        selected = isCurrent,
                        compact = compact,
                        onClick = {
                            onSeek(frameIndex)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineSegment(
    bitmap: Bitmap?,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    val width = if (compact) 52.dp else 64.dp

    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .background(if (selected) PanelSurfaceRaised else Color.Transparent)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun currentSegment(indices: List<Int>, frame: Int): Int =
    indices.indexOfLast { it <= frame }.coerceAtLeast(0)
