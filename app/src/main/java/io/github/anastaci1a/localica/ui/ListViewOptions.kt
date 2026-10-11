package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.navigation.*

@Composable
internal fun ListViewOptions(route: BrowseRoute, value: BrowseStyle, overridden: Boolean, onDismiss: () -> Unit,
    onSave: (BrowseStyle, Boolean) -> Unit, onReset: () -> Unit) {
    var edited by remember { mutableStateOf(value) }
    var override by remember { mutableStateOf(overridden) }
    val albumSongs = route.collection?.kind == MediaKind.Album
    val albumCatalog = route.collection == null && (route.tab == MainTab.Albums || route.filter == MediaKind.Album)
    val width = LocalConfiguration.current.screenWidthDp.toFloat()
    SettingsPage("List view options", onDismiss, backLabel = "Back from view options") {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsHeading(route.title ?: route.filter?.label ?: route.tab.label)
            ChoicePreference("View mode", edited.presentation != Presentation.List, listOf(false, true), { if (it) "Gallery" else "List" }) {
                edited = edited.copy(presentation = if (it) Presentation.Standard else Presentation.List,
                    songGrouping = if (it && albumSongs) SongGrouping.None else edited.songGrouping)
            }
            if (edited.presentation != Presentation.List) {
                val columns = edited.itemsPerRow ?: galleryColumns(edited.presentation, width).coerceIn(1, 5)
                Text("Items per row: $columns", style = MaterialTheme.typography.titleSmall)
                Slider(columns.toFloat(), { edited = edited.copy(itemsPerRow = it.roundToInt().coerceIn(1, 5)) },
                    valueRange = 1f..5f, steps = 3, modifier = Modifier.semantics {
                        contentDescription = "Items per row"; stateDescription = columns.toString()
                    })
            }
            ChoicePreference("Extra information", edited.extraInfo, ExtraInfo.entries, ::extraInfoLabel) { edited = edited.copy(extraInfo = it) }
            if (albumSongs || albumCatalog) {
                ChoicePreference("Group album songs", edited.songGrouping, SongGrouping.entries, { it.label }) {
                    edited = edited.copy(songGrouping = it, presentation = if (albumSongs && it != SongGrouping.None) Presentation.List else edited.presentation)
                }
                Text("Group the album’s songs into sections in list view.", style = MaterialTheme.typography.bodySmall)
            }
            if (!route.search && (route.collection != null || route.tab != MainTab.Library))
                BooleanPreference("Quick-search box", edited.quickSearch) { edited = edited.copy(quickSearch = it) }
            SettingsHeading("Apply to")
            if (route.collection != null) {
                BooleanPreference("Only for this collection", override) { override = it }
                if (overridden) TextButton(onClick = onReset) { Text("Use list-type defaults") }
            }
            Text(if (override) "This collection only." else "All lists of this type.", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(onClick = { onSave(edited, override) }) { Text("Save") }
        }
    }
}

private fun extraInfoLabel(info: ExtraInfo) = when (info) {
    ExtraInfo.Automatic -> "Follow sort order"
    ExtraInfo.Count -> "Number of songs"
    ExtraInfo.Added -> "Date added"
    ExtraInfo.MostPlayed -> "Play count"
    ExtraInfo.Modified -> "Date modified"
    ExtraInfo.AlbumArtist -> "Album Artist"
    else -> info.name
}
