package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.playback.PlaybackProblem
import io.github.anastaci1a.localica.playback.PlaybackState
import java.util.Locale

@Composable
fun PlayerScreen(state: PlaybackState, onToggle: () -> Unit, onSeek: (Long) -> Unit, onOpenLibrary: () -> Unit,
                 onPrevious: () -> Unit = {}, onNext: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(36.dp))
        AlbumArtwork(state.trackUri)
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.now_playing), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(state.title.ifEmpty { stringResource(R.string.nothing_playing) }, style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (state.trackUri == null) {
            Text(stringResource(R.string.choose_song), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onOpenLibrary) { Text(stringResource(R.string.open_library)) }
        } else {
            Text(state.artist ?: stringResource(R.string.unknown_artist), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            state.album?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
            if (state.fileName.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(state.fileName, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(28.dp))
            var draggedPosition by remember(state.trackUri) { mutableStateOf<Float?>(null) }
            val duration = (state.durationMs ?: 0).coerceAtLeast(1).toFloat()
            val seekLabel = stringResource(R.string.seek)
            Slider(value = (draggedPosition ?: state.positionMs.toFloat()).coerceIn(0f, duration),
                onValueChange = { draggedPosition = it }, valueRange = 0f..duration,
                onValueChangeFinished = { draggedPosition?.let { onSeek(it.toLong()) }; draggedPosition = null },
                enabled = state.connected && state.durationMs != null && state.durationMs > 0,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = seekLabel })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(draggedPosition?.toLong() ?: state.positionMs), style = MaterialTheme.typography.labelMedium)
                Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(24.dp))
            val pause = state.isPlaying || state.isBuffering
            Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPrevious, enabled = state.connected) { Text("Previous") }
            FilledIconButton(onClick = onToggle, enabled = state.connected, modifier = Modifier.size(72.dp)) {
                Icon(painterResource(if (pause) R.drawable.ic_pause else R.drawable.ic_play),
                    stringResource(if (pause) R.string.pause else if (state.ended) R.string.replay else R.string.play),
                    Modifier.size(36.dp))
            }
            TextButton(onClick = onNext, enabled = state.connected) { Text("Next") }
            }
            Spacer(Modifier.height(12.dp))
            if (state.isBuffering) Text(stringResource(R.string.loading_audio), style = MaterialTheme.typography.bodySmall)
            if (state.ended) Text(stringResource(R.string.track_finished), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val problem = when (state.problem) {
            PlaybackProblem.Connection -> R.string.connection_failed
            PlaybackProblem.Unreadable -> R.string.file_unavailable
            PlaybackProblem.Unsupported -> R.string.format_unsupported
            PlaybackProblem.Failed -> R.string.playback_failed
            null -> null
        }
        if (problem != null) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(problem), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        } else if (!state.connected) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.connecting), style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun formatTime(milliseconds: Long?): String {
    if (milliseconds == null) return "–:––"
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
