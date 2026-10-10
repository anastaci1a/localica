package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.LibraryQuery
import io.github.anastaci1a.localica.playback.PlaybackState

@Composable
fun LocalicaScreen(
    library: LibraryUiState,
    playback: PlaybackState,
    onPlay: (Track) -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onChooseFolder: () -> Unit,
    onRescan: (String) -> Unit,
    onCancelScan: () -> Unit,
    onRemoveFolder: (String) -> Unit = {},
    onQuery: (LibraryQuery) -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().height(80.dp).padding(horizontal = 8.dp)) {
                    Row(Modifier.align(Alignment.Center).width(192.dp)) {
                        NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(painterResource(R.drawable.ic_play), null) },
                            label = { Text(stringResource(R.string.player)) })
                        NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(painterResource(R.drawable.ic_library), null) },
                            label = { Text(stringResource(R.string.library)) })
                    }
                    IconButton(onClick = { settingsOpen = true }, modifier = Modifier.align(Alignment.CenterEnd).size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (tab == 0) PlayerScreen(playback, onToggle, onSeek, onOpenLibrary = { tab = 1 })
            else LibraryScreen(library, playback.trackId, enabled = playback.connected,
                onTrack = { onPlay(it); tab = 0 }, onSettings = { settingsOpen = true }, onQuery = onQuery)
        }
    }
    if (settingsOpen) FolderSettings(library,
        onDismiss = { settingsOpen = false }, onChooseFolder, onRescan, onCancelScan, onRemoveFolder)
}

@Composable
internal fun LibraryNotice(state: LibraryUiState) {
    val message = when (state.problem) {
        LibraryProblem.RemoveFailed -> R.string.remove_failed
        LibraryProblem.ScanFailed -> R.string.scan_failed
        LibraryProblem.LoadFailed -> R.string.load_failed
        null -> if (state.unavailableFolders.isNotEmpty()) R.string.access_lost
            else if (state.metadataFailures > 0) R.string.metadata_missing else null
    }
    message?.let {
        Text(stringResource(it), color = if (state.problem != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
