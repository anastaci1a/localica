package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.anastaci1a.localica.R
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.activity.compose.BackHandler
import io.github.anastaci1a.localica.navigation.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
    enabled: Boolean = true, pins: List<MediaRef> = emptyList(), onPin: (MediaRef) -> Unit = {}, scrollToTopRequest: Int = 0, reducedMotion: Boolean = false, selectionVisible: Boolean = true, isPlaying: Boolean = false, onOpen: (io.github.anastaci1a.localica.browse.BrowserItem) -> Unit = {}, onMinimize: (() -> Unit)? = null, listBackEnabled: Boolean = false) {
    var selector by remember { mutableStateOf(false) }
    val selectorAnchor = rememberGlassAnchor()
    val sortAnchor = rememberGlassAnchor()
    val menuAnchor = rememberGlassAnchor()
    val removeOthersAnchor = rememberGlassAnchor()
    var deletionOrigin by remember { mutableStateOf<GlassAnchor?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Set<String>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    val active = state.library.queues.firstOrNull { it.id == state.browsedId } ?: state.library.active
    val queueNumber = state.library.queues.indexOfFirst { it.id == active?.id } + 1
    val selectorLabel = active?.let { "$queueNumber. ${it.name}" } ?: "Queues"
    @Composable fun SelectorFace() {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(selectorLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(painterResource(R.drawable.ic_down), "Select queue", Modifier.size(24.dp))
        }
    }
    SideEffect { selectorAnchor.face = { SelectorFace() } }
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
    val browseHolder = rememberSaveableStateHolder()
    // Saved scroll state may survive, but drawing nodes must belong to this route.
    // Reusing the old queue's recorded children can leave stale clip/ink layers.
    key(route.key) { browseHolder.SaveableStateProvider(route.key) {
        BrowserScreen(library, playlists, route, { browsing = browsing.update(it) }, onOpen, {}, { rows, index, _ ->
            rows.getOrNull(index)?.let { controller?.selectEntry(it.id) }
        }, controller, playlistController, enabled, false, pins, onPin, sorting, { sorting = false }, state,
            playingEntryId = active?.takeIf { it.id == state.library.activeId }?.currentEntryId, header = {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        onMinimize?.let { ActionIcon(R.drawable.ic_down, "Minimize queue", onClick = it) }
                        LiquidGlassSurface(Modifier.weight(1f).glassAnchor(selectorAnchor)) {
                            Box(Modifier.fillMaxWidth().height(CompactControlSize).ownButtonDrag(state.library.queues.isNotEmpty())
                                .clickable(enabled = state.library.queues.isNotEmpty()) { selector = true }) { selectorAnchor.captureInk(); SelectorFace() }

                        }
                        Box {
                            ActionIcon(R.drawable.ic_more, "Queue actions", anchor = menuAnchor) { menu = true }
                            GlassMenu(menu, menuAnchor, { menu = false }) {
                                ActionMenuItem("Rename", enabled = active != null, onClick = { menu = false; renameId = active?.id })
                                ActionMenuItem("Delete", enabled = active != null, onClick = { menu = false; deleting = setOfNotNull(active?.id) })
                                if (state.library.queues.size > 1) ActionMenuItem("Remove others",
                                    onClick = { menu = false; deleting = state.library.queues.filterNot { it.id == active?.id }.map { it.id }.toSet() })
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionIcon(R.drawable.ic_sort, "Sort queue", anchor = sortAnchor) { sorting = true }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(position ?: "0 / 0", style = MaterialTheme.typography.titleSmall)
                            Text(duration ?: "0:00", style = MaterialTheme.typography.bodySmall)
                        }
                        ActionIcon(R.drawable.ic_playlist_add, "Save queue to playlist", enabled = active?.entries?.isNotEmpty() == true) { saving = true }

                    }
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (active == null) Text("Tap a song in Library to start a queue.")
                }
            }, listBackEnabled = listBackEnabled, scrollToTopRequest = scrollToTopRequest, reducedMotion = reducedMotion, selectionVisible = selectionVisible, onSearchEdit = { browsing = browsing.editSearch(it) }, onSearchCommit = { browsing = browsing.commitSearch() }, sortOrigin = sortAnchor, initialPlayingPosition = onMinimize != null)
    }
    }
    if (saving && active != null) AddToPlaylistDialog(active.entries.map { it.track }, playlists, playlistController, { saving = false }, { saving = false })
    GlassMorphPopup(selector, selectorAnchor, { selector = false }, GlassPlacement.Center) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            val showHeading = maxHeight >= 240.dp
            Column(Modifier.padding(vertical = 12.dp)) {
                if (showHeading) Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_queue), null, Modifier.size(24.dp))
                    Text("Queues", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
                }
                QueueSelectorList(state.library.queues, active?.id, state.library.activeId, isPlaying, reducedMotion,
                    enabled = selector && enabled && controller != null, modifier = Modifier.weight(1f, fill = false),
                    onSelect = { controller?.switchTo(it); selector = false }, onRename = { renameId = it },
                    onDelete = { deleting = setOf(it) }, onOffset = { id, offset -> controller?.moveQueue(id, offset) },
                    onMove = { id, before, complete -> controller?.moveQueueBefore(id, before, complete) ?: complete(false) })
                SideEffect { removeOthersAnchor.face = { Text("Remove all other queues", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                if (state.library.queues.size > 1) GlassActionButton(R.drawable.ic_trash, "Remove all other queues",
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp).glassAnchor(removeOthersAnchor),
                    enabled = state.library.queues.size > 1, onClick = {
                    deletionOrigin = removeOthersAnchor
                    deleting = state.library.queues.filterNot { it.id == active?.id }.map { it.id }.toSet()
                })
            }
        }
    }
    state.library.queues.firstOrNull { it.id == renameId }?.let { queue ->
        var name by remember(queue.id) { mutableStateOf(queue.name) }
        GlassAlertDialog(onDismissRequest = { renameId = null }, title = { Text("Rename queue") },
            text = { GlassTextField(name, { name = it }, label = { Text("Queue name") }) },
            confirmButton = { ActionTextButton(enabled = name.isNotBlank(), onClick = { controller?.rename(queue.id, name); renameId = null }) { Text("Save") } },
            dismissButton = { ActionTextButton(onClick = { renameId = null }) { Text("Cancel") } })
    }
    deleting?.let { ids -> CompositionLocalProvider(LocalGlassOrigin provides deletionOrigin) {
        fun dismissDeletion() { deleting = null; deletionOrigin = null }
        GlassAlertDialog(onDismissRequest = ::dismissDeletion, title = { Text("Delete queues?") },
            text = {
                val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
                LazyColumn(Modifier.scrollEdgeFade(scroll), state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(state.library.queues.filter { it.id in ids }, key = { _, queue -> queue.id }) { _, queue ->
                        Text(queue.name, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            confirmButton = { ActionTextButton(onClick = { controller?.removeQueues(ids); dismissDeletion() }) { Text("Delete") } },
            dismissButton = { ActionTextButton(onClick = ::dismissDeletion) { Text("Cancel") } })
    } }
}

@Composable
internal fun QueueDialogs(state: QueueUiState, controller: QueueController?) {
    state.pending?.let { pending ->
        var removals by remember(pending) { mutableStateOf(setOfNotNull(pending.suggestedRemoval)) }
        GlassAlertDialog(onDismissRequest = { controller?.cancelCreation() }, title = { Text("Make room for a new queue") },
            text = {
                Column(Modifier.controlVerticalScroll(rememberScrollState())) {
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
                    ActionTextButton(onClick = { removals = inactive.map { it.id }.toSet() }) { Text("Select all except current") }
                }
            },
            confirmButton = { ActionTextButton(enabled = QueueEdits.canCreate(state.library, state.settings.maximum, removals),
                onClick = { controller?.confirmCreation(removals) }) { Text("Remove and create") } },
            dismissButton = { ActionTextButton(onClick = { controller?.cancelCreation() }) { Text("Cancel") } })
    }
    if (state.problem != null && state.pending == null) GlassAlertDialog(onDismissRequest = { controller?.dismissProblem() },
        title = { Text("Queue change failed") }, text = { Text(state.problem) },
        confirmButton = { ActionTextButton(onClick = { controller?.dismissProblem() }) { Text("Close") } })
}

@Composable
internal fun QueueSettingsControls(settings: QueueSettings, section: String = "Playback and queues", onChange: ((QueueSettings) -> QueueSettings) -> Unit) {
    var limitOpen by remember { mutableStateOf(false) }
    var thresholdOpen by remember { mutableStateOf(false) }
    if (section == "Song tap behavior") {
        SettingsHeading("Default song action")
        ChoicePreference("Song tap", settings.songTap, SongTap.entries, { it.label() }) { action -> onChange { it.copy(songTap = action) } }
        SettingsHeading("List overrides")
        SongListView.entries.forEach { view ->
            ChoicePreference(view.label, settings.tapOverrides[view], listOf(null) + SongTap.entries,
                { it?.label() ?: "Inherit app default" }) { action ->
                onChange { it.copy(tapOverrides = if (action == null) it.tapOverrides - view else it.tapOverrides + (view to action)) }
            }
        }
        ActionTextButton(onClick = { onChange { it.copy(tapOverrides = emptyMap()) } }) { Text("Reset all tap overrides") }
    } else {
        SettingsHeading("Playback")
        BooleanPreference("Preload next track", settings.preloadNext) { enabled -> onChange { it.copy(preloadNext = enabled) } }
        ChoicePreference("Previous button", settings.previousBehavior, PreviousBehavior.entries, { it.label() }) { behavior ->
            onChange { it.copy(previousBehavior = behavior) }
        }
        if (settings.previousBehavior == PreviousBehavior.RestartAfterThreshold) {
            SettingsLink("Restart threshold", "${settings.previousThresholdSeconds} seconds; earlier taps go to the previous song") { thresholdOpen = true }
        }
        SettingsHeading("Queues")
        ChoicePreference("Switch queue", settings.switching, QueueSwitch.entries, { it.label() }) { switching -> onChange { it.copy(switching = switching) } }
        SettingsLink("Maximum queues: " + (settings.maximum ?: "unlimited"), "A limit never deletes existing queues automatically.") { limitOpen = true }
    }
    if (thresholdOpen) {
        var seconds by remember(settings.previousThresholdSeconds) { mutableStateOf(settings.previousThresholdSeconds.toString()) }
        val valid = seconds.toIntOrNull()?.let { it in 1..60 } == true
        GlassAlertDialog(onDismissRequest = { thresholdOpen = false }, title = { Text("Restart threshold") }, text = {
            GlassTextField(seconds, { seconds = it }, label = { Text("Seconds (1–60)") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                isError = !valid)
        }, confirmButton = { ActionTextButton(enabled = valid, onClick = {
            val threshold = seconds.toInt()
            onChange { it.copy(previousThresholdSeconds = threshold) }; thresholdOpen = false
        }) { Text("Save") } }, dismissButton = { ActionTextButton(onClick = { thresholdOpen = false }) { Text("Cancel") } })
    }
    if (limitOpen) {
        var limit by remember(settings.maximum) { mutableStateOf(settings.maximum?.toString().orEmpty()) }
        GlassAlertDialog(onDismissRequest = { limitOpen = false }, title = { Text("Queue limit") }, text = {
            GlassTextField(limit, { limit = it }, label = { Text("Maximum queues (blank = unlimited)") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                isError = limit.isNotBlank() && (limit.toIntOrNull() ?: 0) < 1)
        }, confirmButton = { ActionTextButton(enabled = limit.isBlank() || (limit.toIntOrNull() ?: 0) > 0, onClick = {
            val maximum = limit.toIntOrNull()
            onChange { it.copy(maximum = maximum) }; limitOpen = false
        }) { Text("Save queue limit") } }, dismissButton = { ActionTextButton(onClick = { limitOpen = false }) { Text("Cancel") } })
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
