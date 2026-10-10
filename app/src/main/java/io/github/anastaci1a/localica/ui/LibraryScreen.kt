package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.queue.QueueController
import io.github.anastaci1a.localica.playlist.*

@Composable
fun LibraryScreen(state: LibraryUiState, currentTrackId: String?, enabled: Boolean,
    onTrack: (Track) -> Unit, onSettings: () -> Unit, onQuery: (LibraryQuery) -> Unit = {},
    onView: (SongListView) -> Unit = {}, onGroup: (String?) -> Unit = {},
    queues: QueueController? = null, playlists: PlaylistController? = null,
    playlistState: PlaylistState = PlaylistState()) {
    var viewMenu by remember { mutableStateOf(false) }
    var selection by remember(state.view, state.groupId, state.query) { mutableStateOf(emptySet<String>()) }
    val rows = remember(state.visibleTracks) { state.visibleTracks.map { SongRow(it.id, it) } }
    LazyColumn(Modifier.fillMaxSize().testTag("library-list"), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.your_library), style = MaterialTheme.typography.headlineMedium)
                val count = state.snapshot.tracks.size
                val songs = if (count == 1) stringResource(R.string.one_song) else stringResource(R.string.song_count, count)
                val folders = state.snapshot.folders.singleOrNull()?.name ?: stringResource(R.string.folder_count, state.snapshot.folders.size)
                Text("$songs · $folders")
                Box {
                    TextButton(onClick = { viewMenu = true }) { Text("View: ${state.view.label}") }
                    DropdownMenu(viewMenu, { viewMenu = false }) {
                        SongListView.entries.filter { it != SongListView.Playlists && it != SongListView.Search }.forEach { view ->
                            DropdownMenuItem(text = { Text(view.label) }, onClick = { onView(view); viewMenu = false })
                        }
                    }
                }
                if (state.groupId != null) {
                    TextButton(onClick = { onGroup(null) }) { Text("Back to ${state.view.label.lowercase()}") }
                    Text(state.groups.firstOrNull { it.id == state.groupId }?.title ?: "No matching group")
                }
                if (state.snapshot.tracks.isNotEmpty()) QueryControls(state.query, state.snapshot.folders, state.view, onQuery)
                if (rows.isNotEmpty()) SelectionActions(rows, selection, { selection = it }, queues, playlists, playlistState)
                LibraryNotice(state)
                if (state.loading || state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.scanning) Text(stringResource(R.string.scanning, state.scannedCount))
                if (!state.loading && state.snapshot.tracks.isEmpty()) {
                    Text(stringResource(if (state.snapshot.folders.isEmpty()) R.string.library_empty else R.string.empty_folder))
                    Text(stringResource(if (state.snapshot.folders.isEmpty()) R.string.choose_folder_hint else R.string.empty_folder_hint))
                    TextButton(onClick = onSettings) { Text(stringResource(R.string.open_settings)) }
                }
            }
        }
        if (state.view != SongListView.Songs && state.groupId == null) {
            if (state.groups.isEmpty() && !state.loading) item { Text("No matching groups", Modifier.padding(16.dp)) }
            items(state.groups, key = { it.id }) { group ->
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onGroup(group.id) }.padding(20.dp)) {
                    Text(group.title, style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(group.subtitle, "${group.tracks.size} songs").joinToString(" · "))
                }
            }
        } else {
            if (rows.isEmpty() && state.snapshot.tracks.isNotEmpty()) item { Text(stringResource(R.string.no_matches), Modifier.padding(16.dp)) }
            items(rows, key = { it.id }) { row ->
                SongItem(row, currentTrackId == row.track.id, enabled, selection.isNotEmpty(), row.id in selection,
                    onSelect = { selection = if (row.id in selection) selection - row.id else selection + row.id },
                    onPlay = { onTrack(row.track) })
            }
        }
    }
}

@Composable
internal fun QueryControls(query: LibraryQuery, folders: List<FolderSelection>, view: SongListView, onQuery: (LibraryQuery) -> Unit) {
    var foldersOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    OutlinedTextField(value = query.text, onValueChange = { onQuery(query.copy(text = it)) },
        label = { Text(stringResource(R.string.search_library)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { foldersOpen = true }) {
                Text(folders.firstOrNull { it.uri == query.folderUri }?.name ?: stringResource(R.string.all_folders),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = foldersOpen, onDismissRequest = { foldersOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.all_folders)) },
                    onClick = { onQuery(query.copy(folderUri = null)); foldersOpen = false })
                folders.forEach { folder ->
                    DropdownMenuItem(text = { Text(folder.name) },
                        onClick = { onQuery(query.copy(folderUri = folder.uri)); foldersOpen = false })
                }
            }
        }
        Box {
            TextButton(onClick = { sortOpen = true }) { Text(stringResource(R.string.sort_by, stringResource(query.sort.label()))) }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                TrackSort.entries.filter { it != TrackSort.Manual || view == SongListView.Playlists }.forEach { sort ->
                    DropdownMenuItem(text = { Text(stringResource(sort.label())) },
                        onClick = { onQuery(query.copy(sort = sort)); sortOpen = false })
                }
            }
        }
    }
    Row {
        TextButton(onClick = { onQuery(query.copy(ascending = !query.ascending)) }) {
            Text(stringResource(if (query.ascending) R.string.ascending else R.string.descending))
        }
        TextButton(onClick = { onQuery(LibraryViews.defaults(view)) }) { Text(stringResource(R.string.reset_filters)) }
    }
}

private fun TrackSort.label(): Int = when (this) {
    TrackSort.Manual -> R.string.sort_manual
    TrackSort.Title -> R.string.sort_title
    TrackSort.Artist -> R.string.sort_artist
    TrackSort.Album -> R.string.sort_album
    TrackSort.AlbumArtist -> R.string.sort_album_artist
    TrackSort.Folder -> R.string.sort_path
    TrackSort.Year -> R.string.sort_year
    TrackSort.Duration -> R.string.sort_duration
}
