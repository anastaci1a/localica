package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.github.anastaci1a.localica.ui.spec.*

/** Stock settings slots share validated spacing without changing caller semantics or hit targets. */
@Composable internal fun SettingsOptionBody(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(LocalUiSpec.current.spec.space.getValue(Space.Base)), content = content)
}

@Composable internal fun SettingsOptionActions(content: @Composable RowScope.() -> Unit) {
    Row(content = content)
}

@Composable internal fun SettingsDialogBody(content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LocalUiSpec.current.spec.space.getValue(Space.Base)), content = content)
}

/** A settings page body: the caller's scroll chain, then the page inset and the compact row gap. */
@Composable internal fun SettingsPageColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val spec = LocalUiSpec.current.spec
    Column(modifier.padding(spec.space.getValue(Space.Section)),
        verticalArrangement = Arrangement.spacedBy(spec.space.getValue(Space.Compact)), content = content)
}

/** Stacked rows inside one settings entry (folders, tabs, filters, packs) at the compact row gap. */
@Composable internal fun SettingsEntryList(content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LocalUiSpec.current.spec.space.getValue(Space.Compact)), content = content)
}

@Composable internal fun SettingsCaption(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, content = content)
}

@Composable internal fun SettingsEntryHost(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().then(modifier), content = content)
}

@Composable internal fun BoxScope.SettingsHighlightMarker(modifier: Modifier) {
    Box(Modifier.matchParentSize().then(modifier))
}

@Composable internal fun PreferenceTrailingSlot(modifier: Modifier, content: @Composable () -> Unit) { Box(modifier) { content() } }

@Composable internal fun SettingsPreferenceRow(compact: Boolean, inset: Dp, textInset: Dp, modifier: Modifier, rowPart: Modifier,
    label: @Composable (Modifier) -> Unit, control: @Composable () -> Unit) {
    val spec = LocalUiSpec.current.spec
    val vertical = if (compact) PaddingValues() else PaddingValues(vertical = spec.space.getValue(Space.Tight))
    Row(modifier.fillMaxWidth().heightIn(min = spec.control.getValue(
        if (compact) ControlSize.PreferenceCompact else ControlSize.PreferenceRow))
        .padding(vertical).then(rowPart).padding(horizontal = inset), verticalAlignment = Alignment.CenterVertically) {
        label(Modifier.weight(1f).padding(start = textInset, end = spec.space.getValue(Space.Base)))
        control()
    }
}

/** Inert Material text-button geometry, shared with envelope measurement; no extra interaction owner. */
@Composable internal fun SettingsTextButtonMeasurement(content: @Composable () -> Unit) {
    Box(Modifier.minimumInteractiveComponentSize()) {
        Row(Modifier.defaultMinSize(minWidth = ButtonDefaults.MinWidth, minHeight = ButtonDefaults.MinHeight)
            .padding(ButtonDefaults.TextButtonContentPadding),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable internal fun SettingsChoiceFace(icon: (@Composable (Modifier) -> Unit)?, content: @Composable () -> Unit) {
    val spec = LocalUiSpec.current.spec
    Row(Modifier.padding(horizontal = spec.space.getValue(Space.Base)), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spec.space.getValue(Space.Compact))) {
        icon?.invoke(Modifier.size(spec.control.getValue(ControlSize.SettingsIcon)))
        content()
    }
}
