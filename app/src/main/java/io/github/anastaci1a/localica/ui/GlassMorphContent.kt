package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Two faces inside one glass surface, sharing the modal glass morph's interruptible clock.
 * Both sizes remain measurable; only the destination's children are placed. The outgoing
 * face is a retained drawing, so it cannot accept input or accessibility during the morph. */
@Composable
internal fun GlassMorphContent(expanded: Boolean, modifier: Modifier = Modifier,
    compact: @Composable () -> Unit, content: @Composable () -> Unit) {
    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    val reduced = LocalReducedMotion.current
    val speed = LocalMotionSpeed.current
    LaunchedEffect(expanded, reduced, speed) {
        val target = if (expanded) 1f else 0f
        if (reduced) progress.snapTo(target) else progress.animateTo(target, glassMorphSpring(speed))
    }
    fun phase() = if (reduced) { if (expanded) 1f else 0f } else progress.value.coerceIn(0f, 1f)
    Layout(modifier = modifier, content = {
        RetainedGlassFace(!expanded, { 1f - phase() }, content = compact)
        RetainedGlassFace(expanded, { phase() }, content = content)
    }) { children, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val small = children[0].measure(loose)
        val large = children[1].measure(loose)
        val p = phase()
        val width = (small.width + (large.width - small.width) * p).roundToInt()
        val height = (small.height + (large.height - small.height) * p).roundToInt()
        layout(width, height) {
            small.placeRelative((width - small.width) / 2, (height - small.height) / 2)
            large.placeRelative((width - large.width) / 2, (height - large.height) / 2)
        }
    }
}

/** Outgoing children are immediately unplaced. Floating exits also retain their measured
 * footprint, so hidden content changes cannot move the surface or adjacent controls. */
@Composable
internal fun RetainedGlassFace(visible: Boolean, alpha: () -> Float, retainShadow: Boolean = false,
    retainSize: Boolean = false,
    content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val measurement = remember { RetainedGlassMeasurement() }
    val inset = if (retainShadow) glassShadowInset() else 0.dp
    Layout(modifier = Modifier.then(if (visible) Modifier else Modifier.clearAndSetSemantics { })
        .drawWithContent {
            val pad = inset.roundToPx()
            if (visible) layer.record(size = IntSize(size.width.toInt() + pad * 2, size.height.toInt() + pad * 2)) {
                translate(pad.toFloat(), pad.toFloat()) { this@drawWithContent.drawContent() }
            }
            val opacity = alpha()
            if (opacity > 0f && layer.size.width > 0) {
                layer.alpha = opacity
                translate(-pad.toFloat(), -pad.toFloat()) { drawLayer(layer) }
            }
        }, content = { Box { content() } }) { children, constraints ->
        val child = children.single().measure(constraints)
        if (visible || measurement.size == IntSize.Zero) measurement.size = IntSize(child.width, child.height)
        val size = if (retainSize && !visible) measurement.size else IntSize(child.width, child.height)
        layout(size.width, size.height) { if (visible) child.place(0, 0) }
    }
}

private class RetainedGlassMeasurement { var size = IntSize.Zero }
