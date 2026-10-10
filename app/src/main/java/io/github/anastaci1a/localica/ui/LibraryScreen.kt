package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.*

@Composable
fun LibraryScreen(state: LibraryUiState, currentTrackId: String?, enabled: Boolean,
                  onTrack: (Track) -> Unit, onSettings: () -> Unit, onQuery: (LibraryQuery) -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize().testTag("library-list"), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "header") {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.local_audio), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.your_library), style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold)
                if (state.snapshot.folders.isNotEmpty()) {
                    val count = state.snapshot.tracks.size
                    val countText = if (count == 1) stringResource(R.string.one_song) else stringResource(R.string.song_count, count)
                    Text("$countText · ${if (state.snapshot.folders.size == 1) state.snapshot.folders.first().name else stringResource(R.string.folder_count, state.snapshot.folders.size)}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (state.snapshot.tracks.isNotEmpty()) QueryControls(state, onQuery)
                LibraryNotice(state)
                if (state.scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.scanning, state.scannedCount), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.loading) {
            item { Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else if (state.snapshot.tracks.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_library), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(if (state.snapshot.folders.isEmpty()) R.string.library_empty else R.string.empty_folder),
                        style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(if (state.snapshot.folders.isEmpty()) R.string.choose_folder_hint else R.string.empty_folder_hint),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.open_settings)) }
                }
            }
        } else {
            if (state.visibleTracks.isEmpty()) item {
                Column(Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.no_matches))
                    TextButton(onClick = { onQuery(LibraryQuery()) }) { Text(stringResource(R.string.reset_filters)) }
                }
            }
            items(state.visibleTracks, key = { it.id }) { track ->
                val selected = currentTrackId == track.id
                Row(Modifier.fillMaxWidth().semantics { this.selected = selected }
                    .clickable(enabled = enabled, role = Role.Button) { onTrack(track) }
                    .padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer) {
                        Icon(painterResource(if (selected) R.drawable.ic_play else R.drawable.ic_music), null,
                            Modifier.padding(12.dp).size(24.dp), tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface)
                        Text(track.artist ?: track.fileName, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(formatTime(track.durationMs), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(start = 88.dp, end = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun QueryControls(state: LibraryUiState, onQuery: (LibraryQuery) -> Unit) {
    var foldersOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    OutlinedTextField(value = state.query.text, onValueChange = { onQuery(state.query.copy(text = it)) },
        label = { Text(stringResource(R.string.search_library)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { foldersOpen = true }) {
                Text(state.snapshot.folders.firstOrNull { it.uri == state.query.folderUri }?.name ?: stringResource(R.string.all_folders),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = foldersOpen, onDismissRequest = { foldersOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.all_folders)) },
                    onClick = { onQuery(state.query.copy(folderUri = null)); foldersOpen = false })
                state.snapshot.folders.forEach { folder ->
                    DropdownMenuItem(text = { Text(folder.name) },
                        onClick = { onQuery(state.query.copy(folderUri = folder.uri)); foldersOpen = false })
                }
            }
        }
        Box {
            TextButton(onClick = { sortOpen = true }) { Text(stringResource(R.string.sort_by, stringResource(state.query.sort.label()))) }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                TrackSort.entries.forEach { sort ->
                    DropdownMenuItem(text = { Text(stringResource(sort.label())) },
                        onClick = { onQuery(state.query.copy(sort = sort)); sortOpen = false })
                }
            }
        }
    }
    Row {
        TextButton(onClick = { onQuery(state.query.copy(ascending = !state.query.ascending)) }) {
            Text(stringResource(if (state.query.ascending) R.string.ascending else R.string.descending))
        }
        TextButton(onClick = { onQuery(LibraryQuery()) }) { Text(stringResource(R.string.reset_filters)) }
    }
}

private fun TrackSort.label(): Int = when (this) {
    TrackSort.Title -> R.string.sort_title
    TrackSort.Artist -> R.string.sort_artist
    TrackSort.Album -> R.string.sort_album
    TrackSort.AlbumArtist -> R.string.sort_album_artist
    TrackSort.Folder -> R.string.sort_path
    TrackSort.Year -> R.string.sort_year
    TrackSort.Duration -> R.string.sort_duration
}
