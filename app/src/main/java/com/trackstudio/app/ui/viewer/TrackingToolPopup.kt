package com.trackstudio.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
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
    anchorSegmentCount: Int,
    showAnchor: Boolean,
    anchorX: Double,
    anchorY: Double,
    anchorZ: Double,
    defaultAnchorX: Double,
    defaultAnchorY: Double,
    defaultAnchorZ: Double,
    anchorAxisLength: Double,
    anchorLineWidthDp: Float,
    onAnchorChanged: (Boolean) -> Unit,
    onAnchorPositionChanged: (Double, Double, Double) -> Unit,
    onAnchorAxisLengthChanged: (Double) -> Unit,
    onAnchorLineWidthChanged: (Float) -> Unit,
    onResetAnchor: () -> Unit,
    showTracks: Boolean,
    markerColorArgb: Int,
    markerSizeDp: Float,
    markerOutline: Boolean,
    onMarkerColorChanged: (Int) -> Unit,
    onMarkerSizeChanged: (Float) -> Unit,
    onMarkerOutlineChanged: (Boolean) -> Unit,
    onTracksChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var dragOffset by remember(title) { mutableStateOf(Offset.Zero) }
    var xText by remember(title, anchorX) { mutableStateOf(anchorX.toString()) }
    var yText by remember(title, anchorY) { mutableStateOf(anchorY.toString()) }
    var zText by remember(title, anchorZ) { mutableStateOf(anchorZ.toString()) }

    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(14.dp))
                .background(PanelBackground)
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
                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = TextSecondary,
                    modifier = Modifier.size(22.dp).clickable(onClick = onDismiss))
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (title == "Anchor") {
                    PopupSwitch("Show world anchor", showAnchor, onAnchorChanged)
                    Text("WORLD POSITION", color = TextSecondary, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = xText, onValueChange = {
                                xText = it
                                it.toDoubleOrNull()?.let { x ->
                                    onAnchorPositionChanged(x, yText.toDoubleOrNull() ?: anchorY, zText.toDoubleOrNull() ?: anchorZ)
                                }
                            },
                            label = { Text("X") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = yText, onValueChange = {
                                yText = it
                                it.toDoubleOrNull()?.let { y ->
                                    onAnchorPositionChanged(xText.toDoubleOrNull() ?: anchorX, y, zText.toDoubleOrNull() ?: anchorZ)
                                }
                            },
                            label = { Text("Y") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = zText, onValueChange = {
                                zText = it
                                it.toDoubleOrNull()?.let { z ->
                                    onAnchorPositionChanged(xText.toDoubleOrNull() ?: anchorX, yText.toDoubleOrNull() ?: anchorY, z)
                                }
                            },
                            label = { Text("Z") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text("Axis length · ${String.format(java.util.Locale.US, "%.3f", anchorAxisLength)} units",
                        color = TextPrimary, fontSize = 12.sp)
                    Slider(
                        value = anchorAxisLength.toFloat().coerceIn(0.001f, 1f),
                        onValueChange = { onAnchorAxisLengthChanged(it.toDouble()) },
                        valueRange = 0.001f..1f
                    )
                    Text("Axis thickness · ${String.format(java.util.Locale.US, "%.1f", anchorLineWidthDp)} dp",
                        color = TextPrimary, fontSize = 12.sp)
                    Slider(
                        value = anchorLineWidthDp.coerceIn(1f, 5f),
                        onValueChange = onAnchorLineWidthChanged,
                        valueRange = 1f..5f
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("X red · Y green · Z blue", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("$anchorSegmentCount axes", color = AccentPrimary, fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        xText = defaultAnchorX.toString()
                        yText = defaultAnchorY.toString()
                        zText = defaultAnchorZ.toString()
                        onResetAnchor()
                    }) { Text("Reset to detected anchor", color = AccentPrimary) }
                    Text(
                        "The anchor is a fixed point in the reconstructed world and is projected through each solved camera. XYZ values use relative scene units; absolute scale is not calibrated yet.",
                        color = TextSecondary, fontSize = 11.sp
                    )
                } else {
                    PopupSwitch("Show feature tracks", showTracks, onTracksChanged)
                    Text("Marker colour", color = TextPrimary, fontSize = 13.sp)
                    MarkerColourSelector(markerColorArgb, onMarkerColorChanged)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Marker size", color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("${String.format(java.util.Locale.US, "%.1f", markerSizeDp)} dp", color = AccentPrimary, fontSize = 11.sp)
                    }
                    Slider(
                        value = markerSizeDp.coerceIn(2f, 5.5f),
                        onValueChange = onMarkerSizeChanged,
                        valueRange = 2f..5.5f
                    )
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
private fun MarkerColourSelector(selectedArgb: Int, onSelected: (Int) -> Unit) {
    val colours = listOf(0xFF00E5FF.toInt(), 0xFFFFD740.toInt(), 0xFF76FF03.toInt(), 0xFFFF4081.toInt(), 0xFFB388FF.toInt(), 0xFFFFFFFF.toInt())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        colours.forEach { argb ->
            val selected = argb == selectedArgb
            Box(
                Modifier.size(27.dp).clip(CircleShape)
                    .background(Color(argb.toLong() and 0xFFFFFFFFL))
                    .border(if (selected) 2.dp else 1.dp, if (selected) TextPrimary else TextSecondary.copy(alpha = 0.45f), CircleShape)
                    .clickable { onSelected(argb) }
            )
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
