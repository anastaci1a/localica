package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.activity.compose.BackHandler
import io.github.anastaci1a.localica.navigation.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.library.SongListView

@Composable
fun QueueScreen(state: QueueUiState, controller: QueueController?, library: LibraryUiState = LibraryUiState(loading = false),
    playlists: io.github.anastaci1a.localica.playlist.PlaylistState = io.github.anastaci1a.localica.playlist.PlaylistState(loading = false),
    playlistController: io.github.anastaci1a.localica.playlist.PlaylistController? = null,
    enabled: Boolean = true, pins: List<MediaRef> = emptyList(), onPin: (MediaRef) -> Unit = {}) {
    var selector by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Set<String>?>(null) }
    val active = state.library.active
    var searching by rememberSaveable(active?.id) { mutableStateOf(false) }
    var text by rememberSaveable(active?.id) { mutableStateOf("") }
    var sorting by remember { mutableStateOf(false) }
    val routeKey = rememberSaveable(active?.id) { java.util.UUID.randomUUID().toString() }
    val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, active?.id.orEmpty()), active?.name ?: "Queue",
        search = searching, text = text, key = "$routeKey:$searching")
    BackHandler(searching) { searching = false; text = "" }
    rememberSaveableStateHolder().SaveableStateProvider(route.key) {
    BrowserScreen(library, playlists, route, { text = it.text }, {}, {}, { rows, index, _ ->
        rows.getOrNull(index)?.let { controller?.selectEntry(it.id) }
    }, controller, playlistController, enabled, false, pins, onPin, sorting, { sorting = false }, state,
        playingEntryId = active?.currentEntryId, header = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Queues", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                if (active != null) {
                    ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_sort, "Sort queue") { sorting = true }
                    if (!searching) ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_search, "Search queue") { searching = true }
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box {
                TextButton(onClick = { selector = true }, enabled = state.library.queues.isNotEmpty()) {
                    Text(active?.name ?: "Select a queue")
                }
                DropdownMenu(expanded = selector, onDismissRequest = { selector = false }) {
                    state.library.queues.forEach { queue ->
                        DropdownMenuItem(text = { Text("${queue.name} · ${queue.entries.size} songs") },
                            onClick = { controller?.switchTo(queue.id); selector = false })
                    }
                }
            }
            if (state.library.queues.isEmpty()) Text("Tap a song in Library to start a queue.")
            if (active != null) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { renaming = true }) { Text("Rename") }
                    TextButton(onClick = { deleting = setOf(active.id) }) { Text("Delete") }
                    TextButton(onClick = { deleting = state.library.queues.filterNot { it.id == active.id }.map { it.id }.toSet() },
                        enabled = state.library.queues.size > 1) { Text("Remove others") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { controller?.shuffle() }) { Text("Shuffle: ${if (active.shuffle) "on" else "off"}") }
                    TextButton(onClick = { controller?.repeat() }) { Text("Repeat: ${active.repeat.name.lowercase()}") }
                }
                Text("${active.entries.size} songs", style = MaterialTheme.typography.bodySmall)
            }
        })
    }
    if (renaming && active != null) {
        var name by remember(active.id) { mutableStateOf(active.name) }
        AlertDialog(onDismissRequest = { renaming = false }, title = { Text("Rename queue") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Queue name") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { controller?.rename(active.id, name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } })
    }
    deleting?.let { ids ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete queues?") },
            text = { Text(state.library.queues.filter { it.id in ids }.joinToString("\n") { it.name }) },
            confirmButton = { TextButton(onClick = { controller?.removeQueues(ids); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable
internal fun QueueDialogs(state: QueueUiState, controller: QueueController?) {
    state.pending?.let { pending ->
        var removals by remember(pending) { mutableStateOf(setOfNotNull(pending.suggestedRemoval)) }
        AlertDialog(onDismissRequest = { controller?.cancelCreation() }, title = { Text("Make room for a new queue") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("Queue limit: ${state.settings.maximum}. Choose queues to remove. Your current queue is protected.")
                    val inactive = state.library.queues.filterNot { it.id == state.library.activeId }
                    if (inactive.isEmpty()) Text("There are no inactive queues. Cancel and increase the limit in Settings, or delete the current queue explicitly.")
                    inactive.forEach { queue ->
                        Row(Modifier.fillMaxWidth().toggleable(value = queue.id in removals, role = Role.Checkbox,
                            onValueChange = { checked -> removals = if (checked) removals + queue.id else removals - queue.id }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(queue.id in removals, null)
                            Text(queue.name, Modifier.weight(1f))
                        }
                    }
                    TextButton(onClick = { removals = inactive.map { it.id }.toSet() }) { Text("Select all except current") }
                }
            },
            confirmButton = { TextButton(enabled = QueueEdits.canCreate(state.library, state.settings.maximum, removals),
                onClick = { controller?.confirmCreation(removals) }) { Text("Remove and create") } },
            dismissButton = { TextButton(onClick = { controller?.cancelCreation() }) { Text("Cancel") } })
    }
    if (state.problem != null && state.pending == null) AlertDialog(onDismissRequest = { controller?.dismissProblem() },
        title = { Text("Queue change failed") }, text = { Text(state.problem) },
        confirmButton = { TextButton(onClick = { controller?.dismissProblem() }) { Text("Close") } })
}

@Composable
internal fun QueueSettingsControls(settings: QueueSettings, onChange: (QueueSettings) -> Unit) {
    var tapOpen by remember { mutableStateOf(false) }
    var switchOpen by remember { mutableStateOf(false) }
    var limitOpen by remember { mutableStateOf(false) }
    HorizontalDivider()
    Text("Listening behavior", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Preload next track", Modifier.weight(1f))
        Switch(settings.preloadNext, { onChange(settings.copy(preloadNext = it)) })
    }
    Box {
        TextButton(onClick = { tapOpen = true }) { Text("Song tap: ${settings.songTap.label()}") }
        DropdownMenu(expanded = tapOpen, onDismissRequest = { tapOpen = false }) {
            SongTap.entries.forEach { action -> DropdownMenuItem(text = { Text(action.label()) },
                onClick = { onChange(settings.copy(songTap = action)); tapOpen = false }) }
        }
    }
    Box {
        TextButton(onClick = { switchOpen = true }) { Text("Switch queue: ${settings.switching.label()}") }
        DropdownMenu(expanded = switchOpen, onDismissRequest = { switchOpen = false }) {
            QueueSwitch.entries.forEach { action -> DropdownMenuItem(text = { Text(action.label()) },
                onClick = { onChange(settings.copy(switching = action)); switchOpen = false }) }
        }
    }
    SongListView.entries.forEach { view ->
        var open by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { open = true }) { Text(view.label+": "+(settings.tapOverrides[view]?.label() ?: "Inherit app default")) }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(text = { Text("Inherit app default") }, onClick = {
                    onChange(settings.copy(tapOverrides = settings.tapOverrides - view)); open = false
                })
                SongTap.entries.forEach { action -> DropdownMenuItem(text = { Text(action.label()) }, onClick = {
                    onChange(settings.copy(tapOverrides = settings.tapOverrides + (view to action))); open = false
                }) }
            }
        }
    }
    TextButton(onClick = { onChange(settings.copy(tapOverrides = emptyMap())) }) { Text("Reset all tap overrides") }
    TextButton(onClick = { limitOpen = true }) { Text("Maximum queues: ${settings.maximum ?: "unlimited"}") }
    Text("A limit never deletes existing queues automatically.", style = MaterialTheme.typography.bodySmall)
    if (limitOpen) {
        var limit by remember(settings.maximum) { mutableStateOf(settings.maximum?.toString().orEmpty()) }
        AlertDialog(onDismissRequest = { limitOpen = false }, title = { Text("Queue limit") }, text = {
            OutlinedTextField(limit, { limit = it }, label = { Text("Maximum queues (blank = unlimited)") }, singleLine = true,
                isError = limit.isNotBlank() && (limit.toIntOrNull() ?: 0) < 1)
        }, confirmButton = { TextButton(enabled = limit.isBlank() || (limit.toIntOrNull() ?: 0) > 0, onClick = {
            onChange(settings.copy(maximum = limit.toIntOrNull())); limitOpen = false
        }) { Text("Save queue limit") } }, dismissButton = {
            TextButton(onClick = { limitOpen = false }) { Text("Cancel") }
        })
    }
}
internal fun SongTap.label() = when (this) { SongTap.NewQueue -> "New queue from displayed list"
    SongTap.PlayNow -> "Insert after current and play"; SongTap.PlayNext -> "Add next"; SongTap.Append -> "Append to current" }
private fun QueueSwitch.label() = when (this) { QueueSwitch.Paused -> "Restore paused"
    QueueSwitch.Resume -> "Resume immediately"; QueueSwitch.KeepPlaybackState -> "Keep playing or paused" }
