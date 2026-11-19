package io.github.anastaci1a.localica.ui.engine

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer

/** The opacity is read in the draw phase from the owning surface's phase, exactly as a graphicsLayer block would. */
internal fun Modifier.presentedAlpha(alpha: () -> Float): Modifier = graphicsLayer { this.alpha = alpha() }

/** A stacking slot inside a surface; screens supply its content only. */
@Composable internal fun SurfaceLayer(modifier: Modifier = Modifier, contentAlignment: Alignment = Alignment.TopStart, content: @Composable BoxScope.() -> Unit) =
    Box(modifier, contentAlignment, content = content)

/** #748 R5: a popup's optical body B and dependency D; consumers draw D, then B. The restored source empties B and emits
 * D -> empty B at its draw entry; the popup empties D before B records optics again, in one step, so no cycle forms. */
internal class OpticalBody(private val body: androidx.compose.ui.graphics.layer.GraphicsLayer, private val dependency: androidx.compose.ui.graphics.layer.GraphicsLayer) {
    private var linked = false
    fun withdraw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, take: Boolean) = with(scope) {
        if (take) { body.record { }; dependency.record { drawLayer(body) }; linked = true }
        drawLayer(dependency)
    }
    /** Whether D carried a withdrawal and was emptied first. */
    fun refill(scope: androidx.compose.ui.graphics.drawscope.DrawScope, optics: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) =
        with(scope) { linked.also { if (it) dependency.record { }; linked = false; body.record(block = optics) } }
    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) { drawLayer(dependency); drawLayer(body) }
}
@Composable internal fun rememberOpticalBody(): OpticalBody = androidx.compose.ui.graphics.rememberGraphicsLayer().let { body ->
    androidx.compose.ui.graphics.rememberGraphicsLayer().let { dependency -> androidx.compose.runtime.remember(body, dependency) { OpticalBody(body, dependency) } } }
