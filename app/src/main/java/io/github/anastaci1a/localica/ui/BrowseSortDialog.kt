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
    onDismiss: () -> Unit, onSave: (BrowseStyle, Boolean) -> Unit, onReset: () -> Unit) {
    var edited by remember { mutableStateOf(value) }
    var override by remember { mutableStateOf(overridden) }
    val songs = route.collection != null || route.tab == MainTab.Songs || route.filter == MediaKind.Song
    val fields = if (songs) TrackSort.entries.filter { it != TrackSort.Manual || route.collection?.kind == MediaKind.Playlist }
        else listOf(TrackSort.Title)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Sort and presentation") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Sort by")
            fields.forEach { field -> TextButton(onClick = { edited = edited.copy(sort = field) }) {
                Text((if (edited.sort == field) "✓ " else "") + if (field == TrackSort.Album) "Album / disc / track" else field.name)
            } }
            BooleanPreference("Ascending", edited.ascending) { edited = edited.copy(ascending = it) }
            Text("Presentation")
            Presentation.entries.forEach { mode -> TextButton(onClick = { edited = edited.copy(presentation = mode) }) {
                Text((if (edited.presentation == mode) "✓ " else "") + mode.label)
            } }
            if (route.collection != null) {
                BooleanPreference("Only for this collection", override) { override = it }
                if (overridden) TextButton(onClick = onReset) { Text("Use list-type defaults") }
            }
            Text(if (override) "Saved for this collection." else "Saved for this type of list.")
        }
    }, confirmButton = { TextButton(onClick = { onSave(edited, override) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
