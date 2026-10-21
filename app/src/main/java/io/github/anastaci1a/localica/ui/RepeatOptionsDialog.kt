package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.queue.*

private val repeatModeLabels = listOf("Simple", "Advanced")

@Composable
internal fun RepeatOptionsDialog(settings: QueueSettings, onDismiss: () -> Unit, onApply: ((QueueSettings) -> QueueSettings) -> Unit) {
    var advanced by remember { mutableStateOf(settings.advancedRepeat != null) }
    var simple by remember { mutableStateOf(settings.repeat) }
    var policy by remember { mutableStateOf(settings.advancedRepeat ?: AdvancedRepeat(
        songEnd = if (settings.repeat == QueueRepeat.One) SongEnd.Repeat else SongEnd.Next,
        queueEnd = if (settings.repeat == QueueRepeat.All) QueueEnd.Repeat else QueueEnd.Stop)) }
    val simpleScroll = rememberScrollState()
    val advancedScroll = rememberScrollState()
    GlassAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("repeat-options-dialog"), title = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Repeat modes")
            GlassChoices(repeatModeLabels, if (advanced) 1 else 0, horizontal = true,
                modifier = Modifier.testTag("repeat-options-tabs")) { advanced = it == 1 }
        }
    }, text = {
        // Simple's complete natural layout is the common compact viewport, not a Repeat-only
        // dp/screen-fraction cap. The shared host reserves chrome and bounds it to the window.
        StablePopupBody(Modifier.testTag("repeat-options-body"), measurement = {
            RepeatOptionsFields(advanced = false)
        }) {
            val scroll = if (advanced) advancedScroll else simpleScroll
            RepeatOptionsFields(advanced, simple = simple, policy = policy,
                modifier = Modifier.testTag("repeat-options-scroll").scrollEdgeFade(scroll).controlVerticalScroll(scroll),
                onSimple = { simple = it }, onPolicy = { policy = it },
                onReset = { simple = QueueRepeat.Off; policy = AdvancedRepeat() })
        }
    }, confirmButton = { ActionTextButton(onClick = {
        val appliedPolicy = if (advanced) policy else null
        val appliedRepeat = if (advanced) policy.repeat else simple
        onApply { it.copy(advancedRepeat = appliedPolicy, repeat = appliedRepeat) }; onDismiss()
    }) { Text("Apply") } }, dismissButton = { ActionTextButton(onClick = onDismiss) { Text("Cancel") } },
        widthMeasurement = {
            Column(Modifier.padding(24.dp)) {
                Text("Repeat modes", style = MaterialTheme.typography.titleLarge)
                GlassChoicesMeasurement(repeatModeLabels)
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                    PopupMeasurementMaximum {
                        RepeatOptionsFields(advanced = false)
                        RepeatOptionsFields(advanced = true, policy = AdvancedRepeat(songEnd = SongEnd.Repeat))
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButtonMeasurement("Cancel"); TextButtonMeasurement("Apply")
                }
            }
        })
}

/** Omitting callbacks uses only inert shared faces: no input, glass recordings or effects. */
@Composable
private fun RepeatOptionsFields(advanced: Boolean, modifier: Modifier = Modifier,
    simple: QueueRepeat = QueueRepeat.Off, policy: AdvancedRepeat = AdvancedRepeat(),
    onSimple: ((QueueRepeat) -> Unit)? = null, onPolicy: ((AdvancedRepeat) -> Unit)? = null,
    onReset: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!advanced) RepeatChoices(QueueRepeat.entries, simple, { when (it) {
            QueueRepeat.Off -> "Play next song"; QueueRepeat.All -> "Repeat queue"; QueueRepeat.One -> "Repeat song"
        } }, onSimple)
        else {
            Text("When a song ends", style = MaterialTheme.typography.titleSmall)
            RepeatChoices(SongEnd.entries, policy.songEnd, { it.label }, onPolicy?.let { change ->
                { change(policy.copy(songEnd = it)) }
            })
            Spacer(Modifier.height(16.dp))
            Text("When the queue ends", style = MaterialTheme.typography.titleSmall)
            RepeatBoolean("Reset current song to the first song", policy.resetToFirst, onPolicy?.let { change ->
                { change(policy.copy(resetToFirst = it)) }
            })
            RepeatBoolean("Reshuffle when shuffle is enabled", policy.reshuffle, onPolicy?.let { change ->
                { change(policy.copy(reshuffle = it)) }
            })
            RepeatChoices(QueueEnd.entries, policy.queueEnd, { it.label }, onPolicy?.let { change ->
                { change(policy.copy(queueEnd = it)) }
            })
            if (policy.songEnd == SongEnd.Repeat) Text("Queue-end options apply after you turn off repeating the current song.", style = MaterialTheme.typography.bodySmall)
        }
        if (onReset == null) TextButtonMeasurement("Reset")
        else ActionTextButton(onClick = onReset) { Text("Reset") }
    }
}

@Composable
private fun <T> RepeatChoices(values: List<T>, selected: T, label: (T) -> String, onSelect: ((T) -> Unit)?) {
    if (onSelect == null) GlassChoicesMeasurement(values.map(label), horizontal = false)
    else GlassChoices(values.map(label), values.indexOf(selected), horizontal = false) { onSelect(values[it]) }
}

@Composable
private fun RepeatBoolean(label: String, value: Boolean, onValue: ((Boolean) -> Unit)?) {
    if (onValue == null) BooleanPreferenceFrame(label) { Spacer(Modifier.size(InterfaceSwitchWidth, InterfaceSwitchHeight)) }
    else BooleanPreference(label, value, onValue)
}
