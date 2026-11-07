package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import io.github.anastaci1a.localica.R

/** Opt-in compact rows keep the shared switch and the full 48dp input target. */
internal val LocalCompactPreferenceFrames = androidx.compose.runtime.compositionLocalOf { false }

/** Geometry-test handles for the shared settings row parts (unmerged semantics tree). */
internal enum class SettingsRowPart { Row, Icon, Text, Trailing, Heading }
internal val SettingsRowPartKey = SemanticsPropertyKey<SettingsRowPart>("SettingsRowPart")
internal fun Modifier.settingsRowPart(part: SettingsRowPart) =
    semantics { this[SettingsRowPartKey] = part }

internal val SettingsRowInset = 16.dp
internal val SettingsIconSize = 24.dp
/** The leading icon slot: the icon plus its gap to the text. */
internal val SettingsIconSlotWidth = 40.dp

/**
 * One settings-row standard (#655): links, switches, sliders, choices and headings start their text at [textStart].
 * Settings pages provide [inset] so flat rows line up with the text inside glass links, and [iconSlot] on pages
 * whose rows carry icons, so rows without one keep the empty slot. Popups keep the default (no inset, no slot).
 */
@androidx.compose.runtime.Immutable
internal data class SettingsRowMetrics(val inset: Dp = 0.dp, val iconSlot: Boolean = false) {
    val textStart: Dp get() = inset + if (iconSlot) SettingsIconSlotWidth else 0.dp
}
internal val LocalSettingsRowMetrics = androidx.compose.runtime.compositionLocalOf { SettingsRowMetrics() }

/**
 * Stateless field faces shared by live controls and popup envelope measurement.
 * The icon sits in a fixed-width slot and the icon and chevron stay centred on the row at any line count or font scale.
 */
@Composable
internal fun SettingsLinkFace(title: String, summary: String?, icon: AppIcon?, modifier: Modifier = Modifier,
    headlineStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    summaryStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    colors: ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent),
    metrics: SettingsRowMetrics = LocalSettingsRowMetrics.current) {
    Row(modifier.heightIn(min = 64.dp).settingsRowPart(SettingsRowPart.Row)
        .padding(horizontal = SettingsRowInset, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null || metrics.iconSlot) Box(Modifier.width(SettingsIconSlotWidth), contentAlignment = Alignment.CenterStart) {
            icon?.let { Icon(appIconPainter(it), null, Modifier.size(SettingsIconSize).settingsRowPart(SettingsRowPart.Icon),
                tint = colors.leadingIconColor) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.settingsRowPart(SettingsRowPart.Text), style = headlineStyle, color = colors.headlineColor)
            summary?.let { Text(it, style = summaryStyle, color = colors.supportingTextColor) }
        }
        Icon(appIconPainter(AppIcon.ActionBack), null, Modifier.padding(start = 16.dp).size(20.dp).rotate(180f)
            .settingsRowPart(SettingsRowPart.Trailing), tint = colors.trailingIconColor)
    }
}

internal val ChoiceOuterVerticalInset = 4.dp
internal val ChoiceInnerInset = 4.dp
internal val ChoiceLabelHorizontalInset = 12.dp
internal val InterfaceSliderHeight = 48.dp
internal val InterfaceSwitchWidth = 60.dp
internal val InterfaceSwitchHeight = 48.dp
@Composable internal fun glassChoiceCellHeight() = (52 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp

@Composable
internal fun SliderPreferenceFrame(label: String, low: String?, high: String?, description: String?,
    track: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        val textStart = LocalSettingsRowMetrics.current.textStart
        Text(label, Modifier.padding(start = textStart).settingsRowPart(SettingsRowPart.Text), style = MaterialTheme.typography.titleMedium)
        track()
        if (low != null && high != null) SliderEndpoints(low, high)
        if (description != null) Text(description, Modifier.padding(start = textStart), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun BooleanPreferenceFrame(label: String, modifier: Modifier = Modifier, control: @Composable () -> Unit) {
    val compact = LocalCompactPreferenceFrames.current
    val metrics = LocalSettingsRowMetrics.current
    Row(modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 56.dp)
        .padding(vertical = if (compact) 0.dp else 4.dp).settingsRowPart(SettingsRowPart.Row).padding(horizontal = metrics.inset),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(start = metrics.textStart - metrics.inset, end = 12.dp).settingsRowPart(SettingsRowPart.Text))
        Box(Modifier.settingsRowPart(SettingsRowPart.Trailing)) { control() }
    }
}

/** One width decision for live choices and their inert measurement faces. */
internal fun choiceFitsInline(count: Int, cellWidth: Float, availableWidth: Float, popup: Boolean) =
    !popup && count in 2..3 && cellWidth * count <= availableWidth

@Composable
internal fun choiceRequiredCellWidth(labels: List<String>): androidx.compose.ui.unit.Dp {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyLarge
    return with(density) {
        (labels.maxOfOrNull { measurer.measure(androidx.compose.ui.text.AnnotatedString(it), style).size.width } ?: 0).toDp() +
            (ChoiceInnerInset + ChoiceLabelHorizontalInset) * 2
    }
}

/** The adaptive choice decision is identical for live content and measurement. */
@Composable
internal fun ChoicePreferenceFrame(title: String, labels: List<String>, popup: Boolean,
    choices: @Composable () -> Unit, link: @Composable () -> Unit) {
    val required = choiceRequiredCellWidth(labels)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (choiceFitsInline(labels.size, required.value, maxWidth.value, popup)) Column {
            Text(title, Modifier.padding(start = LocalSettingsRowMetrics.current.textStart).settingsRowPart(SettingsRowPart.Text),
                style = MaterialTheme.typography.titleMedium)
            choices()
        } else link()
    }
}

