package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.anastaci1a.localica.ui.layout.SettingsStack
import io.github.anastaci1a.localica.ui.spec.Space

internal data class ChoiceDialogActions(val confirm: String, val dismiss: String, val enabled: Boolean = true)

/** The original single-select dialog is unchanged. This decision stages one field value until confirmed. */
@Composable
@GlassPopupOpener
internal fun <T> ChoiceDialog(actions: ChoiceDialogActions, title: String, choices: List<T>, selected: T?,
    label: (T) -> String, onDismiss: () -> Unit, onSelect: (T) -> Unit,
    onDraft: (T) -> Unit = {}, header: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {}, onCancel: () -> Unit = onDismiss,
    isValue: (T) -> Boolean = { true }) {
    var draft by remember(choices, selected) { mutableStateOf(selected) }
    var choosing by remember { mutableStateOf(false) }
    val anchor = rememberGlassAnchor()
    fun valid(value: T?): Boolean = value != null && value in choices && isValue(value)
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        val scroll = rememberScrollState()
        SettingsStack(Modifier.fillMaxWidth().scrollEdgeFade(scroll).controlVerticalScroll(scroll), gap = Space.Base) {
            header()
            SettingsLink(draft?.takeIf { valid(it) }?.let(label) ?: "Choose…", anchor = anchor,
                enabled = actions.enabled && choices.isNotEmpty(), modifier = Modifier.testTag("lastfm-match-field")) { choosing = true }
            footer()
        }
    }, confirmButton = {
        GlassActionButton(true, AppIcon.ActionCheck, actions.confirm, enabled = actions.enabled && valid(draft)) {
            draft?.takeIf { actions.enabled && valid(it) }?.let(onSelect)
        }
    }, dismissButton = { GlassActionButton(AppIcon.ActionArrowRight, actions.dismiss, onClick = onCancel) })
    if (choosing) CompositionLocalProvider(LocalGlassOrigin provides anchor) {
        ChoiceDialog<T?>("Select match", choices, draft, { it?.let(label).orEmpty() }, { choosing = false }) { next ->
            if (actions.enabled && next != null && next in choices) {
                draft = next.takeIf(isValue)
                onDraft(next)
            }
        }
    }
}
