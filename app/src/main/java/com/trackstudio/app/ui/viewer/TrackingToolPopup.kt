package com.trackstudio.app.ui.viewer

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
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
    onCubeChanged: (Boolean) -> Unit,
    onTracksChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var dragOffset by remember(title) { mutableStateOf(Offset.Zero) }

    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
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
                    PopupMetric("Projected wireframe lines", verificationLineCount.toString())
                    Text(
                        "This diagnostic cube is projected through the solved camera poses. It is not a physical object track. The current reconstruction uses approximate camera intrinsics and provisional translation scale, so visual alignment is a diagnostic—not a final matchmove guarantee.",
                        color = TextSecondary, fontSize = 11.sp
                    )
                } else {
                    PopupSwitch("Show feature tracks", showTracks, onTracksChanged)
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
