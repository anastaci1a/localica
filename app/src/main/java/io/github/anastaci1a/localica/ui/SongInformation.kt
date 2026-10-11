package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.editing.EditField
import io.github.anastaci1a.localica.library.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongInformation(track: Track, onDismiss: () -> Unit, onEdit: () -> Unit, onAction: (PlayerAction) -> Unit) {
    GlassDialog(onDismiss, GlassPlacement.Sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AlbumArtwork(track.uri, Modifier.size(144.dp))
            Text(track.title, style = MaterialTheme.typography.headlineSmall)
            Text(track.fileName); Text(track.relativePath, style = MaterialTheme.typography.bodySmall)
            Text(track.uri, style = MaterialTheme.typography.bodySmall)
            Text("Duration: " + formatTime(track.durationMs))
            track.modifiedAt?.let { Text("Modified: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))) }
            track.addedAt?.let { Text("Added to library: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))) }
            Text("Qualified plays: ${track.playCount}")
            EditField.entries.forEach { field -> if (field.value(track).isNotBlank()) Text(field.label + ": " + field.value(track)) }
            FlowRow { listOf(PlayerAction.Album, PlayerAction.Artist, PlayerAction.AlbumArtist, PlayerAction.Folder).forEach {
                TextButton(onClick = { onDismiss(); onAction(it) }) { Text(it.label) }
            } }
            Row { TextButton(onClick = { onDismiss(); onEdit() }) { Text("Edit tags") }
                TextButton(onClick = onDismiss) { Text("Done") } }
        }
    }
}
