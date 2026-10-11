package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.anastaci1a.localica.R

@Composable
internal fun SettingsPage(title: String, onBack: () -> Unit, onClose: () -> Unit = onBack,
    backLabel: String = "Back from settings", content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(R.drawable.ic_back, backLabel, onClick = onBack)
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    ActionIcon(R.drawable.ic_close, "Close settings", onClick = onClose)
                }
                content()
            }
        }
    }
}

@Composable
internal fun SettingsLink(title: String, summary: String? = null, icon: Int? = null, enabled: Boolean = true,
    onClick: () -> Unit) {
    Surface(shape = Corners.card, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f)) {
        ListItem(headlineContent = { Text(title) }, supportingContent = summary?.let { { Text(it) } },
            leadingContent = icon?.let { { Icon(painterResource(it), null, Modifier.size(24.dp)) } },
            trailingContent = { Icon(painterResource(R.drawable.ic_back), null, Modifier.size(20.dp).rotate(180f)) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.clickable(enabled, onClick = onClick).heightIn(min = 64.dp))
    }
}

@Composable
internal fun SettingsHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp, start = 8.dp))
}

@Composable
internal fun <T> ChoiceDialog(title: String, choices: List<T>, selected: T, label: (T) -> String,
    onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
            choices.forEach { choice ->
                OptionRow(label(choice), choice == selected) { onSelect(choice); onDismiss() }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
internal fun <T> ChoicePreference(title: String, selected: T, choices: List<T>, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsLink(title, label(selected)) { open = true }
    if (open) ChoiceDialog(title, choices, selected, label, { open = false }, onSelect)
}
