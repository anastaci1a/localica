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
                   onRescan: (String) -> Unit, onCancel: () -> Unit, onRemove: (String) -> Unit, extraContent: @Composable () -> Unit = {}) {
    var removing by remember { mutableStateOf<FolderSelection?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
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
    removing?.let { folder ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.remove_folder)) },
            text = { Text(stringResource(R.string.remove_folder_message, folder.name)) },
            confirmButton = { TextButton(onClick = { onRemove(folder.uri); removing = null }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
