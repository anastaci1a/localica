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
                 onPrevious: () -> Unit = {}, onNext: () -> Unit = {}, albumArtistFirst: Boolean = true) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val artworkSize = (maxHeight * 0.3f).coerceIn(80.dp, 240.dp)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        AlbumArtwork(state.trackUri, Modifier.size(artworkSize))
        Spacer(Modifier.height(12.dp))
        Text(state.title.ifEmpty { stringResource(R.string.nothing_playing) }, style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (state.trackUri == null) {
            Text(stringResource(R.string.choose_song), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onOpenLibrary) { Text(stringResource(R.string.open_library)) }
        } else {
            Text((if (albumArtistFirst) state.albumArtist ?: state.artist else state.artist ?: state.albumArtist)
                ?: stringResource(R.string.unknown_artist), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            state.album?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
            Spacer(Modifier.height(8.dp))
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
            Spacer(Modifier.height(8.dp))
            val pause = state.isPlaying || state.isBuffering
            Row(verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(R.drawable.ic_previous, "Previous", state.connected, onPrevious)
            FilledIconButton(onClick = onToggle, enabled = state.connected, modifier = Modifier.size(64.dp)) {
                Icon(painterResource(if (pause) R.drawable.ic_pause else R.drawable.ic_play),
                    stringResource(if (pause) R.string.pause else if (state.ended) R.string.replay else R.string.play),
                    Modifier.size(36.dp))
            }
            ActionIcon(R.drawable.ic_next, "Next", state.connected, onNext)
            }
            Spacer(Modifier.height(8.dp))
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
}

internal fun formatTime(milliseconds: Long?): String {
    if (milliseconds == null) return "–:––"
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
