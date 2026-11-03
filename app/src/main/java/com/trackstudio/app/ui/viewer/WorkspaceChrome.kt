package com.trackstudio.app.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.ScatterPlot
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackstudio.app.ui.theme.AccentPrimary
import com.trackstudio.app.ui.theme.PanelBackground
import com.trackstudio.app.ui.theme.PanelBorder
import com.trackstudio.app.ui.theme.PanelSurface
import com.trackstudio.app.ui.theme.SelectedItemBackground
import com.trackstudio.app.ui.theme.TextDisabled
import com.trackstudio.app.ui.theme.TextPrimary
import com.trackstudio.app.ui.theme.TextSecondary

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

/**
 * App bar of the tracking workspace. Everything except the project name
 * and the close-project menu entry is a placeholder for later modules.
 */
@Composable
fun WorkspaceTopBar(
    projectName: String,
    compact: Boolean,
    showTitle: Boolean,
    showPanelToggle: Boolean,
    onTogglePanel: () -> Unit,
    onCloseProject: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 48.dp else 60.dp)
            .background(PanelBackground)
            .padding(horizontal = if (compact) 8.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        // App identity and active project.
        Box(
            modifier = Modifier
                .size(if (compact) 30.dp else 36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SelectedItemBackground),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.ViewInAr,
                contentDescription = null,
                tint = AccentPrimary,
                modifier = Modifier.size(if (compact) 18.dp else 22.dp)
            )
        }

        Column(
            modifier = Modifier.padding(start = 10.dp),
            verticalArrangement = Arrangement.Center
        ) {
            if (showTitle) {
                Text(
                    text = "3D Camera Tracker",
                    color = TextPrimary,
                    fontSize = if (compact) 13.sp else 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = projectName.ifBlank { "Untitled Project" },
                    color = TextSecondary,
                    fontSize = if (compact) 10.sp else 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = if (compact) 120.dp else 200.dp)
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "Select project",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (showPanelToggle) {
            ChromeIconButton(
                icon = Icons.Outlined.Tune,
                contentDescription = "Tracking panel",
                onClick = onTogglePanel
            )
        }

        // Placeholders
        ChromeIconButton(Icons.Outlined.Undo, "Undo", onClick = { })
        ChromeIconButton(Icons.Outlined.Redo, "Redo", onClick = { }, tint = TextDisabled)
        ChromeIconButton(Icons.Outlined.Settings, "Settings", onClick = { })

        Spacer(Modifier.width(if (compact) 4.dp else 10.dp))

        if (compact) {
            ChromeIconButton(
                icon = Icons.Outlined.FileUpload,
                contentDescription = "Export",
                onClick = { },
                tint = Color.White,
                background = AccentPrimary
            )
        } else {
            ChromeButton(
                text = "Export",
                icon = Icons.Outlined.FileUpload,
                filled = true,
                onClick = { },
                modifier = Modifier.width(112.dp)
            )
        }

        Box {
            ChromeIconButton(
                icon = Icons.Outlined.MoreVert,
                contentDescription = "More",
                onClick = { menuOpen = true }
            )

            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Close project") },
                    onClick = {
                        menuOpen = false
                        onCloseProject()
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Left sidebar
// ---------------------------------------------------------------------------

enum class SidebarMode { FULL, RAIL }

private data class NavItem(
    val label: String,
    val icon: ImageVector
)

private val trackingItems = listOf(
    NavItem("Track", Icons.Outlined.MyLocation),
    NavItem("Adjust", Icons.Outlined.Tune),
    NavItem("3D Scene", Icons.Outlined.ViewInAr),
    NavItem("Camera", Icons.Outlined.PhotoCamera)
)

private val editItems = listOf(
    NavItem("Points", Icons.Outlined.ScatterPlot),
    NavItem("Mask", Icons.Outlined.Brush),
    NavItem("Stabilize", Icons.Outlined.ShowChart)
)

private val viewItems = listOf(
    NavItem("2D / 3D", Icons.Outlined.ViewInAr),
    NavItem("Grid", Icons.Outlined.GridOn),
    NavItem("Axes", Icons.Outlined.OpenWith)
)

/**
 * Tool navigation. Every entry is a placeholder: tapping only moves the
 * highlight. Full labelled list on wide screens, icon rail on narrow ones.
 */
@Composable
fun WorkspaceSidebar(
    mode: SidebarMode,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val full = mode == SidebarMode.FULL

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(if (full) 188.dp else 60.dp)
            .background(PanelSurface)
            .padding(vertical = 8.dp, horizontal = if (full) 10.dp else 6.dp)
    ) {

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            NavRow(NavItem("Home", Icons.Outlined.Home), full, selected == "Home") {
                onSelect("Home")
            }

            NavSection("TRACKING", trackingItems, full, selected, onSelect)
            NavSection("EDIT", editItems, full, selected, onSelect)
            NavSection("VIEW", viewItems, full, selected, onSelect)
        }

        Box(
            modifier = Modifier
                .padding(vertical = 6.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(PanelBorder)
        )

        NavRow(NavItem("Help", Icons.Outlined.HelpOutline), full, selected == "Help") {
            onSelect("Help")
        }
    }
}

@Composable
private fun NavSection(
    title: String,
    items: List<NavItem>,
    full: Boolean,
    selected: String,
    onSelect: (String) -> Unit
) {
    if (full) {
        Text(
            text = title,
            color = TextDisabled,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 10.dp, top = 16.dp, bottom = 6.dp)
        )
    } else {
        Box(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(PanelBorder)
        )
    }

    items.forEach { item ->
        NavRow(item, full, selected == item.label) {
            onSelect(item.label)
        }
    }
}

@Composable
private fun NavRow(
    item: NavItem,
    full: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(if (isSelected) SelectedItemBackground else Color.Transparent)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            if (full) Arrangement.Start else Arrangement.Center
    ) {
        if (isSelected && full) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(24.dp)
                    .background(AccentPrimary)
            )
        }

        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = if (isSelected) AccentPrimary else TextSecondary,
            modifier = Modifier
                .padding(start = if (full) 11.dp else 0.dp)
                .size(22.dp)
        )

        if (full) {
            Text(
                text = item.label,
                color = if (isSelected) TextPrimary else TextSecondary,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 14.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom workspace navigation
// ---------------------------------------------------------------------------

@Composable
fun WorkspaceBottomBar(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        NavItem("Home", Icons.Outlined.Home),
        NavItem("Tracking", Icons.Outlined.MyLocation),
        NavItem("Adjust", Icons.Outlined.Tune),
        NavItem("3D Scene", Icons.Outlined.ViewInAr),
        NavItem("Camera", Icons.Outlined.PhotoCamera),
        NavItem("Tools", Icons.Outlined.Build),
        NavItem("More", Icons.Outlined.MoreVert)
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
            .background(PanelBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            val active = selected == item.label
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) SelectedItemBackground else Color.Transparent)
                    .clickable { onSelect(item.label) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = if (active) AccentPrimary else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = item.label,
                    color = if (active) TextPrimary else TextSecondary,
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }
        }
    }
}
