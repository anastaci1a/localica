package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize

/** Nonmodal glass emergence with stable measurement and a draw-only retained exit.
 * Unplaced children cannot receive taps/focus/accessibility during disappearance.
 * Keep this call composed when hidden; a reversal continues from its displayed phase. */
@Composable
internal fun GlassPresence(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    val layer = rememberGraphicsLayer()
    val reduced = LocalReducedMotion.current
    val speed = LocalMotionSpeed.current
    val inset = glassShadowInset()
    LaunchedEffect(visible, reduced, speed) {
        val target = if (visible) 1f else 0f
        if (reduced) progress.snapTo(target) else progress.animateTo(target, glassMorphSpring(speed))
    }
    // The retained layer owns the whole material, including its shadow. Hoisting
    // that shadow outside this lifetime would leave a dark silhouette on exit.
    CompositionLocalProvider(LocalSurfaceShadows provides null, LocalBottomShadows provides null) {
        Layout(modifier = modifier.then(if (visible) Modifier else Modifier.clearAndSetSemantics { })
            .drawWithContent {
                val pad = inset.roundToPx()
                if (visible) layer.record(size = IntSize(size.width.toInt() + pad * 2, size.height.toInt() + pad * 2)) {
                    translate(pad.toFloat(), pad.toFloat()) { this@drawWithContent.drawContent() }
                }
                val phase = if (reduced) { if (visible) 1f else 0f } else progress.value.coerceIn(0f, 1f)
                if (phase > 0f && layer.size.width > 0) {
                    // A compact surface resolves into its resting footprint, driven
                    // by the shared interruptible material spring rather than a fade timer.
                    layer.scaleX = .82f + .18f * phase
                    layer.scaleY = .82f + .18f * phase
                    layer.alpha = phase
                    translate(-pad.toFloat(), -pad.toFloat()) { drawLayer(layer) }
                }
            }, content = { Box { content() } }) { children, constraints ->
            val child = children.single().measure(constraints)
            layout(child.width, child.height) { if (visible) child.place(0, 0) }
        }
    }
}
