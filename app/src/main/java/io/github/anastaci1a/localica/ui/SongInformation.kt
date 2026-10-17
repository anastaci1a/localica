package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.editing.EditField
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SongInformation(track: Track, onDismiss: () -> Unit, onEdit: () -> Unit,
    folders: List<FolderSelection> = emptyList(),
    rootNameResolver: suspend (android.content.Context, List<FolderSelection>) -> List<SongRootName> = ::resolveSongRootNames,
    onAction: (PlayerAction) -> Unit) {
    val context = LocalContext.current.applicationContext
    // The dialog body lives in a retained composition. Give it a stable observable input,
    // rather than capturing a replaced producer State or a derived map from the caller.
    val currentFolders by rememberUpdatedState(folders)
    val currentRootNameResolver by rememberUpdatedState(rootNameResolver)
    var viewingArtwork by remember(track.uri) { mutableStateOf(false) }
    GlassAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("song-information"),
        title = { Text("Song information") },
        dismissButton = { ActionTextButton(onClick = { onDismiss(); onEdit() }) { Text("Edit tags") } },
        confirmButton = { ActionTextButton(onClick = onDismiss) { Text("Done") } },
        text = {
            val roots = currentFolders
            val rootNames = key(roots, context) {
                produceState(roots.map { SongRootName(it.uri, listOf(it.name)) }) {
                    if (roots.size > 1) value = withContext(Dispatchers.IO) { currentRootNameResolver(context, roots) }
                }
            }
            val labels = remember(rootNames.value) { songRootLabels(rootNames.value) }
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxWidth().testTag("song-information-body")
                .scrollEdgeFade(scroll).controlVerticalScroll(scroll)) {
                AlbumArtwork(track.uri, Modifier.size(112.dp).align(Alignment.CenterHorizontally)
                    .ownButtonDrag().clickable(role = Role.Button, onClickLabel = "View artwork",
                        interactionSource = null, indication = null,
                        onClick = controlActivation { viewingArtwork = true }))
                SongInformationRow("File name", track.fileName, R.drawable.ic_music)
                val location = songInformationLocation(track)
                val folderAction = PlayerAction.Folder.takeIf { location != null && roots.any { it.uri == location.folderUri } }
                SongInformationRow("Location", songLocationText(track, labels), R.drawable.ic_folder, folderAction) {
                    onDismiss(); onAction(it)
                }
                SongInformationDivider()
                EditField.entries.forEach { field ->
                    val value = field.value(track)
                    if (value.isNotBlank()) {
                        val action = when (field) {
                            EditField.Album -> PlayerAction.Album
                            EditField.Artist -> PlayerAction.Artist
                            EditField.AlbumArtist -> PlayerAction.AlbumArtist
                            else -> null
                        }
                        val icon = when (field) {
                            EditField.Album -> R.drawable.ic_albums
                            EditField.Artist, EditField.AlbumArtist, EditField.Composer, EditField.Lyricist -> R.drawable.ic_artist
                            EditField.Comment -> R.drawable.ic_lyrics
                            else -> R.drawable.ic_music
                        }
                        SongInformationRow(if (field == EditField.Title) "Title" else field.label, value, icon, action) {
                            onDismiss(); onAction(it)
                        }
                    }
                }
                track.durationMs?.let {
                    SongInformationDivider()
                    SongInformationRow("Duration", formatTime(it), R.drawable.ic_info)
                }
                SongInformationDivider()
                track.addedAt?.let { SongInformationRow("Added to library", informationDate(it), R.drawable.ic_library) }
                track.modifiedAt?.let { SongInformationRow("Modified", informationDate(it), R.drawable.ic_info) }
                SongInformationRow("Qualified plays", track.playCount.toString(), R.drawable.ic_play)
            }
        })
    if (viewingArtwork) ArtworkViewer(track.uri) { viewingArtwork = false }
}

/** A single full-width action owner; labels and values share the same leading icon inset. */
@Composable
internal fun SongInformationRow(label: String, value: String, icon: Int, action: PlayerAction? = null,
    onAction: (PlayerAction) -> Unit = {}) {
    val activation = controlActivation { if (action != null) onAction(action) }
    val target = if (action == null) Modifier else Modifier.ownButtonDrag().clickable(
        role = Role.Button, onClickLabel = action.label, onClick = activation)
    ListItem(modifier = Modifier.fillMaxWidth().testTag("song-information-$label").then(target),
        headlineContent = { Text(label, style = MaterialTheme.typography.labelLarge) },
        supportingContent = { Text(value, style = MaterialTheme.typography.bodyMedium) },
        leadingContent = { Icon(painterResource(icon), null, Modifier.size(24.dp)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}

@Composable
private fun SongInformationDivider() {
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = LocalContentColor.current.copy(alpha = .16f))
}

private fun informationDate(value: Long) = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(value))
