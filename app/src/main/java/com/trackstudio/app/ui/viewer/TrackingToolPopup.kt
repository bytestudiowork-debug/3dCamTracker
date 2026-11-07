package com.trackstudio.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackstudio.app.ui.theme.AccentPrimary
import com.trackstudio.app.ui.theme.PanelBackground
import com.trackstudio.app.ui.theme.PanelSurfaceRaised
import com.trackstudio.app.ui.theme.TextPrimary
import com.trackstudio.app.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
internal fun TrackingToolPopup(
    title: String,
    currentFrame: Int,
    frameCount: Int,
    trackPointCount: Int,
    verificationLineCount: Int,
    showCube: Boolean,
    showTracks: Boolean,
    markerColorArgb: Int,
    markerSizeDp: Float,
    markerOutline: Boolean,
    cubeColorArgb: Int,
    cubeRotationX: Float,
    cubeRotationY: Float,
    cubeRotationZ: Float,
    cubeScale: Float,
    cubeSize: Float,
    cubeLineWidth: Float,
    onCubeColorChanged: (Int) -> Unit,
    onCubeRotationXChanged: (Float) -> Unit,
    onCubeRotationYChanged: (Float) -> Unit,
    onCubeRotationZChanged: (Float) -> Unit,
    onCubeScaleChanged: (Float) -> Unit,
    onCubeSizeChanged: (Float) -> Unit,
    onCubeLineWidthChanged: (Float) -> Unit,
    onMarkerColorChanged: (Int) -> Unit,
    onMarkerSizeChanged: (Float) -> Unit,
    onMarkerOutlineChanged: (Boolean) -> Unit,
    onCubeChanged: (Boolean) -> Unit,
    onTracksChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var dragOffset by remember(title) { mutableStateOf(Offset.Zero) }
    var activeSlider by remember(title) { mutableStateOf<String?>(null) }

    Box(
        Modifier.fillMaxSize().background(if (activeSlider == null) Color(0x99000000) else Color.Transparent).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(14.dp))
                .background(if (activeSlider == null) PanelBackground else Color.Transparent)
        ) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).background(PanelSurfaceRaised)
                    .pointerInput(title) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            dragOffset += amount
                        }
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Close",
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp).clickable(onClick = onDismiss)
                )
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (title == "3D Test") {
                    PopupSwitch("Show diagnostic cube", showCube, onCubeChanged)
                    Text("Cube colour", color = TextPrimary, fontSize = 13.sp)
                    CubeColourSelector(cubeColorArgb, onCubeColorChanged)
                    CubeAngleSlider("Rotation X", cubeRotationX, onCubeRotationXChanged)
                    CubeAngleSlider("Rotation Y", cubeRotationY, onCubeRotationYChanged)
                    CubeAngleSlider("Rotation Z", cubeRotationZ, onCubeRotationZChanged)
                    CubeValueSlider("Scale", cubeScale, 0.25f..3f, onCubeScaleChanged, "%.2fx")
                    CubeValueSlider("Size", cubeSize, 0.5f..2f, onCubeSizeChanged, "%.2fx")
                    CubeValueSlider("Line thickness", cubeLineWidth, 1f..5f, onCubeLineWidthChanged, "%.1f dp")
                    PopupMetric("Projected wireframe lines", verificationLineCount.toString())
                    androidx.compose.material3.TextButton(onClick = {
                        onCubeColorChanged(0xFF00E5FF.toInt())
                        onCubeRotationXChanged(0f); onCubeRotationYChanged(0f); onCubeRotationZChanged(0f)
                        onCubeScaleChanged(1f); onCubeSizeChanged(1f); onCubeLineWidthChanged(1.8f)
                    }) { Text("Reset cube settings", color = AccentPrimary) }
                    Text(
                        "This diagnostic cube is projected through the solved camera poses. It is not a physical object track. The current reconstruction uses approximate camera intrinsics and provisional translation scale, so visual alignment is a diagnostic—not a final matchmove guarantee.",
                        color = TextSecondary, fontSize = 11.sp
                    )
                } else {
                    PopupSwitch("Show feature tracks", showTracks, onTracksChanged)
                    Text("Marker colour", color = TextPrimary, fontSize = 13.sp)
                    MarkerColourSelector(markerColorArgb, onMarkerColorChanged)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Marker size", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${String.format("%.1f", markerSizeDp)} dp", color = AccentPrimary, fontSize = 12.sp)
                    }
                    Slider(value = markerSizeDp.coerceIn(2f, 5.5f), onValueChange = { activeSlider = "Marker size"; onMarkerSizeChanged(it) }, onValueChangeFinished = { activeSlider = null }, valueRange = 2f..5.5f)
                    PopupSwitch("Dark outline", markerOutline, onMarkerOutlineChanged)
                    PopupMetric("Frame", "$currentFrame / $frameCount")
                    PopupMetric("Tracked observations on frame", trackPointCount.toString())
                    Text(
                        "These markers are persistent 2D feature observations. A visible marker confirms that a feature was tracked on this frame; it does not by itself prove a valid 3D landmark or camera pose.",
                        color = TextSecondary, fontSize = 11.sp
                    )
                }
            }
        }
    }
}


@Composable
private fun CubeColourSelector(selectedArgb: Int, onSelected: (Int) -> Unit) {
    val colours = listOf(0xFF00E5FF.toInt(), 0xFFFFD740.toInt(), 0xFF76FF03.toInt(), 0xFFFF4081.toInt(), 0xFFB388FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF8A50.toInt(), 0xFF448AFF.toInt())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        colours.forEach { argb ->
            val selected = argb == selectedArgb
            Box(Modifier.size(25.dp).clip(CircleShape).background(Color(argb.toLong() and 0xFFFFFFFFL)).border(if (selected) 2.dp else 1.dp, if (selected) TextPrimary else TextSecondary.copy(alpha = 0.45f), CircleShape).clickable { onSelected(argb) })
        }
    }
}

@Composable
private fun CubeAngleSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("${value.toInt()}°", color = AccentPrimary, fontSize = 11.sp)
    }
    Slider(value = value.coerceIn(0f, 360f), onValueChange = { onChange(it) }, valueRange = 0f..360f)
}

@Composable
private fun CubeValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, format: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(String.format(java.util.Locale.US, format, value), color = AccentPrimary, fontSize = 11.sp)
    }
    Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
}
@Composable
private fun MarkerColourSelector(selectedArgb: Int, onSelected: (Int) -> Unit) {
    val colours = listOf(0xFF00E5FF.toInt(), 0xFFFFD740.toInt(), 0xFF76FF03.toInt(), 0xFFFF4081.toInt(), 0xFFB388FF.toInt(), 0xFFFFFFFF.toInt())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        colours.forEach { argb ->
            val selected = argb == selectedArgb
            Box(Modifier.size(27.dp).clip(CircleShape).background(Color(argb.toLong() and 0xFFFFFFFFL))
                .border(if (selected) 2.dp else 1.dp, if (selected) TextPrimary else TextSecondary.copy(alpha = 0.45f), CircleShape)
                .clickable { onSelected(argb) })
        }
    }
}

@Composable
private fun PopupSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PopupMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = AccentPrimary, fontSize = 13.sp)
    }
}
