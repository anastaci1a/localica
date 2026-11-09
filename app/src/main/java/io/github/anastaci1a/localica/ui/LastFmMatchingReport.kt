package io.github.anastaci1a.localica.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.github.anastaci1a.localica.diagnostics.LastFmMatchingExport
import io.github.anastaci1a.localica.lastfm.SavedLastFm
import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LastFmMatchingReportAction(saved: SavedLastFm, library: List<Track>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    SettingsEntry("Export matching report") {
        GlassActionButton(AppIcon.MenuShare, "Export matching report", enabled = saved.snapshot != null && !pending) {
            pending = true; failed = false
            scope.launch {
                try { context.startActivity(LastFmMatchingExport.prepare(context, saved, library)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { pending = false }
            }
        }
    }
    Text("Shares song metadata, candidate scores and reasons as JSON. Nothing is sent until you choose where to share it.",
        style = MaterialTheme.typography.bodySmall)
    if (failed) Text("Could not create or share the matching report. Try again.", color = MaterialTheme.colorScheme.error)
}
