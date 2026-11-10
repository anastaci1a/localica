package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.ui.layout.settingsLinkMarker
import androidx.compose.ui.graphics.Color

@Composable
internal fun SettingsPage(title: String, onBack: () -> Unit, onClose: () -> Unit = onBack,
    backLabel: String = "Back from settings", navigationDepth: Int = 0, onSystemBack: () -> Unit = onBack, content: @Composable ColumnScope.() -> Unit) {
    SettingsNavigationPage(title, { it }, onBack, onClose, backLabel, navigationDepth, onSystemBack) { content() }
}

@Composable
internal fun <T : Any> SettingsNavigationPage(target: T, title: (T) -> String, onBack: () -> Unit, onClose: () -> Unit = onBack,
    backLabel: String = "Back from settings", navigationDepth: Int = 0, onSystemBack: () -> Unit = onBack,
    content: @Composable ColumnScope.(T) -> Unit) {
    var previousDepth by remember { mutableIntStateOf(navigationDepth) }
    var direction by remember { mutableFloatStateOf(1f) }
    if (navigationDepth != previousDepth) {
        direction = if (navigationDepth < previousDepth) -1f else 1f
        previousDepth = navigationDepth
    }
    GlassDialog(onSystemBack, GlassPlacement.Page) {
        GlassPopupNavigation(target, Modifier.fillMaxSize(), direction) { shown ->
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 64.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(AppIcon.ActionChevronLeft, backLabel, onClick = onBack)
                    Text(title(shown), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    ActionIcon(AppIcon.ActionClose, "Close settings", onClick = onClose)
                }
                // #655: flat rows on settings pages line up with the text inside glass links.
                CompositionLocalProvider(LocalSettingsRowMetrics provides SettingsRowMetrics(inset = SettingsRowInset)) {
                    Column(Modifier.weight(1f)) { content(shown) }
                }
            }
        }
        }
    }
}

@Composable
internal fun SettingsLink(title: String, summary: String? = null, icon: AppIcon? = null, enabled: Boolean = true, anchor: GlassAnchor? = null,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    SettingsLink(markerColor = null, title = title, summary = summary, icon = icon, enabled = enabled, anchor = anchor, modifier = modifier, onClick = onClick)
}

@Composable
internal fun SettingsLink(markerColor: Color?, title: String, summary: String? = null, icon: AppIcon? = null, enabled: Boolean = true, anchor: GlassAnchor? = null,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    anchor?.captureInk()
    // Retained modal foreground keeps the source composition’s ink and typography.
    val colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    val headlineStyle = MaterialTheme.typography.bodyLarge
    val summaryStyle = MaterialTheme.typography.bodyMedium
    // Captured here so the retained modal face keeps this page's icon slot.
    val metrics = LocalSettingsRowMetrics.current
    @Composable fun Foreground(modifier: Modifier = Modifier) {
        SettingsLinkFace(title, summary, icon, modifier.settingsLinkMarker(markerColor), headlineStyle, summaryStyle, colors, metrics)
    }
    SideEffect { if (anchor != null) anchor.face = { Foreground() } }
    val opacity by animateControlStateFloat(if (enabled) 1f else .45f, enabled, "Settings link availability")
    SettingsEntry(title) { LiquidGlassSurface(Modifier.fillMaxWidth().glassShadowOpacity { opacity }
        .then(if (anchor == null) Modifier else Modifier.glassAnchor(anchor)), cornerRadius = 20.dp, pressFeedback = enabled) {
        Foreground(modifier.ownButtonDrag(enabled).clickable(enabled, role = Role.Button, onClick = controlActivation(enabled, onClick)))
    }
} }

@Composable
internal fun SettingsHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = LocalSettingsRowMetrics.current.textStart, top = 16.dp, bottom = 4.dp)
            .settingsRowPart(SettingsRowPart.Heading))
}

@Composable
@GlassPopupOpener
internal fun <T> ChoiceDialog(title: String, choices: List<T>, selected: T, label: (T) -> String,
    onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    var draft by remember { mutableStateOf(choices.firstOrNull { it == selected } ?: choices.firstOrNull() ?: selected) }
    GlassAlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        val scroll = rememberScrollState()
        Column(Modifier.scrollEdgeFade(scroll).controlVerticalScroll(scroll).padding(horizontal = 8.dp, vertical = 12.dp).selectableGroup()) {
            GlassChoices(choices.map(label), choices.indexOf(draft).coerceAtLeast(0)) { index -> draft = choices[index] }
        }
    }, confirmButton = { ActionTextButton(enabled = choices.isNotEmpty(), onClick = { onSelect(draft); onDismiss() }) { Text("Select") } },
        dismissButton = { ActionTextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
internal fun <T> ChoicePreference(title: String, selected: T, choices: List<T>, label: (T) -> String,
    popup: Boolean = false, onSelect: (T) -> Unit) {
    if (!LocalSettingsEnabled.current) {
        SettingsLink(title, label(selected), enabled = false) {}
        return
    }
    var open by remember { mutableStateOf(false) }
    val anchor = rememberGlassAnchor()
    val labels = choices.map(label)
    SettingsEntry(title) { ChoicePreferenceFrame(title, labels, popup, choices = {
        GlassChoices(labels, choices.indexOf(selected).coerceAtLeast(0), horizontal = true) { onSelect(choices[it]) }
    }, link = { SettingsLink(title, label(selected), anchor = anchor) { open = true } })
    }
    if (open) CompositionLocalProvider(LocalGlassOrigin provides anchor) {
        ChoiceDialog(title, choices, selected, label, { open = false }, onSelect)
    }
}

