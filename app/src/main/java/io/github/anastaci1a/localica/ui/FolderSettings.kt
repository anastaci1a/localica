package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.FolderSelection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSettings(state: LibraryUiState, onDismiss: () -> Unit, onChooseFolder: () -> Unit,
                   onRescan: (String) -> Unit, onCancel: () -> Unit, onRemove: (String) -> Unit, extraContent: @Composable () -> Unit = {},
                   interfaceContent: @Composable () -> Unit = {}, playbackContent: @Composable () -> Unit = {},
                   aboutContent: @Composable () -> Unit = {}, initialCategory: String? = null) {
    var removing by remember { mutableStateOf<FolderSelection?>(null) }
    var category by remember { mutableStateOf(initialCategory) }
    var search by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.activity.compose.BackHandler(category != null) { category = null }
        Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                ActionIcon(R.drawable.ic_back, "Back from settings") { if (category == null) onDismiss() else category = null }
                Text(category ?: "Settings", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                ActionIcon(R.drawable.ic_close, "Close settings", onClick = onDismiss)
            }
            OutlinedTextField(search, { search = it; category = null }, label = { Text("Search settings") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            if (category == null) {
                val categories = mapOf("Interface and gestures" to "tabs back navigation animation motion artwork player actions",
                    "Playback and queues" to "preload listening song tap switch maximum",
                    "Library and tags" to "folders rescan permissions metadata recovery artwork", "About and licenses" to "help version source licenses")
                categories.filter { (name, keywords) -> (name + " " + keywords).contains(search, true) }.keys.forEach { name ->
                    TextButton(onClick = { category = name }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(name) }
                }
            } else {

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (category == "Interface and gestures") interfaceContent()
            if (category == "Playback and queues") playbackContent()
            if (category == "About and licenses") aboutContent()
            if (category == "Library and tags") {
            Text(stringResource(R.string.music_folders), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.includes_subfolders), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LibraryNotice(state)
            state.snapshot.folders.forEach { folder ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(folder.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.song_count, state.snapshot.tracks.count { track ->
                            track.locations.any { it.folderUri == folder.uri } }), style = MaterialTheme.typography.bodySmall)
                        if (folder.uri in state.unavailableFolders) Text(stringResource(R.string.access_lost),
                            color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onRescan(folder.uri) }, enabled = !state.scanning) { Text(stringResource(R.string.rescan)) }
                            TextButton(onClick = { removing = folder }, enabled = !state.scanning) { Text(stringResource(R.string.remove)) }
                        }
                    }
                }
            }
            if (state.scanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.scanning, state.scannedCount))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel_scan)) }
            } else {
                Button(onClick = onChooseFolder, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.snapshot.folders.isEmpty()) R.string.choose_folder else R.string.add_folder))
                }
            }
            extraContent()
            }
        }
            }
        }
        }
    }
    removing?.let { folder ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.remove_folder)) },
            text = { Text(stringResource(R.string.remove_folder_message, folder.name)) },
            confirmButton = { TextButton(onClick = { onRemove(folder.uri); removing = null }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
