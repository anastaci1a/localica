package io.github.anastaci1a.localica.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.github.anastaci1a.localica.diagnostics.Diagnostics

/**
 * Settings -> About and licenses -> Export diagnostics (#535 b). The explanation dialog states what the
 * file holds; sharing is always the user's choice in the Android share sheet.
 */
@Composable
internal fun DiagnosticsExportAction() {
    var open by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    SettingsEntry("Export diagnostics") { ActionTextButton(onClick = { open = true }) { Text("Export diagnostics") } }
    FrameStatsActions()
    if (open) GlassUnanchoredPopup("text button; anchor tracked in #781") { GlassAlertDialog(onDismissRequest = { open = false }, title = { Text("Export diagnostics") },
        text = {
            Text("Saves the app version, device model, Android version and the last ${Diagnostics.ring.size} recorded pinch and " +
                "transition events and any opt-in frame capture to a text file, then opens the share sheet. Nothing is sent unless you choose where to share it.",
                style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = { ActionTextButton(onClick = {
            open = false
            failed = runCatching { io.github.anastaci1a.localica.diagnostics.DiagnosticsExport.export(context) }.isFailure
        }) { Text("Export") } },
        dismissButton = { ActionTextButton(onClick = { open = false }) { Text("Cancel") } }) }
    if (failed) GlassUnanchoredPopup("failure notice") { GlassAlertDialog(onDismissRequest = { failed = false }, title = { Text("Could not export") },
        text = { Text("The diagnostics file could not be created or shared.") },
        confirmButton = { ActionTextButton(onClick = { failed = false }) { Text("Close") } }) }
}

@Composable
private fun FrameStatsActions() {
    val context = LocalContext.current
    val activity = remember(context) {
        var current = context
        while (current is android.content.ContextWrapper && current !is android.app.Activity) current = current.baseContext
        current as? android.app.Activity
    }
    val recording by io.github.anastaci1a.localica.diagnostics.FrameStats.recording
    if (activity != null) {
        Text("Record up to two minutes of frame timings while you repeat an action. Stop here, then export diagnostics. No song names or search text are captured.")
        SettingsEntry("Frame capture") { ActionTextButton(onClick = {
            if (recording) io.github.anastaci1a.localica.diagnostics.FrameStats.stop()
            else io.github.anastaci1a.localica.diagnostics.FrameStats.start(activity)
        }) { Text(if (recording) "Stop frame capture" else "Start frame capture") } }
    }
}
