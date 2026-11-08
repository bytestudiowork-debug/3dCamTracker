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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ScatterPlot
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.trackstudio.app.tracking.CameraTrackingProgress
import com.trackstudio.app.tracking.ReconstructionMode
import com.trackstudio.app.viewmodel.CameraTrackingSummary
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Right-hand tool panel. Every control here is a placeholder that only
 * keeps its own local state - real tracking tools will replace these in
 * later modules.
 */
@Composable
fun WorkspaceRightPanel(
    projectName: String,
    fps: Double,
    frameCount: Int,
    isAutoTracking: Boolean,
    trackingProgress: CameraTrackingProgress?,
    trackingError: String?,
    trackingSummary: CameraTrackingSummary?,
    reconstructionMode: ReconstructionMode,
    onReconstructionModeChanged: (ReconstructionMode) -> Unit,
    onAutoTrack: () -> Unit,
    onCancelAutoTrack: () -> Unit,
    onOpen3DTest: () -> Unit,
    onOpenTrackerTools: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember(projectName) { mutableStateOf("Tracking") }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PanelCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InspectorTab(
                    title = "Tracking",
                    icon = Icons.Outlined.GpsFixed,
                    selected = selectedTab == "Tracking",
                    onClick = { selectedTab = "Tracking" },
                    modifier = Modifier.weight(1f)
                )
                InspectorTab(
                    title = "Tools",
                    icon = Icons.Outlined.Build,
                    selected = selectedTab == "Tools",
                    onClick = { selectedTab = "Tools" },
                    modifier = Modifier.weight(1f)
                )
                InspectorTab(
                    title = "Project",
                    icon = Icons.Outlined.FolderOpen,
                    selected = selectedTab == "Project",
                    onClick = { selectedTab = "Project" },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        when (selectedTab) {
            "Tracking" -> TrackingToolsCard(isAutoTracking, trackingProgress, trackingError, trackingSummary, reconstructionMode, onReconstructionModeChanged, onAutoTrack, onCancelAutoTrack)
            "Tools" -> QuickActionsCard(onOpen3DTest, onOpenTrackerTools)
            else -> ProjectSummaryCard(projectName, fps, frameCount)
        }
    }
}

@Composable
private fun InspectorTab(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (selected) AccentPrimary else TextSecondary,
            modifier = Modifier.size(21.dp)
        )
        Text(
            text = title,
            color = if (selected) AccentPrimary else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(AccentPrimary)
            )
        } else {
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun ProjectSummaryCard(
    projectName: String,
    fps: Double,
    frameCount: Int
) {
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        CollapsibleSection(
            icon = Icons.Outlined.ViewInAr,
            title = "Project Details",
            titleSize = 16
        ) {
            ProjectDetailRow("Name", projectName.ifBlank { "Untitled Project" })
            ProjectDetailRow("Frame rate", "${fps.toInt()} FPS")
            ProjectDetailRow("Footage", "$frameCount frames")
        }
    }
}

