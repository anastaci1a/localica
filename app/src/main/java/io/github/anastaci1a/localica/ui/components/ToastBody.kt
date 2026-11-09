package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import io.github.anastaci1a.localica.toast.ToastCommand
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import io.github.anastaci1a.localica.ui.spec.*

/** Stock row content; the one body action and its separate trailing action share presenter claims. */
@Composable internal fun ToastBody(model: ToastContent, style: TextStyle,
    artwork: @Composable (String, Modifier) -> Unit, action: @Composable (String, () -> Unit, Boolean, Modifier) -> Unit) {
    val spec = LocalUiSpec.current.spec
    val gap = spec.space.getValue(Space.Compact)
    val base = spec.space.getValue(Space.Base)
    val tappable = model.entry.content.tap?.let { it !is ToastCommand.Operation } == true
    val click = if (tappable) Modifier.clickable(enabled = model.enabled, onClick = model.bodyAction) else Modifier
    ToastContentLayout(Modifier.fillMaxWidth().heightIn(min = spec.control.getValue(ControlSize.Compact))
        .then(click).padding(base), gap = gap,
        artwork = { if (model.entry.content.artworkUris.isNotEmpty()) ToastArtworkFan(model.entry.content.artworkUris, artwork) },
        action = { model.entry.content.action?.takeUnless { it.command is ToastCommand.Operation }?.let {
            action(it.label, model.action, model.enabled, Modifier.testTag("toast713-action"))
        } }) {
        ToastReadingText(model.entry.id to model.entry.generation, model.entry.content.message,
            model.readingPaused, style, model.pending, model.read, model.readingReady,
            Modifier.testTag("toast713-message"))
    }
}

/** Offset covers reuse the ordinary artwork loading and placeholder callback. Geometry is static. */
@Composable private fun ToastArtworkFan(uris: List<String>, artwork: @Composable (String, Modifier) -> Unit) {
    val spec = LocalUiSpec.current.spec
    val covers = uris.take(3)
    val size = spec.control.getValue(ControlSize.MiniArtwork)
    val step = spec.space.getValue(Space.Tight)
    Box(Modifier.size(size + step * (covers.size - 1)).clearAndSetSemantics {}) {
        covers.indices.reversed().forEach { index ->
            artwork(covers[index], Modifier.offset(step * index, step * (covers.size - index - 1)).size(size))
        }
    }
}

/** Reserve one stock compact-control width for reading before measuring optional visuals/actions.
 * At exceptionally narrow widths the text owns the remaining positive pixel; material is unclipped. */
@Composable private fun ToastContentLayout(modifier: Modifier, gap: androidx.compose.ui.unit.Dp,
    artwork: @Composable () -> Unit, action: @Composable () -> Unit, text: @Composable () -> Unit) {
    val reserve = LocalUiSpec.current.spec.control.getValue(ControlSize.Compact)
    Layout(content = { Box { artwork() }; Box { action() }; Box { text() } }, modifier = modifier) { nodes, limits ->
        val width = limits.maxWidth
        val textReserve = reserve.roundToPx().coerceAtLeast(1).coerceAtMost(width)
        val gapPx = gap.roundToPx()
        val optional = (width - textReserve - 2 * gapPx).coerceAtLeast(0)
        val actionNode = nodes[1].measure(Constraints(maxWidth = optional, maxHeight = limits.maxHeight))
        val artNode = nodes[0].measure(Constraints(maxWidth = (optional - actionNode.width).coerceAtLeast(0), maxHeight = limits.maxHeight))
        val artGap = if (artNode.width > 0) gapPx.coerceAtMost((width - textReserve - artNode.width - actionNode.width).coerceAtLeast(0)) else 0
        val actionGap = if (actionNode.width > 0) gapPx.coerceAtMost((width - textReserve - artNode.width - actionNode.width - artGap).coerceAtLeast(0)) else 0
        val textWidth = (width - artNode.width - actionNode.width - artGap - actionGap).coerceAtLeast(0)
        val textNode = nodes[2].measure(Constraints(minWidth = textWidth, maxWidth = textWidth, maxHeight = limits.maxHeight))
        val height = maxOf(artNode.height, actionNode.height, textNode.height).coerceIn(limits.minHeight, limits.maxHeight)
        layout(width, height) {
            artNode.placeRelative(0, (height - artNode.height) / 2)
            textNode.placeRelative(artNode.width + artGap, (height - textNode.height) / 2)
            actionNode.placeRelative(width - actionNode.width, (height - actionNode.height) / 2)
        }
    }
}
