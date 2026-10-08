package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSettings(state: LibraryUiState, playerConnected: Boolean, onDismiss: () -> Unit,
                   onChooseFolder: () -> Unit, onRescan: () -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.music_folder), style = MaterialTheme.typography.titleMedium)
            Text(state.snapshot.folder?.name ?: stringResource(R.string.no_folder), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.includes_subfolders), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LibraryNotice(state)
            if (state.scanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.scanning, state.scannedCount))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel_scan)) }
            } else {
                Button(onClick = onChooseFolder, enabled = playerConnected && !state.loading, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.snapshot.folder == null) R.string.choose_folder else R.string.change_folder))
                }
                if (state.snapshot.folder != null) {
                    OutlinedButton(onClick = onRescan, enabled = playerConnected, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.rescan))
                    }
                }
            }
        }
    }
}
