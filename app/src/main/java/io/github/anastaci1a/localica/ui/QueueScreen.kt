package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.clickable
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

@Composable
fun QueueScreen(state: QueueUiState, controller: QueueController?) {
    var selector by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Set<String>?>(null) }
    val active = state.library.active
    LazyColumn(Modifier.fillMaxSize().testTag("queue-list"), contentPadding = PaddingValues(16.dp)) {
        item {
            Text("Queues", style = MaterialTheme.typography.headlineMedium)
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
                Row {
                    TextButton(onClick = { renaming = true }) { Text("Rename") }
                    TextButton(onClick = { deleting = setOf(active.id) }) { Text("Delete") }
                    TextButton(onClick = { deleting = state.library.queues.filterNot { it.id == active.id }.map { it.id }.toSet() },
                        enabled = state.library.queues.size > 1) { Text("Remove others") }
                }
                Row {
                    TextButton(onClick = { controller?.shuffle() }) { Text("Shuffle: ${if (active.shuffle) "on" else "off"}") }
                    TextButton(onClick = { controller?.repeat() }) { Text("Repeat: ${active.repeat.name.lowercase()}") }
                }
                Text("${active.entries.size} songs", style = MaterialTheme.typography.bodySmall)
                if (active.entries.isEmpty()) Text("This queue is empty. Add songs from Library using a song-tap setting in Settings.")
            }
        }
        itemsIndexed(active?.entries.orEmpty(), key = { _, entry -> entry.id }) { index, entry ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text((if (entry.id == active?.currentEntryId) "▶ " else "") + entry.track.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { controller?.selectEntry(entry.id) }.padding(vertical = 8.dp))
                Text(entry.track.artist ?: entry.track.fileName, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { controller?.moveEntry(entry.id, -1) }, enabled = index > 0) { Text("Up") }
                    TextButton(onClick = { controller?.moveEntry(entry.id, 1) }, enabled = index < (active?.entries?.lastIndex ?: 0)) { Text("Down") }
                    TextButton(onClick = { controller?.removeEntry(entry.id) }) { Text("Remove") }
                }
                HorizontalDivider()
            }
        }
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
    var limit by remember(settings.maximum) { mutableStateOf(settings.maximum?.toString().orEmpty()) }
    HorizontalDivider()
    Text("Listening behavior", style = MaterialTheme.typography.titleMedium)
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
    OutlinedTextField(limit, { limit = it }, label = { Text("Maximum queues (blank = unlimited)") }, singleLine = true,
        isError = limit.isNotBlank() && (limit.toIntOrNull() ?: 0) < 1)
    TextButton(enabled = limit.isBlank() || (limit.toIntOrNull() ?: 0) > 0,
        onClick = { onChange(settings.copy(maximum = limit.toIntOrNull())) }) { Text("Save queue limit") }
    Text("A limit never deletes existing queues automatically.", style = MaterialTheme.typography.bodySmall)
}
private fun SongTap.label() = when (this) { SongTap.NewQueue -> "New queue from displayed list"
    SongTap.PlayNow -> "Insert after current and play"; SongTap.PlayNext -> "Add next"; SongTap.Append -> "Append to current" }
private fun QueueSwitch.label() = when (this) { QueueSwitch.Paused -> "Restore paused"
    QueueSwitch.Resume -> "Resume immediately"; QueueSwitch.KeepPlaybackState -> "Keep playing or paused" }
