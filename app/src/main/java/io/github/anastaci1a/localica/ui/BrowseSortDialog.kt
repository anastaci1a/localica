package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.navigation.*

@Composable
internal fun BrowseSortDialog(route: BrowseRoute, value: BrowseStyle, overridden: Boolean,
    onDismiss: () -> Unit, onSave: (BrowseStyle, Boolean) -> Unit, onReset: () -> Unit, presentationOnly: Boolean = false, onRandomize: (() -> Unit)? = null, reducedMotion: Boolean = false) {
    if (presentationOnly) {
        ListViewOptions(route, value, overridden, onDismiss, onSave, onReset)
        return
    }
    var edited by remember { mutableStateOf(value) }
    var override by remember { mutableStateOf(overridden) }
    val songs = route.collection != null || route.tab in setOf(MainTab.Songs, MainTab.MostPlayed) || route.filter == MediaKind.Song
    val fields = if (songs) TrackSort.entries.filter { it != TrackSort.Count && (it != TrackSort.Manual || route.collection?.kind in setOf(MediaKind.Playlist, MediaKind.Queue)) }
        else TrackSort.entries.filterNot { it in setOf(TrackSort.Manual, TrackSort.Album, TrackSort.Folder) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Sort") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (onRandomize != null) {
                TextButton(onClick = onRandomize) { Text("Randomize queue order") }
                Text("Changes the saved order; keeps the current song and playback position.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Sort by")
            fields.forEach { field -> OptionRow(sortLabel(field), edited.sort == field, onClick = { edited = edited.copy(sort = field, ascending = if (field != edited.sort && field in setOf(TrackSort.Added, TrackSort.Modified, TrackSort.MostPlayed)) false else edited.ascending) }) }
            SortDirection(edited.ascending, reducedMotion) { edited = edited.copy(ascending = it) }
            if (route.collection != null) {
                BooleanPreference("Only for this collection", override) { override = it }
                if (overridden) TextButton(onClick = onReset) { Text("Use list-type defaults") }
            }
            Text(if (override) "Saved for this collection." else "Saved for this type of list.")
        }
    }, confirmButton = { TextButton(onClick = { onSave(edited, override) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun sortLabel(sort: TrackSort) = when (sort) {
    TrackSort.Album -> "Album / disc / track"
    TrackSort.AlbumArtist -> "Album Artist"
    TrackSort.Added -> "Date added"
    TrackSort.Modified -> "Date modified"
    TrackSort.MostPlayed -> "Most played"
    TrackSort.Count -> "Number of songs"
    else -> sort.name
}
