package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.queue.*

@Composable
internal fun RepeatOptionsDialog(settings: QueueSettings, onDismiss: () -> Unit, onApply: (QueueSettings) -> Unit) {
    var advanced by remember { mutableStateOf(settings.advancedRepeat != null) }
    var simple by remember { mutableStateOf(settings.repeat) }
    var policy by remember { mutableStateOf(settings.advancedRepeat ?: AdvancedRepeat(
        songEnd = if (settings.repeat == QueueRepeat.One) SongEnd.Repeat else SongEnd.Next,
        queueEnd = if (settings.repeat == QueueRepeat.All) QueueEnd.Repeat else QueueEnd.Stop)) }
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text("Repeat modes") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChoices(listOf("Simple", "Advanced"), if (advanced) 1 else 0, horizontal = true) { advanced = it == 1 }
            Spacer(Modifier.height(12.dp))
            if (!advanced) RepeatChoices(QueueRepeat.entries, simple, { when (it) {
                QueueRepeat.Off -> "Play next song"; QueueRepeat.All -> "Repeat queue"; QueueRepeat.One -> "Repeat song"
            } }) { simple = it }
            else {
                Text("When a song ends", style = MaterialTheme.typography.titleSmall)
                RepeatChoices(SongEnd.entries, policy.songEnd, { it.label }) { policy = policy.copy(songEnd = it) }
                Spacer(Modifier.height(16.dp))
                Text("When the queue ends", style = MaterialTheme.typography.titleSmall)
                BooleanPreference("Reset current song to the first song", policy.resetToFirst) { policy = policy.copy(resetToFirst = it) }
                BooleanPreference("Reshuffle when shuffle is enabled", policy.reshuffle) { policy = policy.copy(reshuffle = it) }
                RepeatChoices(QueueEnd.entries, policy.queueEnd, { it.label }) { policy = policy.copy(queueEnd = it) }
                if (policy.songEnd == SongEnd.Repeat) Text("Queue-end options apply after you turn off repeating the current song.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { simple = QueueRepeat.Off; policy = AdvancedRepeat() }) { Text("Reset") }
        }
    }, confirmButton = { TextButton(onClick = {
        onApply(settings.copy(advancedRepeat = if (advanced) policy else null, repeat = if (advanced) policy.repeat else simple)); onDismiss()
    }) { Text("Apply") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun <T> RepeatChoices(values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    GlassChoices(values.map(label), values.indexOf(selected), horizontal = false) { onSelect(values[it]) }
}
