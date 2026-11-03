package com.trackstudio.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ScatterPlot
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackstudio.app.ui.theme.AccentError
import com.trackstudio.app.ui.theme.AccentPrimary
import com.trackstudio.app.ui.theme.AccentSuccess
import com.trackstudio.app.ui.theme.PanelBorder
import com.trackstudio.app.ui.theme.PanelSurfaceRaised
import com.trackstudio.app.ui.theme.TextPrimary
import com.trackstudio.app.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Right-hand tool panel. Every control here is a placeholder that only
 * keeps its own local state - real tracking tools will replace these in
 * later modules.
 */
@Composable
fun WorkspaceRightPanel(
    modifier: Modifier = Modifier,
    canTrack: Boolean,
    isTracking: Boolean,
    progress: Float,
    onAutoTrack: () -> Unit,
    onCancelTracking: () -> Unit
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PanelCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Build,
                    contentDescription = null,
                    tint = AccentPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Tools",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }
        TrackingToolsCard(canTrack, isTracking, progress, onAutoTrack, onCancelTracking)
        QuickActionsCard()
    }
}

@Composable
private fun TrackingToolsCard(
    canTrack: Boolean,
    isTracking: Boolean,
    progress: Float,
    onAutoTrack: () -> Unit,
    onCancelTracking: () -> Unit
) {
    PanelCard(modifier = Modifier.fillMaxWidth()) {

        CollapsibleSection(
            icon = Icons.Outlined.Build,
            title = "Tracking Tools",
            titleSize = 16
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isTracking) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(progress = progress, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Tracking in progress", color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    }
                    ChromeButton(
                        text = "Stop Tracking",
                        icon = Icons.Outlined.DeleteOutline,
                        onClick = onCancelTracking,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    ChromeButton(
                        text = "Start Auto Tracking",
                        icon = Icons.Outlined.GpsFixed,
                        filled = true,
                        onClick = onAutoTrack,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Automatically detect feature points on the current frame and follow them through the remaining sequence.",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        SectionDivider()

        TrackSettingsSection()

        SectionDivider()

        ReconstructionSection()

        SectionDivider()

        TrackPointsSection()
    }
}

@Composable
private fun TrackSettingsSection() {
    var pointSize by remember { mutableFloatStateOf(0.27f) }
    var sensitivity by remember { mutableFloatStateOf(0.7f) }
    var continuous by remember { mutableStateOf(true) }

    CollapsibleSection(title = "Track Settings") {
        LabeledSlider(
            label = "Point Size",
            valueText = "${(1 + pointSize * 11).roundToInt()} px",
            value = pointSize,
            onValueChange = { pointSize = it }
        )

        LabeledSlider(
            label = "Detection Sensitivity",
            valueText = "${(sensitivity * 100).roundToInt()}%",
            value = sensitivity,
            onValueChange = { sensitivity = it }
        )

        ToggleRow(
            label = "Track Continuously",
            checked = continuous,
            onCheckedChange = { continuous = it }
        )
    }
}

@Composable
private fun ReconstructionSection() {
    var showGrid by remember { mutableStateOf(true) }
    var showFrustum by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(0.47f) }

    CollapsibleSection(
        icon = Icons.Outlined.ViewInAr,
        title = "3D Reconstruction"
    ) {
        ToggleRow(
            label = "Show 3D Grid",
            checked = showGrid,
            onCheckedChange = { showGrid = it }
        )

        ToggleRow(
            label = "Show Camera Frustum",
            checked = showFrustum,
            onCheckedChange = { showFrustum = it }
        )

        LabeledSlider(
            label = "Scene Scale",
            valueText = String.format(Locale.US, "%.1f", 0.1f + scale * 1.9f),
            value = scale,
            onValueChange = { scale = it }
        )
    }
}

@Composable
private fun TrackPointsSection() {
    CollapsibleSection(
        icon = Icons.Outlined.ScatterPlot,
        title = "Track Points"
    ) {
        // Counts stay at zero until real tracking exists.
        PointCountRow("Active Points", 0, AccentSuccess)
        PointCountRow("Lost Points", 0, AccentError)
        PointCountRow("Total Points", 0, AccentPrimary)
    }
}

@Composable
private fun QuickActionsCard() {
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        CollapsibleSection(
            icon = Icons.Outlined.Schedule,
            title = "Quick Actions"
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChromeButton(
                    text = "Reset Track",
                    icon = Icons.Outlined.Refresh,
                    onClick = { },
                    modifier = Modifier.weight(1f)
                )

                ChromeButton(
                    text = "Clear Points",
                    icon = Icons.Outlined.DeleteOutline,
                    onClick = { },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------

@Composable
private fun CollapsibleSection(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    titleSize: Int = 14,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Column(modifier = modifier.fillMaxWidth()) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AccentPrimary,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(Modifier.size(10.dp))
            }

            Text(
                text = title,
                color = TextPrimary,
                fontSize = titleSize.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector =
                    if (expanded) Icons.Outlined.KeyboardArrowUp
                    else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        if (expanded) {
            content()

            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PanelBorder)
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = valueText,
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        ThinSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentPrimary,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = PanelSurfaceRaised,
                uncheckedBorderColor = PanelBorder
            )
        )
    }
}

@Composable
private fun PointCountRow(
    label: String,
    count: Int,
    dotColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )

        Text(
            text = label,
            color = TextPrimary,
            fontSize = 13.sp,
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f)
        )

        Text(
            text = count.toString(),
            color = TextSecondary,
            fontSize = 13.sp
        )
    }
}
