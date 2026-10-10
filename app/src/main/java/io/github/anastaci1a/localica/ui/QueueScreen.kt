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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.library.SongListView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(state: QueueUiState, controller: QueueController?, library: LibraryUiState = LibraryUiState(loading = false),
    playlists: io.github.anastaci1a.localica.playlist.PlaylistState = io.github.anastaci1a.localica.playlist.PlaylistState(loading = false),
    playlistController: io.github.anastaci1a.localica.playlist.PlaylistController? = null,
    enabled: Boolean = true, pins: List<MediaRef> = emptyList(), onPin: (MediaRef) -> Unit = {}, scrollToTopRequest: Int = 0, reducedMotion: Boolean = false, selectionVisible: Boolean = true) {
    var selector by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Set<String>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var presentation by remember { mutableStateOf(false) }
    val active = state.library.queues.firstOrNull { it.id == state.browsedId } ?: state.library.active
    var browsing by rememberSaveable(active?.id, stateSaver = androidx.compose.runtime.saveable.Saver<NavigationState, String>(
        save = { NavigationCodec.encode(it) }, restore = { NavigationCodec.decode(it) })) {
        mutableStateOf(NavigationState(MainTab.Queues, mapOf(MainTab.Queues to listOf(BrowseRoute(MainTab.Queues,
            MediaRef(MediaKind.Queue, active?.id.orEmpty()), active?.name ?: "Queue")))))
    }
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > 0) browsing = browsing.resetTab(MainTab.Queues)
    }
    val route = browsing.route
    BackHandler(browsing.stacks.getValue(MainTab.Queues).size > 1) { browsing.back(false)?.let { browsing = it } }
    rememberSaveableStateHolder().SaveableStateProvider(route.key) {
        BrowserScreen(library, playlists, route, { browsing = browsing.update(it) }, {}, {}, { rows, index, _ ->
            rows.getOrNull(index)?.let { controller?.selectEntry(it.id) }
        }, controller, playlistController, enabled, false, pins, onPin, sorting, { sorting = false }, state,
            playingEntryId = active?.takeIf { it.id == state.library.activeId }?.currentEntryId, header = {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selector = true }, enabled = state.library.queues.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Text(active?.name ?: "Queues", style = MaterialTheme.typography.titleLarge)
                        }
                        ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_sort, "Sort queue") { presentation = false; sorting = true }
                        ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_settings, "Queue view options") { presentation = true; sorting = true }
                        Box {
                            ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_more, "Queue actions") { menu = true }
                            DropdownMenu(menu, { menu = false }) {
                                ActionMenuItem("Save as playlist", enabled = active?.entries?.isNotEmpty() == true, onClick = { menu = false; saving = true })
                                ActionMenuItem("Rename", enabled = active != null, onClick = { menu = false; renameId = active?.id })
                                ActionMenuItem("Delete", enabled = active != null, onClick = { menu = false; deleting = setOfNotNull(active?.id) })
                                ActionMenuItem("Remove others", enabled = state.library.queues.size > 1,
                                    onClick = { menu = false; deleting = state.library.queues.filterNot { it.id == active?.id }.map { it.id }.toSet() })
                            }
                        }
                    }
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (active != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${active.entries.indexOfFirst { it.id == active.currentEntryId } + 1} / ${active.entries.size} · " +
                            formatTime(active.entries.mapNotNull { it.track.durationMs }.takeIf { it.isNotEmpty() }?.sum()), Modifier.weight(1f))
                        ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_shuffle, "Shuffle: ${active.shuffle}") { controller?.shuffle(active.id) }
                        ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_repeat, "Repeat: ${active.repeat.name}") { controller?.repeat(active.id) }
                    } else Text("Tap a song in Library to start a queue.")
                }
            }, presentationOnly = presentation, scrollToTopRequest = scrollToTopRequest, reducedMotion = reducedMotion, selectionVisible = selectionVisible, onSearchEdit = { browsing = browsing.editSearch(it) }, onSearchCommit = { browsing = browsing.commitSearch() })
    }
    if (saving && active != null) AddToPlaylistDialog(active.entries.map { it.track }, playlists, playlistController, { saving = false }, { saving = false })
    if (selector) ModalBottomSheet(onDismissRequest = { selector = false }, shape = Corners.panel) {
        LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
            item { Text("Listening queues", style = MaterialTheme.typography.titleLarge) }
            itemsIndexed(state.library.queues, key = { _, queue -> queue.id }) { _, queue ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReorderHandle("Reorder " + queue.name) { controller?.moveQueue(queue.id, it) }
                    TextButton(onClick = { controller?.switchTo(queue.id); selector = false }, modifier = Modifier.weight(1f)) {
                        Text((if (queue.id == active?.id) "✓ " else "") + queue.name + " · " + queue.entries.size)
                    }
                    ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_settings, "Rename " + queue.name) { renameId = queue.id }
                    ActionIcon(io.github.anastaci1a.localica.R.drawable.ic_close, "Delete " + queue.name) { deleting = setOf(queue.id) }
                }
            }
        }
    }
    state.library.queues.firstOrNull { it.id == renameId }?.let { queue ->
        var name by remember(queue.id) { mutableStateOf(queue.name) }
        AlertDialog(onDismissRequest = { renameId = null }, title = { Text("Rename queue") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Queue name") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { controller?.rename(queue.id, name); renameId = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("Cancel") } })
    }
    deleting?.let { ids -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete queues?") },
        text = { Text(state.library.queues.filter { it.id in ids }.joinToString("\n") { it.name }) },
        confirmButton = { TextButton(onClick = { controller?.removeQueues(ids); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
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
            SongTap.entries.forEach { action -> ActionMenuItem(action.label(),
                onClick = { onChange(settings.copy(songTap = action)); tapOpen = false }) }
        }
    }
    Box {
        TextButton(onClick = { switchOpen = true }) { Text("Switch queue: ${settings.switching.label()}") }
        DropdownMenu(expanded = switchOpen, onDismissRequest = { switchOpen = false }) {
            QueueSwitch.entries.forEach { action -> ActionMenuItem(action.label(),
                onClick = { onChange(settings.copy(switching = action)); switchOpen = false }) }
        }
    }
    SongListView.entries.forEach { view ->
        var open by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { open = true }) { Text(view.label+": "+(settings.tapOverrides[view]?.label() ?: "Inherit app default")) }
            DropdownMenu(open, { open = false }) {
                ActionMenuItem("Inherit app default", onClick = {
                    onChange(settings.copy(tapOverrides = settings.tapOverrides - view)); open = false
                })
                SongTap.entries.forEach { action -> ActionMenuItem(action.label(), onClick = {
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
private fun QueueSwitch.label() = when (this) { QueueSwitch.Browse -> "Browse without changing playback"; QueueSwitch.Paused -> "Restore paused"
    QueueSwitch.Resume -> "Resume immediately"; QueueSwitch.KeepPlaybackState -> "Keep playing or paused" }
