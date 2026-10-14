package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.playback.PlaybackProblem
import io.github.anastaci1a.localica.playback.PlaybackState

/** One notice per failure, independent of whether the player is expanded. Normal loading is silent. */
@Composable
internal fun PlaybackFailureDialog(state: PlaybackState, canSkip: Boolean, onRetry: () -> Unit, onSkip: () -> Unit) {
    var dismissed by rememberSaveable(state.trackUri, state.problem) { mutableStateOf(false) }
    val problem = state.problem ?: return
    if (dismissed) return
    GlassAlertDialog(onDismissRequest = { dismissed = true },
        icon = { Icon(painterResource(R.drawable.ic_info), null, Modifier.size(28.dp)) },
        title = { Text(if (problem == PlaybackProblem.Connection) "Playback disconnected" else "Couldn't play this song") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.title.isNotBlank()) Text(state.title, style = MaterialTheme.typography.titleSmall)
            Text(when (problem) {
                PlaybackProblem.Unreadable -> "This file can't be opened. It may have moved, or Localica may no longer have access to its folder. Check your music folders and try again."
                PlaybackProblem.Unsupported -> "Your device couldn't read this audio format. You can try another copy of the song or skip it."
                PlaybackProblem.Connection -> "Localica lost its connection to the audio player. Close and reopen the app to reconnect."
                PlaybackProblem.Failed -> "This song couldn't start playing. Try again, or skip to another song."
            })
        } },
        confirmButton = {
            if (state.connected && problem != PlaybackProblem.Unsupported) FilledTonalButton(onClick = { dismissed = true; onRetry() }) { Text("Try again") }
            else ActionTextButton(onClick = { dismissed = true }) { Text("Close") }
        },
        dismissButton = { Row {
            if (state.connected && problem != PlaybackProblem.Unsupported) ActionTextButton(onClick = { dismissed = true }) { Text("Close") }
            if (state.connected && canSkip) ActionTextButton(onClick = { dismissed = true; onSkip() }) { Text("Skip song") }
        } })
}
