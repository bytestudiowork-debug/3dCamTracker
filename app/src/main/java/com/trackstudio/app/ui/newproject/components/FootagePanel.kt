package com.trackstudio.app.ui.newproject.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trackstudio.app.ui.common.DesktopButton
import com.trackstudio.app.ui.common.LabeledValueRow
import com.trackstudio.app.ui.common.Panel
import com.trackstudio.app.ui.theme.AccentError
import com.trackstudio.app.ui.theme.AccentWarning
import com.trackstudio.app.ui.theme.TextSecondary
import com.trackstudio.app.util.FileUtils
import com.trackstudio.app.viewmodel.ImportPhase
import com.trackstudio.app.viewmodel.NewProjectEvent
import com.trackstudio.app.viewmodel.NewProjectUiState

@Composable
fun FootagePanel(
    state: NewProjectUiState,
    onEvent: (NewProjectEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    // ACTION_OPEN_DOCUMENT (not GET_CONTENT) - we take a real, revocable
    // grant on each file and only ever accept image/* so the picker itself
    // filters out non-image files before Kotlin even sees them.
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onEvent(NewProjectEvent.FramesSelected(uris))
        }
    }

    Panel(title = "Footage Import", modifier = modifier) {
        Text(
            "Image sequence only - select every frame of one shot at once.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))

        DesktopButton(
            text = when (state.importPhase) {
                ImportPhase.IDLE, ImportPhase.FAILED -> "Import Image Sequence..."
                ImportPhase.PROBING -> "Reading Frames..."
                ImportPhase.PROBED -> "Replace Sequence..."
            },
            enabled = state.importPhase != ImportPhase.PROBING,
            onClick = { pickerLauncher.launch(arrayOf("image/*")) }
        )

        when (state.importPhase) {
            ImportPhase.PROBING -> ProbingProgress(state)
            ImportPhase.PROBED -> FootageSummary(state)
            ImportPhase.FAILED -> Text(
                state.importError ?: "Import failed.",
                color = AccentError,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp)
            )
            ImportPhase.IDLE -> Unit
        }
    }
}

@Composable
private fun ProbingProgress(state: NewProjectUiState) {
    val (done, total) = state.probingProgress
    Column(modifier = Modifier.padding(top = 12.dp)) {
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else done.toFloat() / total },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Reading frame $done / $total - ${state.probingCurrentName}",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun FootageSummary(state: NewProjectUiState) {
    val footage = state.footage
    Column(
        modifier = Modifier.padding(top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LabeledValueRow("Pattern", footage.detectedPattern)
        LabeledValueRow("Frames", footage.frameCount.toString())
        LabeledValueRow("Resolution", "${footage.width} x ${footage.height}")
        LabeledValueRow("Total Size", FileUtils.humanReadableBytes(footage.totalSizeBytes))

        if (!footage.isResolutionConsistent) {
            Text(
                "Warning: not all frames share the same resolution.",
                color = AccentWarning,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (footage.requiresProxyByDefault) {
            Text(
                "High-resolution footage detected - proxies recommended (see Proxy tab).",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
