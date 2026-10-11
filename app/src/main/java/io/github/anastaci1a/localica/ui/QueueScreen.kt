package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.anastaci1a.localica.R
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
    enabled: Boolean = true, pins: List<MediaRef> = emptyList(), onPin: (MediaRef) -> Unit = {}, scrollToTopRequest: Int = 0, reducedMotion: Boolean = false, selectionVisible: Boolean = true, isPlaying: Boolean = false, onOpen: (io.github.anastaci1a.localica.browse.BrowserItem) -> Unit = {}) {
    var selector by remember { mutableStateOf(false) }
    val selectorAnchor = rememberGlassAnchor()
    val sortAnchor = rememberGlassAnchor()
    val menuAnchor = rememberGlassAnchor()
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Set<String>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    val active = state.library.queues.firstOrNull { it.id == state.browsedId } ?: state.library.active
    val queueNumber = state.library.queues.indexOfFirst { it.id == active?.id } + 1
    val selectorLabel = active?.let { "$queueNumber. ${it.name}" } ?: "Queues"
    SideEffect { selectorAnchor.face = { Text(selectorLabel, Modifier.padding(horizontal = 12.dp),
        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
    val playingThisQueue = active?.id == state.library.activeId && isPlaying
    val position = active?.let { "${it.entries.indexOfFirst { entry -> entry.id == it.currentEntryId } + 1} / ${it.entries.size}" }
    val duration = active?.let { formatTime(it.entries.mapNotNull { entry -> entry.track.durationMs }.takeIf { it.isNotEmpty() }?.sum()) }
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
        BrowserScreen(library, playlists, route, { browsing = browsing.update(it) }, onOpen, {}, { rows, index, _ ->
            rows.getOrNull(index)?.let { controller?.selectEntry(it.id) }
        }, controller, playlistController, enabled, false, pins, onPin, sorting, { sorting = false }, state,
            playingEntryId = active?.takeIf { it.id == state.library.activeId }?.currentEntryId, header = {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        LiquidGlassSurface(Modifier.weight(1f).glassAnchor(selectorAnchor)) {
                            Row(Modifier.clickable(enabled = state.library.queues.isNotEmpty()) { selector = true }
                                .heightIn(min = CompactControlSize).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(selectorLabel, Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(painterResource(R.drawable.ic_down), "Select queue", Modifier.size(24.dp))
                            }
                        }
                        ActionIcon(R.drawable.ic_close, "Delete current queue", enabled = active != null) { deleting = setOfNotNull(active?.id) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionIcon(if (playingThisQueue) R.drawable.ic_pause else R.drawable.ic_play,
                            if (playingThisQueue) "Pause queue" else "Play queue", enabled = enabled && active?.entries?.isNotEmpty() == true) {
                            if (playingThisQueue) controller?.togglePlayback() else active?.id?.let { controller?.playQueue(it) }
                        }
                        ActionIcon(R.drawable.ic_sort, "Sort queue", anchor = sortAnchor) { sorting = true }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(position ?: "0 / 0", style = MaterialTheme.typography.titleSmall)
                            Text(duration ?: "0:00", style = MaterialTheme.typography.bodySmall)
                        }
                        ActionIcon(R.drawable.ic_save, "Save queue to playlist", enabled = active?.entries?.isNotEmpty() == true) { saving = true }
                        Box {
                            ActionIcon(R.drawable.ic_more, "Queue actions", anchor = menuAnchor) { menu = true }
                            GlassMenu(menu, menuAnchor, { menu = false }) {
                                ActionMenuItem("Rename", enabled = active != null, onClick = { menu = false; renameId = active?.id })
                                ActionMenuItem("Remove others", enabled = state.library.queues.size > 1,
                                    onClick = { menu = false; deleting = state.library.queues.filterNot { it.id == active?.id }.map { it.id }.toSet() })
                            }
                        }
                    }
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (active == null) Text("Tap a song in Library to start a queue.")
                }
            }, scrollToTopRequest = scrollToTopRequest, reducedMotion = reducedMotion, selectionVisible = selectionVisible, onSearchEdit = { browsing = browsing.editSearch(it) }, onSearchCommit = { browsing = browsing.commitSearch() }, sortOrigin = sortAnchor)
    }
    if (saving && active != null) AddToPlaylistDialog(active.entries.map { it.track }, playlists, playlistController, { saving = false }, { saving = false })
    GlassMorphPopup(selector, selectorAnchor, { selector = false }, GlassPlacement.Center) {
        Box(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_queue), null, Modifier.size(24.dp))
                    Text("Queues", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
                }
                LazyColumn(Modifier.weight(1f, fill = false).selectableGroup().padding(horizontal = 8.dp)) {
                    itemsIndexed(state.library.queues, key = { _, queue -> queue.id }) { index, queue ->
                        val chosen = queue.id == active?.id
                        Surface(shape = Corners.card, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (chosen) .08f else 0f),
                            modifier = Modifier.padding(vertical = 2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ReorderHandle("Reorder " + queue.name) { controller?.moveQueue(queue.id, it) }
                                Row(Modifier.weight(1f).selectable(chosen, role = Role.RadioButton,
                                    onClick = { controller?.switchTo(queue.id); selector = false }).heightIn(min = 56.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    InterfaceCheckbox(chosen, null, Modifier.size(32.dp))
                                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                                        if (chosen) MarqueeTitle("${index + 1}. ${queue.name}", reducedMotion,
                                            style = MaterialTheme.typography.bodyLarge)
                                        else Text("${index + 1}. ${queue.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (queue.id == state.library.activeId) Text(if (isPlaying) "Playing" else "Playback queue", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                ActionIcon(R.drawable.ic_edit, "Rename " + queue.name) { renameId = queue.id }
                                ActionIcon(R.drawable.ic_close, "Delete " + queue.name) { deleting = setOf(queue.id) }
                            }
                        }
                    }
                }
                TextButton(enabled = state.library.queues.size > 1, onClick = {
                    deleting = state.library.queues.filterNot { it.id == active?.id }.map { it.id }.toSet()
                }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Remove all other queues") }
            }
        }
    }
    state.library.queues.firstOrNull { it.id == renameId }?.let { queue ->
        var name by remember(queue.id) { mutableStateOf(queue.name) }
        GlassAlertDialog(onDismissRequest = { renameId = null }, title = { Text("Rename queue") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Queue name") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { controller?.rename(queue.id, name); renameId = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("Cancel") } })
    }
    deleting?.let { ids -> GlassAlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete queues?") },
        text = { Text(state.library.queues.filter { it.id in ids }.joinToString("\n") { it.name }) },
        confirmButton = { TextButton(onClick = { controller?.removeQueues(ids); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable
internal fun QueueDialogs(state: QueueUiState, controller: QueueController?) {
    state.pending?.let { pending ->
        var removals by remember(pending) { mutableStateOf(setOfNotNull(pending.suggestedRemoval)) }
        GlassAlertDialog(onDismissRequest = { controller?.cancelCreation() }, title = { Text("Make room for a new queue") },
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
                            InterfaceCheckbox(queue.id in removals, null)
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
    if (state.problem != null && state.pending == null) GlassAlertDialog(onDismissRequest = { controller?.dismissProblem() },
        title = { Text("Queue change failed") }, text = { Text(state.problem) },
        confirmButton = { TextButton(onClick = { controller?.dismissProblem() }) { Text("Close") } })
}

@Composable
internal fun QueueSettingsControls(settings: QueueSettings, section: String = "Playback and queues", onChange: (QueueSettings) -> Unit) {
    var limitOpen by remember { mutableStateOf(false) }
    var thresholdOpen by remember { mutableStateOf(false) }
    if (section == "Song tap behavior") {
        SettingsHeading("Default song action")
        ChoicePreference("Song tap", settings.songTap, SongTap.entries, { it.label() }) { onChange(settings.copy(songTap = it)) }
        SettingsHeading("List overrides")
        SongListView.entries.forEach { view ->
            ChoicePreference(view.label, settings.tapOverrides[view], listOf(null) + SongTap.entries,
                { it?.label() ?: "Inherit app default" }) { action ->
                onChange(settings.copy(tapOverrides = if (action == null) settings.tapOverrides - view else settings.tapOverrides + (view to action)))
            }
        }
        TextButton(onClick = { onChange(settings.copy(tapOverrides = emptyMap())) }) { Text("Reset all tap overrides") }
    } else {
        SettingsHeading("Playback")
        BooleanPreference("Preload next track", settings.preloadNext) { onChange(settings.copy(preloadNext = it)) }
        ChoicePreference("Previous button", settings.previousBehavior, PreviousBehavior.entries, { it.label() }) {
            onChange(settings.copy(previousBehavior = it))
        }
        if (settings.previousBehavior == PreviousBehavior.RestartAfterThreshold) {
            SettingsLink("Restart threshold", "${settings.previousThresholdSeconds} seconds; earlier taps go to the previous song") { thresholdOpen = true }
        }
        SettingsHeading("Queues")
        ChoicePreference("Switch queue", settings.switching, QueueSwitch.entries, { it.label() }) { onChange(settings.copy(switching = it)) }
        SettingsLink("Maximum queues: " + (settings.maximum ?: "unlimited"), "A limit never deletes existing queues automatically.") { limitOpen = true }
    }
    if (thresholdOpen) {
        var seconds by remember(settings.previousThresholdSeconds) { mutableStateOf(settings.previousThresholdSeconds.toString()) }
        val valid = seconds.toIntOrNull()?.let { it in 1..60 } == true
        GlassAlertDialog(onDismissRequest = { thresholdOpen = false }, title = { Text("Restart threshold") }, text = {
            OutlinedTextField(seconds, { seconds = it }, label = { Text("Seconds (1–60)") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                isError = !valid)
        }, confirmButton = { TextButton(enabled = valid, onClick = {
            onChange(settings.copy(previousThresholdSeconds = seconds.toInt())); thresholdOpen = false
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { thresholdOpen = false }) { Text("Cancel") } })
    }
    if (limitOpen) {
        var limit by remember(settings.maximum) { mutableStateOf(settings.maximum?.toString().orEmpty()) }
        GlassAlertDialog(onDismissRequest = { limitOpen = false }, title = { Text("Queue limit") }, text = {
            OutlinedTextField(limit, { limit = it }, label = { Text("Maximum queues (blank = unlimited)") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                isError = limit.isNotBlank() && (limit.toIntOrNull() ?: 0) < 1)
        }, confirmButton = { TextButton(enabled = limit.isBlank() || (limit.toIntOrNull() ?: 0) > 0, onClick = {
            onChange(settings.copy(maximum = limit.toIntOrNull())); limitOpen = false
        }) { Text("Save queue limit") } }, dismissButton = { TextButton(onClick = { limitOpen = false }) { Text("Cancel") } })
    }
}
internal fun SongTap.label() = when (this) { SongTap.NewQueue -> "New queue from displayed list"
    SongTap.PlayNow -> "Insert after current and play"; SongTap.PlayNext -> "Add next"; SongTap.Append -> "Append to current" }
private fun PreviousBehavior.label() = when (this) {
    PreviousBehavior.RestartAfterThreshold -> "Restart after threshold"
    PreviousBehavior.PreviousSong -> "Always previous song"
    PreviousBehavior.RestartSong -> "Always restart song"
}
private fun QueueSwitch.label() = when (this) { QueueSwitch.Browse -> "Browse without changing playback"; QueueSwitch.Paused -> "Restore paused"
    QueueSwitch.Resume -> "Resume immediately"; QueueSwitch.KeepPlaybackState -> "Keep playing or paused" }
