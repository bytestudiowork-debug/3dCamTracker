package com.trackstudio.app.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackstudio.app.ui.theme.AccentPrimary
import com.trackstudio.app.ui.theme.PanelBorder
import com.trackstudio.app.ui.theme.TextDisabled
import com.trackstudio.app.ui.theme.TextPrimary
import com.trackstudio.app.ui.theme.TextSecondary

/**
 * Thin, compact slider used by the playback scrubber and the settings
 * panel. Material's Slider enforces a 48dp touch height and a heavy track,
 * neither of which fits the dense desktop-style layout.
 *
 * [value] is normalised to 0..1.
 */
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val currentOnChange by rememberUpdatedState(onValueChange)

    val activeColor = if (enabled) AccentPrimary else TextDisabled

    Canvas(
        modifier = modifier
            .height(28.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput

                val inset = 8.dp.toPx()

                detectTapGestures { offset ->
                    currentOnChange(
                        fractionAt(offset.x, size.width.toFloat(), inset)
                    )
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput

                val inset = 8.dp.toPx()

                detectHorizontalDragGestures { change, _ ->
                    change.consume()

                    currentOnChange(
                        fractionAt(
                            change.position.x,
                            size.width.toFloat(),
                            inset
                        )
                    )
                }
            }
    ) {
        val inset = 8.dp.toPx()
        val trackHeight = 4.dp.toPx()
        val centerY = size.height / 2f

        val left = inset
        val right = (size.width - inset).coerceAtLeast(left)

        val corner = CornerRadius(trackHeight / 2f)
        val top = centerY - trackHeight / 2f

        drawRoundRect(
            color = PanelBorder,
            topLeft = Offset(left, top),
            size = Size(right - left, trackHeight),
            cornerRadius = corner
        )

        val thumbX =
            left + (right - left) * value.coerceIn(0f, 1f)

        drawRoundRect(
            color = activeColor,
            topLeft = Offset(left, top),
            size = Size(thumbX - left, trackHeight),
            cornerRadius = corner
        )

        drawCircle(
            color = Color.White,
            radius = 7.dp.toPx(),
            center = Offset(thumbX, centerY)
        )

        drawCircle(
            color = activeColor,
            radius = 4.5.dp.toPx(),
            center = Offset(thumbX, centerY)
        )
    }
}

private fun fractionAt(
    x: Float,
    width: Float,
    inset: Float
): Float {
    val span = (width - inset * 2f).coerceAtLeast(1f)
    return ((x - inset) / span).coerceIn(0f, 1f)
}

/** Square icon-only button with a rounded ripple. */
@Composable
fun ChromeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = TextSecondary,
    buttonSize: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    background: Color = Color.Transparent
) {
    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else TextDisabled,
            modifier = Modifier.size(iconSize)
        )
    }
}

/** Text button with optional leading icon, filled or outlined. */
@Composable
fun ChromeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
    height: Dp = 40.dp
) {
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(if (filled) AccentPrimary else Color.Transparent)
            .border(
                BorderStroke(
                    1.dp,
                    if (filled) AccentPrimary else PanelBorder
                ),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            androidx.compose.foundation.layout.Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (filled) Color.White else TextPrimary,
                modifier = Modifier.size(18.dp)
            )

            androidx.compose.foundation.layout.Spacer(
                Modifier.size(6.dp)
            )
        }

        Text(
            text = text,
            color = if (filled) Color.White else TextPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Rounded, bordered surface used for every workspace panel. */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(com.trackstudio.app.ui.theme.PanelSurface)
            .border(
                BorderStroke(
                    1.dp,
                    PanelBorder
                ),
                RoundedCornerShape(12.dp)
            ),
        content = content
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 12.sp,
        modifier = modifier.fillMaxWidth()
    )
}