/** Material text-button sizing without installing its interaction or semantics owners. */
@Composable
internal fun TextButtonMeasurement(label: String, labelStyle: TextStyle? = null) {
    Box(Modifier.minimumInteractiveComponentSize()) {
        Row(Modifier.defaultMinSize(minWidth = ButtonDefaults.MinWidth, minHeight = ButtonDefaults.MinHeight)
            .padding(ButtonDefaults.TextButtonContentPadding),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = labelStyle ?: MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun GlassChoiceFace(label: String, icon: AppIcon? = null, tab: Boolean = false, labelStyle: TextStyle? = null) {
    Row(Modifier.padding(horizontal = ChoiceLabelHorizontalInset), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon?.let { Icon(appIconPainter(it), null, Modifier.size(24.dp)) }
        Text(label, style = labelStyle ?: if (tab) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** Pure equal-cell geometry without selector/input owners. */
@Composable
internal fun GlassChoicesMeasurement(labels: List<String>, icons: List<AppIcon>? = null, tab: Boolean = false,
    horizontal: Boolean = true, cellHeight: Dp? = null, labelStyle: TextStyle? = null,
    outerVerticalInset: Dp = ChoiceOuterVerticalInset) {
    if (labels.isEmpty()) return
    val height = cellHeight ?: glassChoiceCellHeight()
    androidx.compose.ui.layout.Layout(modifier = Modifier.padding(vertical = outerVerticalInset).padding(ChoiceInnerInset),
        content = { labels.forEachIndexed { index, label -> GlassChoiceFace(label, icons?.getOrNull(index), tab, labelStyle) } },
        measurePolicy = object : androidx.compose.ui.layout.MeasurePolicy {
            override fun androidx.compose.ui.layout.IntrinsicMeasureScope.maxIntrinsicHeight(
                children: List<androidx.compose.ui.layout.IntrinsicMeasurable>, width: Int): Int =
                height.roundToPx() * (if (horizontal) 1 else children.size)

            override fun androidx.compose.ui.layout.IntrinsicMeasureScope.minIntrinsicHeight(
                children: List<androidx.compose.ui.layout.IntrinsicMeasurable>, width: Int): Int =
                height.roundToPx() * (if (horizontal) 1 else children.size)

            override fun androidx.compose.ui.layout.IntrinsicMeasureScope.maxIntrinsicWidth(
                children: List<androidx.compose.ui.layout.IntrinsicMeasurable>, height: Int): Int =
                (children.maxOfOrNull { it.maxIntrinsicWidth(height) } ?: 0) * (if (horizontal) children.size else 1)

            override fun androidx.compose.ui.layout.IntrinsicMeasureScope.minIntrinsicWidth(
                children: List<androidx.compose.ui.layout.IntrinsicMeasurable>, height: Int): Int =
                (children.maxOfOrNull { it.minIntrinsicWidth(height) } ?: 0) * (if (horizontal) children.size else 1)

            override fun androidx.compose.ui.layout.MeasureScope.measure(children: List<androidx.compose.ui.layout.Measurable>, constraints: androidx.compose.ui.unit.Constraints): androidx.compose.ui.layout.MeasureResult {
                require(constraints.hasBoundedWidth)
                val width = constraints.maxWidth
                val cellHeight = if (horizontal) constraints.constrainHeight(height.roundToPx()) else height.roundToPx()
                val cellWidth = if (horizontal) width / children.size else width
                val totalHeight = constraints.constrainHeight(cellHeight * (if (horizontal) 1 else children.size))
                children.forEach { it.measure(androidx.compose.ui.unit.Constraints.fixed(cellWidth, cellHeight)) }
                return layout(width, totalHeight) { }
            }

        })
}
