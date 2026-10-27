package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

/** One composed input subtree, with a pointer-free replay of its original padded drawing.
 * This is an ordinary display list, never a bitmap/readback or an asynchronous publication.
 * The enclosing logical clip owns hit testing; it must not surround [replay].
 */
internal class ActionStripDrawing(private val layer: GraphicsLayer) {
    fun record(enabled: () -> Boolean = { true }): Modifier = Modifier.drawWithContent {
        if (enabled()) layer.record { this@drawWithContent.drawContent() }
        else drawContent()
        // Only the outer replay draws this content. In particular, do not also
        // draw it through the logical input clip and accumulate material/shadow.
    }

    fun replay(inset: Dp, enabled: () -> Boolean = { true }): Modifier = Modifier.drawWithContent {
        // Traverse the real child FIRST. Its clip layer updates a dirty display
        // list synchronously here; unchanged cached content remains valid. Never
        // replay before this traversal or publish a separately scheduled image.
        drawContent()
        if (enabled() && layer.size != IntSize.Zero) {
            val pad = inset.roundToPx().toFloat()
            translate(-pad, -pad) { drawLayer(layer) }
        }
    }
}

@Composable
internal fun rememberActionStripDrawing(): ActionStripDrawing {
    val layer = rememberGraphicsLayer()
    return remember(layer) { ActionStripDrawing(layer) }
}