@Composable
private fun ProjectDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun TrackingToolsCard(
    isAutoTracking: Boolean,
    progress: CameraTrackingProgress?,
    error: String?,
    summary: CameraTrackingSummary?,
    reconstructionMode: ReconstructionMode,
    onReconstructionModeChanged: (ReconstructionMode) -> Unit,
    onAutoTrack: () -> Unit,
    onCancelAutoTrack: () -> Unit
) {
    var detailsExpanded by remember { mutableStateOf(false) }
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReconstructionMode.values().forEach { mode ->
                    Box(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (reconstructionMode == mode) AccentPrimary else PanelSurfaceRaised)
                            .clickable(enabled = !isAutoTracking) { onReconstructionModeChanged(mode) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            mode.label,
                            color = if (reconstructionMode == mode) Color.White else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Box(
                modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (isAutoTracking) PanelSurfaceRaised else AccentPrimary)
                    .clickable(enabled = !isAutoTracking, onClick = onAutoTrack)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(
                        if (isAutoTracking) "Tracking · ${progress?.frameLabel ?: "Preparing"}" else "Auto Track",
                        color = if (isAutoTracking) TextPrimary else Color.White,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                    if (isAutoTracking) {
                        Box(Modifier.fillMaxWidth().padding(top = 5.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(PanelBorder)) {
                            Box(Modifier.fillMaxWidth((progress?.fraction ?: 0f).coerceIn(0f, 1f)).height(3.dp).background(AccentPrimary))
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(progress?.phase ?: "Preparing", color = TextSecondary, fontSize = 9.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            Text(((progress?.fraction ?: 0f) * 100).roundToInt().toString() + "%", color = TextSecondary, fontSize = 9.sp)
                        }
                    }
                }
            }
            if (isAutoTracking) ChromeButton(text = "Cancel Tracking", icon = Icons.Filled.Stop, onClick = onCancelAutoTrack, modifier = Modifier.fillMaxWidth(), height = 40.dp)
            if (error != null) Text(error, color = AccentError, fontSize = 11.sp)
            if (summary != null) {
                Text("${summary.registeredCameras}/${summary.totalFrames} cameras registered · ${summary.landmarkCount} 3D points", color = TextPrimary, fontSize = 11.sp)
                Text("Inlier ratio ${(summary.inlierRatio * 100).roundToInt()}% · estimated intrinsics", color = TextSecondary, fontSize = 10.sp)
                Text(
                    "Mean solve error ${String.format(Locale.US, "%.2f", summary.meanReprojectionErrorPx)} px",
                    color = if (summary.meanReprojectionErrorPx <= 2.0) AccentSuccess else AccentError,
                    fontSize = 11.sp
                )
            }
        }
        SectionDivider()
        Row(Modifier.fillMaxWidth().clickable { detailsExpanded = !detailsExpanded }.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ScatterPlot, null, tint = AccentPrimary, modifier = Modifier.size(19.dp))
            Text("Solve Details", color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
            Icon(if (detailsExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, tint = TextSecondary)
        }
        if (detailsExpanded) {
            ProjectDetailRow("Registered cameras", summary?.registeredCameras?.toString() ?: "—")
            ProjectDetailRow("3D landmarks", summary?.landmarkCount?.toString() ?: "—")
            ProjectDetailRow(
                "Mean reprojection error",
                summary?.let { "${String.format(Locale.US, "%.2f", it.meanReprojectionErrorPx)} px" } ?: "—"
            )
            ProjectDetailRow("Initial pair", summary?.initialPairFrames?.let { "${it.first + 1} – ${it.second + 1}" } ?: "—")
            ProjectDetailRow(
                "Global positioning",
                summary?.let {
                    if (it.globalPositioningApplied) "Applied · ${it.globalPositioningIterations} iterations"
                    else it.globalPositioningStatus
                } ?: "—"
            )
            ProjectDetailRow(
                "Ceres cost",
                summary?.takeIf {
                    it.globalPositioningInitialCost.isFinite() &&
                        it.globalPositioningFinalCost.isFinite()
                }?.let {
                    "${String.format(Locale.US, "%.3g", it.globalPositioningInitialCost)} → ${String.format(Locale.US, "%.3g", it.globalPositioningFinalCost)}"
                } ?: "—"
            )
        }
    }
}

@Composable
private fun TrackingOptionSelector(
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(horizontal = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(PanelSurfaceRaised).clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(selected, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = TextSecondary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelected(option); expanded = false }
                )
            }
        }
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
private fun QuickActionsCard(
    onOpen3DTest: () -> Unit,
    onOpenTrackerTools: () -> Unit
) {
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ToolEntry("Anchor", "Set a world-space XYZ anchor and coordinate axes", Icons.Outlined.GpsFixed, onOpen3DTest)
            ToolEntry("Tracker Tools", "Inspect feature tracks and overlay visibility", Icons.Outlined.ScatterPlot, onOpenTrackerTools)
        }
    }
}

@Composable
private fun ToolEntry(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .background(PanelSurfaceRaised).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextSecondary, fontSize = 10.sp)
        }
        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Open $title", tint = TextSecondary)
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
