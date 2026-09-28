package com.trackstudio.app.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import com.trackstudio.app.ui.theme.AccentPrimary
import com.trackstudio.app.ui.theme.PanelBorder
import com.trackstudio.app.ui.theme.TextPrimary
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.ui.theme.ViewerBackground
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
    onPreviousFrame: () -> Unit,
    onNextFrame: () -> Unit,
    onSeek: (Int) -> Unit,
    onThumbnailsNeeded: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val playback = state.playback
    val hasFrames = playback.frameCount > 0

    PanelCard(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val thumbnailCount = ((maxWidth - 24.dp) / if (compact) 76.dp else 100.dp)
                .toInt().coerceIn(5, 18)

            LaunchedEffect(thumbnailCount, playback.frameCount) {
                if (hasFrames) onThumbnailsNeeded(thumbnailCount)
            }

            Column(
                Modifier.fillMaxWidth().padding(
                    horizontal = if (compact) 8.dp else 12.dp,
                    vertical = if (compact) 6.dp else 10.dp
                )
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Transport(
                        isPlaying = playback.isPlaying,
                        hasFrames = hasFrames,
                        canStepBack = playback.currentFrame > 0,
                        canStepForward = !playback.isLastFrame,
                        onPlayPause = onPlayPause,
                        onPrevious = onPreviousFrame,
                        onNext = onNextFrame
                    )
                    Text(
                        "Frame ${playback.frameNumber} / ${playback.frameCount}",
                        color = TextPrimary,
                        fontSize = if (compact) 12.sp else 13.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    Text(
                        "${formatTimecode(playback.currentFrame, playback.fps)} / " +
                            formatTimecode(maxOf(0, playback.frameCount - 1), playback.fps),
                        color = TextSecondary,
                        fontSize = if (compact) 11.sp else 12.sp,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    FullscreenButton(isFullscreen, onToggleFullscreen)
                }

                Spacer(Modifier.height(if (compact) 5.dp else 8.dp))

                Timeline(
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
        ChromeIconButton(Icons.Filled.SkipPrevious, "Previous frame", onPrevious,
            enabled = hasFrames && canStepBack, tint = TextPrimary)
        ChromeIconButton(
            icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            onClick = onPlayPause, enabled = hasFrames, tint = TextPrimary,
            buttonSize = 44.dp, iconSize = 30.dp
        )
        ChromeIconButton(Icons.Filled.SkipNext, "Next frame", onNext,
            enabled = hasFrames && canStepForward, tint = TextPrimary)
    }
}

@Composable
private fun FullscreenButton(isFullscreen: Boolean, onToggle: () -> Unit) {
    ChromeIconButton(
        icon = if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
        contentDescription = "Toggle fullscreen viewer",
        onClick = onToggle,
        tint = TextSecondary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun Timeline(
    thumbnails: Map<Int, Bitmap>,
    frameCount: Int,
    fps: Double,
    currentFrame: Int,
    thumbnailCount: Int,
    onSeek: (Int) -> Unit
) {
    if (frameCount <= 0) return

    val indices = remember(frameCount, thumbnailCount) {
        if (thumbnailCount <= 1 || frameCount == 1) listOf(0)
        else (0 until thumbnailCount).map { i ->
            (i.toLong() * (frameCount - 1) / (thumbnailCount - 1)).toInt()
        }.distinct()
    }
    val progress = if (frameCount > 1) currentFrame.toFloat() / (frameCount - 1) else 0f

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("START  F1", color = TextSecondary, fontSize = 10.sp)
            Text("END  F${frameCount}", color = TextSecondary, fontSize = 10.sp)
        }

        BoxWithConstraints(
            Modifier.fillMaxWidth()
                .padding(top = 4.dp)
                .height(if (frameCount > 1) 64.dp else 54.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(ViewerBackground)
                .border(BorderStroke(1.dp, PanelBorder), RoundedCornerShape(7.dp))
                .pointerInput(frameCount) {
                    detectTapGestures { position ->
                        val fraction = (position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeek(((fraction * (frameCount - 1)).roundToInt())
                            .coerceIn(0, frameCount - 1))
                    }
                }
        ) {
            Row(Modifier.fillMaxSize()) {
                indices.forEachIndexed { position, frameIndex ->
                    val nextIndex = indices.getOrNull(position + 1) ?: frameCount
                    val selected = currentFrame >= frameIndex && currentFrame < nextIndex
                    Box(
                        Modifier.weight(1f).fillMaxSize().padding(1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(
                                BorderStroke(
                                    if (selected) 1.dp else 0.5.dp,
                                    if (selected) AccentPrimary else Color.Transparent
                                ), RoundedCornerShape(4.dp)
                            )
                    ) {
                        thumbnails[frameIndex]?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .offset(x = maxWidth * progress - 1.dp)
                    .background(AccentPrimary)
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("F1", color = TextSecondary, fontSize = 10.sp)
            Text("F${currentFrame + 1}", color = TextPrimary, fontSize = 11.sp)
            Text("F${frameCount}", color = TextSecondary, fontSize = 10.sp)
        }

        Text(
            text = if (fps == fps.toInt().toDouble()) "${fps.toInt()} FPS timeline" else "${fps} FPS timeline",
            color = TextSecondary,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
