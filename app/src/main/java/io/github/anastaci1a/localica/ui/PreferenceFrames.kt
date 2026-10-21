package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import io.github.anastaci1a.localica.R

/** Stateless field faces shared by live controls and popup envelope measurement. */
@Composable
internal fun SettingsLinkFace(title: String, summary: String?, icon: Int?, modifier: Modifier = Modifier,
    headlineStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    summaryStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    colors: ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)) {
    ListItem(headlineContent = { Text(title, style = headlineStyle) },
        supportingContent = summary?.let { { Text(it, style = summaryStyle) } },
        leadingContent = icon?.let { { Icon(painterResource(it), null, Modifier.size(24.dp)) } },
        trailingContent = { Icon(painterResource(R.drawable.ic_back), null, Modifier.size(20.dp).rotate(180f)) },
        colors = colors, modifier = modifier.heightIn(min = 64.dp))
}

internal val ChoiceOuterVerticalInset = 4.dp
internal val ChoiceInnerInset = 4.dp
internal val InterfaceSliderHeight = 48.dp
internal val InterfaceSwitchWidth = 60.dp
internal val InterfaceSwitchHeight = 48.dp
@Composable internal fun glassChoiceCellHeight() = (52 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp

@Composable
internal fun SliderPreferenceFrame(label: String, low: String?, high: String?, description: String?,
    track: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        track()
        if (low != null && high != null) SliderEndpoints(low, high)
        if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun BooleanPreferenceFrame(label: String, modifier: Modifier = Modifier, control: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        control()
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
        (labels.maxOfOrNull { measurer.measure(androidx.compose.ui.text.AnnotatedString(it), style).size.width } ?: 0).toDp() + 32.dp
    }
}

/** The adaptive choice decision is identical for live content and measurement. */
@Composable
internal fun ChoicePreferenceFrame(title: String, labels: List<String>, popup: Boolean,
    choices: @Composable () -> Unit, link: @Composable () -> Unit) {
    val required = choiceRequiredCellWidth(labels)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (choiceFitsInline(labels.size, required.value, maxWidth.value, popup)) Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            choices()
        } else link()
    }
}

/** Material text-button sizing without installing its interaction or semantics owners. */
@Composable
internal fun TextButtonMeasurement(label: String) {
    Box(Modifier.minimumInteractiveComponentSize()) {
        Row(Modifier.defaultMinSize(minWidth = ButtonDefaults.MinWidth, minHeight = ButtonDefaults.MinHeight)
            .padding(ButtonDefaults.TextButtonContentPadding),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun GlassChoiceFace(label: String, icon: Int? = null, tab: Boolean = false) {
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon?.let { Icon(painterResource(it), null, Modifier.size(24.dp)) }
        Text(label, style = if (tab) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** Pure equal-cell geometry without selector/input owners. */
@Composable
internal fun GlassChoicesMeasurement(labels: List<String>, icons: List<Int>? = null, tab: Boolean = false,
    horizontal: Boolean = true) {
    if (labels.isEmpty()) return
    val height = glassChoiceCellHeight()
    androidx.compose.ui.layout.Layout(modifier = Modifier.padding(vertical = ChoiceOuterVerticalInset).padding(ChoiceInnerInset),
        content = { labels.forEachIndexed { index, label -> GlassChoiceFace(label, icons?.getOrNull(index), tab) } },
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
