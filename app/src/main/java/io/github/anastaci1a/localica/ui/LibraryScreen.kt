package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.library.Track

@Composable
fun LibraryScreen(state: LibraryUiState, currentUri: String?, enabled: Boolean,
                  onTrack: (Track) -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.local_audio), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.your_library), style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold)
            if (state.snapshot.folder != null) {
                val count = state.snapshot.tracks.size
                val countText = if (count == 1) stringResource(R.string.one_song) else stringResource(R.string.song_count, count)
                Text("$countText · ${state.snapshot.folder.name}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            LibraryNotice(state)
            if (state.scanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.scanning, state.scannedCount), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (state.snapshot.tracks.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_library), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(20.dp))
                Text(stringResource(if (state.snapshot.folder == null) R.string.library_empty else R.string.empty_folder),
                    style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(if (state.snapshot.folder == null) R.string.choose_folder_hint else R.string.empty_folder_hint),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.open_settings)) }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(state.snapshot.tracks, key = { it.uri }) { track ->
                    val selected = currentUri == track.uri
                    Row(Modifier.fillMaxWidth().semantics { this.selected = selected }
                        .clickable(enabled = enabled, role = Role.Button) { onTrack(track) }
                        .padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(12.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer) {
                            Icon(painterResource(if (selected) R.drawable.ic_play else R.drawable.ic_music), null,
                                Modifier.padding(12.dp).size(24.dp), tint = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface)
                            Text(track.artist ?: track.fileName, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(formatTime(track.durationMs), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(Modifier.padding(start = 88.dp, end = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
