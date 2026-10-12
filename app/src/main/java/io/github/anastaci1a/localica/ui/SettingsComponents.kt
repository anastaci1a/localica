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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R

@Composable
internal fun SettingsPage(title: String, onBack: () -> Unit, onClose: () -> Unit = onBack,
    backLabel: String = "Back from settings", navigationDepth: Int = 0, content: @Composable ColumnScope.() -> Unit) {
    var previousDepth by remember { mutableIntStateOf(navigationDepth) }
    var direction by remember { mutableFloatStateOf(1f) }
    if (navigationDepth != previousDepth) {
        direction = if (navigationDepth < previousDepth) -1f else 1f
        previousDepth = navigationDepth
    }
    GlassDialog(onBack, GlassPlacement.Page) {
        Surface(Modifier.fillMaxSize()) {
            val reduced = LocalReducedMotion.current
            val speed = LocalMotionSpeed.current
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 64.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(R.drawable.ic_back, backLabel, onClick = onBack)
                    androidx.compose.animation.Crossfade(title, Modifier.weight(1f),
                        androidx.compose.animation.core.tween(if (reduced) 0 else (220 / speed).toInt()), label = "Settings heading") {
                        Text(it, style = MaterialTheme.typography.titleLarge)
                    }
                    ActionIcon(R.drawable.ic_close, "Close settings", onClick = onClose)
                }
                key(title) {
                    var entered by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { entered = true }
                    val reveal by androidx.compose.animation.core.animateFloatAsState(if (entered) 1f else 0f,
                        androidx.compose.animation.core.tween(if (reduced) 0 else (240 / speed).toInt()), label = "Settings page")
                    Column(Modifier.weight(1f).graphicsLayer { alpha = reveal; translationX = direction * 16.dp.toPx() * (1f - reveal) }, content = content)
                }
            }
        }
    }
}

@Composable
internal fun SettingsLink(title: String, summary: String? = null, icon: Int? = null, enabled: Boolean = true,
    onClick: () -> Unit) {
    LiquidGlassSurface(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f), cornerRadius = 20.dp) {
        ListItem(headlineContent = { Text(title) }, supportingContent = summary?.let { { Text(it) } },
            leadingContent = icon?.let { { Icon(painterResource(it), null, Modifier.size(24.dp)) } },
            trailingContent = { Icon(painterResource(R.drawable.ic_back), null, Modifier.size(20.dp).rotate(180f)) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
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
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
            GlassChoices(choices.map(label), choices.indexOf(selected).coerceAtLeast(0)) { index -> onSelect(choices[index]); onDismiss() }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
internal fun <T> ChoicePreference(title: String, selected: T, choices: List<T>, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsLink(title, label(selected)) { open = true }
    if (open) ChoiceDialog(title, choices, selected, label, { open = false }, onSelect)
}
