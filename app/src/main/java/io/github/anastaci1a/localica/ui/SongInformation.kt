package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.editing.EditField
import io.github.anastaci1a.localica.library.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongInformation(track: Track, onDismiss: () -> Unit, onEdit: () -> Unit, onAction: (PlayerAction) -> Unit) {
    var viewingArtwork by remember(track.uri) { mutableStateOf(false) }
    GlassDialog(onDismiss, GlassPlacement.Sheet) {
        Column(Modifier.fillMaxWidth().controlVerticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AlbumArtwork(track.uri, Modifier.size(144.dp).align(androidx.compose.ui.Alignment.CenterHorizontally)
                .ownButtonDrag().clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "View artwork",
                    interactionSource = null, indication = null) { viewingArtwork = true })
            Text(track.title, style = MaterialTheme.typography.headlineSmall)
            Text(android.net.Uri.decode(track.fileName)); Text(android.net.Uri.decode(track.relativePath), style = MaterialTheme.typography.bodySmall)
            Text(android.net.Uri.decode(track.uri), style = MaterialTheme.typography.bodySmall)
            Text("Duration: " + formatTime(track.durationMs))
            track.modifiedAt?.let { Text("Modified: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))) }
            track.addedAt?.let { Text("Added to library: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))) }
            Text("Qualified plays: ${track.playCount}")
            EditField.entries.forEach { field -> if (field.value(track).isNotBlank()) Text(field.label + ": " + field.value(track)) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Explore", style = MaterialTheme.typography.titleSmall)
            listOf(PlayerAction.Album, PlayerAction.Artist, PlayerAction.AlbumArtist, PlayerAction.Folder).forEach { action ->
                GlassActionButton(action.icon, action.label, Modifier.fillMaxWidth()) { onDismiss(); onAction(action) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row { ActionTextButton(onClick = { onDismiss(); onEdit() }) { Text("Edit tags") }
                ActionTextButton(onClick = onDismiss) { Text("Done") } }
        }
    }
    if (viewingArtwork) ArtworkViewer(track.uri) { viewingArtwork = false }
}
