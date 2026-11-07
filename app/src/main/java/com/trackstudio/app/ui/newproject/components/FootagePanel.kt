package com.trackstudio.app.ui.newproject.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { videoUri ->
        videoUri ?: return@rememberLauncherForActivityResult
        onEvent(NewProjectEvent.VideoSelected(videoUri))
    }

    val folderPicker =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.OpenDocumentTree()
        ) { folderUri ->

            folderUri ?: return@rememberLauncherForActivityResult

            onEvent(
                NewProjectEvent.FramesFolderSelected(
                    folderUri
                )
            )
        }

    Panel(
        title = "Footage Import",
        modifier = modifier
    ) {

        Text(
            "Import a video directly or select a folder containing an image sequence. "
                    + "Video frames are decoded into project storage; optional playback "
                    + "proxies are generated as a separate stage.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(
            Modifier.height(14.dp)
        )

        DesktopButton(
            text = when (state.importPhase) {
                ImportPhase.IDLE, ImportPhase.FAILED -> "Select Frames Folder..."
                ImportPhase.PROBING -> "Reading Frames..."
                ImportPhase.EXTRACTING_VIDEO -> "Extracting Video Frames..."
                ImportPhase.PROBED -> "Replace Frames Folder..."
            },
            enabled = state.importPhase != ImportPhase.PROBING &&
                state.importPhase != ImportPhase.EXTRACTING_VIDEO,
            onClick = { folderPicker.launch(null) }
        )
        Spacer(Modifier.height(8.dp))
        DesktopButton(
            text = "Import Video...",
            enabled = state.importPhase != ImportPhase.PROBING &&
                state.importPhase != ImportPhase.EXTRACTING_VIDEO,
            onClick = { videoPicker.launch(arrayOf("video/*")) }
        )
        if (state.importPhase == ImportPhase.PROBING ||
            state.importPhase == ImportPhase.EXTRACTING_VIDEO) {
            DesktopButton(text = "Cancel Import", onClick = {
                onEvent(NewProjectEvent.CancelImport)
            })
        }

        when (state.importPhase) {

            ImportPhase.PROBING ->
                ProbingProgress(state)

            ImportPhase.EXTRACTING_VIDEO ->
                ExtractionProgress(state)

            ImportPhase.PROBED ->
                FootageSummary(state)

            ImportPhase.FAILED ->
                Text(
                    state.importError
                        ?: "Import failed.",
                    color = AccentError,
                    style =
                        MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier.padding(top = 10.dp)
                )

            ImportPhase.IDLE ->
                Unit
        }
    }
}

@Composable
private fun ExtractionProgress(state: NewProjectUiState) {
    val (done, total) = state.extractionProgress
    Column(Modifier.padding(top = 14.dp)) {
        LinearProgressIndicator(
            progress = { if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            if (total > 0) "Decoded $done / $total frames" else "Preparing video decoder...",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (state.extractionCurrentName.isNotBlank()) {
            Text(state.extractionCurrentName, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

@Composable
private fun ProbingProgress(
    state: NewProjectUiState
) {
    val (done, total) =
        state.probingProgress

    Column(
        modifier =
            Modifier.padding(top = 14.dp)
    ) {

        LinearProgressIndicator(
            progress = {
                if (total == 0) {
                    0f
                } else {
                    done.toFloat() / total.toFloat()
                }
            },
            modifier =
                Modifier.fillMaxWidth()
        )

        Text(
            if (total == 0) {
                "Scanning frames..."
            } else {
                "Reading frame ${done + 1} / $total"
            },
            style =
                MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier =
                Modifier.padding(top = 6.dp)
        )

        if (state.probingCurrentName.isNotBlank()) {
            Text(
                state.probingCurrentName,
                style =
                    MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier =
                    Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun FootageSummary(
    state: NewProjectUiState
) {
    val footage = state.footage

    Column(
        modifier =
            Modifier.padding(top = 16.dp),
        verticalArrangement =
            Arrangement.spacedBy(6.dp)
    ) {

        Text(
            "Sequence detected",
            style =
                MaterialTheme.typography.titleSmall
        )

        LabeledValueRow(
            "Pattern",
            footage.detectedPattern
        )

        LabeledValueRow(
            "Frames",
            footage.frameCount.toString()
        )

        LabeledValueRow(
            "Resolution",
            "${footage.width} × ${footage.height}"
        )

        LabeledValueRow(
            "Total Size",
            FileUtils.humanReadableBytes(
                footage.totalSizeBytes
            )
        )

        if (!footage.isResolutionConsistent) {
            Text(
                "Warning: not all frames share the same resolution.",
                color = AccentWarning,
                style =
                    MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier.padding(top = 6.dp)
            )
        }

        if (footage.requiresProxyByDefault) {
            Text(
                "High-resolution footage detected — "
                        + "proxies are recommended. "
                        + "See the Proxy tab.",
                color = TextSecondary,
                style =
                    MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier.padding(top = 6.dp)
            )
        }
    }
}