package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.layout.SettingsStack

/** Required config distinguishes this overload; existing ChoiceDialog calls remain unchanged. */
internal data class ChoiceDialogActions(val confirm: String, val dismiss: String, val enabled: Boolean = true)

@Composable
internal fun <T> ChoiceDialog(actions: ChoiceDialogActions, title: String, choices: List<T>, selected: T?,
    label: (T) -> String, onDismiss: () -> Unit, onSelect: (T) -> Unit,
    onDraft: (T) -> Unit = {}, header: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {}) {
    var draft by remember(choices, selected) { mutableStateOf(selected) }
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        val scroll = rememberScrollState()
        SettingsStack(Modifier.fillMaxWidth().scrollEdgeFade(scroll).controlVerticalScroll(scroll)) {
            header()
            GlassChoices(choices.map(label), choices.indexOf(draft)) { index ->
                draft = choices[index]; onDraft(choices[index])
            }
            footer()
        }
    }, confirmButton = {
        GlassActionButton(AppIcon.ActionSave, actions.confirm, enabled = actions.enabled && draft != null && draft in choices) { draft?.takeIf { actions.enabled && it in choices }?.let(onSelect) }
    }, dismissButton = { GlassActionButton(AppIcon.ActionForward, actions.dismiss, onClick = onDismiss) })
}
