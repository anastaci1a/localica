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
internal fun SettingsLink(title: String, summary: String? = null, icon: Int? = null, enabled: Boolean = true, anchor: GlassAnchor? = null,
    onClick: () -> Unit) {
    anchor?.captureInk()
    val colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    val headlineStyle = MaterialTheme.typography.bodyLarge
    val summaryStyle = MaterialTheme.typography.bodyMedium
    // The modal owns the same foreground while the live trigger is hidden. Capture
    // its colors and text styles here so nested panels keep their original ink.
    @Composable fun Foreground(modifier: Modifier = Modifier) {
        ListItem(headlineContent = { Text(title, style = headlineStyle) },
            supportingContent = summary?.let { { Text(it, style = summaryStyle) } },
            leadingContent = icon?.let { { Icon(painterResource(it), null, Modifier.size(24.dp)) } },
            trailingContent = { Icon(painterResource(R.drawable.ic_back), null, Modifier.size(20.dp).rotate(180f)) },
            colors = colors, modifier = modifier.heightIn(min = 64.dp))
    }
    SideEffect { if (anchor != null) anchor.face = { Foreground() } }
    LiquidGlassSurface(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f)
        .then(if (anchor == null) Modifier else Modifier.glassAnchor(anchor)), cornerRadius = 20.dp, pressFeedback = enabled) {
        Foreground(Modifier.ownButtonDrag(enabled).clickable(enabled, role = Role.Button, onClick = onClick))
    }
}

@Composable
internal fun SettingsHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp))
}

@Composable
internal fun <T> ChoiceDialog(title: String, choices: List<T>, selected: T, label: (T) -> String,
    onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    var draft by remember { mutableStateOf(choices.firstOrNull { it == selected } ?: choices.firstOrNull() ?: selected) }
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        val scroll = rememberScrollState()
        Column(Modifier.scrollEdgeFade(scroll).verticalScroll(scroll).padding(horizontal = 8.dp, vertical = 12.dp).selectableGroup()) {
            GlassChoices(choices.map(label), choices.indexOf(draft).coerceAtLeast(0)) { index -> draft = choices[index] }
        }
    }, confirmButton = { ActionTextButton(enabled = choices.isNotEmpty(), onClick = { onSelect(draft); onDismiss() }) { Text("Select") } },
        dismissButton = { ActionTextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
internal fun <T> ChoicePreference(title: String, selected: T, choices: List<T>, label: (T) -> String,
    popup: Boolean = false, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val anchor = rememberGlassAnchor()
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val labels = choices.map(label)
    val style = MaterialTheme.typography.bodyLarge
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val required = with(density) { (labels.maxOfOrNull { measurer.measure(androidx.compose.ui.text.AnnotatedString(it), style).size.width } ?: 0).toDp() + 32.dp }
        if (!popup && choices.size in 2..3 && required * choices.size <= maxWidth) Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            GlassChoices(labels, choices.indexOf(selected).coerceAtLeast(0), horizontal = true) { onSelect(choices[it]) }
        } else SettingsLink(title, label(selected), anchor = anchor) { open = true }
    }
    if (open) CompositionLocalProvider(LocalGlassOrigin provides anchor) {
        ChoiceDialog(title, choices, selected, label, { open = false }, onSelect)
    }
}
